package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.junit.Test;

/// KI-17 (Gallery/Group export) real end-to-end integration test for the Objects7-layer capability:
/// AccessPoint.exportGroup/findGroupExport -> GroupExportUtil -> ZipUtil. No LLM/embedding calls are
/// involved (pure walk + extract/JSON-fallback + zip), so unlike TestPageIndex this runs in the default
/// suite, no env-flag gate needed. Never uses the admin user as the acting user.
public class TestGroupExport extends BaseTest {

	private Map<String, byte[]> readZip(byte[] zipBytes) throws IOException {
		Map<String, byte[]> entries = new HashMap<>();
		try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
			ZipEntry ze;
			while ((ze = zin.getNextEntry()) != null) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				byte[] buf = new byte[1024];
				int r;
				while ((r = zin.read(buf)) != -1) {
					baos.write(buf, 0, r);
				}
				entries.put(ze.getName(), baos.toByteArray());
				zin.closeEntry();
			}
		}
		return entries;
	}

	private byte[] readArchiveBytes(BaseRecord testUser, BaseRecord container) throws Exception {
		BaseRecord archiveRef = container.get(FieldNames.FIELD_ARCHIVE);
		assertNotNull("[GROUPEXPORT] container has no archive FK", archiveRef);
		String archiveObjectId = archiveRef.get(FieldNames.FIELD_OBJECT_ID);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, archiveObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		/// ByteModelUtil.getValue() decides whether/how to decompress (and decipher) purely from fields
		/// on the SAME record instance - FIELD_COMPRESSION_TYPE, FIELD_VAULTED, FIELD_ENCIPHERED - via
		/// `d.get(fieldName, default)` calls that silently fall back to their default ("NONE"/false) if
		/// the field was never requested/populated in this query's projection. The original field list
		/// here omitted FIELD_COMPRESSION_TYPE entirely, so compressionType always read back as the
		/// default "NONE" and ZipUtil.gunzipBytes was never called - for a SMALL archive (under
		/// ByteModelUtil's 512-byte auto-compress threshold, e.g. the other tests' 2-tiny-file archives)
		/// nothing was ever gzipped in the first place so this silently "worked" by accident; only once
		/// this test's archive genuinely exceeded 512 bytes did the gap surface as "0 zip entries" (the
		/// still-gzip-compressed bytes don't parse as a zip). Root-caused by direct inspection: gunzip-ing
		/// the raw returned bytes by hand (outside the JVM) revealed a perfectly valid, fully intact
		/// 3-entry zip - proving the archive-building/streaming path itself was correct all along and the
		/// bug was purely in this read helper's field projection.
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_STREAM,
			FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_VAULTED, FieldNames.FIELD_ENCIPHERED, FieldNames.FIELD_KEYS });
		q.setCache(false);
		BaseRecord archive = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
		assertNotNull("[GROUPEXPORT] archive data.data not readable", archive);
		/// ByteModelUtil.getValue() transparently decompresses/deciphers when the fields above say to -
		/// a raw field read (the original form of this helper) would return the STILL-COMPRESSED bytes
		/// whenever the persisted archive exceeds ByteModelUtil's 512-byte auto-gzip threshold, since
		/// "zip"'s registered content type ("multipart/x-zip") doesn't match any of tryCompress()'s
		/// exemption prefixes (image/, application/ (except json), audio/, video) and so gets
		/// transparently gzipped on persist like any other type. GroupExportService.download() had (and
		/// still needed) the identical fix - both are corrected to request the fields decompression
		/// depends on, not just to call the decompressing accessor.
		byte[] bytes = ByteModelUtil.getValue(archive);
		assertNotNull("[GROUPEXPORT] archive byte store is null (small archive should be inline, not stream-backed)", bytes);
		assertTrue("[GROUPEXPORT] archive byte store is empty", bytes.length > 0);
		return bytes;
	}

	@Test
	public void TestExportGroupWithImagesAndText() throws Exception {
		logger.info("=== TestExportGroupWithImagesAndText ===");
		Factory mf = ioContext.getFactory();
		OrganizationContext octx = getTestOrganization("/Development/GroupExport");
		BaseRecord testUser1 = mf.getCreateUser(octx.getAdminUser(), "testUser1", octx.getOrganizationId());
		assertNotNull("Test user is null", testUser1);

		String galleryPath = "~/GalleryExportTest";
		BaseRecord textDoc = getCreateData(testUser1, "notes.txt", "text/plain", "Hello export world".getBytes(), galleryPath, octx.getOrganizationId());
		assertNotNull(textDoc);
		BaseRecord imgDoc = getCreateData(testUser1, "photo.png", "image/png", new byte[] { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4 }, galleryPath, octx.getOrganizationId());
		assertNotNull(imgDoc);

		BaseRecord gallery = ioContext.getPathUtil().findPath(testUser1, ModelNames.MODEL_GROUP, galleryPath, "DATA", octx.getOrganizationId());
		assertNotNull("[GROUPEXPORT] gallery group not found", gallery);
		String galleryObjectId = gallery.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord container = IOSystem.getActiveContext().getAccessPoint().exportGroup(testUser1, galleryObjectId, "data.data");
		assertNotNull("[GROUPEXPORT] exportGroup returned null", container);
		int itemCount = container.get(FieldNames.FIELD_ITEM_COUNT);
		assertEquals("[GROUPEXPORT] expected 2 exported items", 2, itemCount);

		byte[] zipBytes = readArchiveBytes(testUser1, container);
		Map<String, byte[]> entries = readZip(zipBytes);
		assertEquals("[GROUPEXPORT] expected 2 zip entries", 2, entries.size());
		assertTrue("[GROUPEXPORT] expected notes.txt entry", entries.containsKey("notes.txt"));
		assertTrue("[GROUPEXPORT] expected photo.png entry", entries.containsKey("photo.png"));
		assertEquals("Hello export world", new String(entries.get("notes.txt"), "UTF-8"));
		assertEquals(8, entries.get("photo.png").length);

		/// findGroupExport should now return the same container.
		BaseRecord found = IOSystem.getActiveContext().getAccessPoint().findGroupExport(testUser1, galleryObjectId);
		assertNotNull("[GROUPEXPORT] findGroupExport returned null after export", found);
		String containerObjectId = container.get(FieldNames.FIELD_OBJECT_ID);
		String foundObjectId = found.get(FieldNames.FIELD_OBJECT_ID);
		assertEquals(containerObjectId, foundObjectId);
	}

	@Test
	public void TestExportGroupJsonFallbackForNonContentBearingType() throws Exception {
		logger.info("=== TestExportGroupJsonFallbackForNonContentBearingType ===");
		Factory mf = ioContext.getFactory();
		OrganizationContext octx = getTestOrganization("/Development/GroupExport");
		BaseRecord testUser1 = mf.getCreateUser(octx.getAdminUser(), "testUser1", octx.getOrganizationId());
		assertNotNull(testUser1);

		String notesPath = "~/GalleryExportNotesTest";
		BaseRecord note = DocumentUtil.getCreateNote(testUser1, "ExportFallbackNote", notesPath, "This note has no byte content.");
		assertNotNull(note);

		BaseRecord group = ioContext.getPathUtil().findPath(testUser1, ModelNames.MODEL_GROUP, notesPath, "DATA", octx.getOrganizationId());
		assertNotNull(group);
		String groupObjectId = group.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord container = IOSystem.getActiveContext().getAccessPoint().exportGroup(testUser1, groupObjectId, ModelNames.MODEL_NOTE);
		assertNotNull("[GROUPEXPORT-JSON] exportGroup returned null", container);
		assertEquals(1, (int) container.get(FieldNames.FIELD_ITEM_COUNT));

		byte[] zipBytes = readArchiveBytes(testUser1, container);
		Map<String, byte[]> entries = readZip(zipBytes);
		assertEquals(1, entries.size());
		String entryName = entries.keySet().iterator().next();
		assertTrue("[GROUPEXPORT-JSON] expected a .json fallback entry, got " + entryName, entryName.endsWith(".json"));
		String json = new String(entries.get(entryName), "UTF-8");
		assertTrue("[GROUPEXPORT-JSON] expected the note's schema in the JSON fallback", json.contains(ModelNames.MODEL_NOTE));
		assertTrue("[GROUPEXPORT-JSON] expected the note's text in the JSON fallback", json.contains("This note has no byte content."));
	}

	@Test
	public void TestExportGroupRebuildReplacesPriorArchive() throws Exception {
		logger.info("=== TestExportGroupRebuildReplacesPriorArchive ===");
		Factory mf = ioContext.getFactory();
		OrganizationContext octx = getTestOrganization("/Development/GroupExport");
		BaseRecord testUser1 = mf.getCreateUser(octx.getAdminUser(), "testUser1", octx.getOrganizationId());
		assertNotNull(testUser1);

		String path = "~/GalleryExportRebuildTest";
		getCreateData(testUser1, "one.txt", "text/plain", "one".getBytes(), path, octx.getOrganizationId());
		BaseRecord group = ioContext.getPathUtil().findPath(testUser1, ModelNames.MODEL_GROUP, path, "DATA", octx.getOrganizationId());
		String groupObjectId = group.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord first = IOSystem.getActiveContext().getAccessPoint().exportGroup(testUser1, groupObjectId, "data.data");
		assertNotNull(first);
		String firstArchiveId = ((BaseRecord) first.get(FieldNames.FIELD_ARCHIVE)).get(FieldNames.FIELD_OBJECT_ID);

		/// Add a second item, rebuild.
		getCreateData(testUser1, "two.txt", "text/plain", "two".getBytes(), path, octx.getOrganizationId());
		BaseRecord second = IOSystem.getActiveContext().getAccessPoint().exportGroup(testUser1, groupObjectId, "data.data");
		assertNotNull(second);
		assertEquals(2, (int) second.get(FieldNames.FIELD_ITEM_COUNT));
		String secondArchiveId = ((BaseRecord) second.get(FieldNames.FIELD_ARCHIVE)).get(FieldNames.FIELD_OBJECT_ID);
		assertNotEquals("[GROUPEXPORT-REBUILD] rebuild should persist a fresh archive object", firstArchiveId, secondArchiveId);

		/// Old archive should no longer be readable (deleted by the rebuild).
		Query oldQ = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, firstArchiveId);
		oldQ.field(FieldNames.FIELD_ORGANIZATION_ID, testUser1.get(FieldNames.FIELD_ORGANIZATION_ID));
		BaseRecord old = IOSystem.getActiveContext().getAccessPoint().find(testUser1, oldQ);
		assertNull("[GROUPEXPORT-REBUILD] old archive should have been deleted on rebuild", old);

		/// Only one groupExport container should exist for this group (rebuild-in-place, not accumulate).
		BaseRecord onlyOne = IOSystem.getActiveContext().getAccessPoint().findGroupExport(testUser1, groupObjectId);
		assertNotNull(onlyOne);
		String secondObjectId = second.get(FieldNames.FIELD_OBJECT_ID);
		String onlyOneObjectId = onlyOne.get(FieldNames.FIELD_OBJECT_ID);
		assertEquals(secondObjectId, onlyOneObjectId);
	}

	@Test
	public void TestExportGroupAccessPointGate() throws Exception {
		logger.info("=== TestExportGroupAccessPointGate ===");
		Factory mf = ioContext.getFactory();
		OrganizationContext octx = getTestOrganization("/Development/GroupExport");
		BaseRecord testUser1 = mf.getCreateUser(octx.getAdminUser(), "testUser1", octx.getOrganizationId());
		BaseRecord testUser2 = mf.getCreateUser(octx.getAdminUser(), "testUser2", octx.getOrganizationId());
		assertNotNull(testUser1);
		assertNotNull(testUser2);

		String path = "~/GalleryExportGateTest";
		getCreateData(testUser1, "secret.txt", "text/plain", "secret contents".getBytes(), path, octx.getOrganizationId());
		BaseRecord group = ioContext.getPathUtil().findPath(testUser1, ModelNames.MODEL_GROUP, path, "DATA", octx.getOrganizationId());
		String groupObjectId = group.get(FieldNames.FIELD_OBJECT_ID);

		/// --- Positive: owner (testUser1) can export and find it. ---
		BaseRecord container = IOSystem.getActiveContext().getAccessPoint().exportGroup(testUser1, groupObjectId, "data.data");
		assertNotNull("[GROUPEXPORT-GATE] owner export should succeed", container);
		BaseRecord foundByOwner = IOSystem.getActiveContext().getAccessPoint().findGroupExport(testUser1, groupObjectId);
		assertNotNull(foundByOwner);

		/// --- Negative: testUser2 (same org, no access to testUser1's private group) is denied. ---
		BaseRecord deniedExport = IOSystem.getActiveContext().getAccessPoint().exportGroup(testUser2, groupObjectId, "data.data");
		assertNull("[GROUPEXPORT-GATE] second user's export should be denied (null)", deniedExport);

		BaseRecord deniedFind = IOSystem.getActiveContext().getAccessPoint().findGroupExport(testUser2, groupObjectId);
		assertNull("[GROUPEXPORT-GATE] second user's findGroupExport should be denied (null)", deniedFind);

		/// Denied export attempt must not have touched the owner's existing export.
		BaseRecord stillThere = IOSystem.getActiveContext().getAccessPoint().findGroupExport(testUser1, groupObjectId);
		assertNotNull("[GROUPEXPORT-GATE] owner's export must survive the denied attempt", stillThere);
		String containerObjectId = container.get(FieldNames.FIELD_OBJECT_ID);
		String stillThereObjectId = stillThere.get(FieldNames.FIELD_OBJECT_ID);
		assertEquals(containerObjectId, stillThereObjectId);
	}

	/// Regression test for the >1GB group export OutOfMemoryError (KI-17 follow-up). Doesn't push a
	/// literal 1GB through (impractical for CI time), but exercises the exact code path that mattered:
	/// SEVERAL stream-backed (i.e. over StreamUtil's ~1MB inline cutoff) source files, each spanning
	/// multiple EXPORT_STREAM_CHUNK_SIZE (1MB) chunks, exported together. Proves correctness (every
	/// byte round-trips through the streaming archive build) with a real, passing test; the "doesn't
	/// materialize everything in memory at once" claim for content beyond what's practical to push
	/// through a unit test is additionally backed by reading the changed code (GroupExportUtil no
	/// longer builds a Map<String,byte[]> of every child - see its class javadoc and buildEntrySource -
	/// and StreamSegmentUtil#streamToOutput pages a stream-backed file in fixed chunks instead of
	/// reading it whole), not merely by this test passing.
	@Test
	public void TestExportGroupLargeStreamBackedContent() throws Exception {
		logger.info("=== TestExportGroupLargeStreamBackedContent ===");
		Factory mf = ioContext.getFactory();
		OrganizationContext octx = getTestOrganization("/Development/GroupExport");
		BaseRecord testUser1 = mf.getCreateUser(octx.getAdminUser(), "testUser1", octx.getOrganizationId());
		assertNotNull(testUser1);

		/// Unique path per run: StreamUtil.streamToData (unlike BaseTest#getCreateData) hard-fails if a
		/// same-named record already exists in the target group, and the live DB is never reset between
		/// runs - a fixed path would collide with a prior run's leftover fixture.
		String path = "~/GalleryExportLargeTest-" + java.util.UUID.randomUUID();
		long orgId = octx.getOrganizationId();
		int fileSize = 2 * 1024 * 1024 + 12345; // ~2MB + odd remainder, each file spans multiple 1MB chunks
		int fileCount = 3;
		Map<String, byte[]> expected = new HashMap<>();
		for(int i = 0; i < fileCount; i++) {
			String name = "large-" + i + ".bin";
			byte[] content = deterministicBytes(fileSize, i);
			BaseRecord streamRec = createStreamBackedFile(testUser1, path, name, content, orgId);
			assertNotNull("[GROUPEXPORT-LARGE] stream-backed file " + name + " was not created", streamRec);
			assertNotNull("[GROUPEXPORT-LARGE] " + name + " unexpectedly has no stream FK (stayed inline)",
				streamRec.get(FieldNames.FIELD_STREAM));
			expected.put(name, content);
		}

		BaseRecord group = ioContext.getPathUtil().findPath(testUser1, ModelNames.MODEL_GROUP, path, "DATA", orgId);
		assertNotNull(group);
		String groupObjectId = group.get(FieldNames.FIELD_OBJECT_ID);

		long t0 = System.currentTimeMillis();
		BaseRecord container = IOSystem.getActiveContext().getAccessPoint().exportGroup(testUser1, groupObjectId, "data.data");
		long elapsed = System.currentTimeMillis() - t0;
		assertNotNull("[GROUPEXPORT-LARGE] exportGroup returned null", container);
		assertEquals(fileCount, (int) container.get(FieldNames.FIELD_ITEM_COUNT));
		logger.info("[GROUPEXPORT-LARGE] exported " + fileCount + " files (~" + (fileCount * fileSize / (1024 * 1024))
			+ "MB total) in " + elapsed + "ms");

		byte[] zipBytes = readArchiveBytes(testUser1, container);
		Map<String, byte[]> entries = readZip(zipBytes);
		assertEquals("[GROUPEXPORT-LARGE] expected " + fileCount + " zip entries", fileCount, entries.size());
		for(Map.Entry<String, byte[]> e : expected.entrySet()) {
			byte[] actual = entries.get(e.getKey());
			assertNotNull("[GROUPEXPORT-LARGE] missing entry " + e.getKey(), actual);
			assertEquals("[GROUPEXPORT-LARGE] size mismatch for " + e.getKey(), e.getValue().length, actual.length);
			assertArrayEquals("[GROUPEXPORT-LARGE] content mismatch for " + e.getKey() + " - chunked "
				+ "stream read/write corrupted data", e.getValue(), actual);
		}
	}

	/// Deterministic, non-degenerate (not all-zero) content so a truncated/corrupted chunk boundary would
	/// actually be detectable via assertArrayEquals rather than accidentally matching.
	private byte[] deterministicBytes(int size, int seed) {
		byte[] b = new byte[size];
		for(int i = 0; i < size; i++) {
			b[i] = (byte) ((i * 31 + seed * 17 + 7) & 0xFF);
		}
		return b;
	}

	/// Creates a genuinely stream-backed data.data record (bypassing the inline-byte-store path that
	/// BaseTest#getCreateData uses) via the same StreamUtil.streamToData entry point real uploads use,
	/// so `content.length` must exceed StreamUtil's ~1MB inline cutoff for the returned record to carry a
	/// `stream` FK - callers should assert on that FK to confirm the fixture is shaped as intended.
	private BaseRecord createStreamBackedFile(BaseRecord user, String path, String name, byte[] content, long orgId) throws Exception {
		boolean ok = org.cote.accountmanager.util.StreamUtil.streamToData(user, name, "Large export fixture", path, 0L, new ByteArrayInputStream(content));
		assertTrue("[GROUPEXPORT-LARGE] streamToData failed for " + name, ok);
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, path, "DATA", orgId);
		BaseRecord[] match = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_DATA, (long) dir.get(FieldNames.FIELD_ID), name);
		assertTrue("[GROUPEXPORT-LARGE] could not re-read newly streamed file " + name, match != null && match.length > 0);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, (String) match[0].get(FieldNames.FIELD_OBJECT_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(true, java.util.Arrays.asList(FieldNames.FIELD_BYTE_STORE));
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}
}
