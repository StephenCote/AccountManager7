package org.cote.accountmanager.objects.tests;

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

	private byte[] readArchiveBytes(BaseRecord testUser, BaseRecord container) {
		BaseRecord archiveRef = container.get(FieldNames.FIELD_ARCHIVE);
		assertNotNull("[GROUPEXPORT] container has no archive FK", archiveRef);
		String archiveObjectId = archiveRef.get(FieldNames.FIELD_OBJECT_ID);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, archiveObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_STREAM, FieldNames.FIELD_CONTENT_TYPE });
		BaseRecord archive = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
		assertNotNull("[GROUPEXPORT] archive data.data not readable", archive);
		byte[] bytes = archive.get(FieldNames.FIELD_BYTE_STORE);
		assertNotNull("[GROUPEXPORT] archive byte store is null (small archive should be inline, not stream-backed)", bytes);
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
}
