package org.cote.accountmanager.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
	public static BaseRecord exportGroup(BaseRecord user, BaseRecord group, String type) throws FieldException, ValueException, ModelNotFoundException, FactoryException, ReaderException, IndexException, ModelException, IOException, WriterException {
		deleteGroupExport(group);

		long groupId = group.get(FieldNames.FIELD_ID);
		long orgId = group.get(FieldNames.FIELD_ORGANIZATION_ID);

		Query q = QueryUtil.createQuery(type, FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(true, new ArrayList<>());
		q.setCache(false);
		q.setRequestRange(0, 10000);
		QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
		BaseRecord[] children = qr.getResults();
		if(children.length == 0) {
			logger.warn("No " + type + " children found in group " + groupId + " - nothing to export");
			return null;
		}

		Map<String, byte[]> entries = ZipUtil.newOrderedEntries();
		Set<String> usedNames = new HashSet<>();
		for(BaseRecord child : children) {
			String entryName = buildEntry(child, entries, usedNames);
			logger.debug("Added export entry: " + entryName);
		}
		byte[] zipBytes = ZipUtil.createArchive(entries);

		BaseRecord exportsDir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, EXPORT_PATH, GroupEnumType.DATA.toString(), orgId);
		String exportsDirPath = exportsDir.get(FieldNames.FIELD_PATH);
		String zipName = "export-" + groupId + ".zip";
		/// streamToData uses groupPath (not just groupId) to build the ParameterList the factory needs
		/// to properly apply name/path-derived fields on the new data.data record — passing null here
		/// left "name" unset and failed validation ("\S" pattern) on insert.
		boolean ok = StreamUtil.streamToData(user, zipName, "Export of " + group.get(FieldNames.FIELD_NAME), exportsDirPath, (long) exportsDir.get(FieldNames.FIELD_ID), new ByteArrayInputStream(zipBytes));
		if(!ok) {
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

	/// Add one archive entry for `child`: extracted bytes (named as-is) when it's content-bearing
	/// (has dataBytesStore + contentType, stream-backed or inline — mirrors ExportAction's extract
	/// branch), else its full JSON under "<name-or-id>.json" (the fallback Stephen asked for).
	private static String buildEntry(BaseRecord child, Map<String, byte[]> entries, Set<String> usedNames) throws ValueException, FieldException {
		byte[] content;
		String baseName;
		boolean contentBearing = child.hasField(FieldNames.FIELD_BYTE_STORE) && child.hasField(FieldNames.FIELD_CONTENT_TYPE);
		if(contentBearing) {
			if(child.hasField(FieldNames.FIELD_STREAM) && child.get(FieldNames.FIELD_STREAM) != null) {
				BaseRecord stream = child.get(FieldNames.FIELD_STREAM);
				StreamSegmentUtil ssu = new StreamSegmentUtil();
				content = ssu.streamToEnd(stream.get(FieldNames.FIELD_OBJECT_ID), 0, 0);
			}
			else {
				content = ByteModelUtil.getValue(child);
			}
			baseName = safeName(child);
			if(baseName.indexOf('.') == -1) {
				String ext = ContentTypeUtil.getExtensionFromType(child.get(FieldNames.FIELD_CONTENT_TYPE));
				if(ext != null && ext.length() > 0) {
					baseName = baseName + "." + ext;
				}
			}
		}
		else {
			content = child.toFullString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
			baseName = safeName(child) + ".json";
		}
		String entryName = dedupe(baseName, usedNames);
		entries.put(entryName, content);
		return entryName;
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
