package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.LlmEmulator;
import org.cote.accountmanager.olio.picturebook.PbLocationUtil.LocationPassResult;
import org.cote.accountmanager.olio.picturebook.PbLocationUtil.ProposalAcceptResult;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConnectionDialectEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.cote.accountmanager.schema.type.PbLocationKindEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbProposalStatusEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 3, P3-3: the whole-book location pass ({@link PbLocationUtil#proposeLocations}) and its review
 * ({@link PbLocationUtil#acceptProposal} / {@link PbLocationUtil#rejectProposal}) against the live {@code am7db},
 * driven DETERMINISTICALLY through the EMULATOR dialect ({@link LlmEmulator} {@code cluster-locations}
 * synthesizer) so every assertion is about what the pass does with a clustering, not about what a model said.
 * The live-LLM counterpart on a real manuscript is {@code TestPbLocationPassLive}.
 * <ol>
 *  <li>a pass writes one PROPOSED row per place in the book's group, groups the scenes that share a setting,
 *      skips scenes with no setting, creates no location / POI / store and touches no scene; a second pass
 *      re-writes the same rows (same objectIds, new passRun) and retires nothing;</li>
 *  <li>accepting creates the location with its map-free POI and store, re-points the proposal's scenes the
 *      user may update ({@code overwrite=false} leaves a scene that already has a location), marks the row
 *      ACCEPTED with the location and a reason; accept/reject are 409 unless PROPOSED; a stranger in the
 *      organization can neither run the pass, see, nor accept; another book's proposal is 404; a re-run leaves
 *      settled rows alone;</li>
 *  <li>{@code sameAs} resolves to the known location and folds the proposed name in as an alias; a locked
 *      location keeps its aliases (warning) but its scenes are still re-pointed ({@code overwrite=true});</li>
 *  <li>a PROPOSED row the current pass did not produce is retired only after a complete pass: a hard LLM
 *      failure (HTTP 500 on the one chunk) retires nothing, and a complete pass whose model returned an empty
 *      array retires everything PROPOSED (§6.C 172) - the next complete pass regenerates it;</li>
 *  <li>a cluster the model split off a place by room, view, time of day, weather or season is folded back into
 *      the place by name shape (its name becomes an alias, its scenes and description merge); a place that merely
 *      shares words with another (an inn on a road) is not;</li>
 *  <li>the facade's {@link PbServiceFacade#acceptLocationProposal} materializes the location's LOCATION node only
 *      when the book already has a workflow and the location is unlocked (P3-4 seam); it never creates the
 *      workflow;</li>
 *  <li>a chunk answered with prose leaves a {@code no-json} failure record carrying the whole reply in the
 *      result's warnings, and {@link PbServiceFacade#locationPassDto} / {@link PbServiceFacade#stripRaw} put
 *      {@code rawResponseLength} on the wire instead of the text while the result keeps it (P3-4 transport
 *      boundary).</li>
 *  <li>(case11, P4-4) a ChapBook page has no {@code setting}: the pass clusters it by its {@code sdPrompt},
 *      then by its stanza, and skips a page with neither ({@link PbLocationUtil#settingTextOf}); the text the
 *      emulator was handed is read back off the proposal rows it produced.</li>
 * </ol>
 * Lives in {@code org.cote.accountmanager.olio.picturebook} for the package-private {@code PbGraphUtil.patchOf}
 * and the {@code PbLocationUtil} helpers. Each case uses its own throwaway book in {@code /Development/World
 * Building} as {@code testUser1}, deletes it and its emulator {@code system.connection} in {@code finally}; the
 * olio principal is used only to lock a fixture, never to assert through.
 */
public class TestPbLocationProposal extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String STRANGER_USER = "p33Stranger";
	/// case09: entitled on the Book group only, never on the world's Locations group.
	private static final String COLLAB_USER = "p33Collab";
	private static final String MODEL = "qwen3:8b";
	private static final int REQUEST_TIMEOUT_SEC = 20;

	/// The emulator names a cluster after the setting up to its first comma, so these are the proposed names.
	private static final String TAVERN_SETTING = "The Gilded Tankard, a low-beamed tavern by the quay";
	private static final String TAVERN_NAME = "The Gilded Tankard";
	private static final String HILL_SETTING = "A windswept hill road at dusk";
	private static final String HILL_NAME = "A Windswept Hill Road At Dusk";

	private Path tmpRoot;
	private final List<BaseRecord> connections = new ArrayList<>();

	@Before
	public void setupEach() throws Exception {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
		tmpRoot = Files.createTempDirectory("am7-pb-location-proposal-");
		LlmEmulator.configure(tmpRoot.toString(), null);
		LlmEmulator.resetCounters();
		Chat.clearLastCallError();
	}

	@After
	public void teardownEach() throws Exception {
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
		LlmEmulator.configure(null, null);
		LlmEmulator.resetCounters();
		if(tmpRoot != null) {
			deleteTree(tmpRoot);
		}
	}

	// ─────────────────────────────── emulator fixture ───────────────────────────────

	private String newSet(String manifestJson) throws Exception {
		String set = "p33-" + UUID.randomUUID().toString().substring(0, 8);
		Path dir = tmpRoot.resolve(set);
		Files.createDirectories(dir);
		if(manifestJson != null) {
			Files.write(dir.resolve(LlmEmulator.MANIFEST_FILE), manifestJson.getBytes(StandardCharsets.UTF_8));
		}
		return set;
	}

	private static void deleteTree(Path root) throws Exception {
		if(!Files.exists(root)) {
			return;
		}
		try (Stream<Path> s = Files.walk(root)) {
			s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				}
				catch(Exception e) {
					/// best effort
				}
			});
		}
	}

	/// Chat.configureChat re-queries the connection by FK id and resolves the dialect from THAT row, so the
	/// connection must be persisted; it is deleted in the case's finally.
	private BaseRecord persistConnection(BaseRecord user, String name, String serverUrl) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord c = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CONNECTION, user, null, plist);
		assertNotNull("connection factory newInstance returned null", c);
		c.set("serverUrl", serverUrl);
		c.set("requestTimeout", REQUEST_TIMEOUT_SEC);
		c.set(FieldNames.FIELD_DIALECT, ConnectionDialectEnumType.EMULATOR);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, c);
		assertNotNull("AccessPoint.create returned null for system.connection '" + name + "'", created);
		long connId = created.get(FieldNames.FIELD_ID);
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_CONNECTION, FieldNames.FIELD_ID, connId);
		cq.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_ORGANIZATION_ID, "serverUrl", "requestTimeout", "apiKey", FieldNames.FIELD_DIALECT, FieldNames.FIELD_UPSTREAM});
		cq.setCache(false);
		BaseRecord back = IOSystem.getActiveContext().getAccessPoint().find(user, cq);
		assertNotNull("persisted connection id=" + connId + " could not be read back", back);
		assertEquals("system.connection.dialect did not persist", ConnectionDialectEnumType.EMULATOR, back.getEnum(FieldNames.FIELD_DIALECT));
		connections.add(back);
		return back;
	}

	/// In-memory chatConfig bound to an EMULATOR connection whose serverUrl names {@code set}.
	private BaseRecord emulatorConfig(BaseRecord user, String set) throws Exception {
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord conn = persistConnection(user, "P33 Emu Conn " + nonce, LlmEmulator.SCHEME + set);
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "P33 Emu Cfg " + nonce);
		BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
		assertNotNull("chatConfig factory newInstance returned null", cfg);
		cfg.set("model", MODEL);
		cfg.set("stream", false);
		cfg.set("connection", conn);
		return cfg;
	}

	private void deleteConnections(BaseRecord user) {
		for(BaseRecord c : connections) {
			try {
				if(!ioContext.getAccessPoint().delete(user, c)) {
					logger.warn("cleanup: connection " + c.get(FieldNames.FIELD_NAME) + " was not deleted");
				}
			}
			catch(Exception e) {
				logger.error("cleanup: connection " + c.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
			}
		}
		connections.clear();
	}

	// ─────────────────────────────── book fixture ───────────────────────────────

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

	private BaseRecord stranger(long orgId) {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord s = ioContext.getFactory().getCreateUser(org.getAdminUser(), STRANGER_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + STRANGER_USER, s);
		assertEquals("stranger must be in the same organization", orgId, orgOf(s));
		return s;
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

	private BaseRecord olioUser(long orgId) {
		BaseRecord o = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal", o);
		return o;
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

	private BookContext bookContext(BaseRecord u, BaseRecord bk) {
		BookContext bc = PbBookUtil.openBookContext(u, bk);
		assertNotNull("The book must carry a world", bc);
		return bc;
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

	/// Titles must not contain " - ": the emulator strips a trailing " - <title>" from each scene line.
	private BaseRecord addScene(BaseRecord u, BaseRecord bk, int index, String title, String setting) throws Exception {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = PbBookUtil.createScene(u, bk, index, title, PbBookUtil.bookGroupPath(slug));
		assertNotNull("createScene " + index, scene);
		if(setting != null) {
			setSetting(u, scene, setting);
		}
		return readScene(u, oidOf(scene), orgOf(u));
	}

	private void setSetting(BaseRecord u, BaseRecord scene, String setting) throws Exception {
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SETTING);
		patch.set(OlioFieldNames.FIELD_PB_SETTING, setting);
		assertNotNull("set setting on " + scene.get(FieldNames.FIELD_NAME), ioContext.getAccessPoint().update(u, patch));
	}

	private void pointSceneAt(BaseRecord u, BaseRecord scene, BaseRecord loc) throws Exception {
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_LOCATION);
		patch.set(FieldNames.FIELD_LOCATION, loc);
		assertNotNull("point " + scene.get(FieldNames.FIELD_NAME) + " at " + loc.get(FieldNames.FIELD_NAME), ioContext.getAccessPoint().update(u, patch));
	}

	private BaseRecord readScene(BaseRecord actor, String objectId, long orgId) {
		BaseRecord s = PbBookUtil.readScene(actor, objectId, orgId);
		assertNotNull("scene " + objectId + " readable by " + actor.get(FieldNames.FIELD_NAME), s);
		return s;
	}

	private BaseRecord readLocation(BaseRecord actor, String objectId, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_LOCATION, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(PbBookUtil.locationRequest());
		q.setCache(false);
		return ioContext.getAccessPoint().find(actor, q);
	}

	private static BaseRecord byName(List<BaseRecord> proposals, String rowName) {
		for(BaseRecord p : proposals) {
			if(rowName.equals(p.get(FieldNames.FIELD_NAME))) {
				return p;
			}
		}
		return null;
	}

	private static BaseRecord require(List<BaseRecord> proposals, String rowName) {
		BaseRecord p = byName(proposals, rowName);
		assertNotNull("proposal row " + rowName + " among " + names(proposals), p);
		return p;
	}

	private static List<String> names(List<BaseRecord> recs) {
		List<String> out = new ArrayList<>();
		for(BaseRecord r : recs) {
			out.add(r.get(FieldNames.FIELD_NAME));
		}
		return out;
	}

	private static PbLocationKindEnumType kindOf(BaseRecord rec) {
		Object k = rec.get(OlioFieldNames.FIELD_PB_KIND);
		return PbLocationUtil.kindOf(k != null ? String.valueOf(k) : null);
	}

	private static void assertSceneIds(BaseRecord proposal, BaseRecord... scenes) {
		List<String> ids = proposal.get(OlioFieldNames.FIELD_PB_SCENE_OBJECT_IDS);
		assertNotNull("sceneObjectIds", ids);
		assertEquals("scene count on " + proposal.get(FieldNames.FIELD_NAME) + ": " + ids, scenes.length, ids.size());
		for(BaseRecord s : scenes) {
			assertTrue(proposal.get(FieldNames.FIELD_NAME) + " must list scene " + s.get(FieldNames.FIELD_NAME), ids.contains(oidOf(s)));
		}
	}

	private static int expectStatus(String what, int expected, Runnable r) {
		try {
			r.run();
		}
		catch(PictureBookException e) {
			assertEquals(what + " status (" + e.getMessage() + ")", expected, e.getStatus());
			return e.getStatus();
		}
		fail(what + " must throw PictureBookException " + expected);
		return -1;
	}

	private static boolean anyContains(List<String> warnings, String needle) {
		for(String w : warnings) {
			if(w != null && w.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	// ─────────────────────────────── cases ───────────────────────────────

	@Test
	public void case01_passProposesWithoutResolvingAndIsIdempotent() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "pass");
			BookContext bctx = bookContext(u, bk);
			long locGid = groupIdOf(bctx, FieldNames.FIELD_LOCATIONS);
			long poiGid = groupIdOf(bctx, "pointsOfInterest");
			long storeGid = groupIdOf(bctx, "stores");
			int locs0 = countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId);
			int pois0 = countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId);
			int stores0 = countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId);

			BaseRecord s1 = addScene(u, bk, 1, "Arrival", TAVERN_SETTING);
			BaseRecord s2 = addScene(u, bk, 2, "The Road", HILL_SETTING);
			BaseRecord s3 = addScene(u, bk, 3, "Return", TAVERN_SETTING);
			BaseRecord s4 = addScene(u, bk, 4, "Interlude", null);

			BaseRecord cfg = emulatorConfig(u, newSet(null));
			LocationPassResult r1 = PbLocationUtil.proposeLocations(u, bk, cfg);
			logger.info("case01 pass 1: " + describe(r1));
			assertFalse("pass 1 hard failure: " + r1.warnings, r1.hardFailure);
			assertEquals("scenes considered", 3, r1.scenesConsidered);
			assertEquals("scenes skipped (no setting)", 1, r1.scenesSkipped);
			assertEquals("chunks", 1, r1.chunks);
			assertEquals("chunks parsed", 1, r1.chunksParsed);
			assertEquals("proposed", 2, r1.proposed);
			assertEquals("updated", 0, r1.updated);
			assertEquals("settled", 0, r1.settled);
			assertEquals("retired", 0, r1.retired);
			assertNotNull(r1.passRun);

			List<BaseRecord> rows = PbLocationUtil.listProposals(u, bk, null);
			assertEquals("proposal rows: " + names(rows), 2, rows.size());
			assertEquals(2, PbLocationUtil.listProposals(u, bk, PbProposalStatusEnumType.PROPOSED).size());
			assertEquals(0, PbLocationUtil.listProposals(u, bk, PbProposalStatusEnumType.ACCEPTED).size());

			BaseRecord tavern = require(rows, PbLocationUtil.proposalName(TAVERN_NAME));
			assertEquals(TAVERN_NAME, tavern.get(OlioFieldNames.FIELD_PB_PROPOSED_NAME));
			assertEquals(PbLocationKindEnumType.INTERIOR, kindOf(tavern));
			assertEquals(TAVERN_SETTING, tavern.get(FieldNames.FIELD_DESCRIPTION));
			assertNull("no known location, so no sameAs", tavern.get(OlioFieldNames.FIELD_PB_SAME_AS));
			assertEquals(PbProposalStatusEnumType.PROPOSED, PbLocationUtil.statusOf(tavern));
			assertEquals(r1.passRun, tavern.get(OlioFieldNames.FIELD_PB_PASS_RUN));
			assertEquals("proposal belongs to the book", idOf(bk), fkId(tavern, OlioFieldNames.FIELD_PB_BOOK));
			assertSceneIds(tavern, s1, s3);
			assertNull("nothing resolved yet", tavern.get(FieldNames.FIELD_LOCATION));

			BaseRecord hill = require(rows, PbLocationUtil.proposalName(HILL_NAME));
			assertEquals(HILL_NAME, hill.get(OlioFieldNames.FIELD_PB_PROPOSED_NAME));
			assertEquals(PbLocationKindEnumType.EXTERIOR, kindOf(hill));
			assertSceneIds(hill, s2);

			/// Nothing resolved: no location, POI or store, and no scene touched
			assertEquals("locations", locs0, countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId));
			assertEquals("POIs", pois0, countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId));
			assertEquals("stores", stores0, countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId));
			for(BaseRecord s : new BaseRecord[] {s1, s2, s3, s4}) {
				assertEquals("scene " + s.get(FieldNames.FIELD_NAME) + " untouched", 0L, fkId(readScene(u, oidOf(s), orgId), FieldNames.FIELD_LOCATION));
			}

			/// Idempotent: same rows, re-written under the new run, nothing retired
			LocationPassResult r2 = PbLocationUtil.proposeLocations(u, bk, cfg);
			logger.info("case01 pass 2: " + describe(r2));
			assertFalse(r2.hardFailure);
			assertNotEquals(r1.passRun, r2.passRun);
			assertEquals("proposed on re-run", 0, r2.proposed);
			assertEquals("updated on re-run", 2, r2.updated);
			assertEquals("settled on re-run", 0, r2.settled);
			assertEquals("retired on re-run", 0, r2.retired);
			List<BaseRecord> rows2 = PbLocationUtil.listProposals(u, bk, null);
			assertEquals(2, rows2.size());
			BaseRecord tavern2 = require(rows2, PbLocationUtil.proposalName(TAVERN_NAME));
			assertEquals("same row, not a duplicate", oidOf(tavern), oidOf(tavern2));
			assertEquals(r2.passRun, tavern2.get(OlioFieldNames.FIELD_PB_PASS_RUN));
			assertSceneIds(tavern2, s1, s3);
		}
		finally {
			deleteBook(u, bk);
			deleteConnections(u);
		}
	}

	@Test
	public void case02_acceptResolvesRepointsAndSettles() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		BaseRecord other = null;
		try {
			bk = newBook(u, "accept");
			BookContext bctx = bookContext(u, bk);
			long locGid = groupIdOf(bctx, FieldNames.FIELD_LOCATIONS);
			long poiGid = groupIdOf(bctx, "pointsOfInterest");
			long storeGid = groupIdOf(bctx, "stores");

			BaseRecord elsewhere = PbLocationUtil.getCreateLocation(u, bk, "Elsewhere", null, PbLocationKindEnumType.ABSTRACT, null);
			assertNotNull(elsewhere);
			int locs0 = countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId);
			int pois0 = countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId);
			int stores0 = countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId);

			BaseRecord s1 = addScene(u, bk, 1, "Arrival", TAVERN_SETTING);
			BaseRecord s2 = addScene(u, bk, 2, "The Road", HILL_SETTING);
			BaseRecord s3 = addScene(u, bk, 3, "Return", TAVERN_SETTING);
			pointSceneAt(u, s3, elsewhere);

			BaseRecord cfg = emulatorConfig(u, newSet(null));

			/// A stranger in the organization cannot run the pass
			BaseRecord stranger = stranger(orgId);
			final BaseRecord fbk = bk;
			try {
				PbLocationUtil.proposeLocations(stranger, fbk, cfg);
				fail("stranger must not run the pass");
			}
			catch(PictureBookException e) {
				assertTrue("stranger pass status " + e.getStatus(), e.getStatus() == 403 || e.getStatus() == 404);
			}

			LocationPassResult r1 = PbLocationUtil.proposeLocations(u, bk, cfg);
			logger.info("case02 pass 1: " + describe(r1));
			assertFalse(r1.hardFailure);
			assertEquals(2, r1.proposed);
			List<BaseRecord> rows = PbLocationUtil.listProposals(u, bk, null);
			BaseRecord tavern = require(rows, PbLocationUtil.proposalName(TAVERN_NAME));
			BaseRecord hill = require(rows, PbLocationUtil.proposalName(HILL_NAME));
			assertNull("'Elsewhere' is not in any setting", tavern.get(OlioFieldNames.FIELD_PB_SAME_AS));

			/// The stranger sees none of it and cannot accept
			assertEquals("stranger list", 0, PbLocationUtil.listProposals(stranger, bk, null).size());
			final BaseRecord ftavern = tavern;
			try {
				PbLocationUtil.acceptProposal(stranger, fbk, ftavern, false);
				fail("stranger must not accept");
			}
			catch(PictureBookException e) {
				assertTrue("stranger accept status " + e.getStatus(), e.getStatus() == 403 || e.getStatus() == 404);
			}

			/// Another book's proposal is not found through this book
			other = newBook(u, "other");
			addScene(u, other, 1, "Elsewhere", HILL_SETTING);
			LocationPassResult ro = PbLocationUtil.proposeLocations(u, other, cfg);
			assertEquals(1, ro.proposed);
			final BaseRecord foreign = ro.proposals.get(0);
			expectStatus("accept another book's proposal", 404, () -> PbLocationUtil.acceptProposal(u, fbk, foreign, false));
			expectStatus("reject another book's proposal", 404, () -> PbLocationUtil.rejectProposal(u, fbk, foreign, "x"));

			/// Accept the tavern without overwriting: s1 re-pointed, s3 (already at Elsewhere) skipped
			ProposalAcceptResult a = PbLocationUtil.acceptProposal(u, bk, tavern, false);
			logger.info("case02 accept tavern: created=" + a.locationCreated + " repointed=" + a.scenesRepointed + " skipped=" + a.scenesSkipped
				+ " folded=" + a.aliasesFolded + " warnings=" + a.warnings);
			assertTrue("location created", a.locationCreated);
			assertNotNull(a.location);
			assertEquals(TAVERN_NAME, a.location.get(FieldNames.FIELD_NAME));
			assertEquals(PbLocationKindEnumType.INTERIOR, kindOf(a.location));
			assertEquals(TAVERN_SETTING, a.location.get(FieldNames.FIELD_DESCRIPTION));
			assertEquals("scenes re-pointed", 1, a.scenesRepointed);
			assertEquals("scenes skipped", 1, a.scenesSkipped);
			assertEquals("aliases folded on a created location", 0, a.aliasesFolded);
			long locId = idOf(a.location);
			assertTrue("POI linked", fkId(a.location, OlioFieldNames.FIELD_PB_POI) > 0L);
			assertEquals("one location created", locs0 + 1, countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId));
			assertEquals("one POI created", pois0 + 1, countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId));
			assertEquals("one store created", stores0 + 1, countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId));
			assertEquals("s1 now at the tavern", locId, fkId(readScene(u, oidOf(s1), orgId), FieldNames.FIELD_LOCATION));
			assertEquals("s3 left at Elsewhere", idOf(elsewhere), fkId(readScene(u, oidOf(s3), orgId), FieldNames.FIELD_LOCATION));
			assertEquals("s2 untouched", 0L, fkId(readScene(u, oidOf(s2), orgId), FieldNames.FIELD_LOCATION));

			BaseRecord accepted = a.proposal;
			assertNotNull(accepted);
			assertEquals(PbProposalStatusEnumType.ACCEPTED, PbLocationUtil.statusOf(accepted));
			assertEquals("proposal.location", locId, fkId(accepted, FieldNames.FIELD_LOCATION));
			assertEquals("created:" + TAVERN_NAME, accepted.get(OlioFieldNames.FIELD_PB_REASON));
			assertEquals(1, PbLocationUtil.listProposals(u, bk, PbProposalStatusEnumType.ACCEPTED).size());

			/// Not PROPOSED any more: 409 either way
			expectStatus("accept twice", 409, () -> PbLocationUtil.acceptProposal(u, fbk, ftavern, true));
			expectStatus("reject after accept", 409, () -> PbLocationUtil.rejectProposal(u, fbk, ftavern, "late"));

			/// Reject the hill with a note; then it cannot be accepted
			BaseRecord rejected = PbLocationUtil.rejectProposal(u, bk, hill, "  not a real place  ");
			assertEquals(PbProposalStatusEnumType.REJECTED, PbLocationUtil.statusOf(rejected));
			assertEquals("not a real place", rejected.get(OlioFieldNames.FIELD_PB_REASON));
			assertNull(rejected.get(FieldNames.FIELD_LOCATION));
			final BaseRecord fhill = hill;
			expectStatus("accept after reject", 409, () -> PbLocationUtil.acceptProposal(u, fbk, fhill, true));
			assertEquals("hill scene untouched by a rejection", 0L, fkId(readScene(u, oidOf(s2), orgId), FieldNames.FIELD_LOCATION));
			assertEquals("no second location", locs0 + 1, countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId));

			/// A re-run leaves both settled rows alone and retires nothing
			LocationPassResult r2 = PbLocationUtil.proposeLocations(u, bk, cfg);
			logger.info("case02 pass 2: " + describe(r2));
			assertFalse(r2.hardFailure);
			assertEquals("settled", 2, r2.settled);
			assertEquals(0, r2.proposed);
			assertEquals(0, r2.updated);
			assertEquals(0, r2.retired);
			List<BaseRecord> rows2 = PbLocationUtil.listProposals(u, bk, null);
			assertEquals(2, rows2.size());
			assertEquals(PbProposalStatusEnumType.ACCEPTED, PbLocationUtil.statusOf(require(rows2, PbLocationUtil.proposalName(TAVERN_NAME))));
			assertEquals(PbProposalStatusEnumType.REJECTED, PbLocationUtil.statusOf(require(rows2, PbLocationUtil.proposalName(HILL_NAME))));
			assertEquals("no stale PROPOSED after settling", 0, PbLocationUtil.listProposals(u, bk, PbProposalStatusEnumType.PROPOSED).size());
		}
		finally {
			deleteBook(u, other);
			deleteBook(u, bk);
			deleteConnections(u);
		}
	}

	@Test
	public void case03_sameAsFoldsIntoKnownLocationUnlessLocked() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "sameas");
			BookContext bctx = bookContext(u, bk);
			long locGid = groupIdOf(bctx, FieldNames.FIELD_LOCATIONS);

			BaseRecord gilded = PbLocationUtil.getCreateLocation(u, bk, "Gilded Tankard", "A tavern.", PbLocationKindEnumType.INTERIOR, null);
			BaseRecord hill = PbLocationUtil.getCreateLocation(u, bk, "Windswept Hill", "A hill.", PbLocationKindEnumType.EXTERIOR, null);
			assertNotNull(gilded);
			assertNotNull(hill);
			/// Lock the hill as the principal that owns world sub-records
			BaseRecord olio = olioUser(orgId);
			BaseRecord lockPatch = PbGraphUtil.patchOf(hill, OlioModelNames.MODEL_PB_LOCATION, OlioFieldNames.FIELD_PB_LOCKED);
			lockPatch.set(OlioFieldNames.FIELD_PB_LOCKED, true);
			assertNotNull("lock hill", ioContext.getAccessPoint().update(olio, lockPatch));
			assertEquals(Boolean.TRUE, readLocation(u, oidOf(hill), orgId).get(OlioFieldNames.FIELD_PB_LOCKED));
			int locs0 = countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId);

			BaseRecord s1 = addScene(u, bk, 1, "Arrival", TAVERN_SETTING);
			BaseRecord s2 = addScene(u, bk, 2, "The Road", HILL_SETTING);
			pointSceneAt(u, s2, gilded);

			BaseRecord cfg = emulatorConfig(u, newSet(null));
			LocationPassResult r1 = PbLocationUtil.proposeLocations(u, bk, cfg);
			logger.info("case03 pass 1: " + describe(r1));
			assertFalse(r1.hardFailure);
			assertEquals(2, r1.proposed);
			List<BaseRecord> rows = PbLocationUtil.listProposals(u, bk, null);
			BaseRecord tavernP = require(rows, PbLocationUtil.proposalName(TAVERN_NAME));
			BaseRecord hillP = require(rows, PbLocationUtil.proposalName(HILL_NAME));
			assertEquals("the setting contains the known name", "Gilded Tankard", tavernP.get(OlioFieldNames.FIELD_PB_SAME_AS));
			assertEquals("Windswept Hill", hillP.get(OlioFieldNames.FIELD_PB_SAME_AS));

			/// sameAs → the known location; the proposed name becomes an alias
			ProposalAcceptResult a = PbLocationUtil.acceptProposal(u, bk, tavernP, true);
			logger.info("case03 accept tavern: created=" + a.locationCreated + " folded=" + a.aliasesFolded + " warnings=" + a.warnings);
			assertFalse("resolved to the known location", a.locationCreated);
			assertEquals(idOf(gilded), idOf(a.location));
			assertEquals("sameAs:Gilded Tankard", a.proposal.get(OlioFieldNames.FIELD_PB_REASON));
			assertEquals("aliases folded", 1, a.aliasesFolded);
			assertEquals(1, a.scenesRepointed);
			List<String> aliases = readLocation(u, oidOf(gilded), orgId).get(OlioFieldNames.FIELD_PB_ALIASES);
			assertTrue("alias folded into the location: " + aliases, aliases.contains(TAVERN_NAME));
			assertEquals(idOf(gilded), fkId(readScene(u, oidOf(s1), orgId), FieldNames.FIELD_LOCATION));
			/// and the folded alias now resolves to it
			BaseRecord byAlias = PbLocationUtil.findByNameOrAlias(olio, bctx, TAVERN_NAME);
			assertNotNull("alias lookup", byAlias);
			assertEquals(idOf(gilded), idOf(byAlias));

			/// Locked: aliases untouched (warning), scenes still re-pointed when overwriting
			ProposalAcceptResult b = PbLocationUtil.acceptProposal(u, bk, hillP, true);
			logger.info("case03 accept hill: created=" + b.locationCreated + " folded=" + b.aliasesFolded + " warnings=" + b.warnings);
			assertFalse(b.locationCreated);
			assertEquals(idOf(hill), idOf(b.location));
			assertEquals("locked location folds nothing", 0, b.aliasesFolded);
			assertTrue("locked warning: " + b.warnings, anyContains(b.warnings, "is locked"));
			assertEquals("s2 moved from the tavern to the hill", 1, b.scenesRepointed);
			assertEquals(idOf(hill), fkId(readScene(u, oidOf(s2), orgId), FieldNames.FIELD_LOCATION));
			List<String> hillAliases = readLocation(u, oidOf(hill), orgId).get(OlioFieldNames.FIELD_PB_ALIASES);
			assertTrue("locked aliases unchanged: " + hillAliases, hillAliases == null || hillAliases.isEmpty());
			assertEquals("sameAs:Windswept Hill", b.proposal.get(OlioFieldNames.FIELD_PB_REASON));

			assertEquals("no location created by either acceptance", locs0, countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId));
		}
		finally {
			deleteBook(u, bk);
			deleteConnections(u);
		}
	}

	@Test
	public void case04_staleProposalsRetireOnlyAfterACompletePass() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "retire");
			bookContext(u, bk);
			BaseRecord s1 = addScene(u, bk, 1, "Arrival", TAVERN_SETTING);
			BaseRecord s2 = addScene(u, bk, 2, "The Road", HILL_SETTING);

			BaseRecord good = emulatorConfig(u, newSet(null));
			LocationPassResult r1 = PbLocationUtil.proposeLocations(u, bk, good);
			logger.info("case04 pass 1: " + describe(r1));
			assertEquals(2, r1.proposed);
			BaseRecord hillRow = require(PbLocationUtil.listProposals(u, bk, null), PbLocationUtil.proposalName(HILL_NAME));

			/// The hill scene is now set in the tavern, so a complete pass no longer proposes the hill
			setSetting(u, s2, TAVERN_SETTING);

			/// (a) a hard LLM failure on the only chunk: nothing written, nothing retired
			BaseRecord faulty = emulatorConfig(u, newSet("{\"faults\":[{\"kind\":\"" + LlmEmulator.KIND_CLUSTER_LOCATIONS + "\",\"mode\":\"http500\",\"occurrence\":1}]}"));
			LocationPassResult r2 = PbLocationUtil.proposeLocations(u, bk, faulty);
			logger.info("case04 pass 2 (http500): " + describe(r2));
			assertTrue("hard failure reported, not thrown", r2.hardFailure);
			assertEquals(1, r2.chunks);
			assertEquals(0, r2.chunksParsed);
			assertEquals(0, r2.proposed);
			assertEquals(0, r2.updated);
			assertEquals("nothing retired after a failed pass", 0, r2.retired);
			assertTrue("hard-failure warning: " + r2.warnings, anyContains(r2.warnings, "hard failure"));
			assertEquals("both rows survive", 2, PbLocationUtil.listProposals(u, bk, PbProposalStatusEnumType.PROPOSED).size());
			assertNotNull(PbLocationUtil.readProposal(u, oidOf(hillRow), orgId));

			/// (b) a complete pass: the tavern row is re-written, the hill row retired
			LocationPassResult r3 = PbLocationUtil.proposeLocations(u, bk, good);
			logger.info("case04 pass 3: " + describe(r3));
			assertFalse(r3.hardFailure);
			assertEquals(1, r3.chunksParsed);
			assertEquals(0, r3.proposed);
			assertEquals(1, r3.updated);
			assertEquals("stale hill retired", 1, r3.retired);
			List<BaseRecord> rows3 = PbLocationUtil.listProposals(u, bk, null);
			assertEquals("rows after retirement: " + names(rows3), 1, rows3.size());
			assertSceneIds(require(rows3, PbLocationUtil.proposalName(TAVERN_NAME)), s1, s2);
			assertNull("hill row gone", PbLocationUtil.readProposal(u, oidOf(hillRow), orgId));

			/// (c) §6.C 172: a complete pass whose model returned [] is complete, so it retires every PROPOSED row
			BaseRecord empty = emulatorConfig(u, newSet("{\"faults\":[{\"kind\":\"" + LlmEmulator.KIND_CLUSTER_LOCATIONS + "\",\"mode\":\"empty\",\"occurrence\":1}]}"));
			LocationPassResult r4 = PbLocationUtil.proposeLocations(u, bk, empty);
			logger.info("case04 pass 4 (empty array): " + describe(r4));
			assertFalse(r4.hardFailure);
			assertEquals(1, r4.chunksParsed);
			assertEquals(0, r4.proposed);
			assertEquals("empty array retires the remaining PROPOSED row", 1, r4.retired);
			assertEquals(0, PbLocationUtil.listProposals(u, bk, null).size());

			/// and the next complete pass regenerates it
			LocationPassResult r5 = PbLocationUtil.proposeLocations(u, bk, good);
			logger.info("case04 pass 5: " + describe(r5));
			assertEquals(1, r5.proposed);
			assertEquals(1, PbLocationUtil.listProposals(u, bk, PbProposalStatusEnumType.PROPOSED).size());
		}
		finally {
			deleteBook(u, bk);
			deleteConnections(u);
		}
	}

	/**
	 * Design §7: a place and its rooms or its doorstep are ONE location; inside/outside is a variant, not a second
	 * record. The live model split "Gilded Tankard" from "Gilded Tankard Taproom" on 2026-10-16 despite the prompt,
	 * so the pass folds such splits back by name shape; the next live run split the scriptorium by time of day
	 * ("Abbey Scriptorium" / "Abbey Scriptorium Night") instead, and time of day, weather and season are variant keys
	 * too. The emulator names a cluster from its setting text up to the first comma, which is exactly how to make it
	 * split a place four ways on purpose. An inn on Harbour Road is a place of its own and must NOT be folded into
	 * the road.
	 */
	@Test
	public void case05_subPlaceClustersFoldIntoTheirPlace() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = null;
		try {
			bk = newBook(u, "fold");
			bookContext(u, bk);
			String taproom = "The Gilded Tankard taproom, ale stained tables and a low fire";
			String outside = "Outside the Gilded Tankard, lanterns lit on the quay";
			String midnight = "The Gilded Tankard at midnight, the fire out and the benches empty";
			String inn = "Harbour Road Inn, a whitewashed inn above the fishing boats";
			String road = "Harbour Road, gulls over the boats";
			BaseRecord s1 = addScene(u, bk, 1, "Arrival", TAVERN_SETTING);
			BaseRecord s2 = addScene(u, bk, 2, "The Taproom", taproom);
			BaseRecord s3 = addScene(u, bk, 3, "Lanterns", outside);
			BaseRecord s4 = addScene(u, bk, 4, "The Road", HILL_SETTING);
			BaseRecord s5 = addScene(u, bk, 5, "The Inn", inn);
			BaseRecord s6 = addScene(u, bk, 6, "Gulls", road);
			BaseRecord s7 = addScene(u, bk, 7, "Closing Time", midnight);

			LocationPassResult r1 = PbLocationUtil.proposeLocations(u, bk, emulatorConfig(u, newSet(null)));
			logger.info("case05 pass: " + describe(r1));
			assertFalse("hard failure: " + r1.warnings, r1.hardFailure);
			assertEquals("chunks parsed", 1, r1.chunksParsed);
			assertEquals("folded (taproom + outside view + midnight)", 3, r1.folded);
			assertEquals("proposed (tavern, hill, inn, road)", 4, r1.proposed);
			assertTrue("fold warning names the taproom: " + r1.warnings, anyContains(r1.warnings, "folded 'The Gilded Tankard Taproom' into 'The Gilded Tankard' - its taproom"));
			assertTrue("fold warning names the outside view: " + r1.warnings, anyContains(r1.warnings, "folded 'Outside The Gilded Tankard' into 'The Gilded Tankard' - a view outside it"));
			assertTrue("fold warning names the time of day: " + r1.warnings, anyContains(r1.warnings, "folded 'The Gilded Tankard At Midnight' into 'The Gilded Tankard' - its midnight variant"));

			List<BaseRecord> rows = PbLocationUtil.listProposals(u, bk, null);
			assertEquals("rows: " + names(rows), 4, rows.size());
			BaseRecord tavern = require(rows, PbLocationUtil.proposalName(TAVERN_NAME));
			assertSceneIds(tavern, s1, s2, s3, s7);
			assertEquals("inside and outside views make the place MIXED", PbLocationKindEnumType.MIXED, kindOf(tavern));
			List<String> aliases = tavern.get(OlioFieldNames.FIELD_PB_ALIASES);
			assertNotNull(aliases);
			assertTrue("folded names become aliases: " + aliases, aliases.contains("The Gilded Tankard Taproom"));
			assertTrue("folded names become aliases: " + aliases, aliases.contains("Outside The Gilded Tankard"));
			assertTrue("folded names become aliases: " + aliases, aliases.contains("The Gilded Tankard At Midnight"));
			String desc = tavern.get(FieldNames.FIELD_DESCRIPTION);
			assertTrue("descriptions are joined: " + desc, desc.startsWith(TAVERN_SETTING) && desc.contains(taproom) && desc.contains(outside) && desc.contains(midnight));
			assertNull("no split cluster survives as its own row", byName(rows, PbLocationUtil.proposalName("The Gilded Tankard Taproom")));
			assertNull("no split cluster survives as its own row", byName(rows, PbLocationUtil.proposalName("Outside The Gilded Tankard")));
			assertNull("no split cluster survives as its own row", byName(rows, PbLocationUtil.proposalName("The Gilded Tankard At Midnight")));

			assertSceneIds(require(rows, PbLocationUtil.proposalName(HILL_NAME)), s4);
			assertSceneIds(require(rows, PbLocationUtil.proposalName("Harbour Road Inn")), s5);
			assertSceneIds(require(rows, PbLocationUtil.proposalName("Harbour Road")), s6);

			/// The rule itself, on names alone
			assertEquals("its taproom", PbLocationUtil.subPlaceOf("gilded tankard", "gilded tankard taproom"));
			assertEquals("a view inside it", PbLocationUtil.subPlaceOf("gilded tankard", "inside the gilded tankard"));
			assertEquals("the same name", PbLocationUtil.subPlaceOf("gilded tankard", PbLocationUtil.placeKey("The Gilded Tankard")));
			assertEquals("its night variant", PbLocationUtil.subPlaceOf("abbey scriptorium", "abbey scriptorium night"));
			assertEquals("its dawn variant", PbLocationUtil.subPlaceOf("harbour road", "harbour road at dawn"));
			assertEquals("its winter night variant", PbLocationUtil.subPlaceOf("abbey scriptorium", "abbey scriptorium on a winter night"));
			assertEquals("its taproom by night", PbLocationUtil.subPlaceOf("gilded tankard", "gilded tankard taproom at night"));
			assertEquals("its taproom stairs", PbLocationUtil.subPlaceOf("gilded tankard", "gilded tankard taproom stairs"));
			assertNull("an inn is a place, not a part", PbLocationUtil.subPlaceOf("harbour road", "harbour road inn"));
			assertNull("a leading qualifier is left alone (Night Market is a place)", PbLocationUtil.subPlaceOf("market", "night market"));
			assertNull("the base never folds into its part", PbLocationUtil.subPlaceOf("gilded tankard taproom", "gilded tankard"));
			assertNull("a different place with a shared word", PbLocationUtil.subPlaceOf("gilded tankard", "gilded cup"));
			assertNull("a part plus a stranger word is something else", PbLocationUtil.subPlaceOf("gilded tankard", "gilded tankard taproom brawl"));
		}
		finally {
			deleteBook(u, bk);
			deleteConnections(u);
		}
	}

	/**
	 * P3-4 seam: the facade's {@link PbServiceFacade#acceptLocationProposal} materializes the location's LOCATION
	 * node (design §28 step 3) only when the book already has a workflow and the location is unlocked - a book
	 * with no graph yet gets its node at the first render, and a locked {@code sameAs} target gets no node at
	 * all. Acceptance never creates the workflow.
	 */
	@Test
	public void case06_facadeAcceptMaterializesTheNodeOnlyWithAWorkflowAndUnlocked() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "facade");
			bookContext(u, bk);
			String bkOid = oidOf(bk);
			String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
			String inn = "Harbour Road Inn, a whitewashed inn above the fishing boats";
			BaseRecord s1 = addScene(u, bk, 1, "Arrival", TAVERN_SETTING);
			BaseRecord s2 = addScene(u, bk, 2, "The Road", HILL_SETTING);
			BaseRecord s3 = addScene(u, bk, 3, "Return", TAVERN_SETTING);
			BaseRecord s4 = addScene(u, bk, 4, "The Inn", inn);

			/// A known, locked location the hill resolves to through sameAs
			BaseRecord hill = PbLocationUtil.getCreateLocation(u, bk, "Windswept Hill", "A hill.", PbLocationKindEnumType.EXTERIOR, null);
			assertNotNull(hill);
			BaseRecord olio = olioUser(orgId);
			BaseRecord lockPatch = PbGraphUtil.patchOf(hill, OlioModelNames.MODEL_PB_LOCATION, OlioFieldNames.FIELD_PB_LOCKED);
			lockPatch.set(OlioFieldNames.FIELD_PB_LOCKED, true);
			assertNotNull("lock hill", ioContext.getAccessPoint().update(olio, lockPatch));

			LocationPassResult r1 = PbLocationUtil.proposeLocations(u, bk, emulatorConfig(u, newSet(null)));
			logger.info("case06 pass: " + describe(r1));
			assertFalse(r1.hardFailure);
			assertEquals(3, r1.proposed);
			List<BaseRecord> rows = PbLocationUtil.listProposals(u, bk, null);
			BaseRecord tavernP = require(rows, PbLocationUtil.proposalName(TAVERN_NAME));
			BaseRecord hillP = require(rows, PbLocationUtil.proposalName(HILL_NAME));
			BaseRecord innP = require(rows, PbLocationUtil.proposalName("Harbour Road Inn"));
			assertEquals("Windswept Hill", hillP.get(OlioFieldNames.FIELD_PB_SAME_AS));
			assertNull("no workflow before any acceptance", PbGraphUtil.findWorkflow(u, bk));

			/// (a) no workflow yet: the location is created and its scenes re-pointed, but no node and no workflow
			Map<String, Object> a = PbServiceFacade.acceptLocationProposal(u, bkOid, oidOf(tavernP), false);
			logger.info("case06 accept tavern (no workflow): " + JSONUtil.exportObject(a));
			assertEquals(Boolean.TRUE, a.get("locationCreated"));
			String tavernOid = (String) a.get("locationObjectId");
			assertNotNull(tavernOid);
			assertEquals(Integer.valueOf(2), a.get("scenesRepointed"));
			assertFalse("no node key without a workflow: " + a.keySet(), a.containsKey("nodeObjectId"));
			assertFalse(a.containsKey("nodeMaterialized"));
			assertFalse(a.containsKey("seeded"));
			assertNull("acceptance never creates the workflow", PbGraphUtil.findWorkflow(u, bk));
			BaseRecord tavern = readLocation(u, tavernOid, orgId);
			assertNotNull(tavern);
			assertEquals(idOf(tavern), fkId(readScene(u, oidOf(s1), orgId), FieldNames.FIELD_LOCATION));
			assertEquals(idOf(tavern), fkId(readScene(u, oidOf(s3), orgId), FieldNames.FIELD_LOCATION));

			/// (b) with a workflow: the new location gets its LOCATION node under the deterministic handle,
			///     seeded from nothing (no landscape nodes yet), the one re-pointed scene counted as skipped
			BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(slug));
			assertNotNull("workflow", wf);
			Map<String, Object> b = PbServiceFacade.acceptLocationProposal(u, bkOid, oidOf(innP), false);
			logger.info("case06 accept inn (workflow): " + JSONUtil.exportObject(b));
			assertEquals(Boolean.TRUE, b.get("locationCreated"));
			String innOid = (String) b.get("locationObjectId");
			assertNotNull(innOid);
			assertEquals(Integer.valueOf(1), b.get("scenesRepointed"));
			assertEquals(Boolean.TRUE, b.get("nodeMaterialized"));
			assertEquals(Integer.valueOf(0), b.get("seeded"));
			assertEquals("the re-pointed scene has no landscape to seed from", Integer.valueOf(1), b.get("seedSkipped"));
			String nodeOid = (String) b.get("nodeObjectId");
			assertNotNull(nodeOid);
			BaseRecord byHandle = PbPipelineUtil.findNodeByHandle(u, wf, PbPipelineUtil.locationHandle(innOid));
			assertNotNull("node under the location handle", byHandle);
			assertEquals(nodeOid, oidOf(byHandle));
			BaseRecord node = PbGraphUtil.readNode(u, nodeOid, orgId);
			assertNotNull(node);
			assertEquals(PbNodeTypeEnumType.LOCATION.name(), String.valueOf((Object) node.get(OlioFieldNames.FIELD_PB_NODE_TYPE)).toUpperCase());
			assertEquals(PbPipelineUtil.SCOPE_LOCATION, node.get(OlioFieldNames.FIELD_PB_SCOPE));
			assertEquals(innOid, node.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
			assertEquals(idOf(readLocation(u, innOid, orgId)), fkId(readScene(u, oidOf(s4), orgId), FieldNames.FIELD_LOCATION));
			assertEquals("the tavern accepted before the workflow existed still has no node", null,
				PbPipelineUtil.findNodeByHandle(u, wf, PbPipelineUtil.locationHandle(tavernOid)));

			/// (c) a locked sameAs target: scene re-pointed, aliases untouched, no node
			Map<String, Object> c = PbServiceFacade.acceptLocationProposal(u, bkOid, oidOf(hillP), false);
			logger.info("case06 accept hill (locked): " + JSONUtil.exportObject(c));
			assertEquals(Boolean.FALSE, c.get("locationCreated"));
			assertEquals(oidOf(hill), c.get("locationObjectId"));
			assertEquals(Integer.valueOf(1), c.get("scenesRepointed"));
			assertEquals(Integer.valueOf(0), c.get("aliasesFolded"));
			assertTrue("locked warning: " + c.get("warnings"), anyContains(castWarnings(c.get("warnings")), "is locked"));
			assertFalse("no node for a locked location: " + c.keySet(), c.containsKey("nodeObjectId"));
			assertFalse(c.containsKey("nodeMaterialized"));
			assertNull(PbPipelineUtil.findNodeByHandle(u, wf, PbPipelineUtil.locationHandle(oidOf(hill))));
			assertEquals(idOf(hill), fkId(readScene(u, oidOf(s2), orgId), FieldNames.FIELD_LOCATION));
			assertEquals("every row settled", 0, PbLocationUtil.listProposals(u, bk, PbProposalStatusEnumType.PROPOSED).size());
		}
		finally {
			deleteBook(u, bk);
			deleteConnections(u);
		}
	}

	/**
	 * P3-4 transport boundary: a chunk whose reply is prose instead of a JSON array leaves an extraction-failure
	 * record ({@code {context, kind:"no-json", error, rawResponse, failedAt}}) in {@code LocationPassResult.warnings}
	 * with the WHOLE reply, and {@link PbServiceFacade#locationPassDto} / {@link PbServiceFacade#stripRaw} replace
	 * {@code rawResponse} with {@code rawResponseLength} on the wire while the result object itself keeps the raw
	 * text. Driven by overriding the emulator's {@code scene-blurb} detector to the cluster-locations marker, so the
	 * earlier-registered blurb synthesizer answers the clustering prompt with two sentences of prose.
	 */
	@Test
	public void case07_transportStripsRawResponseButTheResultKeepsIt() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = null;
		try {
			bk = newBook(u, "strip");
			bookContext(u, bk);
			String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
			addScene(u, bk, 1, "Arrival", TAVERN_SETTING);
			addScene(u, bk, 2, "The Road", HILL_SETTING);

			/// scene-blurb sits before cluster-locations in the emulator's detector order; pointing its regex at the
			/// clustering prompt's own marker makes the blurb synthesizer win and reply with prose (no '[' at all).
			String manifest = "{\"kinds\":{\"" + LlmEmulator.KIND_SCENE_BLURB + "\":\"SCENE SETTINGS \\\\(one per line\"}}";
			LocationPassResult r = PbLocationUtil.proposeLocations(u, bk, emulatorConfig(u, newSet(manifest)));
			logger.info("case07 pass: " + describe(r));
			assertFalse("a prose reply is not a hard failure", r.hardFailure);
			assertEquals(1, r.chunks);
			assertEquals("the chunk did not parse", 0, r.chunksParsed);
			assertEquals(0, r.proposed);
			assertTrue(r.proposals.isEmpty());
			assertEquals("no proposal rows from a reply with no array", 0, PbLocationUtil.listProposals(u, bk, null).size());

			/// The util's warnings carry the failure record WITH the raw reply, plus the plain per-chunk warning.
			String failureJson = null;
			String plain = null;
			for(String w : r.warnings) {
				if(w.trim().startsWith("{") && w.contains("\"rawResponse\"")) {
					assertNull("exactly one failure record: " + r.warnings, failureJson);
					failureJson = w;
				}
				else if(w.contains("response did not parse as a whole array")) {
					assertNull("exactly one per-chunk warning: " + r.warnings, plain);
					plain = w;
				}
			}
			assertNotNull("failure record in the util's warnings: " + r.warnings, failureJson);
			assertNotNull("per-chunk warning in the util's warnings: " + r.warnings, plain);
			assertEquals(2, r.warnings.size());
			assertTrue(plain, plain.contains("cluster-locations chunk 1 of " + slug + ": response did not parse as a whole array (0 item(s) salvaged)"));
			Map<String, Object> failure = JSONUtil.getMap(failureJson.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
			assertNotNull("failure record parses: " + failureJson, failure);
			assertEquals("cluster-locations:" + slug + ":chunk1", failure.get("context"));
			assertEquals("no-json", failure.get("kind"));
			assertTrue("error names the missing array: " + failure.get("error"), String.valueOf(failure.get("error")).contains("No JSON array"));
			Object rawObj = failure.get("rawResponse");
			assertTrue("rawResponse is the model's text: " + rawObj, rawObj instanceof String);
			String raw = (String) rawObj;
			assertTrue("the raw reply is the emulator's blurb prose: " + raw, raw.contains("the light settles"));
			assertFalse("the prose holds no array", raw.contains("["));
			assertFalse("rawResponseLength is a transport-only key", failure.containsKey("rawResponseLength"));

			/// The DTO (what the REST/MCP transport returns) has the same two warnings, the record without the text.
			Map<String, Object> dto = PbServiceFacade.locationPassDto(r);
			logger.info("case07 dto: " + JSONUtil.exportObject(dto));
			assertEquals(Integer.valueOf(1), dto.get("chunks"));
			assertEquals(Integer.valueOf(0), dto.get("chunksParsed"));
			assertEquals(Boolean.FALSE, dto.get("hardFailure"));
			assertEquals(Integer.valueOf(0), dto.get("proposed"));
			List<String> wire = castWarnings(dto.get("warnings"));
			assertEquals("same number of warnings on the wire: " + wire, r.warnings.size(), wire.size());
			String wireFailure = null;
			boolean plainOnWire = false;
			for(String w : wire) {
				assertFalse("no raw reply on the wire: " + w, w.contains("rawResponse\""));
				assertFalse("no raw reply on the wire: " + w, w.contains("the light settles"));
				if(w.trim().startsWith("{")) {
					assertNull("exactly one failure record on the wire: " + wire, wireFailure);
					wireFailure = w;
				}
				else if(w.equals(plain)) {
					plainOnWire = true;
				}
			}
			assertNotNull("failure record on the wire: " + wire, wireFailure);
			assertTrue("the plain warning passes through unchanged: " + wire, plainOnWire);
			Map<String, Object> stripped = JSONUtil.getMap(wireFailure.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
			assertNotNull(stripped);
			assertFalse("rawResponse removed: " + wireFailure, stripped.containsKey("rawResponse"));
			assertTrue("rawResponseLength added: " + wireFailure, stripped.get("rawResponseLength") instanceof Number);
			assertEquals("rawResponseLength is the raw reply's length", raw.length(), ((Number) stripped.get("rawResponseLength")).intValue());
			assertEquals("context survives the strip", failure.get("context"), stripped.get("context"));
			assertEquals("kind survives the strip", failure.get("kind"), stripped.get("kind"));
			assertEquals("error survives the strip", failure.get("error"), stripped.get("error"));
			assertEquals("failedAt survives the strip", failure.get("failedAt"), stripped.get("failedAt"));

			/// Building the DTO did not touch the result: the raw reply is still on the util's warnings.
			assertTrue("the result still carries the raw reply after the DTO: " + r.warnings, r.warnings.contains(failureJson));
			assertEquals(2, r.warnings.size());

			/// stripRaw on its own: nulls dropped, plain text and a '{'-prefixed non-JSON entry pass through as they are.
			List<String> mixed = new ArrayList<>();
			mixed.add(null);
			mixed.add("plain warning");
			mixed.add("{not json but mentions rawResponse");
			mixed.add(failureJson);
			List<String> out = PbServiceFacade.stripRaw(mixed);
			assertEquals("null dropped, the rest kept: " + out, 3, out.size());
			assertEquals("plain warning", out.get(0));
			assertEquals("{not json but mentions rawResponse", out.get(1));
			assertFalse(out.get(2).contains("the light settles"));
			Map<String, Object> direct = JSONUtil.getMap(out.get(2).getBytes(StandardCharsets.UTF_8), String.class, Object.class);
			assertNotNull(direct);
			assertFalse(direct.containsKey("rawResponse"));
			assertEquals(raw.length(), ((Number) direct.get("rawResponseLength")).intValue());
			assertTrue("stripRaw(null) is an empty list", PbServiceFacade.stripRaw(null).isEmpty());
		}
		finally {
			deleteBook(u, bk);
			deleteConnections(u);
		}
	}

	/**
	 * Gate run 17 (2026-10-17, qwen3:8b through LiteLLM) split the scriptorium by its leading qualifier: "Scriptorium"
	 * (alias "abbey scriptorium") for scene 2 and "Abbey Scriptorium" (sameAs "Scriptorium") for scene 4. The model
	 * had said they are one place twice over and the pass threw both signals away - sameAs counted only against
	 * known locations, the fold looked at name shape only, and a leading qualifier is by design never folded on
	 * shape ("Night Market" is a place). The emulator now returns that exact shape for a later name ending with an
	 * earlier one, so the fold is proven here without the model; a name ending in another cluster's name with no
	 * alias and no sameAs still never folds.
	 */
	@Test
	public void case08_aliasAndSiblingSameAsFoldByTheModelsOwnWord() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = null;
		try {
			bk = newBook(u, "sibling");
			bookContext(u, bk);
			String scriptoriumA = "Scriptorium, candles guttering over the copyists' desks";
			String scriptoriumB = "Abbey scriptorium, the same candlelit desks after midnight";
			BaseRecord s1 = addScene(u, bk, 1, "Arrival", TAVERN_SETTING);
			BaseRecord s2 = addScene(u, bk, 2, "The Copyist", scriptoriumA);
			BaseRecord s3 = addScene(u, bk, 3, "The Road", HILL_SETTING);
			BaseRecord s4 = addScene(u, bk, 4, "After Midnight", scriptoriumB);

			LocationPassResult r1 = PbLocationUtil.proposeLocations(u, bk, emulatorConfig(u, newSet(null)));
			logger.info("case08 pass: " + describe(r1));
			assertFalse("hard failure: " + r1.warnings, r1.hardFailure);
			assertEquals("chunks parsed", 1, r1.chunksParsed);
			assertEquals("folded (abbey scriptorium)", 1, r1.folded);
			assertEquals("proposed (tavern, scriptorium, hill)", 3, r1.proposed);
			assertTrue("fold warning names the alias: " + r1.warnings, anyContains(r1.warnings, "folded 'Abbey Scriptorium' into 'Scriptorium' - named among its aliases"));
			assertFalse("the sibling claim is met, not ignored: " + r1.warnings, anyContains(r1.warnings, "ignored"));

			List<BaseRecord> rows = PbLocationUtil.listProposals(u, bk, null);
			assertEquals("rows: " + names(rows), 3, rows.size());
			BaseRecord scriptorium = require(rows, PbLocationUtil.proposalName("Scriptorium"));
			assertSceneIds(scriptorium, s2, s4);
			List<String> aliases = scriptorium.get(OlioFieldNames.FIELD_PB_ALIASES);
			assertNotNull(aliases);
			assertTrue("the folded name is an alias: " + aliases, aliases.contains("Abbey Scriptorium"));
			assertNull("no sameAs: nothing known", scriptorium.get(OlioFieldNames.FIELD_PB_SAME_AS));
			String desc = scriptorium.get(FieldNames.FIELD_DESCRIPTION);
			assertTrue("descriptions are joined: " + desc, desc.startsWith(scriptoriumA) && desc.contains(scriptoriumB));
			assertNull("no split cluster survives as its own row", byName(rows, PbLocationUtil.proposalName("Abbey Scriptorium")));
			assertSceneIds(require(rows, PbLocationUtil.proposalName(TAVERN_NAME)), s1);
			assertSceneIds(require(rows, PbLocationUtil.proposalName(HILL_NAME)), s3);

			/// The rule itself, on clusters alone: each signal on its own, the sameAs-only path, the chain, the negative
			PbLocationUtil.Cluster base = cluster("Scriptorium");
			PbLocationUtil.Cluster part = cluster("Abbey Scriptorium");
			assertNull("a leading qualifier with no signal never folds", PbLocationUtil.sameSiblingOf(base, "scriptorium", part, "abbey scriptorium"));
			assertNull("shape alone never folds it either", PbLocationUtil.subPlaceOf("scriptorium", "abbey scriptorium"));
			part.sameAsClaim = "The Scriptorium";
			assertEquals("marked the same as it", PbLocationUtil.sameSiblingOf(base, "scriptorium", part, "abbey scriptorium"));
			part.sameAsClaim = "The Copyists' Hall";
			assertNull("a claim about something else", PbLocationUtil.sameSiblingOf(base, "scriptorium", part, "abbey scriptorium"));
			base.aliases.add("the copyists hall");
			assertEquals("a claim naming an alias", "marked the same as it", PbLocationUtil.sameSiblingOf(base, "scriptorium", part, "abbey scriptorium"));
			part.sameAsClaim = null;
			base.aliases.add("Abbey Scriptorium");
			assertEquals("named among its aliases", PbLocationUtil.sameSiblingOf(base, "scriptorium", part, "abbey scriptorium"));
			assertNull("never the other way round", PbLocationUtil.sameSiblingOf(part, "abbey scriptorium", base, "scriptorium"));

			/// foldSubPlaces end to end: sameAs-only (no alias), a claim that resolves to nothing is reported once
			java.util.Map<String, PbLocationUtil.Cluster> clusters = new java.util.LinkedHashMap<>();
			PbLocationUtil.Cluster a = cluster("Scriptorium");
			PbLocationUtil.Cluster b = cluster("Abbey Scriptorium");
			b.sameAsClaim = "Scriptorium";
			PbLocationUtil.Cluster c = cluster("Harbour Road");
			c.sameAsClaim = "The Quay";
			for(PbLocationUtil.Cluster x : new PbLocationUtil.Cluster[] {a, b, c}) {
				clusters.put(PbLocationUtil.proposalName(x.proposedName), x);
			}
			List<String> warnings = new ArrayList<>();
			assertEquals(1, PbLocationUtil.foldSubPlaces(clusters, "unit", warnings));
			assertEquals("survivors: " + clusters.keySet(), 2, clusters.size());
			assertTrue(clusters.containsKey(PbLocationUtil.proposalName("Scriptorium")));
			assertTrue("the folded name becomes an alias: " + a.aliases, a.aliases.contains("Abbey Scriptorium"));
			assertNull("the met claim is cleared", a.sameAsClaim);
			assertTrue("fold reported: " + warnings, anyContains(warnings, "folded 'Abbey Scriptorium' into 'Scriptorium' - marked the same as it"));
			assertTrue("unmet claim reported: " + warnings, anyContains(warnings, "'Harbour Road' claims sameAs 'The Quay', which is neither a known location nor another place of this pass; ignored"));
			assertEquals("one line per fact: " + warnings, 2, warnings.size());
		}
		finally {
			deleteBook(u, bk);
			deleteConnections(u);
		}
	}

	/// P4-0 (3): proposeLocations reads the world's known locations AS THE CALLER (listLocations(user, bctx)),
	/// not as the olio principal. The emulator's cluster-locations synthesizer sets sameAs only when a setting
	/// contains a name from the prompt's known-locations section, so sameAs is a direct readout of what the
	/// pass put in the prompt:
	///  - the owner (Read on the Locations group) runs the pass and gets sameAs == "Gilded Tankard";
	///  - a collaborator entitled Read+Update+Create on the BOOK group only - NOT on the world's Locations
	///    group - passes every gate (readBook, canUpdate PERMIT), sees no locations, and gets a proposal row
	///    with sameAs == null. The row EXISTS, so the collaborator could write into the book group.
	/// Under the previous code (listLocations(olioUser, bctx)) the olio principal owns every location and
	/// would have put "Gilded Tankard" into the collaborator's prompt; the emulator would then have answered
	/// sameAs == "Gilded Tankard" and the second half of this case would fail at the null assertion - the
	/// collaborator would have been handed the name of a record they are not entitled to read.
	@Test
	public void case09_knownLocationsAreReadAsTheCaller() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord collab = ioContext.getFactory().getCreateUser(org.getAdminUser(), COLLAB_USER, org.getOrganizationId());
		assertNotNull("collaborator " + COLLAB_USER, collab);
		assertNotEquals("collaborator must be a different user from the owner", idOf(u), idOf(collab));
		assertEquals("collaborator in the same organization", orgId, orgOf(collab));
		BaseRecord bk = null;
		try {
			bk = newBook(u, "caller");
			String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
			BookContext bctx = bookContext(u, bk);

			BaseRecord gilded = PbLocationUtil.getCreateLocation(u, bk, "Gilded Tankard", "A tavern.", PbLocationKindEnumType.INTERIOR, null);
			assertNotNull(gilded);
			BaseRecord s1 = addScene(u, bk, 1, "Arrival", TAVERN_SETTING);
			assertNotNull(s1);

			/// Positive control: the owner can read the Locations group, so the known name reaches the prompt.
			BaseRecord ownerCfg = emulatorConfig(u, newSet(null));
			long t0 = System.currentTimeMillis();
			LocationPassResult r1 = PbLocationUtil.proposeLocations(u, bk, ownerCfg);
			logger.info("case09 owner pass (" + (System.currentTimeMillis() - t0) + "ms): " + describe(r1));
			assertFalse(r1.hardFailure);
			assertEquals(1, r1.proposed);
			BaseRecord ownerRow = require(PbLocationUtil.listProposals(u, bk, null), PbLocationUtil.proposalName(TAVERN_NAME));
			assertEquals("owner: the setting contains the known name", "Gilded Tankard", ownerRow.get(OlioFieldNames.FIELD_PB_SAME_AS));

			/// Clear the owner's row so the collaborator's pass has to CREATE one (a re-run would only patch it).
			DeleteResult dr = PictureBookUtil.deleteRecordExplained(u, ownerRow);
			assertTrue("owner deletes their proposal row: " + dr.reason, dr.deleted);
			assertEquals("no proposal rows before the collaborator's pass", 0, PbLocationUtil.listProposals(u, bk, null).size());
			deleteConnections(u);

			/// The collaborator: Read + Update + Create on the Book group ONLY. The admin of the book's OWN
			/// organization (ORG_A, not BaseTest's default orgContext) applies the grant.
			BaseRecord olio = olioUser(orgId);
			BaseRecord bookGroup = ioContext.getPathUtil().findPath(olio, ModelNames.MODEL_GROUP, PbBookUtil.bookGroupPath(slug),
				GroupEnumType.DATA.toString(), orgId);
			assertNotNull("book group", bookGroup);
			assertEquals("book group is in the book's organization", orgId, ((Number) bookGroup.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue());
			assertEquals("the book sits in the group being granted", ((Number) bookGroup.get(FieldNames.FIELD_ID)).longValue(),
				((Number) bk.get(FieldNames.FIELD_GROUP_ID)).longValue());
			ioContext.getAuthorizationUtil().setEntitlement(org.getAdminUser(), collab, new BaseRecord[] {bookGroup},
				new String[] {"Read", "Update", "Create"},
				new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()});
			/// readBook projects the book's world FK, and PBAC adds a dynamic READ rule for olio.pb.book.world
			/// (policy trace: groupdatareadaccess / groupreadaccess on /Olio/Universes/Books/Worlds, then
			/// resourceReadAccess on the world record - all UNKNOWN without a grant, and the whole read is
			/// DENIED). Grant Read on the WORLD RECORD only (resourceReadAccess) - never on the shared Worlds
			/// group, and never on the world's Locations group, which is what this case is about.
			BaseRecord world = bctx.getWorld();
			assertNotNull("world record", world);
			ioContext.getAuthorizationUtil().setEntitlement(org.getAdminUser(), collab, new BaseRecord[] {world},
				new String[] {"Read"}, new String[] {PermissionEnumType.DATA.toString()});

			/// Prove the gates: the collaborator reaches the pass (not a 404/403), can see the scene, and
			/// CANNOT see the location the owner created.
			BaseRecord asCollab = PbBookUtil.readBook(collab, oidOf(bk), orgId);
			assertNotNull("collaborator must be able to READ the book (else the pass 404s before anything is read)", asCollab);
			PolicyResponseType canUpd = ioContext.getAuthorizationUtil().canUpdate(collab, collab, asCollab);
			assertTrue("collaborator must be PERMITted to UPDATE the book (else the pass 403s)",
				canUpd != null && canUpd.getType() == PolicyResponseEnumType.PERMIT);
			assertEquals("collaborator sees the scene", 1, PbBookUtil.listScenes(collab, asCollab).size());
			assertTrue("gate: collaborator must NOT see any location (no Read on the Locations group)",
				PbLocationUtil.listLocations(collab, bctx).isEmpty());
			assertEquals("control: the owner still sees the location", 1, PbLocationUtil.listLocations(u, bctx).size());
			assertNull("gate: collaborator cannot read the location by id", readLocation(collab, oidOf(gilded), orgId));

			BaseRecord collabCfg = emulatorConfig(collab, newSet(null));
			t0 = System.currentTimeMillis();
			LocationPassResult r2 = PbLocationUtil.proposeLocations(collab, bk, collabCfg);
			logger.info("case09 collaborator pass (" + (System.currentTimeMillis() - t0) + "ms): " + describe(r2));
			assertFalse("collaborator pass must not hard-fail: " + r2.warnings, r2.hardFailure);
			assertEquals("collaborator pass proposes the tavern", 1, r2.proposed);
			List<BaseRecord> collabRows = PbLocationUtil.listProposals(collab, bk, PbProposalStatusEnumType.PROPOSED);
			BaseRecord collabRow = require(collabRows, PbLocationUtil.proposalName(TAVERN_NAME));
			assertEquals("the collaborator wrote the row", idOf(collab), ((Number) collabRow.get(FieldNames.FIELD_OWNER_ID)).longValue());
			assertNull("collaborator: the known location was NOT in the prompt, so no sameAs", collabRow.get(OlioFieldNames.FIELD_PB_SAME_AS));
			assertSceneIds(collabRow, s1);
			/// and the owner sees the same row the same way
			BaseRecord ownerView = require(PbLocationUtil.listProposals(u, bk, null), PbLocationUtil.proposalName(TAVERN_NAME));
			assertEquals(oidOf(collabRow), oidOf(ownerView));
			assertNull(ownerView.get(OlioFieldNames.FIELD_PB_SAME_AS));
		}
		finally {
			deleteBook(u, bk);
			/// two creators: each connection is deleted by whoever created it
			for(BaseRecord c : new ArrayList<>(connections)) {
				boolean gone = false;
				for(BaseRecord who : new BaseRecord[] {u, collab}) {
					try {
						if(ioContext.getAccessPoint().delete(who, c)) {
							gone = true;
							break;
						}
					}
					catch(Exception e) {
						/// try the other creator
					}
				}
				if(!gone) {
					logger.warn("cleanup: connection " + c.get(FieldNames.FIELD_NAME) + " was not deleted");
				}
			}
			connections.clear();
		}
	}

	/// P4-0 (4a): acceptProposal marks the re-pointed scene's page node STALE (the render on file is of the
	/// old place) and counts it in nodesMarkedStale. Sub-case 2: a READY, pinned node is still marked STALE
	/// and stays pinned - no exception, no un-pin.
	@Test
	public void case10_acceptMarksRepointedPageNodeStale() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "stale");
			String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
			BaseRecord s1 = addScene(u, bk, 1, "Arrival", TAVERN_SETTING);
			BaseRecord s2 = addScene(u, bk, 2, "The Road", HILL_SETTING);
			BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(slug));
			assertNotNull("workflow", wf);

			/// Sub-case 1: DONE page node on s1
			BaseRecord n1 = ensurePageNode(u, bk, wf, oidOf(s1));
			n1 = setStatus(u, n1, PbNodeStatusEnumType.DONE, orgId);
			/// Sub-case 2: READY + pinned page node on s2
			BaseRecord n2 = ensurePageNode(u, bk, wf, oidOf(s2));
			n2 = setStatus(u, n2, PbNodeStatusEnumType.READY, orgId);
			assertTrue("pin n2", PbGraphUtil.setPinned(u, n2, true));
			n2 = readNode(u, oidOf(n2), orgId);
			assertEquals("precondition: n2 pinned", Boolean.TRUE, n2.get(OlioFieldNames.FIELD_PB_PINNED));
			assertEquals("precondition: n2 READY", "READY", status(n2, OlioFieldNames.FIELD_PB_NODE_STATUS));

			BaseRecord cfg = emulatorConfig(u, newSet(null));
			LocationPassResult r1 = PbLocationUtil.proposeLocations(u, bk, cfg);
			logger.info("case10 pass: " + describe(r1));
			assertFalse(r1.hardFailure);
			assertEquals(2, r1.proposed);
			List<BaseRecord> rows = PbLocationUtil.listProposals(u, bk, null);
			BaseRecord tavernP = require(rows, PbLocationUtil.proposalName(TAVERN_NAME));
			BaseRecord hillP = require(rows, PbLocationUtil.proposalName(HILL_NAME));
			assertSceneIds(tavernP, s1);
			assertSceneIds(hillP, s2);

			/// Sub-case 1
			long t0 = System.currentTimeMillis();
			ProposalAcceptResult a = PbLocationUtil.acceptProposal(u, bk, tavernP, true);
			logger.info("case10 accept tavern (" + (System.currentTimeMillis() - t0) + "ms): repointed=" + a.scenesRepointed
				+ " stale=" + a.nodesMarkedStale + " warnings=" + a.warnings);
			assertTrue(a.locationCreated);
			assertEquals("one scene re-pointed", 1, a.scenesRepointed);
			assertEquals("the re-pointed scene's node was marked stale", 1, a.nodesMarkedStale);
			assertEquals(a.scenesRepointed, a.nodesMarkedStale);
			assertFalse("no 'not marked stale' warning: " + a.warnings, anyContains(a.warnings, "was not marked stale"));
			assertEquals("s1 at the tavern", idOf(a.location), fkId(readScene(u, oidOf(s1), orgId), FieldNames.FIELD_LOCATION));
			BaseRecord n1After = readNode(u, oidOf(n1), orgId);
			assertEquals("n1 DONE -> STALE", "STALE", status(n1After, OlioFieldNames.FIELD_PB_NODE_STATUS));

			/// Sub-case 2
			ProposalAcceptResult b = PbLocationUtil.acceptProposal(u, bk, hillP, true);
			logger.info("case10 accept hill: repointed=" + b.scenesRepointed + " stale=" + b.nodesMarkedStale + " warnings=" + b.warnings);
			assertEquals(1, b.scenesRepointed);
			assertEquals("pinned node still marked stale", 1, b.nodesMarkedStale);
			assertEquals("s2 at the hill", idOf(b.location), fkId(readScene(u, oidOf(s2), orgId), FieldNames.FIELD_LOCATION));
			BaseRecord n2After = readNode(u, oidOf(n2), orgId);
			assertEquals("n2 READY -> STALE", "STALE", status(n2After, OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertEquals("n2 still pinned", Boolean.TRUE, n2After.get(OlioFieldNames.FIELD_PB_PINNED));
		}
		finally {
			deleteBook(u, bk);
			deleteConnections(u);
		}
	}

	/// P4-4: a ChapBook page carries no `setting`; the pass clusters it by its `sdPrompt`, then its stanza, and
	/// skips a page with neither. The emulator names a cluster after the text it was handed (up to the first
	/// comma, Title Case) and echoes the text as the description, so the proposal rows are the readout of what
	/// `settingTextOf` put on the prompt line. Texts carry no " - " (the emulator strips a trailing " - <title>").
	@Test
	public void case11_pagesUseSdPromptThenStanzaAsSetting() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		final String sdPrompt = "A moonlit orchard behind the rectory, apple boughs heavy with frost";
		final String sdPromptName = "A Moonlit Orchard Behind The Rectory";
		final String stanza = "Under the pier\nthe tide runs black";
		final String stanzaLine = "Under the pier the tide runs black";
		final String stanzaName = "Under The Pier The Tide Runs Black";
		try {
			bk = newBook(u, "chap");
			bk = makeChapBook(u, bk);
			BaseRecord p0 = addScene(u, bk, 0, "Frost", null);
			BaseRecord p1 = addScene(u, bk, 1, "Tide", null);
			BaseRecord p2 = addScene(u, bk, 2, "Blank", null);
			p0 = setPageField(u, p0, OlioFieldNames.FIELD_CB_SD_PROMPT, sdPrompt);
			p1 = setPageField(u, p1, OlioFieldNames.FIELD_CB_POEM_STANZA, stanza);
			for(BaseRecord p : new BaseRecord[] {p0, p1, p2}) {
				assertNull("a page has no setting: " + p.get(FieldNames.FIELD_NAME), p.get(OlioFieldNames.FIELD_PB_SETTING));
			}
			assertEquals("page 0 clusters by its sdPrompt", sdPrompt, PbLocationUtil.settingTextOf(p0));
			assertEquals("page 1 clusters by its stanza", stanza, PbLocationUtil.settingTextOf(p1));
			assertNull("page 2 has nothing to cluster by", PbLocationUtil.settingTextOf(p2));
			/// Precedence on one record: a page carrying both is clustered by its sdPrompt.
			BaseRecord both = PbBookUtil.readScene(u, oidOf(p1), orgId);
			both.set(OlioFieldNames.FIELD_CB_SD_PROMPT, sdPrompt);
			assertEquals("sdPrompt wins over the stanza", sdPrompt, PbLocationUtil.settingTextOf(both));

			BaseRecord cfg = emulatorConfig(u, newSet(null));
			long t0 = System.currentTimeMillis();
			LocationPassResult r = PbLocationUtil.proposeLocations(u, bk, cfg, null);
			logger.info("MEASURED: case11 pass " + (System.currentTimeMillis() - t0) + " ms: " + describe(r));
			assertFalse("hard failure: " + r.warnings, r.hardFailure);
			assertFalse(r.cancelled);
			assertEquals("pages with an sdPrompt or a stanza", 2, r.scenesConsidered);
			assertEquals("the page with neither", 1, r.scenesSkipped);
			assertEquals(1, r.chunks);
			assertEquals(1, r.chunksParsed);
			assertEquals("one place per page text", 2, r.proposed);
			assertEquals(0, r.folded);

			List<BaseRecord> rows = PbLocationUtil.listProposals(u, bk, null);
			assertEquals("proposal rows: " + names(rows), 2, rows.size());
			BaseRecord orchard = require(rows, PbLocationUtil.proposalName(sdPromptName));
			assertEquals("the prompt line was the sdPrompt", sdPromptName, orchard.get(OlioFieldNames.FIELD_PB_PROPOSED_NAME));
			assertEquals("the emulator echoed the sdPrompt back as the description", sdPrompt, orchard.get(FieldNames.FIELD_DESCRIPTION));
			assertSceneIds(orchard, p0);
			BaseRecord pier = require(rows, PbLocationUtil.proposalName(stanzaName));
			assertEquals("the prompt line was the stanza", stanzaName, pier.get(OlioFieldNames.FIELD_PB_PROPOSED_NAME));
			assertEquals("the stanza reached the prompt on one line", stanzaLine, pier.get(FieldNames.FIELD_DESCRIPTION));
			assertSceneIds(pier, p1);
			for(BaseRecord row : rows) {
				List<String> ids = row.get(OlioFieldNames.FIELD_PB_SCENE_OBJECT_IDS);
				assertFalse("the blank page is in no cluster: " + row.get(FieldNames.FIELD_NAME), ids.contains(oidOf(p2)));
			}
		}
		finally {
			deleteBook(u, bk);
			deleteConnections(u);
		}
	}

	/// Turn a freshly created STORY book into a CHAPBOOK as its owner (the one-field PATCH idiom of
	/// PbBookUtil.setAutoApprove) and re-read it so the record handed to the pass carries the type.
	private BaseRecord makeChapBook(BaseRecord u, BaseRecord bk) throws Exception {
		BaseRecord patch = PbGraphUtil.patchOf(bk, OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_BOOK_TYPE);
		patch.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.CHAPBOOK.toString());
		assertNotNull("bookType patch", ioContext.getAccessPoint().update(u, patch));
		BaseRecord fresh = PbBookUtil.readBook(u, oidOf(bk), orgOf(u));
		assertNotNull("re-read book", fresh);
		assertTrue("the book reads back as a ChapBook: " + fresh.get(OlioFieldNames.FIELD_PB_BOOK_TYPE), PbNodeExecutor.isChapBook(fresh));
		return fresh;
	}

	private BaseRecord setPageField(BaseRecord u, BaseRecord page, String field, String value) throws Exception {
		BaseRecord patch = PbGraphUtil.patchOf(page, OlioModelNames.MODEL_PB_SCENE, field);
		patch.set(field, value);
		assertNotNull("set " + field + " on " + page.get(FieldNames.FIELD_NAME), ioContext.getAccessPoint().update(u, patch));
		BaseRecord fresh = readScene(u, oidOf(page), orgOf(u));
		assertEquals(field + " persisted", value, fresh.get(field));
		return fresh;
	}

	// ─────────────────────────────── page-node fixture (copied from TestPbSceneLocationPointer) ───────────────────────────────

	private static String status(BaseRecord rec, String field) {
		Object v = rec.get(field);
		return (v != null ? String.valueOf(v).toUpperCase() : null);
	}

	private BaseRecord readNode(BaseRecord u, String nodeOid, long orgId) {
		BaseRecord n = PbGraphUtil.readNode(u, nodeOid, orgId);
		assertNotNull("node " + nodeOid, n);
		return n;
	}

	/// The page's LANDSCAPE node linked as scene.sceneNode - PbServiceFacade.ensureChapBookPageNode's shape,
	/// replicated so no render is needed; the link is asserted by re-reading the scene before returning.
	private BaseRecord ensurePageNode(BaseRecord u, BaseRecord bk, BaseRecord wf, String sceneOid) throws Exception {
		long orgId = orgOf(u);
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = readScene(u, sceneOid, orgId);
		long linked = fkId(scene, OlioFieldNames.FIELD_PB_SCENE_NODE);
		if(linked > 0L) {
			BaseRecord existing = PbGraphUtil.readNodeById(u, linked, orgId);
			assertNotNull("the page's linked node #" + linked + " must read back", existing);
			return existing;
		}
		Integer sceneIndex = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
		int idx = (sceneIndex != null ? sceneIndex.intValue() : 0);
		String handle = PbPipelineUtil.landscapeHandle(sceneOid);
		BaseRecord node = PbPipelineUtil.findNodeByHandle(u, wf, handle);
		if(node == null) {
			node = PbGraphUtil.addNode(u, wf, handle, PbNodeTypeEnumType.LANDSCAPE, PbBookUtil.workflowGroupPath(slug), idx);
			assertNotNull("page node " + handle, node);
			assertTrue("page node scope patch", PbPipelineUtil.patchNodeScope(u, wf, node, PbPipelineUtil.SCOPE_SCENE, sceneOid, idx));
		}
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SCENE_NODE);
		patch.set(OlioFieldNames.FIELD_PB_SCENE_NODE, node);
		assertNotNull("scene.sceneNode patch", ioContext.getAccessPoint().update(u, patch));
		BaseRecord back = readScene(u, sceneOid, orgId);
		assertEquals("the page links its node", idOf(node), fkId(back, OlioFieldNames.FIELD_PB_SCENE_NODE));
		return readNode(u, oidOf(node), orgId);
	}

	private BaseRecord setStatus(BaseRecord u, BaseRecord node, PbNodeStatusEnumType to, long orgId) {
		assertTrue("persistStatus " + to, PbGraphUtil.persistStatus(u, node, to));
		BaseRecord fresh = readNode(u, oidOf(node), orgId);
		assertEquals("precondition: node is " + to, to.toString(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		return fresh;
	}

	private static PbLocationUtil.Cluster cluster(String name) {
		PbLocationUtil.Cluster c = new PbLocationUtil.Cluster();
		c.proposedName = name;
		return c;
	}

	@SuppressWarnings("unchecked")
	private static List<String> castWarnings(Object v) {
		assertTrue("warnings is a list: " + v, v instanceof List);
		return (List<String>) v;
	}

	private static String describe(LocationPassResult r) {
		return "run=" + r.passRun + " scenes=" + r.scenesConsidered + " skipped=" + r.scenesSkipped + " chunks=" + r.chunksParsed + "/" + r.chunks
			+ " folded=" + r.folded + " proposed=" + r.proposed + " updated=" + r.updated + " settled=" + r.settled + " retired=" + r.retired
			+ (r.hardFailure ? " HARD-FAILURE" : "") + " warnings=" + r.warnings;
	}
}
