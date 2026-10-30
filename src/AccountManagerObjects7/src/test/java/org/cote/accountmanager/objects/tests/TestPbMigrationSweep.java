package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbDeleteUtil;
import org.cote.accountmanager.olio.picturebook.PbMigrationUtil;
import org.cote.accountmanager.olio.picturebook.PbMigrationUtil.ImportResult;
import org.cote.accountmanager.olio.picturebook.PbPipelineUtil;
import org.cote.accountmanager.olio.picturebook.PbServiceFacade;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * P7-4 verify check 2 (design §30 row 7, verify stage) against the Objects7 JUnit database: the pre-V3
 * (PB1-only) books that actually exist there, imported as their owners through
 * {@link PbMigrationUtil#importV1Book}, then measured the way the V3 reader reads them. A measurement, not
 * a fixture: nothing is synthesized, and one row per book is logged.
 * <p>
 * Gate: {@code -Dpb.sweep=true} (opt-in; every import creates a world and runs the live location pass).
 * Owners: {@code -Dpb.sweep.owners=user@/Org/Path,...}; the default is one owner per PB1 owner class in
 * am7db (pbGuardUser, pbCustomTestUser, pbTestUser, the newest ki42b and ki42c users). Per owner:
 * {@code -Dpb.sweep.perOwner=N} newest PB1-only books (default 2; 0 = every one). {@code -Dpb.sweep.keep=true}
 * leaves the imported books in place; by default each is removed again as its owner
 * ({@link PbDeleteUtil#deleteBookComplete}) so the database is left as found and the sweep can be re-run.
 * Never the admin user: the owner is resolved through the harness' getCreateUser and every call runs as it.
 * <p>
 * Per book: the PB1 scene count and whether any PB1 scene note names a rendered image
 * ({@link PictureBookUtil#listScenes}); whether the group already bridges to a V3 book
 * ({@link PbBookUtil#findBookBySlug} on the derived slug: recorded, skipped); the import result; the
 * pages as {@link PbServiceFacade#bookPageView} returns them, and for every page carrying an image the
 * data.data bytes read back as the owner; the book's health findings. The verdict fails only when a
 * PB1-only book did not import, an imaged page's bytes did not read back, or a reset failed. Timings are
 * logged and never thresholds.
 */
public class TestPbMigrationSweep extends BaseTest {

	public static final Logger logger = LogManager.getLogger(TestPbMigrationSweep.class);

	private static final String PICTURE_BOOKS_PATH = "~/Data/PictureBooks";
	private static final String META_NOTE_NAME = ".pictureBookMeta";
	private static final String DEFAULT_OWNERS =
		"pbGuardUser@/Development,"
		+ "pbCustomTestUser@/Development/PictureBook Custom Tests,"
		+ "pbTestUser@/Development/PictureBook Full Tests,"
		+ "ki42b1792793011461@/Development/PictureBook KI Tests,"
		+ "ki42c1792792986929@/Development/PictureBook KI Tests";

	@Before
	public void sweepSetup() {
		OlioContextUtil.clearCache();
		OlioModelNames.use();
	}

	private static boolean sweepEnabled() {
		return "true".equalsIgnoreCase(System.getProperty("pb.sweep")) || "1".equals(System.getenv("PB_MIGRATE_SWEEP"));
	}

	private String dataPath() {
		return testProperties.getProperty("test.datagen.path");
	}

	private static final class Row {
		String owner;
		String org;
		String book;
		String groupOid;
		int pb1Scenes = -1;
		boolean pb1Images;
		String status;
		String slug;
		String bookOid;
		int scenesImported;
		int scenesFailed;
		int landscapesImported;
		int warnings;
		boolean locationHardFailure;
		long importMs;
		int pages = -1;
		int imagedPages;
		int bytesOk;
		List<String> bytesBad = new ArrayList<>();
		List<String> healthErrors = new ArrayList<>();
		List<String> healthWarns = new ArrayList<>();
		String error;
		String reset;
	}

	/// The owner's PB1 book groups under ~/Data/PictureBooks that hold a .pictureBookMeta note, newest first.
	private List<BaseRecord> pb1BookGroups(BaseRecord user, long orgId) {
		List<BaseRecord> out = new ArrayList<>();
		BaseRecord pbDir = IOSystem.getActiveContext().getPathUtil().findPath(user,
			ModelNames.MODEL_GROUP, PICTURE_BOOKS_PATH, GroupEnumType.DATA.toString(), orgId);
		if (pbDir == null) {
			return out;
		}
		long pbDirId = ((Number) pbDir.get(FieldNames.FIELD_ID)).longValue();
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, pbDirId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		try {
			q.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_ID);
			q.set(FieldNames.FIELD_ORDER, OrderEnumType.DESCENDING.toString());
		} catch (Exception e) {
			logger.warn("Could not set sort order on PB1 book group query: " + e.getMessage());
		}
		q.setRequestRange(0, 500);
		q.setCache(false);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_PATH });
		BaseRecord[] groups = IOSystem.getActiveContext().getSearch().findRecords(q);
		if (groups == null) {
			return out;
		}
		for (BaseRecord g : groups) {
			Query nq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_GROUP_ID, g.get(FieldNames.FIELD_ID));
			nq.field(FieldNames.FIELD_NAME, META_NOTE_NAME);
			nq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			nq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME });
			nq.setCache(false);
			if (IOSystem.getActiveContext().getAccessPoint().find(user, nq) != null) {
				out.add(g);
			}
		}
		return out;
	}

	private BaseRecord freshReadData(BaseRecord user, String objectId, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ATTRIBUTES,
			FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_DATA_HASH, FieldNames.FIELD_SIZE,
			FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_ENCIPHERED,
			FieldNames.FIELD_VAULTED, FieldNames.FIELD_KEY_ID, FieldNames.FIELD_VAULT_ID });
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	@SuppressWarnings("unchecked")
	private void measureImported(BaseRecord user, long orgId, Row row) {
		List<Map<String, Object>> pages = PbServiceFacade.bookPageView(user, row.bookOid);
		row.pages = pages.size();
		for (Map<String, Object> p : pages) {
			Object gp = p.get("imageGroupPath");
			Object nm = p.get("imageName");
			Object oid = p.get("dataObjectId");
			if (gp == null || nm == null) {
				continue;
			}
			row.imagedPages++;
			try {
				BaseRecord data = oid != null ? freshReadData(user, oid.toString(), orgId) : null;
				byte[] bytes = data != null ? ByteModelUtil.getValue(data) : null;
				if (bytes != null && bytes.length > 0) {
					row.bytesOk++;
				} else {
					row.bytesBad.add(nm + " -> " + (data == null ? "unreadable" : "0 bytes"));
				}
			} catch (Exception e) {
				row.bytesBad.add(nm + " -> " + e.getMessage());
			}
		}
		try {
			Map<String, Object> health = PbServiceFacade.bookHealth(user, row.bookOid);
			Object f = health != null ? health.get("findings") : null;
			if (f instanceof List) {
				for (Object o : (List<Object>) f) {
					if (!(o instanceof Map)) continue;
					Map<String, Object> m = (Map<String, Object>) o;
					String sev = String.valueOf(m.get("severity")).toUpperCase();
					String code = String.valueOf(m.get("code"));
					if ("ERROR".equals(sev)) row.healthErrors.add(code);
					else if ("WARN".equals(sev)) row.healthWarns.add(code);
				}
			}
		} catch (PictureBookException e) {
			row.healthErrors.add("health " + e.getStatus());
		}
	}

	@Test
	public void TestSweepPb1BooksMigrateAndRead() {
		assumeTrue("SKIPPED: -Dpb.sweep=true not set (the verify-check-2 migration sweep is opt-in)", sweepEnabled());
		String dataPath = dataPath();
		assertNotNull("test.datagen.path is required (createBook needs the Olio data)", dataPath);
		String ownersProp = System.getProperty("pb.sweep.owners", DEFAULT_OWNERS);
		int perOwner = Integer.parseInt(System.getProperty("pb.sweep.perOwner", "2"));
		boolean keep = "true".equalsIgnoreCase(System.getProperty("pb.sweep.keep"));

		List<Row> rows = new ArrayList<>();
		List<String> unmeasured = new ArrayList<>();
		Map<Row, BaseRecord> importedAs = new LinkedHashMap<>();

		for (String spec : ownersProp.split(",")) {
			String s = spec.trim();
			if (s.isEmpty()) continue;
			int at = s.indexOf('@');
			String ownerName = at > 0 ? s.substring(0, at) : s;
			String orgPath = at > 0 ? s.substring(at + 1) : "/Development";
			assertTrue("the sweep never runs as admin", !"admin".equalsIgnoreCase(ownerName));
			OrganizationContext org = getTestOrganization(orgPath);
			BaseRecord user = getCreateUser(ownerName, org);
			if (user == null) {
				unmeasured.add(ownerName + "@" + orgPath + ": user could not be resolved");
				continue;
			}
			long orgId = org.getOrganizationId();
			List<BaseRecord> groups = pb1BookGroups(user, orgId);
			logger.info("[p74-sweep] " + ownerName + "@" + orgPath + ": " + groups.size() + " PB1 book group(s) with a meta note");
			int taken = 0;
			for (BaseRecord g : groups) {
				if (perOwner > 0 && taken >= perOwner) break;
				Row row = new Row();
				row.owner = ownerName;
				row.org = orgPath;
				row.book = g.get(FieldNames.FIELD_NAME);
				row.groupOid = g.get(FieldNames.FIELD_OBJECT_ID);
				try {
					List<Map<String, Object>> scenes = PictureBookUtil.listScenes(user, row.groupOid);
					row.pb1Scenes = scenes.size();
					row.pb1Images = scenes.stream().anyMatch(m -> m.get("imageObjectId") != null && !String.valueOf(m.get("imageObjectId")).isBlank());
				} catch (Exception e) {
					row.error = "listScenes: " + e.getMessage();
				}
				String slug = PbPipelineUtil.deriveSlug(row.book);
				row.slug = slug;
				BaseRecord existing = slug != null ? PbBookUtil.findBookBySlug(user, slug, orgId) : null;
				if (existing != null) {
					row.status = "already-v3";
					rows.add(row);
					continue;
				}
				taken++;
				rows.add(row);
				long t0 = System.currentTimeMillis();
				try {
					ImportResult r = PbMigrationUtil.importV1Book(user, dataPath, row.groupOid);
					row.importMs = System.currentTimeMillis() - t0;
					row.status = "imported";
					row.bookOid = r.bookObjectId;
					row.slug = r.slug;
					row.scenesImported = r.scenesImported;
					row.scenesFailed = r.scenesFailed;
					row.landscapesImported = r.landscapesImported;
					row.warnings = r.warnings != null ? r.warnings.size() : 0;
					row.locationHardFailure = r.locationPass != null && r.locationPass.hardFailure;
					importedAs.put(row, user);
					measureImported(user, orgId, row);
				} catch (PictureBookException e) {
					row.importMs = System.currentTimeMillis() - t0;
					row.status = "import " + e.getStatus();
					row.error = e.getMessage();
				} catch (Exception e) {
					row.importMs = System.currentTimeMillis() - t0;
					row.status = "import threw";
					row.error = e.toString();
				}
				logger.info("[p74-sweep] " + ownerName + " \"" + row.book + "\" -> " + row.status + " slug=" + row.slug
					+ " scenes=" + row.scenesImported + "/" + row.scenesFailed + " landscapes=" + row.landscapesImported
					+ " pages=" + row.pages + " imaged=" + row.imagedPages + " bytesOk=" + row.bytesOk
					+ " health=" + row.healthErrors + " warnings=" + row.warnings + " locHard=" + row.locationHardFailure
					+ " (" + row.importMs + " ms)" + (row.error != null ? " error=" + row.error : ""));
			}
		}

		// Cleanup: every book this run imported, as its owner, unless asked to keep them.
		if (!keep) {
			for (Map.Entry<Row, BaseRecord> e : importedAs.entrySet()) {
				Row row = e.getKey();
				try {
					DeleteResult dr = PbDeleteUtil.deleteBookComplete(e.getValue(), row.bookOid);
					row.reset = dr.deleted ? "reset" : ("reset failed: " + dr.reason);
				} catch (Exception ex) {
					row.reset = "reset threw: " + ex.getMessage();
				}
			}
		}

		// Report.
		logger.info("[p74-sweep] === RESULT TABLE (" + rows.size() + " PB1 books) ===");
		logger.info("[p74-sweep] owner | org | book | pb1Scenes | pb1Images | status | slug | scenes ok/failed | landscapes | pages/imaged/bytesOk | healthErrors | healthWarns | warnings | locHard | ms | reset");
		for (Row r : rows) {
			logger.info("[p74-sweep] " + String.join(" | ", r.owner, r.org, r.book, String.valueOf(r.pb1Scenes), String.valueOf(r.pb1Images),
				r.status, String.valueOf(r.slug),
				"imported".equals(r.status) ? (r.scenesImported + "/" + r.scenesFailed) : String.valueOf(r.error),
				String.valueOf(r.landscapesImported), r.pages < 0 ? "" : (r.pages + "/" + r.imagedPages + "/" + r.bytesOk),
				r.healthErrors.toString(), r.healthWarns.toString(), String.valueOf(r.warnings), String.valueOf(r.locationHardFailure),
				String.valueOf(r.importMs), String.valueOf(r.reset)));
		}
		long alreadyV3 = rows.stream().filter(r -> "already-v3".equals(r.status)).count();
		long imported = rows.stream().filter(r -> "imported".equals(r.status)).count();
		logger.info("[p74-sweep] already-v3=" + alreadyV3 + " imported=" + imported + " failed=" + (rows.size() - alreadyV3 - imported)
			+ " unmeasuredOwners=" + unmeasured.size() + (unmeasured.isEmpty() ? "" : " " + unmeasured));

		// Verdict.
		List<String> importFailures = new ArrayList<>();
		List<String> bytesFailures = new ArrayList<>();
		List<String> resetFailures = new ArrayList<>();
		for (Row r : rows) {
			if ("already-v3".equals(r.status)) continue;
			if (!"imported".equals(r.status)) importFailures.add(r.owner + " \"" + r.book + "\": " + r.status + " " + r.error);
			if (!r.bytesBad.isEmpty()) bytesFailures.add(r.owner + " \"" + r.book + "\": " + r.bytesBad);
			if (r.reset != null && !"reset".equals(r.reset)) resetFailures.add(r.owner + " \"" + r.book + "\": " + r.reset);
		}
		assertEquals("PB1-only books that did not import: " + importFailures, 0, importFailures.size());
		assertEquals("imported pages whose image bytes did not read back: " + bytesFailures, 0, bytesFailures.size());
		assertEquals("imported books that could not be reset (the database is not as found): " + resetFailures, 0, resetFailures.size());
	}
}
