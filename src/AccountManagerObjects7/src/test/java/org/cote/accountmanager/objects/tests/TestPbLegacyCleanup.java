package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbArtifactUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbGraphUtil;
import org.cote.accountmanager.olio.picturebook.PbLegacyCleanupUtil;
import org.cote.accountmanager.olio.picturebook.PbPipelineUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SdConfigUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.junit.Before;
import org.junit.Test;

/// Phase 10 P10-5 (PictureBook V3, design §37.5): PbLegacyCleanupUtil - the explicit, logged admin
/// task that enumerates (and only on request deletes) the rows only the removed PB1 / Kontext pipeline
/// wrote. Runs against the database this JVM opened (am7db by default; -Dtest.db.url / -Dtest.db.user /
/// -Dtest.db.password point it at the Docker stack DB).
///
/// Actors: a dedicated AccountAdministrators member (pb10LegacyAdmin - NOT the built-in admin user) for
/// the scan and purge, and testUser1 (who must stay a non-administrator: TestPbRetentionSweep's 403
/// precondition) for the refusal case. Every positive assertion is scoped to the ids this run created,
/// because the fixture book accumulates rows across runs and the organization holds other tests' legacy
/// nodes (TestPbGraph's scene-linked COMPOSITE nodes are live V3 data and must be reported as retained,
/// never deleted - that is the whole point of the retention rules).
public class TestPbLegacyCleanup extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String ADMIN_NAME = "pb10LegacyAdmin";
	private static final String NON_ADMIN_NAME = "testUser1";
	/** Stable slug: the fixture book (a V3 book, with an olio.pb.book row) is created once and re-opened. */
	private static final String SLUG = "pb10-legacy";
	/** A PB1-shaped group: holds olio.sd.config rows but no olio.pb.book row, like every PB1 book group. */
	private static final String PB1_GROUP = "~/PictureBooks/pb10-legacy-pb1";

	@Before
	public void cleanupSetup() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	private String dataPath() {
		return testProperties.getProperty("test.datagen.path");
	}

	/** Same precedence BaseTest.dbProperty uses, so the log names the database the scan ran against. */
	private static String dbUrl() {
		String sys = System.getProperty("test.db.url");
		return (sys != null && !sys.isBlank()) ? sys : testProperties.getProperty("test.db.url");
	}

	private OrganizationContext org() {
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull("Failed to resolve " + ORG_A, org);
		return org;
	}

	/// A non-admin user enrolled in AccountAdministrators - the actor the Console7 action expects. The
	/// role grant is an admin act, so the org admin is the setup actor; MemberUtil.member is idempotent.
	private BaseRecord admin() {
		return admin(org());
	}

	private BaseRecord admin(OrganizationContext org) {
		BaseRecord u = getCreateUser(ADMIN_NAME, org);
		assertNotNull("Failed to resolve " + ADMIN_NAME + " - check the $minLen5 rule", u);
		BaseRecord adminRole = AccessSchema.getSystemRole(AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR,
			RoleEnumType.USER.toString(), org.getOrganizationId());
		assertNotNull("AccountAdministrators role is null", adminRole);
		ioContext.getMemberUtil().member(org.getAdminUser(), adminRole, u, null, true);
		assertTrue(ADMIN_NAME + " must be an AccountAdministrators member", ioContext.getMemberUtil().isMember(u, adminRole, null));
		return u;
	}

	private BaseRecord nonAdmin() {
		BaseRecord u = getCreateUser(NON_ADMIN_NAME, org());
		assertNotNull("Failed to resolve " + NON_ADMIN_NAME, u);
		return u;
	}

	private static long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	private BaseRecord book(BaseRecord u) {
		BaseRecord existing = PbBookUtil.findBookBySlug(u, SLUG, orgOf(u));
		if(existing != null) {
			return existing;
		}
		BaseRecord created = PbBookUtil.createBook(u, dataPath(), SLUG, "Phase 10 P10-5 legacy cleanup fixture");
		assertNotNull("Failed to create the fixture book", created);
		return created;
	}

	private BaseRecord workflow(BaseRecord u, BaseRecord bk) {
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(SLUG));
		assertNotNull("Failed to resolve the fixture workflow", wf);
		return wf;
	}

	/** A node with a random handle, so no earlier run's row can satisfy an assertion. */
	private BaseRecord node(BaseRecord u, BaseRecord wf, String prefix, PbNodeTypeEnumType type, int ordinal) {
		String handle = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord n = PbGraphUtil.addNode(u, wf, handle, type, PbBookUtil.workflowGroupPath(SLUG), ordinal);
		assertNotNull("Failed to create node " + handle, n);
		return n;
	}

	/** A real {@code data.data} row created as the user, for an IMAGE artifact. */
	private BaseRecord data(BaseRecord u, String tag) throws Exception {
		BaseRecord image = RecordFactory.newInstance(ModelNames.MODEL_DATA);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, image, "p10-5-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + ".bin",
			PbBookUtil.artifactGroupPath(SLUG), orgOf(u));
		image.set(FieldNames.FIELD_CONTENT_TYPE, "application/octet-stream");
		byte[] bytes = new byte[64];
		Arrays.fill(bytes, (byte) 0x5a);
		ByteModelUtil.setValue(image, bytes);
		BaseRecord created = ioContext.getAccessPoint().create(u, image);
		assertNotNull("data " + tag, created);
		return created;
	}

	/** Get-or-create an {@code olio.sd.config} by name in a group path, as the user. */
	private BaseRecord sdConfig(BaseRecord u, String name, String groupPath, String compositeMode) throws Exception {
		BaseRecord group = ioContext.getPathUtil().makePath(u, ModelNames.MODEL_GROUP, ioContext.getRecordUtil().resolveUserPath(u, groupPath), "DATA", orgOf(u));
		assertNotNull("group " + groupPath, group);
		long groupId = ((Number) group.get(FieldNames.FIELD_ID)).longValue();
		BaseRecord existing = SdConfigUtil.findConfig(u, name, groupId, orgOf(u));
		if(existing != null) {
			return existing;
		}
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, cfg, name, groupPath, orgOf(u));
		cfg.set("compositeMode", compositeMode);
		cfg.set("style", "art"); /// limited field with no default; a bare record fails validation without it
		BaseRecord created = SdConfigUtil.createOrUpdateConfig(u, cfg);
		assertNotNull("sd.config " + name + " in " + groupPath, created);
		return created;
	}

	private static long idOf(BaseRecord r) {
		return ((Number) r.get(FieldNames.FIELD_ID)).longValue();
	}

	// ─────────────────────────────── report readers ───────────────────────────────

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> items(Map<String, Object> scan, String code) {
		for(Map<String, Object> cat : (List<Map<String, Object>>) scan.get("categories")) {
			if(code.equals(cat.get("code"))) {
				return (List<Map<String, Object>>) cat.get("items");
			}
		}
		fail("category " + code + " missing from the report");
		return null;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> retained(Map<String, Object> scan) {
		return (List<Map<String, Object>>) scan.get("retained");
	}

	private static Map<String, Object> find(List<Map<String, Object>> rows, String code, long id) {
		for(Map<String, Object> r : rows) {
			if(code.equals(r.get("code")) && r.get("id") instanceof Number && ((Number) r.get("id")).longValue() == id) {
				return r;
			}
		}
		return null;
	}

	private static Map<String, Object> deletable(Map<String, Object> scan, String code, long id) {
		return find(items(scan, code), code, id);
	}

	private static Map<String, Object> retained(Map<String, Object> scan, String code, long id) {
		return find(retained(scan), code, id);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> result(Map<String, Object> purge, String code, long id) {
		return find((List<Map<String, Object>>) purge.get("results"), code, id);
	}

	private BaseRecord rawById(String model, long id, long orgId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_ID, id);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID});
		q.setCache(false);
		return ioContext.getSearch().findRecord(q);
	}

	// ─────────────────────────────── tests ───────────────────────────────

	/// The task is organization-wide, so it is AccountAdministrators-only: a plain user is refused with
	/// 403 before any scan, and no user at all with 401. Nothing is read or written on either path.
	@Test
	public void nonAdministratorIsRefusedBeforeAnyScan() {
		BaseRecord u = nonAdmin();
		try {
			PbLegacyCleanupUtil.scan(u);
			fail("scan must refuse a non-administrator");
		}
		catch(PictureBookException e) {
			assertEquals(403, e.getStatus());
		}
		try {
			PbLegacyCleanupUtil.purge(u, null);
			fail("purge must refuse a non-administrator");
		}
		catch(PictureBookException e) {
			assertEquals(403, e.getStatus());
		}
		try {
			PbLegacyCleanupUtil.scan(null);
			fail("scan must refuse a null user");
		}
		catch(PictureBookException e) {
			assertEquals(401, e.getStatus());
		}
	}

	/// The retention rules and the purge, end to end on the live database:
	///  - a COMPOSITE node a scene links to (what PbServiceFacade.sceneTerminal reads) is retained, with
	///    the scene named in the reason, and survives the purge with its artifact;
	///  - an unlinked REFERENCE_STRIP node, the COMPOSITE node that consumes it (binding.sourceNode), that
	///    binding, and the strip's referenceStrip-role IMAGE artifact are all listed as deletable (the
	///    strip is referenced only from another deletable legacy node - the fixpoint), and the purge removes
	///    exactly them plus the artifact's unshared data.data;
	///  - an unreferenced book-sdConfig row in a PB1-shaped group (no olio.pb.book) is deletable; the same
	///    row name in the V3 fixture book's group is retained as adoptable by setBookSdConfig; an unreferenced
	///    compositeMode=kontext config is deletable; one the book's compositeSdConfig points at is retained
	///    with the referrer named, and the purge never touches it.
	@Test
	public void scanRetainsLiveLegacyDataListsDeadRowsAndPurgeRemovesExactlyThose() throws Exception {
		BaseRecord u = admin();
		long orgId = orgOf(u);
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);

		/// Live: scene-linked COMPOSITE node with its composite artifact (TestPbGraph's P1-10 shape).
		int sceneIndex = 100000 + Math.abs(UUID.randomUUID().hashCode() % 800000);
		BaseRecord scene = PbBookUtil.createScene(u, bk, sceneIndex, "p10-5 live page", PbBookUtil.bookGroupPath(SLUG));
		assertNotNull("scene", scene);
		BaseRecord live = node(u, wf, "p105-live-composite", PbNodeTypeEnumType.COMPOSITE, 10);
		BaseRecord link = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_SCENE_NODE
		});
		link.set(FieldNames.FIELD_ID, scene.get(FieldNames.FIELD_ID));
		link.set(FieldNames.FIELD_OBJECT_ID, scene.get(FieldNames.FIELD_OBJECT_ID));
		link.set(FieldNames.FIELD_NAME, scene.get(FieldNames.FIELD_NAME));
		link.set(OlioFieldNames.FIELD_PB_SCENE_NODE, live);
		assertNotNull("sceneNode link patch must be written", ioContext.getAccessPoint().update(u, link));
		BaseRecord liveArtifact = PbArtifactUtil.persistArtifact(u, live, "composite", PbArtifactTypeEnumType.TEXT,
			PbBookUtil.artifactGroupPath(SLUG), null, "p10-5 composite stand-in", null, null);
		assertNotNull("live artifact", liveArtifact);

		/// Dead: strip -> composite (binding.sourceNode), strip produced a referenceStrip IMAGE with real bytes.
		BaseRecord strip = node(u, wf, "p105-dead-strip", PbNodeTypeEnumType.REFERENCE_STRIP, 20);
		BaseRecord dead = node(u, wf, "p105-dead-composite", PbNodeTypeEnumType.COMPOSITE, 21);
		BaseRecord binding = PbGraphUtil.addBinding(u, wf, dead, PbPipelineUtil.ROLE_REFERENCE_STRIP, 0, strip, null, PbBookUtil.workflowGroupPath(SLUG));
		assertNotNull("binding", binding);
		BaseRecord stripData = data(u, "strip");
		BaseRecord stripArtifact = PbArtifactUtil.persistArtifact(u, strip, PbPipelineUtil.ROLE_REFERENCE_STRIP, PbArtifactTypeEnumType.IMAGE,
			PbBookUtil.artifactGroupPath(SLUG), stripData, null, null, null);
		assertNotNull("strip artifact", stripArtifact);

		/// Configs: PB1-shaped group (no olio.pb.book) vs the V3 book group; unreferenced vs referenced kontext.
		BaseRecord pb1Settings = sdConfig(u, PbBookUtil.BOOK_SD_CONFIG_NAME, PB1_GROUP, "flux2");
		BaseRecord pb1Kontext = sdConfig(u, "pb10-legacy-kontext-" + UUID.randomUUID().toString().substring(0, 8), PB1_GROUP, PbLegacyCleanupUtil.LEGACY_COMPOSITE_MODE);
		BaseRecord v3Settings = sdConfig(u, PbBookUtil.BOOK_SD_CONFIG_NAME, PbBookUtil.bookGroupPath(SLUG), "flux2");
		BaseRecord v3Kontext = sdConfig(u, "pb10-legacy-kontext-referenced", PbBookUtil.bookGroupPath(SLUG), PbLegacyCleanupUtil.LEGACY_COMPOSITE_MODE);
		BaseRecord bookPatch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_COMPOSITE_SD_CONFIG
		});
		bookPatch.set(FieldNames.FIELD_ID, bk.get(FieldNames.FIELD_ID));
		bookPatch.set(FieldNames.FIELD_OBJECT_ID, bk.get(FieldNames.FIELD_OBJECT_ID));
		bookPatch.set(FieldNames.FIELD_NAME, bk.get(FieldNames.FIELD_NAME));
		bookPatch.set(OlioFieldNames.FIELD_PB_COMPOSITE_SD_CONFIG, v3Kontext);
		assertNotNull("book.compositeSdConfig patch must be written", ioContext.getAccessPoint().update(u, bookPatch));

		long liveId = idOf(live), liveArtifactId = idOf(liveArtifact), stripId = idOf(strip), deadId = idOf(dead);
		long bindingId = idOf(binding), stripArtifactId = idOf(stripArtifact), stripDataId = idOf(stripData);
		long pb1SettingsId = idOf(pb1Settings), pb1KontextId = idOf(pb1Kontext), v3SettingsId = idOf(v3Settings), v3KontextId = idOf(v3Kontext);

		// ── scan ──
		Map<String, Object> scan = PbLegacyCleanupUtil.scan(u);
		assertEquals(Long.valueOf(orgId), scan.get("organizationId"));
		logger.info("P10-5 scan on " + dbUrl() + ": total deletable " + scan.get("total") + ", retained " + scan.get("retainedTotal") + ", warnings " + scan.get("warnings"));

		Map<String, Object> liveRow = retained(scan, PbLegacyCleanupUtil.PB1_LEGACY_NODE, liveId);
		assertNotNull("the scene-linked COMPOSITE node must be retained", liveRow);
		assertTrue("the retained reason names the scene link: " + liveRow.get("reason"),
			String.valueOf(liveRow.get("reason")).contains("scene " + scene.get(FieldNames.FIELD_OBJECT_ID)));
		assertEquals("COMPOSITE", liveRow.get("nodeType"));
		assertNull("a retained node is never also deletable", deletable(scan, PbLegacyCleanupUtil.PB1_LEGACY_NODE, liveId));
		assertNull("a retained node's artifact is never deletable", deletable(scan, PbLegacyCleanupUtil.PB1_LEGACY_ARTIFACT, liveArtifactId));

		Map<String, Object> stripRow = deletable(scan, PbLegacyCleanupUtil.PB1_LEGACY_NODE, stripId);
		assertNotNull("the unlinked REFERENCE_STRIP node is deletable", stripRow);
		assertEquals("REFERENCE_STRIP", stripRow.get("nodeType"));
		assertNotNull("the consuming COMPOSITE node is deletable", deletable(scan, PbLegacyCleanupUtil.PB1_LEGACY_NODE, deadId));
		assertNull("neither dead node is retained", retained(scan, PbLegacyCleanupUtil.PB1_LEGACY_NODE, stripId));
		assertNull("neither dead node is retained", retained(scan, PbLegacyCleanupUtil.PB1_LEGACY_NODE, deadId));
		assertNotNull("the strip->composite binding is deletable", deletable(scan, PbLegacyCleanupUtil.PB1_LEGACY_BINDING, bindingId));
		Map<String, Object> artRow = deletable(scan, PbLegacyCleanupUtil.PB1_LEGACY_ARTIFACT, stripArtifactId);
		assertNotNull("the strip's referenceStrip artifact is deletable", artRow);
		assertEquals(PbPipelineUtil.ROLE_REFERENCE_STRIP, artRow.get("role"));
		assertEquals("the artifact's data.data id travels with it", Long.valueOf(stripDataId), artRow.get("dataId"));
		int bindingListings = 0;
		for(Map<String, Object> r : items(scan, PbLegacyCleanupUtil.PB1_LEGACY_BINDING)) {
			if(((Number) r.get("id")).longValue() == bindingId) {
				bindingListings++;
			}
		}
		assertEquals("a binding reachable from both of its nodes is listed once", 1, bindingListings);

		assertNotNull("book-sdConfig in a PB1-shaped group is deletable", deletable(scan, PbLegacyCleanupUtil.PB1_SETTINGS_ROW, pb1SettingsId));
		assertNotNull("an unreferenced kontext config is deletable", deletable(scan, PbLegacyCleanupUtil.PB1_KONTEXT_CONFIG, pb1KontextId));
		Map<String, Object> v3SettingsRow = retained(scan, PbLegacyCleanupUtil.PB1_SETTINGS_ROW, v3SettingsId);
		assertNotNull("book-sdConfig in a V3 book's group is retained (adoptable)", v3SettingsRow);
		assertTrue(String.valueOf(v3SettingsRow.get("reason")), String.valueOf(v3SettingsRow.get("reason")).contains("adoptable"));
		assertNull(deletable(scan, PbLegacyCleanupUtil.PB1_SETTINGS_ROW, v3SettingsId));
		Map<String, Object> v3KontextRow = retained(scan, PbLegacyCleanupUtil.PB1_KONTEXT_CONFIG, v3KontextId);
		assertNotNull("a referenced kontext config is retained", v3KontextRow);
		assertTrue("the referrer is named: " + v3KontextRow.get("referencedBy"),
			String.valueOf(v3KontextRow.get("referencedBy")).contains("olio.pb.book " + bk.get(FieldNames.FIELD_OBJECT_ID) + " (compositeSdConfig)"));
		assertNull(deletable(scan, PbLegacyCleanupUtil.PB1_KONTEXT_CONFIG, v3KontextId));

		/// Deletable and retained are disjoint over the whole report, not just this run's ids.
		Set<String> deletableKeys = new LinkedHashSet<>();
		for(String code : PbLegacyCleanupUtil.PURGE_ORDER) {
			for(Map<String, Object> r : items(scan, code)) {
				assertTrue("duplicate deletable row " + r, deletableKeys.add(r.get("model") + "#" + r.get("id")));
			}
		}
		for(Map<String, Object> r : retained(scan)) {
			assertFalse("row both retained and deletable: " + r, deletableKeys.contains(r.get("model") + "#" + r.get("id")));
		}

		// ── purge, restricted to the graph categories first: configs must be untouched ──
		Map<String, Object> graphOnly = PbLegacyCleanupUtil.purge(u, Arrays.asList(
			PbLegacyCleanupUtil.PB1_LEGACY_BINDING, PbLegacyCleanupUtil.PB1_LEGACY_ARTIFACT, PbLegacyCleanupUtil.PB1_LEGACY_NODE));
		logger.info("P10-5 graph purge: deleted " + graphOnly.get("deleted") + ", denied " + graphOnly.get("denied") + ", failed " + graphOnly.get("failed") + ", warnings " + graphOnly.get("warnings"));
		assertEquals(PbLegacyCleanupUtil.OUTCOME_DELETED, result(graphOnly, PbLegacyCleanupUtil.PB1_LEGACY_BINDING, bindingId).get("outcome"));
		Map<String, Object> artResult = result(graphOnly, PbLegacyCleanupUtil.PB1_LEGACY_ARTIFACT, stripArtifactId);
		assertEquals(PbLegacyCleanupUtil.OUTCOME_DELETED, artResult.get("outcome"));
		assertEquals("artifact + its unshared data.data", 2, artResult.get("count"));
		assertEquals(PbLegacyCleanupUtil.OUTCOME_DELETED, result(graphOnly, PbLegacyCleanupUtil.PB1_LEGACY_NODE, stripId).get("outcome"));
		assertEquals(PbLegacyCleanupUtil.OUTCOME_DELETED, result(graphOnly, PbLegacyCleanupUtil.PB1_LEGACY_NODE, deadId).get("outcome"));
		assertNull("a restricted purge never lists a config", result(graphOnly, PbLegacyCleanupUtil.PB1_SETTINGS_ROW, pb1SettingsId));
		assertNull(result(graphOnly, PbLegacyCleanupUtil.PB1_KONTEXT_CONFIG, pb1KontextId));
		assertNull("the retained node is never in the results", result(graphOnly, PbLegacyCleanupUtil.PB1_LEGACY_NODE, liveId));

		assertNull("binding gone", rawById(OlioModelNames.MODEL_PB_BINDING, bindingId, orgId));
		assertNull("strip artifact gone", rawById(OlioModelNames.MODEL_PB_ARTIFACT, stripArtifactId, orgId));
		assertNull("strip data.data gone", rawById(ModelNames.MODEL_DATA, stripDataId, orgId));
		assertNull("strip node gone", rawById(OlioModelNames.MODEL_PB_NODE, stripId, orgId));
		assertNull("dead composite node gone", rawById(OlioModelNames.MODEL_PB_NODE, deadId, orgId));
		assertNotNull("the scene-linked node survives", PbGraphUtil.readNodeById(u, liveId, orgId));
		assertNotNull("its composite artifact survives", rawById(OlioModelNames.MODEL_PB_ARTIFACT, liveArtifactId, orgId));
		assertNotNull("configs untouched by the restricted purge", rawById(OlioModelNames.MODEL_SD_CONFIG, pb1SettingsId, orgId));
		assertNotNull(rawById(OlioModelNames.MODEL_SD_CONFIG, pb1KontextId, orgId));

		// ── purge everything that remains deletable: the PB1-group configs go, the retained ones stay ──
		Map<String, Object> all = PbLegacyCleanupUtil.purge(u, null);
		logger.info("P10-5 full purge: deleted " + all.get("deleted") + ", denied " + all.get("denied") + ", failed " + all.get("failed") + ", warnings " + all.get("warnings"));
		assertEquals(PbLegacyCleanupUtil.OUTCOME_DELETED, result(all, PbLegacyCleanupUtil.PB1_SETTINGS_ROW, pb1SettingsId).get("outcome"));
		assertEquals(PbLegacyCleanupUtil.OUTCOME_DELETED, result(all, PbLegacyCleanupUtil.PB1_KONTEXT_CONFIG, pb1KontextId).get("outcome"));
		assertNull(result(all, PbLegacyCleanupUtil.PB1_SETTINGS_ROW, v3SettingsId));
		assertNull(result(all, PbLegacyCleanupUtil.PB1_KONTEXT_CONFIG, v3KontextId));
		assertNull(rawById(OlioModelNames.MODEL_SD_CONFIG, pb1SettingsId, orgId));
		assertNull(rawById(OlioModelNames.MODEL_SD_CONFIG, pb1KontextId, orgId));
		assertNotNull("the V3 book's settings row survives", rawById(OlioModelNames.MODEL_SD_CONFIG, v3SettingsId, orgId));
		assertNotNull("the referenced kontext config survives", rawById(OlioModelNames.MODEL_SD_CONFIG, v3KontextId, orgId));

		// ── re-scan: nothing of this run's dead set is listed any more; the live rows are still retained ──
		Map<String, Object> again = PbLegacyCleanupUtil.scan(u);
		for(String code : PbLegacyCleanupUtil.PURGE_ORDER) {
			for(long id : new long[] {stripId, deadId, bindingId, stripArtifactId, pb1SettingsId, pb1KontextId}) {
				assertNull(code + " " + id + " must not reappear", deletable(again, code, id));
				assertNull(code + " " + id + " must not reappear as retained", retained(again, code, id));
			}
		}
		assertNotNull(retained(again, PbLegacyCleanupUtil.PB1_LEGACY_NODE, liveId));
		assertNotNull(retained(again, PbLegacyCleanupUtil.PB1_SETTINGS_ROW, v3SettingsId));
		assertNotNull(retained(again, PbLegacyCleanupUtil.PB1_KONTEXT_CONFIG, v3KontextId));
	}

	/// Read-only. The dry run the Console7 action would log, against this database, per organization:
	/// counts per category and every row. -Dp105.orgs=/Development,/Public names the organizations
	/// (default: the fixture organization); each must already exist - nothing is created here except the
	/// pb10LegacyAdmin actor and its AccountAdministrators membership. This is the enumeration the P10-5
	/// hand-back quotes for am7db and the stack DB. Nothing is deleted.
	@Test
	public void dryRunReportForThisDatabase() {
		String orgs = System.getProperty("p105.orgs", ORG_A);
		for(String path : orgs.split(",")) {
			path = path.trim();
			if(path.isEmpty()) {
				continue;
			}
			OrganizationContext org = ioContext.getOrganizationContext(path, OrganizationEnumType.UNKNOWN);
			assertTrue("organization " + path + " must exist on " + dbUrl() + " (the dry run never creates one)", org.isInitialized());
			BaseRecord u = admin(org);
			Map<String, Object> scan = PbLegacyCleanupUtil.scan(u);
			List<String> lines = new ArrayList<>();
			for(String code : PbLegacyCleanupUtil.PURGE_ORDER) {
				List<Map<String, Object>> rows = items(scan, code);
				lines.add(code + "=" + rows.size());
				for(Map<String, Object> r : rows) {
					logger.warn("P10-5 would delete " + r);
				}
			}
			for(Map<String, Object> r : retained(scan)) {
				logger.warn("P10-5 retained " + r);
			}
			logger.warn("P10-5 dry run on " + dbUrl() + " org " + scan.get("organizationId") + " (" + path + "): " + String.join(", ", lines)
				+ "; retained=" + scan.get("retainedTotal") + "; warnings=" + scan.get("warnings"));
			assertNotNull(scan.get("total"));
			assertNotNull(scan.get("retainedTotal"));
		}
	}
}
