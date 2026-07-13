package org.cote.accountmanager.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;

/**
 * KI-17 (Gallery/Group export) build utility, the companion to {@link PageIndexUtil}/{@link VectorUtil}
 * for a different capability: walks a group's contents of a given type, builds a ZIP (extracted bytes
 * for content-bearing records such as {@code data.data}, full JSON for everything else — mirroring
 * {@code ExportAction}'s extract-or-{@code toFullString()} fallback), persists the archive the same way
 * any large upload is persisted ({@link StreamUtil#streamToData}, which itself decides blob vs.
 * stream-backed storage purely on size), and tracks it via a {@code data.groupExport} container record
 * (one per source group; rebuilding replaces rather than accumulates).
 *
 * <p>Like {@code PageIndexUtil}/{@code VectorUtil}, this is a utility-bypass class — no {@code user}
 * authorization is performed here. Callers (the {@code AccessPoint} wrappers) MUST authorize (canRead on
 * the source group) before calling into this class.
 *
 * <p><b>Streaming rewrite (OutOfMemoryError fix, >1GB groups):</b> the original implementation built a
 * {@code Map<String,byte[]>} holding every child's FULL extracted content simultaneously, handed the
 * whole map to {@code ZipUtil.createArchive} (which materializes the entire finished ZIP as a second
 * in-memory {@code byte[]}), then wrapped that in a {@code ByteArrayInputStream} for
 * {@code StreamUtil.streamToData} (which itself chunks reads from whatever {@code InputStream} it's
 * given — already fine on that end, but the damage was done upstream). For a >1GB group that's the
 * entire export's content resident at least twice over, regardless of any single item's size. This
 * version defers each entry's bytes to write time via {@code ZipUtil.ZipEntrySource} and writes directly
 * into a {@code ZipOutputStream} backed by a temp file (never a growing in-memory buffer); stream-backed
 * content is paged through {@code StreamSegmentUtil#streamToOutput} in fixed-size chunks rather than
 * read whole; and the initial bulk query excludes the byte-store column entirely (re-read one record at
 * a time, at write time, only for inline content) so listing a group's children doesn't itself eagerly
 * load every inline blob at once. At any point in time only one entry's chunk plus the ZipOutputStream's
 * own fixed internal buffer are resident — never the sum of every entry's content.
 */
public class GroupExportUtil {
	public static final Logger logger = LogManager.getLogger(GroupExportUtil.class);

	/// Fixed relative path (mirrors ChatUtil's "~/Notes/Summaries" convention) so generated archives
	/// don't reappear inside the very gallery/group being exported.
	public static final String EXPORT_PATH = "~/Exports";

	/**
	 * Build (or rebuild) the ZIP export for {@code group}'s children of {@code type}. Deletes any prior
	 * export for this group first (rebuild-in-place semantics, matching {@code PageIndexUtil.createPageIndex}).
	 *
	 * @return the persisted {@code data.groupExport} container record, or null if nothing was exported
	 *         (e.g. the group has no children of the requested type).
	 */
	/// Fixed paging size for stream-backed content (mirrors StreamUtil's own 1MB STREAM_CUTOFF
	/// convention) - the amount of one entry's content resident in memory at any instant, regardless of
	/// how large the underlying file is.
	private static final int EXPORT_STREAM_CHUNK_SIZE = 1048576;

	public static BaseRecord exportGroup(BaseRecord user, BaseRecord group, String type) throws FieldException, ValueException, ModelNotFoundException, FactoryException, ReaderException, IndexException, ModelException, IOException, WriterException {
		deleteGroupExport(group);

		long groupId = group.get(FieldNames.FIELD_ID);
		long orgId = group.get(FieldNames.FIELD_ORGANIZATION_ID);

		Query q = QueryUtil.createQuery(type, FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		/// Exclude the byte-store column from this bulk plan (see class javadoc): otherwise a group with
		/// many inline-content children would have EVERY one of their blobs eagerly loaded by this
		/// single find(), before a single ZIP entry is even built. Each content-bearing child's bytes
		/// are instead acquired one at a time, at write time, in buildEntrySource() below.
		q.planMost(true, Arrays.asList(FieldNames.FIELD_BYTE_STORE));
		q.setCache(false);
		q.setRequestRange(0, 10000);
		QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
		BaseRecord[] children = qr.getResults();
		if(children.length == 0) {
			logger.warn("No " + type + " children found in group " + groupId + " - nothing to export");
			return null;
		}

		List<ZipUtil.ZipEntrySource> sources = new ArrayList<>();
		Set<String> usedNames = new HashSet<>();
		for(BaseRecord child : children) {
			ZipUtil.ZipEntrySource source = buildEntrySource(child, usedNames);
			sources.add(source);
			logger.debug("Added export entry: " + source.getName());
		}

		BaseRecord exportsDir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, EXPORT_PATH, GroupEnumType.DATA.toString(), orgId);
		String exportsDirPath = exportsDir.get(FieldNames.FIELD_PATH);
		String zipName = "export-" + groupId + ".zip";

		/// Build the archive into a TEMP FILE, not an in-memory buffer - ZipUtil.writeArchive writes
		/// each entry's bytes straight through to this FileOutputStream one at a time, so the finished
		/// archive's full size is never resident in the JVM heap either, only on disk.
		File tempZip = File.createTempFile("groupExport-" + groupId + "-", ".zip");
		boolean persisted = false;
		try {
			try(FileOutputStream fos = new FileOutputStream(tempZip)) {
				ZipUtil.writeArchive(fos, sources);
			}
			/// streamToData uses groupPath (not just groupId) to build the ParameterList the factory needs
			/// to properly apply name/path-derived fields on the new data.data record — passing null here
			/// left "name" unset and failed validation ("\S" pattern) on insert. Reading the finished
			/// archive back from disk (not a byte[]) keeps streamToData's own chunked-read loop genuinely
			/// chunked all the way through, rather than wrapping an already-fully-materialized byte[].
			try(FileInputStream fis = new FileInputStream(tempZip)) {
				persisted = StreamUtil.streamToData(user, zipName, "Export of " + group.get(FieldNames.FIELD_NAME), exportsDirPath, (long) exportsDir.get(FieldNames.FIELD_ID), fis);
			}
		}
		finally {
			if(tempZip.exists() && !tempZip.delete()) {
				logger.warn("Could not delete temp export archive file: " + tempZip.getAbsolutePath());
			}
		}
		if(!persisted) {
			logger.error("Failed to persist export archive for group " + groupId);
			return null;
		}
		BaseRecord[] archiveMatch = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_DATA, (long) exportsDir.get(FieldNames.FIELD_ID), zipName);
		if(archiveMatch == null || archiveMatch.length == 0) {
			logger.error("Archive was persisted but could not be re-read for group " + groupId);
			return null;
		}

		return writeContainer(user, group, archiveMatch[0], children.length, exportsDir);
	}

	/// Builds a deferred ZipEntrySource for `child`: extracted bytes (named as-is) when it's
	/// content-bearing (has dataBytesStore + contentType, stream-backed or inline — mirrors
	/// ExportAction's extract branch), else its full JSON under "<name-or-id>.json" (the fallback Stephen
	/// asked for). Unlike the original implementation, NO content is read here — only the entry name is
	/// resolved up front (which needs only the contentType/name fields, already planned); the actual
	/// bytes are acquired later, one entry at a time, when ZipUtil.writeArchive calls writeTo().
	private static ZipUtil.ZipEntrySource buildEntrySource(BaseRecord child, Set<String> usedNames) {
		/// MUST be a schema-level (model) check, not child.hasField(FIELD_BYTE_STORE): the bulk query in
		/// exportGroup() deliberately excludes FIELD_BYTE_STORE from the plan (see its comment), and
		/// hasField() reports whether a field is actually present on THIS record instance - excluding it
		/// from the plan means it's simply absent, so hasField(FIELD_BYTE_STORE) would be false for every
		/// content-bearing child too, misrouting all of them to the JSON-fallback branch below. Checking
		/// the model's inheritance (does this TYPE ever carry byte content) instead of this instance's
		/// currently-populated fields gives the correct answer regardless of what the query planned.
		boolean contentBearing = child.inherits(ModelNames.MODEL_CRYPTOBYTESTORE) && child.hasField(FieldNames.FIELD_CONTENT_TYPE);
		String baseName;
		if(contentBearing) {
			baseName = safeName(child);
			if(baseName.indexOf('.') == -1) {
				String ext = ContentTypeUtil.getExtensionFromType(child.get(FieldNames.FIELD_CONTENT_TYPE));
				if(ext != null && ext.length() > 0) {
					baseName = baseName + "." + ext;
				}
			}
		}
		else {
			baseName = safeName(child) + ".json";
		}
		String entryName = dedupe(baseName, usedNames);
		return new ZipUtil.ZipEntrySource() {
			@Override
			public String getName() {
				return entryName;
			}

			@Override
			public void writeTo(OutputStream out) throws IOException {
				if(contentBearing) {
					BaseRecord stream = (child.hasField(FieldNames.FIELD_STREAM) ? child.get(FieldNames.FIELD_STREAM) : null);
					if(stream != null) {
						/// Stream-backed: page through the underlying file in fixed-size chunks rather
						/// than pulling the whole thing into one byte[] in a single read (the prior
						/// streamToEnd(streamId, 0, 0) call's len=0 convention means "read to end of
						/// file", i.e. unbounded for a single large file).
						new StreamSegmentUtil().streamToOutput(stream.get(FieldNames.FIELD_OBJECT_ID), out, EXPORT_STREAM_CHUNK_SIZE);
					}
					else {
						/// Inline content was deliberately excluded from the bulk query plan (see
						/// exportGroup) so a group with many inline-content children doesn't hold all
						/// of their bytes at once; re-read just THIS one record's byte store now.
						IOSystem.getActiveContext().getReader().populate(child, new String[] { FieldNames.FIELD_BYTE_STORE });
						try {
							out.write(ByteModelUtil.getValue(child));
						}
						catch(ValueException | FieldException e) {
							throw new IOException(e);
						}
					}
				}
				else {
					out.write(child.toFullString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
				}
			}
		};
	}

	private static String safeName(BaseRecord child) {
		String name = null;
		if(child.hasField(FieldNames.FIELD_NAME)) {
			name = child.get(FieldNames.FIELD_NAME);
		}
		if(name == null || name.trim().length() == 0) {
			name = child.getSchema() + "-" + ((String) child.get(FieldNames.FIELD_OBJECT_ID));
		}
		return name.replaceAll("[\\\\/:*?\"<>|]", "_");
	}

	private static String dedupe(String name, Set<String> usedNames) {
		String candidate = name;
		int n = 1;
		int dot = name.lastIndexOf('.');
		String base = (dot > 0 ? name.substring(0, dot) : name);
		String ext = (dot > 0 ? name.substring(dot) : "");
		while(usedNames.contains(candidate)) {
			candidate = base + "_" + (++n) + ext;
		}
		usedNames.add(candidate);
		return candidate;
	}

	/// Create (or replace) the data.groupExport container linking `group` to the persisted `archive`.
	private static BaseRecord writeContainer(BaseRecord user, BaseRecord group, BaseRecord archive, int itemCount, BaseRecord exportsDir) throws FieldException, ValueException, ModelNotFoundException, FactoryException, WriterException {
		long groupId = group.get(FieldNames.FIELD_ID);
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, EXPORT_PATH);
		plist.parameter(FieldNames.FIELD_NAME, "export-container-" + groupId);
		BaseRecord container = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_GROUP_EXPORT, user, null, plist);
		container.set(FieldNames.FIELD_SOURCE_GROUP, group);
		container.set(FieldNames.FIELD_ARCHIVE, archive);
		container.set(FieldNames.FIELD_ITEM_COUNT, itemCount);
		container.set(FieldNames.FIELD_GENERATED_DATE, ZonedDateTime.now());
		IOSystem.getActiveContext().getWriter().write(container);
		return container;
	}

	/// Unauthenticated lookup by source group — callers (AccessPoint wrappers) are responsible for PBAC.
	public static BaseRecord findGroupExport(BaseRecord group) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GROUP_EXPORT, FieldNames.FIELD_SOURCE_GROUP, group.copyRecord(new String[] {FieldNames.FIELD_ID}));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, group.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.planMost(false, new ArrayList<>());
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	/// Deletes the prior export (container + its archive data.data) for `group`, if any. Returns true if
	/// something was deleted.
	public static boolean deleteGroupExport(BaseRecord group) {
		BaseRecord existing = findGroupExport(group);
		if(existing == null) {
			return false;
		}
		try {
			BaseRecord archive = existing.get(FieldNames.FIELD_ARCHIVE);
			if(archive != null) {
				IOSystem.getActiveContext().getRecordUtil().deleteRecord(archive);
			}
			IOSystem.getActiveContext().getRecordUtil().deleteRecord(existing);
			return true;
		}
		catch(Exception e) {
			logger.error(e);
			return false;
		}
	}
}
