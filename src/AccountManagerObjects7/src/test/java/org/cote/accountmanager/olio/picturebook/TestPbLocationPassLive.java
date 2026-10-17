package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbLocationUtil.LocationPassResult;
import org.cote.accountmanager.olio.picturebook.PbLocationUtil.ProposalAcceptResult;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbProposalStatusEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 3, P3-3: the whole-book location pass against the LIVE LLM on the gate's route (LlmTestGate, BaseTest.setup),
 * on the design §30 row 3 exit shape - "scenes 3/7/12 share one tavern". The emulator-driven contract test is
 * {@link TestPbLocationProposal}; this one asks a real model to cluster eight scenes (seven with a setting) and
 * asserts only what the pass must do with ANY sane clustering: the three Gilded Tankard scenes land on ONE proposal,
 * the two scriptorium scenes land on one proposal, every setting-bearing scene is proposed somewhere, the pass
 * resolves nothing, and accepting the tavern creates exactly one location + map-free POI + store and re-points
 * scenes 3, 7 and 12. The proposed names are the model's and are logged, not asserted (the tavern's must at least
 * mention the tankard or a tavern). A second pass after the accept must not create a second place. Timings are
 * recorded, never asserted.
 * <p>
 * Throwaway book in {@code /Development/World Building} as {@code testUser1}; the chatConfig and its connection
 * ({@code OlioTestUtil.getOllamaOpenAIConfig}, model = {@code test.llm.model.analysis}) are deleted in {@code finally}.
 */
public class TestPbLocationPassLive extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String CFG_NAME = "P33 Live Cfg";

	private static final String HARBOUR = "The harbour road at dawn, gulls over the fishing boats";
	private static final String SCRIPTORIUM_A = "A cramped scriptorium in the abbey, candles guttering";
	private static final String TAVERN_3 = "The Gilded Tankard, a low-beamed tavern by the quay";
	private static final String SCRIPTORIUM_B = "The abbey scriptorium, the same candlelit desks after midnight";
	private static final String HILL = "The windswept hill road above the town, dusk";
	private static final String TAVERN_7 = "Inside the Gilded Tankard: the taproom, ale-stained tables, the fire low";
	private static final String TAVERN_12 = "Back at the Gilded Tankard, the tavern's taproom crowded for the festival";

	@Before
	public void setupEach() throws Exception {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
	}

	@Test
	public void livePassClustersSharedTavernAndAcceptResolvesIt() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		BaseRecord cfg = null;
		try {
			cfg = OlioTestUtil.getOllamaOpenAIConfig(u, CFG_NAME, testProperties);
			assertNotNull("chatConfig " + CFG_NAME, cfg);
			logger.info("[p33-live] route=" + testProperties.getProperty("test.llm.route") + " tier=" + testProperties.getProperty("test.llm.resolvedTier")
				+ " server=" + testProperties.getProperty("test.llm.connection.server") + " model=" + (Object) cfg.get("model"));

			bk = newBook(u, "live");
			BookContext bctx = PbBookUtil.openBookContext(u, bk);
			assertNotNull("The book must carry a world", bctx);
			long locGid = groupIdOf(bctx, FieldNames.FIELD_LOCATIONS);
			long poiGid = groupIdOf(bctx, "pointsOfInterest");
			long storeGid = groupIdOf(bctx, "stores");
			int locs0 = countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId);
			int pois0 = countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId);
			int stores0 = countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId);

			Map<Integer, BaseRecord> scenes = new LinkedHashMap<>();
			scenes.put(1, addScene(u, bk, 1, "Dawn on the Quay", HARBOUR));
			scenes.put(2, addScene(u, bk, 2, "The Copyist", SCRIPTORIUM_A));
			scenes.put(3, addScene(u, bk, 3, "Arrival", TAVERN_3));
			scenes.put(4, addScene(u, bk, 4, "After Midnight", SCRIPTORIUM_B));
			scenes.put(5, addScene(u, bk, 5, "Interlude", null));
			scenes.put(6, addScene(u, bk, 6, "The Road Out", HILL));
			scenes.put(7, addScene(u, bk, 7, "The Taproom", TAVERN_7));
			scenes.put(12, addScene(u, bk, 12, "Festival Night", TAVERN_12));

			long t0 = System.currentTimeMillis();
			LocationPassResult r1 = PbLocationUtil.proposeLocations(u, bk, cfg);
			long passMs = System.currentTimeMillis() - t0;
			logger.info("[p33-live] pass 1 (" + passMs + " ms): " + describe(r1));
			assertFalse("pass 1 hard failure: " + r1.warnings, r1.hardFailure);
			assertEquals("scenes considered", 7, r1.scenesConsidered);
			assertEquals("scenes skipped (no setting)", 1, r1.scenesSkipped);
			assertEquals("chunks", 1, r1.chunks);
			assertEquals("chunks parsed", 1, r1.chunksParsed);
			assertTrue("proposed >= 2 (tavern + scriptorium at least), got " + r1.proposed, r1.proposed >= 2);
			assertEquals(0, r1.updated);
			assertEquals(0, r1.settled);
			assertEquals(0, r1.retired);

			List<BaseRecord> rows = PbLocationUtil.listProposals(u, bk, null);
			assertEquals("proposal rows", r1.proposed, rows.size());
			for(BaseRecord p : rows) {
				logger.info("[p33-live] proposal " + (Object) p.get(FieldNames.FIELD_NAME) + " name='" + (Object) p.get(OlioFieldNames.FIELD_PB_PROPOSED_NAME)
					+ "' kind=" + (Object) p.get(OlioFieldNames.FIELD_PB_KIND) + " aliases=" + (Object) p.get(OlioFieldNames.FIELD_PB_ALIASES)
					+ " sameAs=" + (Object) p.get(OlioFieldNames.FIELD_PB_SAME_AS) + " scenes=" + indicesOf(p, scenes)
					+ " desc='" + (Object) p.get(FieldNames.FIELD_DESCRIPTION) + "'");
				assertEquals(PbProposalStatusEnumType.PROPOSED, PbLocationUtil.statusOf(p));
				assertNull("a pass resolves nothing", p.get(FieldNames.FIELD_LOCATION));
				assertNull("no known location, so no sameAs", p.get(OlioFieldNames.FIELD_PB_SAME_AS));
			}

			/// Every setting-bearing scene is proposed somewhere; the setting-less one nowhere. The pass does not
			/// de-duplicate a scene the model listed under two places (the prompt forbids it); log, do not assert.
			for(Map.Entry<Integer, BaseRecord> e : scenes.entrySet()) {
				List<BaseRecord> in = proposalsContaining(rows, e.getValue());
				if(e.getKey() == 5) {
					assertEquals("scene 5 has no setting and must not be proposed", 0, in.size());
					continue;
				}
				assertTrue("scene " + e.getKey() + " must be in at least one proposal", in.size() >= 1);
				if(in.size() > 1) {
					logger.warn("[p33-live] scene " + e.getKey() + " was listed under " + in.size() + " proposals: " + names(in));
				}
			}

			/// Scenes 3, 7 and 12 share ONE proposal; so do 2 and 4; the tavern is not the scriptorium
			BaseRecord tavern = sharedProposal(rows, "tavern", scenes.get(3), scenes.get(7), scenes.get(12));
			BaseRecord scriptorium = sharedProposal(rows, "scriptorium", scenes.get(2), scenes.get(4));
			assertFalse("tavern and scriptorium must be different proposals", oidOf(tavern).equals(oidOf(scriptorium)));
			String tavernText = ((String) tavern.get(OlioFieldNames.FIELD_PB_PROPOSED_NAME) + " " + tavern.get(FieldNames.FIELD_DESCRIPTION)
				+ " " + tavern.get(OlioFieldNames.FIELD_PB_ALIASES)).toLowerCase(Locale.ROOT);
			assertTrue("the tavern proposal must mention the tankard or a tavern: " + tavernText, tavernText.contains("tankard") || tavernText.contains("tavern"));
			assertFalse("scene 1 (harbour) is not the tavern", proposalsContaining(rows, scenes.get(1)).contains(tavern));
			assertFalse("scene 6 (hill road) is not the tavern", proposalsContaining(rows, scenes.get(6)).contains(tavern));

			/// Nothing resolved by the pass
			assertEquals("locations after pass", locs0, countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId));
			assertEquals("POIs after pass", pois0, countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId));
			assertEquals("stores after pass", stores0, countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId));
			for(Map.Entry<Integer, BaseRecord> e : scenes.entrySet()) {
				assertEquals("scene " + e.getKey() + " untouched by the pass", 0L, fkId(readScene(u, oidOf(e.getValue()), orgId), FieldNames.FIELD_LOCATION));
			}

			/// Accept the tavern: one location + POI + store, scenes 3/7/12 re-pointed, the rest untouched
			ProposalAcceptResult a = PbLocationUtil.acceptProposal(u, bk, tavern, false);
			logger.info("[p33-live] accept tavern: created=" + a.locationCreated + " repointed=" + a.scenesRepointed + " skipped=" + a.scenesSkipped
				+ " folded=" + a.aliasesFolded + " warnings=" + a.warnings + " location=" + (a.location != null ? (Object) a.location.get(FieldNames.FIELD_NAME) : null));
			assertTrue("location created", a.locationCreated);
			assertNotNull(a.location);
			assertEquals("scenes re-pointed", 3, a.scenesRepointed);
			assertEquals("scenes skipped", 0, a.scenesSkipped);
			long locId = idOf(a.location);
			assertTrue("POI linked", fkId(a.location, OlioFieldNames.FIELD_PB_POI) > 0L);
			assertEquals("one location created", locs0 + 1, countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId));
			assertEquals("one POI created", pois0 + 1, countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId));
			assertEquals("one store created", stores0 + 1, countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId));
			for(int i : new int[] {3, 7, 12}) {
				assertEquals("scene " + i + " now at the tavern", locId, fkId(readScene(u, oidOf(scenes.get(i)), orgId), FieldNames.FIELD_LOCATION));
			}
			for(int i : new int[] {1, 2, 4, 5, 6}) {
				assertEquals("scene " + i + " untouched by the accept", 0L, fkId(readScene(u, oidOf(scenes.get(i)), orgId), FieldNames.FIELD_LOCATION));
			}
			assertEquals(PbProposalStatusEnumType.ACCEPTED, PbLocationUtil.statusOf(a.proposal));
			assertEquals("proposal.location", locId, fkId(a.proposal, FieldNames.FIELD_LOCATION));

			/// A second live pass sees the tavern as a known location and must not make a second place for it.
			/// Whether the model names it identically (settled) or re-proposes it under another name with sameAs
			/// (proposed, folded on accept) is the model's call - logged, not asserted.
			t0 = System.currentTimeMillis();
			LocationPassResult r2 = PbLocationUtil.proposeLocations(u, bk, cfg);
			logger.info("[p33-live] pass 2 (" + (System.currentTimeMillis() - t0) + " ms): " + describe(r2));
			assertFalse("pass 2 hard failure: " + r2.warnings, r2.hardFailure);
			assertEquals(1, r2.chunksParsed);
			List<BaseRecord> rows2 = PbLocationUtil.listProposals(u, bk, null);
			for(BaseRecord p : rows2) {
				logger.info("[p33-live] after pass 2: " + (Object) p.get(FieldNames.FIELD_NAME) + " status=" + PbLocationUtil.statusOf(p)
					+ " sameAs=" + (Object) p.get(OlioFieldNames.FIELD_PB_SAME_AS) + " scenes=" + indicesOf(p, scenes));
			}
			assertEquals("the accepted row survives a re-run", 1, PbLocationUtil.listProposals(u, bk, PbProposalStatusEnumType.ACCEPTED).size());
			assertEquals("still one location", locs0 + 1, countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId));
			assertEquals("still one POI", pois0 + 1, countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId));
			assertEquals("still one store", stores0 + 1, countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId));
			for(int i : new int[] {3, 7, 12}) {
				assertEquals("scene " + i + " still at the tavern", locId, fkId(readScene(u, oidOf(scenes.get(i)), orgId), FieldNames.FIELD_LOCATION));
			}
		}
		finally {
			deleteBook(u, bk);
			if(cfg != null) {
				BaseRecord conn = cfg.get("connection");
				deleteQuietly(u, cfg);
				deleteQuietly(u, conn);
			}
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private String dataPath() {
		String p = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", p);
		return p;
	}

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull("Failed to resolve " + ORG_A, org);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + TEST_USER, u);
		return u;
	}

	private static long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	private static long idOf(BaseRecord rec) {
		return ((Number) rec.get(FieldNames.FIELD_ID)).longValue();
	}

	private static String oidOf(BaseRecord rec) {
		return rec.get(FieldNames.FIELD_OBJECT_ID);
	}

	private static long fkId(BaseRecord rec, String field) {
		Object o = rec.get(field);
		return (o instanceof BaseRecord ? idOf((BaseRecord) o) : 0L);
	}

	private BaseRecord newBook(BaseRecord u, String tag) {
		String slug = "p33" + tag + shortId();
		BaseRecord bk = PbBookUtil.createBook(u, dataPath(), slug, "P3-3 " + tag + " " + slug);
		assertNotNull("createBook " + slug, bk);
		return bk;
	}

	private void deleteBook(BaseRecord u, BaseRecord bk) {
		if(bk == null) {
			return;
		}
		try {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(u, oidOf(bk));
			logger.info("cleanup " + bk.get(FieldNames.FIELD_NAME) + ": deleted=" + res.deleted + " reason=" + res.reason);
		}
		catch(Exception e) {
			logger.error("cleanup " + bk.get(FieldNames.FIELD_NAME) + " failed: " + e.getMessage());
		}
	}

	private void deleteQuietly(BaseRecord u, BaseRecord rec) {
		if(rec == null) {
			return;
		}
		try {
			if(!ioContext.getAccessPoint().delete(u, rec)) {
				logger.warn("cleanup: " + rec.getSchema() + " " + (Object) rec.get(FieldNames.FIELD_NAME) + " was not deleted");
			}
		}
		catch(Exception e) {
			logger.error("cleanup: " + rec.getSchema() + " " + (Object) rec.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
		}
	}

	private static long groupIdOf(BookContext bctx, String worldField) {
		BaseRecord g = bctx.getGroup(worldField);
		assertNotNull("world group " + worldField, g);
		return idOf(g);
	}

	private int countInGroup(String model, long groupId, long orgId) {
		Query q = QueryUtil.getGroupQuery(model, null, groupId, orgId);
		q.setCache(false);
		return ioContext.getSearch().count(q);
	}

	private BaseRecord addScene(BaseRecord u, BaseRecord bk, int index, String title, String setting) throws Exception {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = PbBookUtil.createScene(u, bk, index, title, PbBookUtil.bookGroupPath(slug));
		assertNotNull("createScene " + index, scene);
		if(setting != null) {
			BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SETTING);
			patch.set(OlioFieldNames.FIELD_PB_SETTING, setting);
			assertNotNull("set setting on " + scene.get(FieldNames.FIELD_NAME), ioContext.getAccessPoint().update(u, patch));
		}
		return readScene(u, oidOf(scene), orgOf(u));
	}

	private BaseRecord readScene(BaseRecord actor, String objectId, long orgId) {
		BaseRecord s = PbBookUtil.readScene(actor, objectId, orgId);
		assertNotNull("scene " + objectId + " readable by " + actor.get(FieldNames.FIELD_NAME), s);
		return s;
	}

	private static List<BaseRecord> proposalsContaining(List<BaseRecord> rows, BaseRecord scene) {
		List<BaseRecord> out = new ArrayList<>();
		String soid = oidOf(scene);
		for(BaseRecord p : rows) {
			List<String> ids = p.get(OlioFieldNames.FIELD_PB_SCENE_OBJECT_IDS);
			if(ids != null && ids.contains(soid)) {
				out.add(p);
			}
		}
		return out;
	}

	/// The one proposal that lists every one of these scenes; fails naming the per-scene rows when there is none.
	private static BaseRecord sharedProposal(List<BaseRecord> rows, String what, BaseRecord... scenes) {
		List<BaseRecord> shared = new ArrayList<>(proposalsContaining(rows, scenes[0]));
		for(int i = 1; i < scenes.length; i++) {
			shared.retainAll(proposalsContaining(rows, scenes[i]));
		}
		StringBuilder detail = new StringBuilder();
		for(BaseRecord s : scenes) {
			detail.append(' ').append((Object) s.get(FieldNames.FIELD_NAME)).append('=').append(names(proposalsContaining(rows, s)));
		}
		assertEquals("the " + what + " scenes must share exactly one proposal:" + detail, 1, shared.size());
		return shared.get(0);
	}

	private static List<Integer> indicesOf(BaseRecord proposal, Map<Integer, BaseRecord> scenes) {
		List<Integer> out = new ArrayList<>();
		List<String> ids = proposal.get(OlioFieldNames.FIELD_PB_SCENE_OBJECT_IDS);
		if(ids == null) {
			return out;
		}
		for(Map.Entry<Integer, BaseRecord> e : scenes.entrySet()) {
			if(ids.contains(oidOf(e.getValue()))) {
				out.add(e.getKey());
			}
		}
		return out;
	}

	private static List<String> names(List<BaseRecord> recs) {
		List<String> out = new ArrayList<>();
		for(BaseRecord r : recs) {
			out.add(r.get(FieldNames.FIELD_NAME));
		}
		return out;
	}

	private static String describe(LocationPassResult r) {
		return "run=" + r.passRun + " scenes=" + r.scenesConsidered + " skipped=" + r.scenesSkipped + " chunks=" + r.chunksParsed + "/" + r.chunks
			+ " proposed=" + r.proposed + " updated=" + r.updated + " settled=" + r.settled + " retired=" + r.retired
			+ (r.hardFailure ? " HARD-FAILURE" : "") + " warnings=" + r.warnings;
	}
}
