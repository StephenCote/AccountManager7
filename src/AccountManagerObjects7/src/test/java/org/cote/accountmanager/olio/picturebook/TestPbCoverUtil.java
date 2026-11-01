package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.CastTier;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PbCoverUtil.CoverResult;
import org.cote.accountmanager.olio.picturebook.PbCoverUtil.Mode;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionSourceEnumType;
import org.cote.accountmanager.schema.type.PbLocationKindEnumType;
import org.cote.accountmanager.schema.type.PbTitleSafeBandEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 9, P9-1: cover-type compositions through {@link PbCoverUtil} on live {@code am7db} as {@code testUser1}
 * in {@code /Development/World Building}. Every case writes a throwaway PictureBook (or a series with chapters)
 * and proves the outcome on the database with uncached re-reads, never from the result alone: MANUAL writes one
 * empty USER-sourced row per purpose and a second call returns the same row untouched; FROM_SCENE clones a SCENE
 * composition's fields, actor and element rows and focal pointer as a CLONE row with {@code scene} null; AUTO
 * stages the cover with the real LLM from the book's description and the HERO rows of its scene compositions
 * ({@code source LLM}, {@code titleSafeBand} written, rows exist); SERIES_ART is one row per series that every
 * chapter finds and is 409 on a standalone book; SCENE is 400; the row editor patches {@code titleSafeBand} and
 * refuses {@code series}; a read-only collaborator is 403 with nothing written.
 */
public class TestPbCoverUtil extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SECOND_USER = "testUser2";
	private static final String CFG_NAME = "P9-1 cover compose";

	private static final String BOOK_DESCRIPTION = "Mara Quayle, a harbour thief with a stolen tide-chart, is hunted through the"
		+ " rain-dark streets of Saltmarrow by the Guild's lantern-men while her only friend, the gull-watcher Tobin, keeps the"
		+ " one boat that can carry her out before the tide turns.";

	@FunctionalInterface
	private interface Call {
		void run() throws Exception;
	}

	@Before
	public void setupEach() throws Exception {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
	}

	// ─────────────────────────────── MANUAL: one row per purpose, idempotent ───────────────────────────────

	/// MANUAL writes an empty composition in the book's group named cover.<bookOid> with purpose COVER, source
	/// USER, revision 1, scene null, book FK = the book, no rows; a second getCreate of the same purpose returns
	/// the SAME objectId with existingReturned and writes no second row; TITLE_PAGE and CHAPTER_OPENER are
	/// separate rows; listCovers lists the three; findCover reads each by purpose.
	@Test
	public void manualOnePerPurposeIdempotent() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "manual");
			assertNull("precondition: no COVER yet", PbCoverUtil.findCover(u, bk, PbCompositionPurposeEnumType.COVER));
			assertTrue("precondition: listCovers is empty", PbCoverUtil.listCovers(u, bk).isEmpty());

			CoverResult r1 = PbCoverUtil.getCreate(u, bk, PbCompositionPurposeEnumType.COVER, Mode.MANUAL, null);
			log("manual COVER: " + describe(r1));
			assertTrue("MANUAL wrote the cover: " + r1.failureReason, r1.isWritten());
			assertFalse(r1.existingReturned);
			assertEquals(PbCompositionSourceEnumType.USER, r1.source);
			assertEquals(Mode.MANUAL, r1.mode);
			assertTrue("no actor rows", r1.actors.isEmpty());
			assertTrue("no element rows", r1.elements.isEmpty());
			String coverOid = oidOf(r1.composition);

			BaseRecord onDb = PbComposeUtil.readComposition(u, coverOid, orgId);
			assertNotNull("the cover row re-reads", onDb);
			assertEquals("name", PbCoverUtil.COVER_NAME_PREFIX + oidOf(bk), onDb.get(FieldNames.FIELD_NAME));
			assertEquals("purpose COVER", "COVER", status(onDb, OlioFieldNames.FIELD_PB_PURPOSE));
			assertEquals("source USER", "USER", status(onDb, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals("revision 1", 1, intOf(onDb.get(OlioFieldNames.FIELD_PB_REVISION)));
			assertEquals("the cover belongs to the book", idOf(bk), fkId(onDb, OlioFieldNames.FIELD_PB_BOOK));
			assertEquals("the cover is in the book's group", (Object) bk.get(FieldNames.FIELD_GROUP_ID), (Object) onDb.get(FieldNames.FIELD_GROUP_ID));
			assertEquals("scene null", 0L, fkId(onDb, OlioFieldNames.FIELD_PB_SCENE));
			assertEquals("series null on a standalone book's cover", 0L, fkId(onDb, OlioFieldNames.FIELD_PB_SERIES));
			assertEquals("userEdited false", Boolean.FALSE, onDb.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			assertTrue("the actor list on the DB is empty", PbComposeUtil.listActors(u, onDb).isEmpty());

			/// Second call, AUTO mode requested: the existing row wins, nothing is composed, nothing new is written.
			CoverResult r2 = PbCoverUtil.getCreate(u, bk, PbCompositionPurposeEnumType.COVER, Mode.AUTO, null);
			log("second COVER: " + describe(r2));
			assertTrue("second call returns the existing row", r2.existingReturned);
			assertFalse("second call created nothing", r2.created);
			assertEquals("same objectId", coverOid, oidOf(r2.composition));
			assertEquals("the existing row's source is reported", PbCompositionSourceEnumType.USER, r2.source);
			assertEquals("still one COVER row for this book on the DB", 1, countCompositions(u, bk, "COVER"));
			BaseRecord again = PbComposeUtil.readComposition(u, coverOid, orgId);
			assertEquals("revision untouched by the second call", 1, intOf(again.get(OlioFieldNames.FIELD_PB_REVISION)));

			CoverResult tp = PbCoverUtil.getCreate(u, bk, PbCompositionPurposeEnumType.TITLE_PAGE, Mode.MANUAL, null);
			CoverResult co = PbCoverUtil.getCreate(u, bk, PbCompositionPurposeEnumType.CHAPTER_OPENER, Mode.MANUAL, null);
			assertTrue("TITLE_PAGE written: " + tp.failureReason, tp.isWritten());
			assertTrue("CHAPTER_OPENER written: " + co.failureReason, co.isWritten());
			assertNotEquals("three distinct rows", coverOid, oidOf(tp.composition));
			assertNotEquals("three distinct rows", oidOf(tp.composition), oidOf(co.composition));
			assertEquals("TITLE_PAGE name", PbCoverUtil.TITLE_PAGE_NAME_PREFIX + oidOf(bk), tp.composition.get(FieldNames.FIELD_NAME));
			assertEquals("CHAPTER_OPENER name", PbCoverUtil.CHAPTER_OPENER_NAME_PREFIX + oidOf(bk), co.composition.get(FieldNames.FIELD_NAME));

			Map<PbCompositionPurposeEnumType, BaseRecord> covers = PbCoverUtil.listCovers(u, bk);
			log("listCovers: " + covers.keySet());
			assertEquals("three covers listed", 3, covers.size());
			assertEquals(coverOid, oidOf(covers.get(PbCompositionPurposeEnumType.COVER)));
			assertEquals(oidOf(tp.composition), oidOf(covers.get(PbCompositionPurposeEnumType.TITLE_PAGE)));
			assertEquals(oidOf(co.composition), oidOf(covers.get(PbCompositionPurposeEnumType.CHAPTER_OPENER)));
			assertFalse("no SERIES_ART on a standalone book", covers.containsKey(PbCompositionPurposeEnumType.SERIES_ART));
			assertEquals("findCover COVER", coverOid, oidOf(PbCoverUtil.findCover(u, bk, PbCompositionPurposeEnumType.COVER)));
			assertEquals("findCover TITLE_PAGE", oidOf(tp.composition), oidOf(PbCoverUtil.findCover(u, bk, PbCompositionPurposeEnumType.TITLE_PAGE)));
			assertNull("findCover SERIES_ART on a standalone book is null, not an error",
				PbCoverUtil.findCover(u, bk, PbCompositionPurposeEnumType.SERIES_ART));

			/// Not a cover purpose: 400 from every entry point.
			final BaseRecord book = bk;
			refused("getCreate SCENE", 400, "Purpose must be one of COVER, TITLE_PAGE, CHAPTER_OPENER, SERIES_ART",
				() -> PbCoverUtil.getCreate(u, book, PbCompositionPurposeEnumType.SCENE, Mode.MANUAL, null));
			refused("findCover CHAPBOOK_PAGE", 400, "Purpose must be one of COVER, TITLE_PAGE, CHAPTER_OPENER, SERIES_ART",
				() -> PbCoverUtil.findCover(u, book, PbCompositionPurposeEnumType.CHAPBOOK_PAGE));
			refused("purposeOf scene", 400, "Purpose must be one of", () -> PbCoverUtil.purposeOf("scene"));
			refused("purposeOf junk", 400, "Purpose must be one of", () -> PbCoverUtil.purposeOf("poster"));
			refused("modeOf junk", 400, "Mode must be one of auto, from_scene, manual", () -> PbCoverUtil.modeOf("magic"));
			assertEquals(PbCompositionPurposeEnumType.TITLE_PAGE, PbCoverUtil.purposeOf("title-page"));
			assertEquals(PbCompositionPurposeEnumType.SERIES_ART, PbCoverUtil.purposeOf("series_art"));
			assertEquals(Mode.AUTO, PbCoverUtil.modeOf(null));
			assertEquals(Mode.FROM_SCENE, PbCoverUtil.modeOf("from-scene"));
			assertEquals(Mode.MANUAL, PbCoverUtil.modeOf("manual"));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── FROM_SCENE: clone a scene composition ───────────────────────────────

	/// A SCENE composition with two actor rows (one a character, one description-only), one element and a focal
	/// actor is cloned as the COVER: source CLONE, revision 0, scene null, the composition fields copied, the rows
	/// copied with their box / role / character / description, the focal pointer re-pointed at the NEW row, and
	/// the source composition left untouched. A scene without a composition is 404; a scene of another book is 400.
	@Test
	public void fromSceneClonesFieldsRowsAndFocal() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		BaseRecord other = null;
		try {
			bk = newBook(u, "clone");
			BookContext bctx = bookContext(u, bk);
			String popPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			assertNotNull("Population group path", popPath);
			BaseRecord mara = createCharPerson(u, popPath, "Mara Quayle", "Mara", "Quayle");

			BaseRecord scene = addScene(u, bk, 1, "Arrival", "a rain-dark harbour street at dusk", "walking toward the tavern door", "wary");
			ComposeResult cr = PbComposeUtil.composeMigrated(u, bk, scene, false);
			assertTrue("MIGRATED composition written: " + cr.failureReason, cr.isWritten());
			BaseRecord src = PbComposeUtil.readComposition(u, oidOf(cr.composition), orgId);
			assertNotNull(src);
			Map<String, Object> hero = new LinkedHashMap<>();
			hero.put("characterObjectId", oidOf(mara));
			hero.put(OlioFieldNames.FIELD_PB_ROLE, "hero");
			hero.put(OlioFieldNames.FIELD_PB_POSE, "standing at the tavern door");
			hero.put(OlioFieldNames.FIELD_PB_X, Double.valueOf(0.25));
			hero.put(OlioFieldNames.FIELD_PB_Y, Double.valueOf(0.5));
			hero.put(OlioFieldNames.FIELD_PB_W, Double.valueOf(0.3));
			hero.put(OlioFieldNames.FIELD_PB_H, Double.valueOf(0.45));
			Map<String, Object> a1 = PbServiceFacade.addActor(u, oidOf(bk), oidOf(src), hero);
			assertEquals(Boolean.TRUE, a1.get("created"));
			String heroOid = (String) a1.get("actorObjectId");
			Map<String, Object> extra = new LinkedHashMap<>();
			extra.put(FieldNames.FIELD_DESCRIPTION, "a gull-watcher on the mole");
			extra.put(OlioFieldNames.FIELD_PB_ROLE, "supporting");
			Map<String, Object> a2 = PbServiceFacade.addActor(u, oidOf(bk), oidOf(src), extra);
			assertEquals(Boolean.TRUE, a2.get("created"));
			Map<String, Object> el = new LinkedHashMap<>();
			el.put(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, "prop");
			el.put(FieldNames.FIELD_DESCRIPTION, "a weathered bollard");
			el.put(OlioFieldNames.FIELD_PB_X, Double.valueOf(0.8));
			Map<String, Object> e1 = PbServiceFacade.addElement(u, oidOf(bk), oidOf(src), el);
			assertEquals(Boolean.TRUE, e1.get("created"));
			Map<String, Object> focal = new LinkedHashMap<>();
			focal.put("focalActorObjectId", heroOid);
			focal.put(OlioFieldNames.FIELD_PB_CAMERA_TEXT, "low, wide, lantern-lit");
			PbServiceFacade.patchSceneComposition(u, oidOf(bk), oidOf(scene), focal);
			src = PbComposeUtil.readComposition(u, oidOf(src), orgId);
			assertEquals("precondition: the source's focal actor is the hero row", heroOid, oidOf(src.get(OlioFieldNames.FIELD_PB_FOCAL_ACTOR)));
			int srcRevision = intOf(src.get(OlioFieldNames.FIELD_PB_REVISION));
			/// The migrated compose already seeded one row per population member (Mara), so the source carries
			/// that row plus the two added above; the clone is measured against what the source really has.
			List<BaseRecord> srcActors = PbComposeUtil.listActors(u, src);
			int srcActorCount = srcActors.size();
			log("source composition " + oidOf(src) + " has " + srcActorCount + " actor row(s)");
			assertTrue("precondition: the two added rows are on the source", srcActorCount >= 2);

			CoverResult r = PbCoverUtil.getCreate(u, bk, PbCompositionPurposeEnumType.COVER, Mode.FROM_SCENE, oidOf(scene));
			log("FROM_SCENE COVER: " + describe(r));
			assertTrue("FROM_SCENE wrote the cover: " + r.failureReason, r.isWritten());
			assertEquals(PbCompositionSourceEnumType.CLONE, r.source);
			assertEquals(oidOf(scene), r.sourceSceneObjectId);
			assertEquals("every source actor row cloned", srcActorCount, r.actors.size());
			assertEquals("one element row cloned", 1, r.elements.size());

			BaseRecord cover = PbComposeUtil.readComposition(u, oidOf(r.composition), orgId);
			assertNotNull(cover);
			assertNotEquals("the clone is a new row", oidOf(src), oidOf(cover));
			assertEquals("source CLONE", "CLONE", status(cover, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals("revision 0 on a clone", 0, intOf(cover.get(OlioFieldNames.FIELD_PB_REVISION)));
			assertEquals("purpose COVER", "COVER", status(cover, OlioFieldNames.FIELD_PB_PURPOSE));
			assertEquals("scene null on the clone", 0L, fkId(cover, OlioFieldNames.FIELD_PB_SCENE));
			assertEquals("the clone belongs to the book", idOf(bk), fkId(cover, OlioFieldNames.FIELD_PB_BOOK));
			assertEquals("settingText copied", (Object) src.get(OlioFieldNames.FIELD_PB_SETTING_TEXT), (Object) cover.get(OlioFieldNames.FIELD_PB_SETTING_TEXT));
			assertEquals("action copied", (Object) src.get(OlioFieldNames.FIELD_PB_ACTION), (Object) cover.get(OlioFieldNames.FIELD_PB_ACTION));
			assertEquals("mood copied", (Object) src.get(OlioFieldNames.FIELD_PB_MOOD), (Object) cover.get(OlioFieldNames.FIELD_PB_MOOD));
			assertEquals("cameraText copied", "low, wide, lantern-lit", cover.get(OlioFieldNames.FIELD_PB_CAMERA_TEXT));
			assertEquals("titleSafeBand is UNKNOWN on a clone (the user sets it)", "UNKNOWN", status(cover, OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND));

			List<BaseRecord> coverActors = PbComposeUtil.listActors(u, cover);
			assertEquals(srcActorCount, coverActors.size());
			BaseRecord heroClone = null;
			BaseRecord extraClone = null;
			for(BaseRecord row : coverActors) {
				assertEquals("row composition FK is the cover", idOf(cover), fkId(row, OlioFieldNames.FIELD_PB_COMPOSITION));
				assertTrue("row name is under the cover's handle", String.valueOf((Object) row.get(FieldNames.FIELD_NAME)).startsWith(PbComposeUtil.ACTOR_NAME_PREFIX + oidOf(cover) + "."));
				if(fkId(row, OlioFieldNames.FIELD_PB_CHARACTER) == idOf(mara) && "standing at the tavern door".equals(row.get(OlioFieldNames.FIELD_PB_POSE))) {
					heroClone = row;
				}
				if("a gull-watcher on the mole".equals(row.get(FieldNames.FIELD_DESCRIPTION))) {
					extraClone = row;
				}
			}
			assertNotNull("the character actor row was cloned with its character FK and pose", heroClone);
			assertNotNull("the description-only row was cloned", extraClone);
			assertEquals("the description-only row has no character", 0L, fkId(extraClone, OlioFieldNames.FIELD_PB_CHARACTER));
			assertEquals("its role copied", "SUPPORTING", status(extraClone, OlioFieldNames.FIELD_PB_ROLE));
			assertNotEquals("the clone row is new", heroOid, oidOf(heroClone));
			assertEquals("role copied", "HERO", status(heroClone, OlioFieldNames.FIELD_PB_ROLE));
			assertEquals("pose copied", "standing at the tavern door", heroClone.get(OlioFieldNames.FIELD_PB_POSE));
			assertEquals("x copied", 0.25, dbl(heroClone, OlioFieldNames.FIELD_PB_X), 0.0001);
			assertEquals("h copied", 0.45, dbl(heroClone, OlioFieldNames.FIELD_PB_H), 0.0001);
			assertEquals("focal re-pointed at the NEW hero row", oidOf(heroClone), oidOf(cover.get(OlioFieldNames.FIELD_PB_FOCAL_ACTOR)));
			BaseRecord elClone = PbComposeUtil.listElements(u, cover).get(0);
			assertEquals("element type copied", "PROP", status(elClone, OlioFieldNames.FIELD_PB_ELEMENT_TYPE));
			assertEquals("element description copied", "a weathered bollard", elClone.get(FieldNames.FIELD_DESCRIPTION));
			assertEquals("element x copied", 0.8, dbl(elClone, OlioFieldNames.FIELD_PB_X), 0.0001);

			BaseRecord srcAfter = PbComposeUtil.readComposition(u, oidOf(src), orgId);
			assertEquals("the source composition is untouched", srcRevision, intOf(srcAfter.get(OlioFieldNames.FIELD_PB_REVISION)));
			assertEquals("the source keeps its focal", heroOid, oidOf(srcAfter.get(OlioFieldNames.FIELD_PB_FOCAL_ACTOR)));
			assertEquals("the source keeps its rows", srcActorCount, PbComposeUtil.listActors(u, srcAfter).size());

			/// Refusals: a scene without a composition, a scene of another book, no scene at all.
			final BaseRecord book = bk;
			BaseRecord bare = addScene(u, bk, 2, "Bare", null, null, null);
			refused("scene without a composition", 404, "has no composition to copy",
				() -> PbCoverUtil.getCreate(u, book, PbCompositionPurposeEnumType.TITLE_PAGE, Mode.FROM_SCENE, oidOf(bare)));
			refused("no scene named", 400, "FROM_SCENE needs the scene to copy",
				() -> PbCoverUtil.getCreate(u, book, PbCompositionPurposeEnumType.TITLE_PAGE, Mode.FROM_SCENE, " "));
			other = newBook(u, "cloneb");
			BaseRecord foreign = addScene(u, other, 1, "Elsewhere", "a field", "waiting", "calm");
			final String foreignOid = oidOf(foreign);
			refused("scene of another book", 400, "does not belong to book " + bk.get(OlioFieldNames.FIELD_PB_SLUG),
				() -> PbCoverUtil.getCreate(u, book, PbCompositionPurposeEnumType.TITLE_PAGE, Mode.FROM_SCENE, foreignOid));
			refused("unknown scene", 404, "Scene not found",
				() -> PbCoverUtil.getCreate(u, book, PbCompositionPurposeEnumType.TITLE_PAGE, Mode.FROM_SCENE, UUID.randomUUID().toString()));
			assertNull("no TITLE_PAGE was written by the refused calls", PbCoverUtil.findCover(u, bk, PbCompositionPurposeEnumType.TITLE_PAGE));
		}
		finally {
			deleteBook(u, other);
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── AUTO: the real LLM ───────────────────────────────

	/// The cover staged by the real LLM: the book carries a description and one scene composition with a HERO row,
	/// so the hero cast tier is HEROES with that character first; the result is source LLM with titleSafeBand
	/// written (TOP / BOTTOM / NONE, never UNKNOWN), the row named cover.<bookOid>, actor rows on the DB, and the
	/// composition in the book's group. The staged content is logged, not asserted - the LLM's picture is its own.
	@Test(timeout = 1_800_000)
	public void autoComposesCoverWithTheRealLlm() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		BaseRecord cfg = null;
		try {
			cfg = OlioTestUtil.getOllamaOpenAIConfig(u, CFG_NAME, testProperties);
			assertNotNull("chatConfig " + CFG_NAME, cfg);
			logger.info("[p91-live] route=" + testProperties.getProperty("test.llm.route") + " tier=" + testProperties.getProperty("test.llm.resolvedTier")
				+ " server=" + testProperties.getProperty("test.llm.connection.server") + " model=" + (Object) cfg.get("model"));

			bk = newBook(u, "auto");
			describeBook(u, bk, BOOK_DESCRIPTION);
			BookContext bctx = bookContext(u, bk);
			String popPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			BaseRecord mara = createCharPerson(u, popPath, "Mara Quayle", "Mara", "Quayle");
			BaseRecord tobin = createCharPerson(u, popPath, "Tobin Wrack", "Tobin", "Wrack");
			BaseRecord loc = PbLocationUtil.getCreateLocation(u, bk, "The Gilded Tankard", "a harbour tavern of black beams and brass lanterns",
				PbLocationKindEnumType.INTERIOR, Arrays.asList("the Tankard"));
			assertNotNull("location", loc);

			/// Two scene compositions: Mara is HERO in both, Tobin supporting in one; the location is set on both
			/// (setSceneLocation's boolean is the node-stale mark, false here because the scenes have no node).
			for(int i = 1; i <= 2; i++) {
				BaseRecord scene = addScene(u, bk, i, "Scene " + i, "the Tankard's taproom at closing", "counting coins by lantern light", "tense");
				PbBookUtil.setSceneLocation(u, scene, loc, null);
				BaseRecord located = PbBookUtil.readScene(u, oidOf(scene), orgId);
				assertEquals("scene " + i + " points at the location", idOf(loc), fkId(located, FieldNames.FIELD_LOCATION));
				ComposeResult cr = PbComposeUtil.composeMigrated(u, bk, located, false);
				assertTrue("scene " + i + " composition: " + cr.failureReason, cr.isWritten());
				Map<String, Object> hero = new LinkedHashMap<>();
				hero.put("characterObjectId", oidOf(mara));
				hero.put(OlioFieldNames.FIELD_PB_ROLE, "hero");
				assertEquals(Boolean.TRUE, PbServiceFacade.addActor(u, oidOf(bk), oidOf(cr.composition), hero).get("created"));
				if(i == 1) {
					Map<String, Object> sup = new LinkedHashMap<>();
					sup.put("characterObjectId", oidOf(tobin));
					sup.put(OlioFieldNames.FIELD_PB_ROLE, "supporting");
					assertEquals(Boolean.TRUE, PbServiceFacade.addActor(u, oidOf(bk), oidOf(cr.composition), sup).get("created"));
				}
			}

			long t0 = System.currentTimeMillis();
			CoverResult r = PbCoverUtil.getCreate(u, bk, PbCompositionPurposeEnumType.COVER, Mode.AUTO, null, cfg, dataPath());
			long ms = System.currentTimeMillis() - t0;
			log("AUTO COVER (" + ms + " ms): " + describe(r));
			log("AUTO warnings: " + r.warnings + " unresolved=" + r.unresolvedNames + " placements=" + r.unrecognizedPlacements + " locationHint=" + r.locationHint);
			assertFalse("the LLM answered (" + r.failureReason + ")", r.llmFailure);
			assertTrue("AUTO wrote the cover: " + r.failureReason, r.isWritten());
			assertEquals(PbCompositionSourceEnumType.LLM, r.source);
			assertEquals("the hero cast came from the HERO rows", CastTier.HEROES, r.castTier);
			assertEquals("Mara, HERO in both compositions, is first", "Mara Quayle", r.heroCast.get(0));
			assertEquals("only HERO-row characters are the hero cast", 1, r.heroCast.size());

			BaseRecord cover = PbComposeUtil.readComposition(u, oidOf(r.composition), orgId);
			assertNotNull(cover);
			assertEquals("name", PbCoverUtil.COVER_NAME_PREFIX + oidOf(bk), cover.get(FieldNames.FIELD_NAME));
			assertEquals("source LLM", "LLM", status(cover, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals("purpose COVER", "COVER", status(cover, OlioFieldNames.FIELD_PB_PURPOSE));
			assertEquals("scene null", 0L, fkId(cover, OlioFieldNames.FIELD_PB_SCENE));
			assertEquals("revision 1", 1, intOf(cover.get(OlioFieldNames.FIELD_PB_REVISION)));
			String band = status(cover, OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND);
			log("AUTO titleSafeBand=" + band + " shot=" + status(cover, OlioFieldNames.FIELD_PB_SHOT_TYPE) + " viewpoint=" + status(cover, OlioFieldNames.FIELD_PB_VIEWPOINT)
				+ " location=" + fkId(cover, FieldNames.FIELD_LOCATION) + " setting=" + cover.get(OlioFieldNames.FIELD_PB_SETTING_TEXT)
				+ " action=" + cover.get(OlioFieldNames.FIELD_PB_ACTION) + " mood=" + cover.get(OlioFieldNames.FIELD_PB_MOOD));
			assertNotNull("titleSafeBand written", band);
			assertNotEquals("titleSafeBand is a real value on an LLM cover", PbTitleSafeBandEnumType.UNKNOWN.toString(), band);
			assertTrue("settingText staged", cover.get(OlioFieldNames.FIELD_PB_SETTING_TEXT) != null
				|| fkId(cover, FieldNames.FIELD_LOCATION) > 0L);
			List<BaseRecord> rows = PbComposeUtil.listActors(u, cover);
			for(BaseRecord row : rows) {
				log("  actor " + row.get(FieldNames.FIELD_NAME) + " character=" + fkId(row, OlioFieldNames.FIELD_PB_CHARACTER) + " role=" + status(row, OlioFieldNames.FIELD_PB_ROLE)
					+ " pose=" + row.get(OlioFieldNames.FIELD_PB_POSE) + " x=" + row.get(OlioFieldNames.FIELD_PB_X) + " y=" + row.get(OlioFieldNames.FIELD_PB_Y));
			}
			for(BaseRecord el : PbComposeUtil.listElements(u, cover)) {
				log("  element " + el.get(FieldNames.FIELD_NAME) + " type=" + status(el, OlioFieldNames.FIELD_PB_ELEMENT_TYPE) + " " + el.get(FieldNames.FIELD_DESCRIPTION));
			}
			assertEquals("the result's rows are the DB's rows", rows.size(), r.actors.size());
			assertFalse("a cover of a book with a hero cast stages at least one figure", rows.isEmpty());
			assertEquals("one COVER row for this book on the DB", 1, countCompositions(u, bk, "COVER"));
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

	// ─────────────────────────────── SERIES_ART ───────────────────────────────

	/// SERIES_ART on chapter A of a two-chapter series: the row is named series_art.<seriesOid> with the series FK
	/// set, book = chapter A, in chapter A's group; chapter B's findCover, listCovers and getCreate all resolve the
	/// SAME row (existingReturned, no second row); a standalone book is 409. Both chapters' baseline cast is read
	/// through coverCast for an AUTO without hero rows - checked offline here through the tier only.
	@Test
	public void seriesArtIsOnePerSeriesAcrossChapters() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		String tag = shortId();
		BaseRecord series = PbSeriesUtil.getCreateSeries(u, dataPath(), "p91s" + tag, "P9-1 series " + tag);
		assertNotNull("series", series);
		BaseRecord chA = null;
		BaseRecord chB = null;
		BaseRecord lone = null;
		try {
			chA = PbBookUtil.createBook(u, dataPath(), "p91a" + tag, "P9-1 chapter A " + tag, series, Integer.valueOf(0));
			chB = PbBookUtil.createBook(u, dataPath(), "p91b" + tag, "P9-1 chapter B " + tag, series, Integer.valueOf(1));
			assertNotNull(chA);
			assertNotNull(chB);
			assertNull("precondition: no SERIES_ART yet (chapter A)", PbCoverUtil.findCover(u, chA, PbCompositionPurposeEnumType.SERIES_ART));
			assertNull("precondition: no SERIES_ART yet (chapter B)", PbCoverUtil.findCover(u, chB, PbCompositionPurposeEnumType.SERIES_ART));

			CoverResult r = PbCoverUtil.getCreate(u, chA, PbCompositionPurposeEnumType.SERIES_ART, Mode.MANUAL, null);
			log("SERIES_ART from chapter A: " + describe(r));
			assertTrue("SERIES_ART written: " + r.failureReason, r.isWritten());
			String artOid = oidOf(r.composition);
			BaseRecord art = PbComposeUtil.readComposition(u, artOid, orgId);
			assertNotNull(art);
			assertEquals("name", PbCoverUtil.SERIES_ART_NAME_PREFIX + oidOf(series), art.get(FieldNames.FIELD_NAME));
			assertEquals("purpose", "SERIES_ART", status(art, OlioFieldNames.FIELD_PB_PURPOSE));
			assertEquals("series FK set", idOf(series), fkId(art, OlioFieldNames.FIELD_PB_SERIES));
			assertEquals("book = the host chapter", idOf(chA), fkId(art, OlioFieldNames.FIELD_PB_BOOK));
			assertEquals("hosted in chapter A's group", (Object) chA.get(FieldNames.FIELD_GROUP_ID), (Object) art.get(FieldNames.FIELD_GROUP_ID));
			assertEquals("scene null", 0L, fkId(art, OlioFieldNames.FIELD_PB_SCENE));

			BaseRecord fromB = PbCoverUtil.findCover(u, chB, PbCompositionPurposeEnumType.SERIES_ART);
			assertNotNull("chapter B finds the series art hosted in chapter A's group", fromB);
			assertEquals("the same row", artOid, oidOf(fromB));
			Map<PbCompositionPurposeEnumType, BaseRecord> coversB = PbCoverUtil.listCovers(u, chB);
			assertEquals("chapter B lists only the series art", 1, coversB.size());
			assertEquals(artOid, oidOf(coversB.get(PbCompositionPurposeEnumType.SERIES_ART)));
			CoverResult r2 = PbCoverUtil.getCreate(u, chB, PbCompositionPurposeEnumType.SERIES_ART, Mode.MANUAL, null);
			assertTrue("chapter B's getCreate returns the existing row", r2.existingReturned);
			assertEquals(artOid, oidOf(r2.composition));
			assertEquals("still one SERIES_ART row in chapter A's group", 1, countCompositions(u, chA, "SERIES_ART"));
			assertEquals("none in chapter B's group", 0, countCompositions(u, chB, "SERIES_ART"));

			/// Chapter B's own COVER is its own row, and its listCovers carries both.
			CoverResult cb = PbCoverUtil.getCreate(u, chB, PbCompositionPurposeEnumType.COVER, Mode.MANUAL, null);
			assertTrue(cb.isWritten());
			assertEquals("series null on a chapter's COVER", 0L, fkId(cb.composition, OlioFieldNames.FIELD_PB_SERIES));
			Map<PbCompositionPurposeEnumType, BaseRecord> coversB2 = PbCoverUtil.listCovers(u, chB);
			assertEquals(2, coversB2.size());
			assertEquals(oidOf(cb.composition), oidOf(coversB2.get(PbCompositionPurposeEnumType.COVER)));
			assertEquals(artOid, oidOf(coversB2.get(PbCompositionPurposeEnumType.SERIES_ART)));

			/// The baseline cast tier: a member enrolled in the series' baseline cast group is offered when the
			/// chapter has no HERO rows (offline - no LLM call; coverCast is the read PbCoverUtil's AUTO uses).
			BookContext bctxA = bookContext(u, chA);
			String popPath = bctxA.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			BaseRecord lead = createCharPerson(u, popPath, "Ysolde Marr", "Ysolde", "Marr");
			String seriesSlug = ((BaseRecord) series.get(OlioFieldNames.FIELD_PB_UNIVERSE)).get(FieldNames.FIELD_NAME);
			BaseRecord baseline = PbCastUtil.getCreateBaselineCastGroup(u, series, seriesSlug, PbBookUtil.bookGroupPath(seriesSlug), orgId);
			assertNotNull("baseline cast group", baseline);
			assertTrue("enrol", PbCastUtil.enrollCastMember(baseline, lead));
			PbComposeUtil.Context ctx = PbComposeUtil.openBookContext(u, chA, PbCoverUtil.WHAT);
			ctx.purpose = PbCompositionPurposeEnumType.SERIES_ART;
			ComposeResult aux = new ComposeResult();
			PbCoverUtil.BookTally tally = PbCoverUtil.tallyBook(u, ctx, aux);
			assertTrue("no hero rows in chapter A", tally.heroIds.isEmpty());
			PbComposeUtil.Cast cast = PbCoverUtil.coverCast(u, ctx, PbCoverUtil.requireSeries(u, ctx), tally, aux, new CoverResult());
			log("coverCast tier=" + cast.tier + " members=" + cast.members.size() + " warnings=" + aux.warnings);
			assertEquals("the baseline cast is the tier without hero rows", CastTier.BASELINE_CAST, cast.tier);
			assertEquals(1, cast.members.size());
			assertEquals("Ysolde Marr", cast.members.get(0).get(FieldNames.FIELD_NAME));

			/// A standalone book cannot have series art.
			lone = newBook(u, "lone");
			final BaseRecord standalone = lone;
			refused("SERIES_ART on a standalone book", 409, "is not a chapter of a series; SERIES_ART needs one",
				() -> PbCoverUtil.getCreate(u, standalone, PbCompositionPurposeEnumType.SERIES_ART, Mode.MANUAL, null));
		}
		finally {
			deleteBook(u, lone);
			deleteBook(u, chA);
			try {
				int chapters = PbSeriesUtil.deleteSeries(u, oidOf(series));
				log("deleteSeries removed " + chapters + " chapter(s)");
			}
			catch(Exception e) {
				logger.error("deleteSeries: " + e.getMessage());
				deleteBook(u, chB);
			}
		}
	}

	// ─────────────────────────────── row editor: titleSafeBand, series ───────────────────────────────

	/// patchCompositionFields on a cover: titleSafeBand "top" lands as TOP (and "none", "bottom"), a value outside
	/// the set is 400, and the series key is refused as fixed.
	@Test
	public void patchTitleSafeBandAndRefuseSeries() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "band");
			CoverResult r = PbCoverUtil.getCreate(u, bk, PbCompositionPurposeEnumType.COVER, Mode.MANUAL, null);
			assertTrue(r.isWritten());
			BaseRecord cover = r.composition;
			assertEquals("precondition: UNKNOWN band on a manual cover", "UNKNOWN", status(cover, OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND));

			PbComposeUtil.patchCompositionFields(u, cover, map(OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND, "top"));
			BaseRecord after = PbComposeUtil.readComposition(u, oidOf(cover), orgId);
			assertEquals("TOP persisted", "TOP", status(after, OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND));
			PbComposeUtil.patchCompositionFields(u, after, map(OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND, "Bottom"));
			after = PbComposeUtil.readComposition(u, oidOf(cover), orgId);
			assertEquals("BOTTOM persisted (case-insensitive)", "BOTTOM", status(after, OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND));
			PbComposeUtil.patchCompositionFields(u, after, map(OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND, "none"));
			after = PbComposeUtil.readComposition(u, oidOf(cover), orgId);
			assertEquals("NONE persisted", "NONE", status(after, OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND));

			final BaseRecord c = after;
			refused("band outside its set", 400, "titleSafeBand must be one of",
				() -> PbComposeUtil.patchCompositionFields(u, c, map(OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND, "middle")));
			refused("series is fixed", 400, "a composition's series is fixed",
				() -> PbComposeUtil.patchCompositionFields(u, c, map(OlioFieldNames.FIELD_PB_SERIES, "x")));
			BaseRecord unchanged = PbComposeUtil.readComposition(u, oidOf(cover), orgId);
			assertEquals("refused patches changed nothing", "NONE", status(unchanged, OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── read-only collaborator ───────────────────────────────

	/// testUser2 with Read on the book group and world can read the book and findCover, but getCreate in every
	/// mode is 403 "Not permitted to create cover art of book <slug>" and nothing is written.
	@Test
	public void readOnlyUserIs403AndWritesNothing() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull(org);
		BaseRecord reader = ioContext.getFactory().getCreateUser(org.getAdminUser(), SECOND_USER, org.getOrganizationId());
		assertNotNull("reader " + SECOND_USER, reader);
		assertNotEquals("reader is a different user from the owner", idOf(u), idOf(reader));
		BaseRecord bk = null;
		try {
			bk = newBook(u, "reader");
			String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
			BookContext bctx = bookContext(u, bk);
			BaseRecord scene = addScene(u, bk, 1, "Arrival", "a rain-dark harbour street at dusk", "walking", "wary");
			ComposeResult cr = PbComposeUtil.composeMigrated(u, bk, scene, false);
			assertTrue(cr.isWritten());

			BaseRecord olio = olioUser(orgId);
			BaseRecord bookGroup = ioContext.getPathUtil().findPath(olio, ModelNames.MODEL_GROUP, PbBookUtil.bookGroupPath(slug),
				GroupEnumType.DATA.toString(), orgId);
			assertNotNull("book group", bookGroup);
			ioContext.getAuthorizationUtil().setEntitlement(org.getAdminUser(), reader, new BaseRecord[] { bookGroup },
				new String[] { "Read" }, new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() });
			BaseRecord world = bctx.getWorld();
			assertNotNull("world record", world);
			ioContext.getAuthorizationUtil().setEntitlement(org.getAdminUser(), reader, new BaseRecord[] { world },
				new String[] { "Read" }, new String[] { PermissionEnumType.DATA.toString() });

			BaseRecord asReader = PbBookUtil.readBook(reader, oidOf(bk), orgId);
			assertNotNull("precondition: the reader can READ the book (else the refusal would be a 404, not the 403 under test)", asReader);
			PolicyResponseType canUpd = ioContext.getAuthorizationUtil().canUpdate(reader, reader, asReader);
			assertFalse("precondition: the reader must NOT be permitted to update the book",
				canUpd != null && canUpd.getType() == PolicyResponseEnumType.PERMIT);
			assertNull("the reader's findCover reads (and finds nothing yet)", PbCoverUtil.findCover(reader, asReader, PbCompositionPurposeEnumType.COVER));
			assertTrue("the reader's listCovers reads", PbCoverUtil.listCovers(reader, asReader).isEmpty());

			final String expected = "Not permitted to create cover art of book " + slug;
			final String sceneOid = oidOf(scene);
			refused("reader MANUAL", 403, expected, () -> PbCoverUtil.getCreate(reader, asReader, PbCompositionPurposeEnumType.COVER, Mode.MANUAL, null));
			refused("reader FROM_SCENE", 403, expected, () -> PbCoverUtil.getCreate(reader, asReader, PbCompositionPurposeEnumType.COVER, Mode.FROM_SCENE, sceneOid));
			refused("reader AUTO", 403, expected, () -> PbCoverUtil.getCreate(reader, asReader, PbCompositionPurposeEnumType.TITLE_PAGE, Mode.AUTO, null));
			refused("reader SERIES_ART (403 precedes the 409)", 403, expected, () -> PbCoverUtil.getCreate(reader, asReader, PbCompositionPurposeEnumType.SERIES_ART, Mode.MANUAL, null));

			assertEquals("nothing was written: no COVER row", 0, countCompositions(u, bk, "COVER"));
			assertEquals("nothing was written: no TITLE_PAGE row", 0, countCompositions(u, bk, "TITLE_PAGE"));
			assertTrue("the owner's listCovers is still empty", PbCoverUtil.listCovers(u, bk).isEmpty());
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private static String describe(CoverResult r) {
		return "purpose=" + r.purpose + " mode=" + r.mode + " created=" + r.created + " existing=" + r.existingReturned + " source=" + r.source
			+ " tier=" + r.castTier + " heroCast=" + r.heroCast + " hard=" + r.hardFailure + " llmFailure=" + r.llmFailure + " reason=" + r.failureReason
			+ " comp=" + (r.composition != null ? (Object) r.composition.get(FieldNames.FIELD_OBJECT_ID) : null)
			+ " actors=" + r.actors.size() + " elements=" + r.elements.size() + " ms=" + r.elapsedMs;
	}

	/// Compositions of the book with the given purpose, counted on the DB uncached (purpose compared in Java,
	/// the way PbCoverUtil's own lookups do).
	private int countCompositions(BaseRecord u, BaseRecord bk, String purpose) {
		int n = 0;
		for(BaseRecord c : PbComposeUtil.listSceneCompositions(u, bk).values()) {
			if(purpose.equalsIgnoreCase(status(c, OlioFieldNames.FIELD_PB_PURPOSE))) {
				n++;
			}
		}
		Map<PbCompositionPurposeEnumType, BaseRecord> covers = PbCoverUtil.listCovers(u, bk);
		for(Map.Entry<PbCompositionPurposeEnumType, BaseRecord> e : covers.entrySet()) {
			if(e.getKey() == PbCompositionPurposeEnumType.SERIES_ART) {
				// listCovers resolves series art org-wide; count only a row hosted in THIS book's group
				if(purpose.equalsIgnoreCase("SERIES_ART") && fkId(e.getValue(), OlioFieldNames.FIELD_PB_BOOK) == idOf(bk)) {
					n++;
				}
			}
			else if(purpose.equalsIgnoreCase(e.getKey().toString())) {
				n++;
			}
		}
		return n;
	}

	private void describeBook(BaseRecord u, BaseRecord bk, String description) throws Exception {
		BaseRecord patch = PbGraphUtil.patchOf(bk, OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_DESCRIPTION);
		patch.set(FieldNames.FIELD_DESCRIPTION, description);
		assertNotNull("book description patch", ioContext.getAccessPoint().update(u, patch));
		BaseRecord fresh = PbBookUtil.readBook(u, oidOf(bk), orgOf(u));
		assertEquals("description persisted", description, fresh.get(FieldNames.FIELD_DESCRIPTION));
	}

	/// A population member created as the user (the TestPbComposeFallback shape).
	private BaseRecord createCharPerson(BaseRecord u, String groupPath, String name, String firstName, String lastName) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, u, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set(FieldNames.FIELD_FIRST_NAME, firstName);
		if(lastName != null) {
			cp.set(FieldNames.FIELD_LAST_NAME, lastName);
		}
		cp.set(FieldNames.FIELD_GENDER, "female");
		BaseRecord created = ioContext.getAccessPoint().create(u, cp);
		assertNotNull("create charPerson " + name, created);
		cp.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
		cp.set(FieldNames.FIELD_OBJECT_ID, created.get(FieldNames.FIELD_OBJECT_ID));
		return cp;
	}

	private void refused(String what, int status, String contains, Call c) throws Exception {
		try {
			c.run();
		}
		catch(PictureBookException e) {
			log(what + ": " + e.getStatus() + " " + e.getMessage());
			assertEquals(what + ": status (" + e.getMessage() + ")", status, e.getStatus());
			assertNotNull(what + ": message", e.getMessage());
			assertTrue(what + ": message must contain '" + contains + "' but was: " + e.getMessage(), e.getMessage().contains(contains));
			return;
		}
		fail(what + ": expected PictureBookException " + status + " but nothing was thrown");
	}

	private static String status(BaseRecord rec, String field) {
		Object v = (rec != null && rec.hasField(field) ? rec.get(field) : null);
		return (v == null ? null : String.valueOf(v).toUpperCase());
	}

	private static double dbl(BaseRecord rec, String field) {
		Object v = rec.get(field);
		assertTrue(field + " is a number but was " + v, v instanceof Number);
		return ((Number) v).doubleValue();
	}

	private BookContext bookContext(BaseRecord u, BaseRecord bk) {
		BookContext bctx = PbBookUtil.openBookContext(u, bk);
		assertNotNull("book context", bctx);
		assertNotNull("book world", bctx.getWorld());
		return bctx;
	}

	/// A scene with the fields the compose step reads; nulls are left unset. The scene is re-read as the user.
	private BaseRecord addScene(BaseRecord u, BaseRecord bk, int index, String title, String setting, String action, String mood) throws Exception {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = PbBookUtil.createScene(u, bk, index, title, PbBookUtil.bookGroupPath(slug));
		assertNotNull("createScene " + index, scene);
		List<String> fields = new ArrayList<>();
		Map<String, Object> values = new LinkedHashMap<>();
		put(fields, values, OlioFieldNames.FIELD_PB_SETTING, setting);
		put(fields, values, OlioFieldNames.FIELD_PB_ACTION, action);
		put(fields, values, OlioFieldNames.FIELD_PB_MOOD, mood);
		if(!fields.isEmpty()) {
			BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, fields.toArray(new String[0]));
			for(Map.Entry<String, Object> e : values.entrySet()) {
				patch.set(e.getKey(), e.getValue());
			}
			assertNotNull("patch scene " + index, ioContext.getAccessPoint().update(u, patch));
		}
		BaseRecord fresh = PbBookUtil.readScene(u, oidOf(scene), orgOf(u));
		assertNotNull("scene " + index + " readable by " + u.get(FieldNames.FIELD_NAME), fresh);
		return fresh;
	}

	private static void put(List<String> fields, Map<String, Object> values, String field, Object value) {
		if(value != null) {
			fields.add(field);
			values.put(field, value);
		}
	}

	private BaseRecord newBook(BaseRecord u, String tag) {
		String slug = ("p91-" + tag + "-" + shortId()).toLowerCase();
		BaseRecord bk = PbBookUtil.createBook(u, dataPath(), slug, "P9-1 " + tag + " " + slug);
		assertNotNull("createBook " + slug, bk);
		log("book " + slug + " " + oidOf(bk));
		return bk;
	}

	private void deleteBook(BaseRecord u, BaseRecord bk) {
		if(bk == null) {
			return;
		}
		try {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(u, oidOf(bk));
			log("cleanup " + bk.get(FieldNames.FIELD_NAME) + ": deleted=" + res.deleted + " reason=" + res.reason);
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

	private static Map<String, Object> map(String k, Object v) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put(k, v);
		return m;
	}

	private static int intOf(Object o) {
		assertTrue("a number but was " + o, o instanceof Number);
		return ((Number) o).intValue();
	}

	private void log(String msg) {
		logger.info("[p91] " + msg);
	}

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

	private BaseRecord olioUser(long orgId) {
		BaseRecord olio = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal in organization " + orgId, olio);
		return olio;
	}

	static long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	static long idOf(BaseRecord rec) {
		return ((Number) rec.get(FieldNames.FIELD_ID)).longValue();
	}

	static String oidOf(BaseRecord rec) {
		assertNotNull("record", rec);
		return rec.get(FieldNames.FIELD_OBJECT_ID);
	}

	static long fkId(BaseRecord rec, String field) {
		Object o = (rec != null && rec.hasField(field) ? rec.get(field) : null);
		return (o instanceof BaseRecord ? idOf((BaseRecord) o) : 0L);
	}
}
