package org.cote.accountmanager.olio.picturebook;

import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.describe;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.enumWord;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.fkId;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.idOf;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.oidOf;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.orgOf;
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

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PbMigrationUtil.CompositionMigrationResult;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionSourceEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.thread.AsyncJobRegistry;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 4, P4-10: the one-off migration of a STORY book's legacy scene fields ({@code sdPrompt},
 * {@code promptLocked}, {@code configOverride}) into {@code MIGRATED} {@code olio.pb.composition} rows -
 * {@link PbMigrationUtil#migrateCompositions}, the {@code failedSceneObjectIds} contract it shares with the
 * compose job ({@link PbComposeJobUtil}), and the rule that a composition's prompt/lock/config survive every
 * later recompose because {@code PbComposeUtil.persist} never patches them on an existing row.
 * <p>
 * Live {@code am7db} as {@code testUser1} in {@code /Development/World Building}; every case creates its own
 * throwaway book and deletes it. Cases 01-04 need no LLM (04 hands the job a chat config whose server accepts
 * no connections, as a sentinel: had the job composed a migration-failed scene the outcome would read
 * {@code error}, never {@code migrationFailed}); 05 drives the real compose job against the LLM the gate
 * resolved (the {@code [LLM-GATE]} line in the run log names the tier). Every assertion is on a record re-read
 * from the DB (uncached {@code readComposition}/{@code readScene}/{@code findSceneComposition}). Timings are
 * logged as MEASURED, never asserted.
 * <ol>
 * <li>Guards: a null user or book, a ChapBook, and a book with no group each return an empty result with
 * exactly one warning (exact text) and write nothing; {@code toMap()} carries every counter plus
 * {@code failedSceneObjectIds} as a list.</li>
 * <li>Legacy fields become MIGRATED compositions: {@code sdPrompt} (trimmed) -> {@code promptOverride},
 * {@code promptLocked} and {@code configOverride} carried; a blank {@code sdPrompt} carries nothing; the
 * scenes themselves are untouched; a second pass creates nothing ({@code skippedExisting}) and leaves exactly
 * one SCENE composition per scene.</li>
 * <li>A user-edited composition is never overwritten: counted as {@code skippedUserEdited} by the migration,
 * refused by {@code composeMigrated(force=false)}; a forced re-migrate bumps the revision and resets
 * {@code userEdited} but still preserves promptOverride/promptLocked/configOverride.</li>
 * <li>A real non-admin migration failure, through the real job: a user who may UPDATE the book (DATA
 * {@code /Update} on the book record) but may not CREATE in its group runs the compose job; the migration
 * fails for every scene ({@code failed}, {@code failedSceneObjectIds}, a "Failed to create composition"
 * warning), the loop skips the compose for those scenes ({@code status:"migrationFailed"}, the exact error
 * text, {@code hardFailures}), no composition is written, and the scene's legacy prompt and lock are kept.</li>
 * <li>Live: the compose job migrates first, then LLM-composes over the MIGRATED rows; {@code migrated} is 0
 * on the first pass; a composed row is LLM revision 2 and a hard failure leaves the MIGRATED row at revision
 * 1; in every case the migrated promptOverride/promptLocked/configOverride survive, and survive a forced
 * recompose too.</li>
 * </ol>
 */
public class TestPbCompositionMigration extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String LIMITED_USER = "pbMigrateLimited";
	private static final String CFG_NAME = "P410 Migration Cfg";
	private static final String DEAD_CFG_NAME = "P410 Migration Dead Cfg";
	private static final String DEAD_LLM_SERVER = "http://127.0.0.1:1";

	private static final long JOB_TIMEOUT_MS = 20L * 60L * 1000L;
	private static final long LOCAL_TIMEOUT_MS = 5L * 60L * 1000L;
	private static final long PHASE_POLL_MS = 2_000L;
	private static final long POLL_MS = 250L;

	private static final String LEGACY_PROMPT_RAW = "   the keeper on the lighthouse gallery, storm light on wet stone  ";
	private static final String LEGACY_PROMPT = "the keeper on the lighthouse gallery, storm light on wet stone";
	private static final String SPARSE_OVERRIDE = "{\"schema\":\"olio.sd.config\",\"steps\":20}";

	private static final String MIGRATION_FAILED_ERROR = "composition migration failed for this scene; not composed so its legacy prompt and lock are kept";
	private static final String[] RESULT_KEYS = { "scanned", "created", "skippedExisting", "skippedUserEdited", "failed",
		"failedSceneObjectIds", "tagsPinned", "tagsSkippedPinned", "tagsAmbiguous", "tagsAffected", "tagsStaled",
		"warnings" };

	private static final String SETTING_1 = "the spiral stair of the Varrow Point lighthouse, salt-damp stone, a storm rising outside";
	private static final String ACTION_1 = "Ilse climbs with the lamp oil while Corin follows two steps behind";
	private static final String TEXT_1 = "Ilse Brandt took the stair two at a time, the oil can knocking against her hip. "
		+ "Corin Hale followed with the lantern, its light swinging across the salt-damp stone. Below them the door "
		+ "banged in the wind and the first thunder rolled in off the water. 'Keep up,' she said, 'the lamp has been "
		+ "dark an hour already.'";
	private static final String SETTING_2 = "the lamp room at the top of the Varrow Point lighthouse, brass and glass, lightning beyond the panes";
	private static final String ACTION_2 = "Ilse trims the wick and lights the great lamp while Corin watches the ship on the rocks";
	private static final String TEXT_2 = "The lamp room was all brass and glass and the smell of cold oil. Ilse Brandt knelt at the "
		+ "burner, trimming the wick with a steady hand, while Corin Hale pressed his face to the pane. Lightning showed "
		+ "him the schooner on the Varrow rocks, her foremast gone. 'Now,' Ilse said, and struck the match, and the "
		+ "great lamp caught.";

	@Before
	public void setupEach() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
	}

	// ─────────────────────────────── 01: guards ───────────────────────────────

	@Test(timeout = 1_200_000)
	public void case01_guardsReturnEmptyResultWithOneWarningAndWriteNothing() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = makeChapBook(u, newBook(u, "c01"));
			String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
			BaseRecord page = addScene(u, bk, 1, "Page One", null, null, null, "A ChapBook page of the P4-10 guard fixture",
				LEGACY_PROMPT, Boolean.TRUE, null);
			List<BaseRecord> pages = PbBookUtil.listScenes(u, bk);
			assertEquals(1, pages.size());
			long gid = ((Number) bk.get(FieldNames.FIELD_GROUP_ID)).longValue();

			/// null user / null book
			CompositionMigrationResult r0 = PbMigrationUtil.migrateCompositions(null, bk, pages);
			assertEmptyWithWarning(r0, "composition migration skipped: user or book is null");
			CompositionMigrationResult r1 = PbMigrationUtil.migrateCompositions(u, null, pages);
			assertEmptyWithWarning(r1, "composition migration skipped: user or book is null");

			/// a ChapBook is not migrated
			CompositionMigrationResult r2 = PbMigrationUtil.migrateCompositions(u, bk, pages);
			assertEmptyWithWarning(r2, "composition migration skipped: '" + slug + "' is a ChapBook (page compositions are created by the ChapBook path)");
			assertNull("no SCENE composition was written for the page",
				PbComposeUtil.findSceneComposition(u, page, gid, orgId));
			assertNull("no CHAPBOOK_PAGE composition was written for the page",
				PbComposeUtil.findSceneComposition(u, page, gid, orgId, PbCompositionPurposeEnumType.CHAPBOOK_PAGE));
			BaseRecord pageAfter = readScene(u, oidOf(page), orgId);
			assertEquals("the page's legacy prompt is untouched", LEGACY_PROMPT, pageAfter.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertEquals(Boolean.TRUE, pageAfter.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));

			/// a STORY book record with no group/organization (in memory; nothing is read or written).
			/// The bare newInstance materialises every long at 0L (not null), and BaseRecord.get() materialises an
			/// absent field on read, so the two ids are set to null explicitly to reach the guard.
			BaseRecord bare = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK,
				new String[] { OlioFieldNames.FIELD_PB_SLUG, OlioFieldNames.FIELD_PB_BOOK_TYPE, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID });
			bare.set(OlioFieldNames.FIELD_PB_SLUG, "p410-bare");
			bare.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.STORY.toString());
			bare.set(FieldNames.FIELD_GROUP_ID, (Long) null);
			bare.set(FieldNames.FIELD_ORGANIZATION_ID, (Long) null);
			assertNull("precondition: the bare book has no groupId", bare.get(FieldNames.FIELD_GROUP_ID));
			assertNull("precondition: the bare book has no organizationId", bare.get(FieldNames.FIELD_ORGANIZATION_ID));
			assertFalse(PbNodeExecutor.isChapBook(bare));
			CompositionMigrationResult r3 = PbMigrationUtil.migrateCompositions(u, bare, pages);
			assertEmptyWithWarning(r3, "composition migration skipped: book 'p410-bare' has no group or organization");

			/// toMap() shape
			Map<String, Object> m = r2.toMap();
			assertEquals("toMap keys in order: " + m.keySet(), Arrays.asList(RESULT_KEYS), new ArrayList<>(m.keySet()));
			assertEquals(Integer.valueOf(0), m.get("scanned"));
			assertEquals(Integer.valueOf(0), m.get("failed"));
			assertTrue("failedSceneObjectIds is a List", m.get("failedSceneObjectIds") instanceof List);
			assertTrue(((List<?>) m.get("failedSceneObjectIds")).isEmpty());
			assertEquals(r2.warnings, m.get("warnings"));
			assertNotEquals("the warnings list is a copy", System.identityHashCode(r2.warnings), System.identityHashCode(m.get("warnings")));
			log("case01 " + r2 + " -> " + m);
		}
		finally {
			deleteBook(u, bk);
		}
	}

	private static void assertEmptyWithWarning(CompositionMigrationResult r, String warning) {
		assertNotNull(r);
		assertEquals("scanned: " + r, 0, r.scanned);
		assertEquals("created: " + r, 0, r.created);
		assertEquals("skippedExisting: " + r, 0, r.skippedExisting);
		assertEquals("skippedUserEdited: " + r, 0, r.skippedUserEdited);
		assertEquals("failed: " + r, 0, r.failed);
		assertTrue("no failed scene ids: " + r.failedSceneObjectIds, r.failedSceneObjectIds.isEmpty());
		assertEquals("exactly one warning: " + r.warnings, 1, r.warnings.size());
		assertEquals(warning, r.warnings.get(0));
	}

	// ─────────────────────────────── 02: legacy fields -> MIGRATED compositions ───────────────────────────────

	@Test(timeout = 1_200_000)
	public void case02_legacyFieldsBecomeMigratedCompositionsAndASecondPassCreatesNothing() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "c02");
			long gid = ((Number) bk.get(FieldNames.FIELD_GROUP_ID)).longValue();
			BaseRecord s1 = addScene(u, bk, 1, "The Climb", SETTING_1, ACTION_1, "urgent", TEXT_1, LEGACY_PROMPT_RAW, Boolean.TRUE, SPARSE_OVERRIDE);
			BaseRecord s2 = addScene(u, bk, 2, "The Lamp Room", SETTING_2, ACTION_2, "resolute", TEXT_2, "   ", null, null);
			assertEquals("precondition: s1 carries the raw legacy prompt", LEGACY_PROMPT_RAW, s1.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertEquals(Boolean.TRUE, s1.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals(SPARSE_OVERRIDE, s1.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertNull(PbComposeUtil.findSceneComposition(u, s1, gid, orgId));
			assertNull(PbComposeUtil.findSceneComposition(u, s2, gid, orgId));

			List<BaseRecord> scenes = PbBookUtil.listScenes(u, bk);
			assertEquals(2, scenes.size());
			long t0 = System.currentTimeMillis();
			CompositionMigrationResult r = PbMigrationUtil.migrateCompositions(u, bk, scenes);
			log("MEASURED: case02 first pass " + (System.currentTimeMillis() - t0) + " ms -> " + r + " warnings=" + r.warnings);
			assertEquals("scanned: " + r, 2, r.scanned);
			assertEquals("created: " + r, 2, r.created);
			assertEquals("failed: " + r, 0, r.failed);
			assertEquals("skippedExisting: " + r, 0, r.skippedExisting);
			assertEquals("skippedUserEdited: " + r, 0, r.skippedUserEdited);
			assertTrue("no failed scene ids: " + r.failedSceneObjectIds, r.failedSceneObjectIds.isEmpty());
			assertEquals("no tags to pin: " + r, 0, r.tagsPinned);
			assertEquals(0, r.tagsSkippedPinned);
			assertEquals(0, r.tagsAmbiguous);
			for(String w : r.warnings) {
				assertFalse("no write-failure warning: " + w, w.contains("was not written") || w.contains("Failed to"));
			}

			/// comp1: the legacy prompt (trimmed), its lock and the config override are carried onto the MIGRATED row
			BaseRecord c1 = PbComposeUtil.findSceneComposition(u, s1, gid, orgId);
			assertNotNull("s1 composition", c1);
			c1 = PbComposeUtil.readComposition(u, oidOf(c1), orgId);
			assertEquals(PbCompositionSourceEnumType.MIGRATED.name(), enumWord(c1, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals(PbCompositionPurposeEnumType.SCENE.name(), enumWord(c1, OlioFieldNames.FIELD_PB_PURPOSE));
			assertEquals(Integer.valueOf(1), c1.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals(Boolean.FALSE, c1.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			assertEquals("promptOverride is the trimmed legacy sdPrompt", LEGACY_PROMPT, c1.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertEquals("promptLocked carried", Boolean.TRUE, c1.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals("configOverride carried", SPARSE_OVERRIDE, c1.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertEquals(idOf(s1), fkId(c1, OlioFieldNames.FIELD_PB_SCENE));
			assertEquals(idOf(bk), fkId(c1, OlioFieldNames.FIELD_PB_BOOK));
			assertEquals("the composition lives in the book group", gid, ((Number) c1.get(FieldNames.FIELD_GROUP_ID)).longValue());
			assertEquals("settingText from the scene", SETTING_1, c1.get(OlioFieldNames.FIELD_PB_SETTING_TEXT));
			assertEquals("mood from the scene", "urgent", c1.get(OlioFieldNames.FIELD_PB_MOOD));
			assertTrue("a MIGRATED row is flagged for a real compose", PbComposeUtil.needsCompose(c1));

			/// comp2: a blank legacy prompt carries nothing
			BaseRecord c2 = PbComposeUtil.findSceneComposition(u, s2, gid, orgId);
			assertNotNull("s2 composition", c2);
			c2 = PbComposeUtil.readComposition(u, oidOf(c2), orgId);
			assertEquals(PbCompositionSourceEnumType.MIGRATED.name(), enumWord(c2, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals(Integer.valueOf(1), c2.get(OlioFieldNames.FIELD_PB_REVISION));
			assertNull("a blank sdPrompt is not a promptOverride", c2.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertEquals(Boolean.FALSE, c2.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertNull(c2.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertEquals(idOf(s2), fkId(c2, OlioFieldNames.FIELD_PB_SCENE));
			assertTrue(PbComposeUtil.needsCompose(c2));
			assertNotEquals(oidOf(c1), oidOf(c2));

			/// the scenes themselves are untouched by the migration
			BaseRecord s1b = readScene(u, oidOf(s1), orgId);
			assertEquals(LEGACY_PROMPT_RAW, s1b.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertEquals(Boolean.TRUE, s1b.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals(SPARSE_OVERRIDE, s1b.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));

			/// second pass: idempotent
			CompositionMigrationResult r2 = PbMigrationUtil.migrateCompositions(u, bk, PbBookUtil.listScenes(u, bk));
			log("case02 second pass " + r2 + " warnings=" + r2.warnings);
			assertEquals(2, r2.scanned);
			assertEquals("nothing created on the second pass", 0, r2.created);
			assertEquals(2, r2.skippedExisting);
			assertEquals(0, r2.skippedUserEdited);
			assertEquals(0, r2.failed);
			assertEquals("exactly one SCENE composition for s1", 1, countSceneCompositions(u, s1, gid, orgId));
			assertEquals("exactly one SCENE composition for s2", 1, countSceneCompositions(u, s2, gid, orgId));
			BaseRecord c1b = PbComposeUtil.readComposition(u, oidOf(c1), orgId);
			assertEquals("revision unchanged by the second pass", Integer.valueOf(1), c1b.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals(LEGACY_PROMPT, c1b.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));

			/// toMap of a real pass
			Map<String, Object> m = r.toMap();
			assertEquals(Arrays.asList(RESULT_KEYS), new ArrayList<>(m.keySet()));
			assertEquals(Integer.valueOf(2), m.get("created"));
			assertTrue(((List<?>) m.get("failedSceneObjectIds")).isEmpty());
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── 03: user-edited is never overwritten ───────────────────────────────

	@Test(timeout = 1_200_000)
	public void case03_userEditedCompositionIsNeverOverwrittenAndForcedRemigratePreservesPromptLockConfig() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "c03");
			long gid = ((Number) bk.get(FieldNames.FIELD_GROUP_ID)).longValue();
			BaseRecord s1 = addScene(u, bk, 1, "The Climb", SETTING_1, ACTION_1, "urgent", TEXT_1, LEGACY_PROMPT_RAW, Boolean.TRUE, SPARSE_OVERRIDE);
			BaseRecord s2 = addScene(u, bk, 2, "The Lamp Room", SETTING_2, ACTION_2, "resolute", TEXT_2, null, null, null);
			CompositionMigrationResult r = PbMigrationUtil.migrateCompositions(u, bk, PbBookUtil.listScenes(u, bk));
			log("case03 first pass " + r + " warnings=" + r.warnings);
			assertEquals(2, r.created);
			assertEquals(0, r.failed);
			BaseRecord c1 = PbComposeUtil.findSceneComposition(u, s1, gid, orgId);
			BaseRecord c2 = PbComposeUtil.findSceneComposition(u, s2, gid, orgId);
			assertNotNull(c1);
			assertNotNull(c2);
			String c1Oid = oidOf(c1);

			/// A user edit through the facade (the board's PATCH): touchEdited marks the row USER + userEdited.
			Map<String, Object> body = new LinkedHashMap<>();
			body.put(OlioFieldNames.FIELD_PB_MOOD, "wistful");
			Map<String, Object> edit = PbServiceFacade.patchSceneComposition(u, oidOf(bk), oidOf(s1), body);
			log("case03 user edit " + edit);
			assertEquals(Boolean.TRUE, edit.get("updated"));
			assertEquals(c1Oid, edit.get("compositionObjectId"));
			BaseRecord e1 = PbComposeUtil.readComposition(u, c1Oid, orgId);
			assertEquals(Boolean.TRUE, e1.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			assertEquals(PbCompositionSourceEnumType.USER.name(), enumWord(e1, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals(Integer.valueOf(2), e1.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals("wistful", e1.get(OlioFieldNames.FIELD_PB_MOOD));
			assertEquals(LEGACY_PROMPT, e1.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertEquals(Boolean.TRUE, e1.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals(SPARSE_OVERRIDE, e1.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertFalse("a user-edited row is not flagged for compose", PbComposeUtil.needsCompose(e1));

			/// The migration counts it separately and never overwrites it.
			CompositionMigrationResult r2 = PbMigrationUtil.migrateCompositions(u, bk, PbBookUtil.listScenes(u, bk));
			log("case03 second pass " + r2 + " warnings=" + r2.warnings);
			assertEquals(2, r2.scanned);
			assertEquals(0, r2.created);
			assertEquals(1, r2.skippedUserEdited);
			assertEquals(1, r2.skippedExisting);
			assertEquals(0, r2.failed);
			BaseRecord e1b = PbComposeUtil.readComposition(u, c1Oid, orgId);
			assertEquals("untouched: revision", Integer.valueOf(2), e1b.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals("untouched: mood", "wistful", e1b.get(OlioFieldNames.FIELD_PB_MOOD));
			assertEquals(PbCompositionSourceEnumType.USER.name(), enumWord(e1b, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals(1, countSceneCompositions(u, s1, gid, orgId));

			/// composeMigrated without force refuses the user-edited row
			ComposeResult skip = PbComposeUtil.composeMigrated(u, bk, s1, false);
			log("case03 composeMigrated(force=false) " + describe(skip));
			assertTrue(skip.skippedUserEdited);
			assertFalse(skip.isWritten());
			assertNotNull(skip.composition);
			assertEquals(c1Oid, oidOf(skip.composition));
			assertEquals(Integer.valueOf(2), PbComposeUtil.readComposition(u, c1Oid, orgId).get(OlioFieldNames.FIELD_PB_REVISION));

			/// composeMigrated with force re-migrates in place: revision+1, MIGRATED, unedited, mood back from the scene -
			/// and promptOverride/promptLocked/configOverride are preserved (persist never patches them on an existing row).
			ComposeResult forced = PbComposeUtil.composeMigrated(u, bk, s1, true);
			log("case03 composeMigrated(force=true) " + describe(forced));
			assertTrue("written: " + describe(forced), forced.isWritten());
			assertFalse(forced.hardFailure);
			assertEquals(PbCompositionSourceEnumType.MIGRATED, forced.source);
			assertEquals(c1Oid, oidOf(forced.composition));
			BaseRecord f1 = PbComposeUtil.readComposition(u, c1Oid, orgId);
			assertEquals(Integer.valueOf(3), f1.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals(PbCompositionSourceEnumType.MIGRATED.name(), enumWord(f1, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals(Boolean.FALSE, f1.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			assertEquals("mood re-staged from the scene", "urgent", f1.get(OlioFieldNames.FIELD_PB_MOOD));
			assertEquals("promptOverride survives the forced re-migrate", LEGACY_PROMPT, f1.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertEquals("promptLocked survives", Boolean.TRUE, f1.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals("configOverride survives", SPARSE_OVERRIDE, f1.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertTrue(PbComposeUtil.needsCompose(f1));
			assertEquals(1, countSceneCompositions(u, s1, gid, orgId));

			/// and the scene is still what it was
			BaseRecord s1b = readScene(u, oidOf(s1), orgId);
			assertEquals(LEGACY_PROMPT_RAW, s1b.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertEquals(Boolean.TRUE, s1b.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals(SPARSE_OVERRIDE, s1b.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertEquals("urgent", s1b.get(OlioFieldNames.FIELD_PB_MOOD));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── 04: a real non-admin migration failure through the job ───────────────────────────────

	/**
	 * The limited user may UPDATE the book (a DATA {@code /Update} entitlement on the book record satisfies
	 * {@code systemUpdateRule}'s {@code resourceUpdateAccess}, so {@code requireBookUpdate} PERMITs) and may READ
	 * the book group and its scenes, but holds no {@code /Update} on the group - so {@code systemCreateRule}
	 * (owner, group owner, admin, group DATA/GROUP {@code /Update}) denies the composition CREATE and
	 * {@code createComposition} throws its 500. Grants are applied by the organization's own admin as fixture
	 * setup, exactly as {@code TestChapBookPageComposition.grantedPartialUser}; the test never acts as the admin.
	 * The chat config handed to the job points at a server that accepts no connections: had the loop composed a
	 * migration-failed scene, the outcome would be {@code error} (an LLM/create failure), never {@code migrationFailed}.
	 */
	@Test(timeout = 1_200_000)
	public void case04_migrationFailureForANonAdminSkipsTheComposeAndKeepsTheLegacyPromptAndLock() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		BaseRecord limited = null;
		BaseRecord deadCfg = null;
		String jobId = null;
		try {
			bk = newBook(u, "c04");
			String bkOid = oidOf(bk);
			long gid = ((Number) bk.get(FieldNames.FIELD_GROUP_ID)).longValue();
			BaseRecord s1 = addScene(u, bk, 1, "The Climb", null, ACTION_1, "urgent", TEXT_1, LEGACY_PROMPT_RAW, Boolean.TRUE, SPARSE_OVERRIDE);
			BaseRecord s2 = addScene(u, bk, 2, "The Lamp Room", null, ACTION_2, "resolute", TEXT_2, null, null, null);
			assertNull(PbComposeUtil.findSceneComposition(u, s1, gid, orgId));
			assertNull(PbComposeUtil.findSceneComposition(u, s2, gid, orgId));

			limited = grantedLimitedUser(bk, orgId);
			long limitedOrg = orgOf(limited);
			assertEquals(orgId, limitedOrg);
			BaseRecord bkAsLimited = PbBookUtil.readBook(limited, bkOid, orgId);
			assertNotNull("precondition: the limited user can READ the book", bkAsLimited);
			assertNotNull("precondition: the limited user can READ scene 1", PbBookUtil.readScene(limited, oidOf(s1), orgId));
			assertNotNull("precondition: the limited user can READ scene 2", PbBookUtil.readScene(limited, oidOf(s2), orgId));
			PolicyResponseType canUpd = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(limited, limited, bkAsLimited);
			assertTrue("precondition: the limited user is permitted to UPDATE the book (resourceUpdateAccess): "
				+ (canUpd != null ? canUpd.getType() : null), canUpd != null && canUpd.getType() == PolicyResponseEnumType.PERMIT);
			BaseRecord fresh = PbBookUtil.requireBookUpdate(limited, bkAsLimited, "compose scenes");
			assertNotNull("precondition: requireBookUpdate PERMITs the limited user", fresh);
			assertEquals(2, PbBookUtil.listScenes(limited, fresh).size());

			deadCfg = OlioTestUtil.getUnreachableOllamaConfig(limited, DEAD_CFG_NAME, DEAD_LLM_SERVER, "qwen3:8b", 5);
			assertNotNull("dead chat config", deadCfg);

			long t0 = System.currentTimeMillis();
			Map<String, Object> dto = PbComposeJobUtil.submitCompose(limited, bkAsLimited, false, false, deadCfg, null, null, dataPath());
			log("case04 submit " + dto);
			assertEquals(Boolean.TRUE, dto.get("submitted"));
			assertEquals(Integer.valueOf(2), dto.get("sceneCount"));
			assertEquals(Integer.valueOf(2), dto.get("scenesToCompose"));
			jobId = (String) dto.get("jobId");
			assertNotNull(jobId);
			AsyncJob job = awaitTerminalLoggingPhases(limited, jobId, JOB_TIMEOUT_MS, "case04");
			log("MEASURED: case04 job wall " + (System.currentTimeMillis() - t0) + " ms -> " + job.getStatus() + " error=" + job.getError());
			log("case04 result " + job.getResult());
			assertEquals("job error: " + job.getError(), AsyncJob.Status.COMPLETED, job.getStatus());
			assertNull(PbComposeJobUtil.inflightJobId(bkOid));

			Map<?, ?> result = JSONUtil.importObject(job.getResult(), Map.class);
			assertNotNull("the result parses: " + job.getResult(), result);
			assertEquals(bkOid, result.get("bookObjectId"));
			assertEquals(2, num(result, "sceneCount"));
			assertEquals(2, num(result, "visited"));
			assertEquals("nothing composed", 0, num(result, "composed"));
			assertEquals("nothing migrated by the loop", 0, num(result, "migrated"));
			assertEquals(0, num(result, "current"));
			assertEquals(0, num(result, "skippedUserEdited"));
			assertEquals("both scenes are hard failures of the migration kind", 2, num(result, "hardFailures"));
			assertEquals(Boolean.FALSE, result.get("cancelled"));
			assertEquals(Boolean.FALSE, result.get("interrupted"));
			assertEquals(Boolean.TRUE, result.get("locationPassRan"));
			log("case04 location pass (dead LLM; reported, never thrown): " + result.get("locationPass"));

			Map<?, ?> migration = (Map<?, ?>) result.get("migration");
			assertNotNull("migration summary on the result: " + result, migration);
			assertNull("the migration did not throw: " + migration, migration.get("error"));
			assertEquals(2, num(migration, "scanned"));
			assertEquals(0, num(migration, "created"));
			assertEquals(2, num(migration, "failed"));
			List<?> failedIds = (List<?>) migration.get("failedSceneObjectIds");
			assertNotNull("failedSceneObjectIds on the wire: " + migration, failedIds);
			assertEquals(Arrays.asList(oidOf(s1), oidOf(s2)), failedIds);
			List<?> warnings = (List<?>) migration.get("warnings");
			assertNotNull(warnings);
			int createRefusals = 0;
			for(Object w : warnings) {
				if(String.valueOf(w).contains("Failed to create composition")) {
					createRefusals++;
				}
			}
			assertEquals("one create refusal per scene: " + warnings, 2, createRefusals);

			List<?> outcomes = (List<?>) result.get("scenes");
			assertEquals(2, outcomes.size());
			BaseRecord[] bySlot = { s1, s2 };
			for(int i = 0; i < 2; i++) {
				Map<?, ?> o = (Map<?, ?>) outcomes.get(i);
				String label = "outcome[" + i + "] " + o;
				assertEquals(label, oidOf(bySlot[i]), o.get("sceneObjectId"));
				assertEquals(label + " status", "migrationFailed", o.get("status"));
				assertEquals(label + " error", MIGRATION_FAILED_ERROR, o.get("error"));
				assertFalse(label + " no composition was written", o.containsKey("compositionObjectId"));
				assertFalse(label + " the compose never ran (no source)", o.containsKey("source"));
			}

			/// Nothing was written, as the owner sees it; the legacy fields are exactly as created.
			assertEquals("no SCENE composition for s1", 0, countSceneCompositions(u, s1, gid, orgId));
			assertEquals("no SCENE composition for s2", 0, countSceneCompositions(u, s2, gid, orgId));
			BaseRecord s1b = readScene(u, oidOf(s1), orgId);
			assertEquals("legacy prompt kept", LEGACY_PROMPT_RAW, s1b.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertEquals("legacy lock kept", Boolean.TRUE, s1b.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals("legacy configOverride kept", SPARSE_OVERRIDE, s1b.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			BaseRecord s2b = readScene(u, oidOf(s2), orgId);
			assertNull(s2b.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertNotEquals(Boolean.TRUE, s2b.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));

			/// The direct util reports the same failure shape the job consumed.
			CompositionMigrationResult direct = PbMigrationUtil.migrateCompositions(limited, fresh, PbBookUtil.listScenes(limited, fresh));
			log("case04 direct migration as the limited user " + direct + " warnings=" + direct.warnings);
			assertEquals(2, direct.scanned);
			assertEquals(0, direct.created);
			assertEquals(2, direct.failed);
			assertEquals(new ArrayList<>(Arrays.asList(oidOf(s1), oidOf(s2))), new ArrayList<>(direct.failedSceneObjectIds));
			assertEquals(0, countSceneCompositions(u, s1, gid, orgId));

			/// And the owner's own migration still works afterwards: nothing about the book was damaged.
			CompositionMigrationResult owner = PbMigrationUtil.migrateCompositions(u, bk, PbBookUtil.listScenes(u, bk));
			log("case04 owner migration " + owner + " warnings=" + owner.warnings);
			assertEquals(2, owner.created);
			assertEquals(0, owner.failed);
			BaseRecord c1 = PbComposeUtil.findSceneComposition(u, s1, gid, orgId);
			assertNotNull(c1);
			assertEquals(LEGACY_PROMPT, PbComposeUtil.readComposition(u, oidOf(c1), orgId).get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
		}
		finally {
			if(jobId != null && limited != null) {
				AsyncJobRegistry.cancel(limited, jobId);
				awaitTerminalQuietly(limited, jobId, LOCAL_TIMEOUT_MS);
			}
			deleteBook(u, bk);
			if(limited != null) {
				deleteConfig(limited, deadCfg);
			}
		}
	}

	// ─────────────────────────────── 05: live job - migrate, then LLM-compose over the MIGRATED rows ───────────────────────────────

	@Test(timeout = 1_800_000)
	public void case05_liveJobMigratesFirstThenComposesOverTheMigratedRowsPreservingPromptLockConfig() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		BaseRecord cfg = null;
		String jobId = null;
		try {
			cfg = liveConfig(u);
			bk = newBook(u, "c05");
			String bkOid = oidOf(bk);
			long gid = ((Number) bk.get(FieldNames.FIELD_GROUP_ID)).longValue();
			BookContext bctx = PbBookUtil.openBookContext(u, bk);
			assertNotNull("The book must carry a world", bctx);
			String popPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			assertNotNull("Population group path", popPath);
			BaseRecord s1 = addScene(u, bk, 1, "The Climb", SETTING_1, ACTION_1, "urgent", TEXT_1, LEGACY_PROMPT_RAW, Boolean.TRUE, SPARSE_OVERRIDE);
			BaseRecord s2 = addScene(u, bk, 2, "The Lamp Room", SETTING_2, ACTION_2, "resolute", TEXT_2, null, null, null);
			BaseRecord ilse = createCharPerson(u, popPath, "Ilse Brandt", "Ilse", "Brandt", "female");
			BaseRecord corin = createCharPerson(u, popPath, "Corin Hale", "Corin", "Hale", "male");
			enrol(u, s1, ilse);
			enrol(u, s1, corin);
			enrol(u, s2, ilse);
			enrol(u, s2, corin);
			assertNull(PbComposeUtil.findSceneComposition(u, s1, gid, orgId));
			assertNull(PbComposeUtil.findSceneComposition(u, s2, gid, orgId));

			long t0 = System.currentTimeMillis();
			Map<String, Object> dto = PbComposeJobUtil.submitCompose(u, bk, false, false, CFG_NAME, null, null, dataPath());
			log("case05 submit " + dto);
			assertEquals(Boolean.TRUE, dto.get("submitted"));
			assertEquals(Integer.valueOf(2), dto.get("scenesToCompose"));
			assertEquals(Integer.valueOf(0), dto.get("scenesCurrent"));
			jobId = (String) dto.get("jobId");
			assertNotNull(jobId);
			AsyncJob job = awaitTerminalLoggingPhases(u, jobId, JOB_TIMEOUT_MS, "case05");
			log("MEASURED: case05 first pass wall " + (System.currentTimeMillis() - t0) + " ms -> " + job.getStatus() + " error=" + job.getError());
			log("case05 result " + job.getResult());
			assertEquals("job error: " + job.getError(), AsyncJob.Status.COMPLETED, job.getStatus());
			assertNull(PbComposeJobUtil.inflightJobId(bkOid));

			Map<?, ?> result = JSONUtil.importObject(job.getResult(), Map.class);
			assertNotNull(result);
			assertEquals(2, num(result, "visited"));
			/// P4-10: the migration runs before the loop and writes a MIGRATED row for every scene, so the loop always
			/// finds an EXISTING row: a parseable reply is "composed" over it (LLM, revision 2); an unparseable one is a
			/// "hardFailure" that leaves the MIGRATED row untouched (revision 1). "migrated" is therefore always 0 here.
			/// How many of the two came back parseable is MEASURED, never asserted.
			Map<?, ?> migration = (Map<?, ?>) result.get("migration");
			assertNotNull("migration summary: " + result, migration);
			assertNull(migration.get("error"));
			assertEquals(2, num(migration, "scanned"));
			assertEquals("the job's migration wrote a MIGRATED row for every scene", 2, num(migration, "created"));
			assertEquals(0, num(migration, "failed"));
			assertEquals(0, num(migration, "skippedExisting"));
			/// The job result is JSON written by JSONUtil's NON_EMPTY mapper, so an empty list is ABSENT on the wire
			/// (measured: null here on the first run). Absent-or-empty is the "no failures" shape.
			List<?> failedOnWire = (List<?>) migration.get("failedSceneObjectIds");
			assertTrue("no migration failures on the wire: " + migration, failedOnWire == null || failedOnWire.isEmpty());
			int composed0 = num(result, "composed");
			int hard0 = num(result, "hardFailures");
			log("MEASURED: case05 first pass composed=" + composed0 + " hardFailures=" + hard0);
			assertEquals("the loop never writes a MIGRATED fallback over the migration's row", 0, num(result, "migrated"));
			assertEquals(0, num(result, "current"));
			assertEquals("every scene is composed or reported untouched", 2, composed0 + hard0);
			assertEquals(Boolean.TRUE, result.get("locationPassRan"));

			List<?> outcomes = (List<?>) result.get("scenes");
			assertEquals(2, outcomes.size());
			BaseRecord[] bySlot = { s1, s2 };
			int[] revision = new int[2];
			for(int i = 0; i < 2; i++) {
				Map<?, ?> o = (Map<?, ?>) outcomes.get(i);
				String label = "outcome[" + i + "] " + o;
				assertEquals(label, oidOf(bySlot[i]), o.get("sceneObjectId"));
				String status = (String) o.get("status");
				BaseRecord comp = PbComposeUtil.findSceneComposition(u, bySlot[i], gid, orgId);
				assertNotNull(label + " composition row", comp);
				assertEquals(label + " the outcome names the scene's row", oidOf(comp), o.get("compositionObjectId"));
				comp = PbComposeUtil.readComposition(u, oidOf(comp), orgId);
				if("composed".equals(status)) {
					assertEquals(label, PbCompositionSourceEnumType.LLM.name(), o.get("source"));
					assertEquals(label, PbCompositionSourceEnumType.LLM.name(), enumWord(comp, OlioFieldNames.FIELD_PB_SOURCE));
					assertEquals(label + " LLM over the MIGRATED row is revision 2", Integer.valueOf(2), comp.get(OlioFieldNames.FIELD_PB_REVISION));
					assertFalse(label, PbComposeUtil.needsCompose(comp));
					assertTrue(label + " actorCount", num(o, "actorCount") >= 1);
					revision[i] = 2;
				}
				else {
					assertEquals(label + " the only other first-pass outcome", "hardFailure", status);
					assertTrue(label + " says the row was left untouched", String.valueOf(o.get("error")).contains("existing composition left untouched"));
					assertEquals(label, PbCompositionSourceEnumType.MIGRATED.name(), enumWord(comp, OlioFieldNames.FIELD_PB_SOURCE));
					assertEquals(label + " the MIGRATED row stays at revision 1", Integer.valueOf(1), comp.get(OlioFieldNames.FIELD_PB_REVISION));
					assertTrue(label, PbComposeUtil.needsCompose(comp));
					revision[i] = 1;
				}
				assertEquals(label, Boolean.FALSE, comp.get(OlioFieldNames.FIELD_PB_USER_EDITED));
				assertEquals(label, idOf(bySlot[i]), fkId(comp, OlioFieldNames.FIELD_PB_SCENE));
				assertEquals(1, countSceneCompositions(u, bySlot[i], gid, orgId));
			}
			assertMigratedCarryOver(u, s1, s2, gid, orgId, "after the first pass");

			/// Forced resubmit: a composed row bumps again, a hard failure stays; the carry-over survives either way.
			t0 = System.currentTimeMillis();
			Map<String, Object> forced = PbComposeJobUtil.submitCompose(u, bk, false, true, CFG_NAME, null, null, dataPath());
			log("case05 forced submit " + forced);
			assertEquals(Boolean.TRUE, forced.get("submitted"));
			assertEquals(Integer.valueOf(2), forced.get("scenesToCompose"));
			jobId = (String) forced.get("jobId");
			AsyncJob job2 = awaitTerminalLoggingPhases(u, jobId, JOB_TIMEOUT_MS, "case05-forced");
			log("MEASURED: case05 forced pass wall " + (System.currentTimeMillis() - t0) + " ms -> " + job2.getStatus() + " error=" + job2.getError());
			log("case05 forced result " + job2.getResult());
			assertEquals("job error: " + job2.getError(), AsyncJob.Status.COMPLETED, job2.getStatus());
			Map<?, ?> r2 = JSONUtil.importObject(job2.getResult(), Map.class);
			Map<?, ?> mig2 = (Map<?, ?>) r2.get("migration");
			assertEquals("the second migration creates nothing", 0, num(mig2, "created"));
			assertEquals(2, num(mig2, "skippedExisting"));
			assertEquals(0, num(mig2, "failed"));
			assertEquals(0, num(r2, "migrated"));
			assertEquals(0, num(r2, "current"));
			assertEquals(2, num(r2, "composed") + num(r2, "hardFailures"));
			log("MEASURED: case05 forced pass composed=" + num(r2, "composed") + " hardFailures=" + num(r2, "hardFailures"));
			List<?> o2 = (List<?>) r2.get("scenes");
			for(int i = 0; i < 2; i++) {
				Map<?, ?> o = (Map<?, ?>) o2.get(i);
				BaseRecord comp = PbComposeUtil.findSceneComposition(u, bySlot[i], gid, orgId);
				assertNotNull(comp);
				assertEquals("same row: " + o, oidOf(comp), o.get("compositionObjectId"));
				comp = PbComposeUtil.readComposition(u, oidOf(comp), orgId);
				if("composed".equals(o.get("status"))) {
					assertEquals("revision bumped by the forced compose: " + o, Integer.valueOf(revision[i] + 1), comp.get(OlioFieldNames.FIELD_PB_REVISION));
					assertEquals(PbCompositionSourceEnumType.LLM.name(), enumWord(comp, OlioFieldNames.FIELD_PB_SOURCE));
				}
				else {
					assertEquals("hardFailure", o.get("status"));
					assertTrue(String.valueOf(o.get("error")).contains("existing composition left untouched"));
					assertEquals("revision unchanged: " + o, Integer.valueOf(revision[i]), comp.get(OlioFieldNames.FIELD_PB_REVISION));
				}
				assertEquals(1, countSceneCompositions(u, bySlot[i], gid, orgId));
			}
			assertMigratedCarryOver(u, s1, s2, gid, orgId, "after the forced pass");

			/// The scenes' own legacy fields are never touched by the STORY compose path.
			BaseRecord s1b = readScene(u, oidOf(s1), orgId);
			assertEquals(LEGACY_PROMPT_RAW, s1b.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertEquals(Boolean.TRUE, s1b.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals(SPARSE_OVERRIDE, s1b.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
		}
		finally {
			if(jobId != null) {
				AsyncJobRegistry.cancel(u, jobId);
				awaitTerminalQuietly(u, jobId, LOCAL_TIMEOUT_MS);
			}
			deleteBook(u, bk);
			deleteConfig(u, cfg);
		}
	}

	/// s1's migrated promptOverride (trimmed legacy), lock and configOverride are on its row whatever the source
	/// now is; s2's row carries none of them.
	private void assertMigratedCarryOver(BaseRecord u, BaseRecord s1, BaseRecord s2, long gid, long orgId, String when) {
		BaseRecord c1 = PbComposeUtil.findSceneComposition(u, s1, gid, orgId);
		assertNotNull(when + ": s1 composition", c1);
		c1 = PbComposeUtil.readComposition(u, oidOf(c1), orgId);
		assertEquals(when + ": promptOverride survives (source " + enumWord(c1, OlioFieldNames.FIELD_PB_SOURCE) + ")",
			LEGACY_PROMPT, c1.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
		assertEquals(when + ": promptLocked survives", Boolean.TRUE, c1.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		assertEquals(when + ": configOverride survives", SPARSE_OVERRIDE, c1.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
		BaseRecord c2 = PbComposeUtil.findSceneComposition(u, s2, gid, orgId);
		assertNotNull(when + ": s2 composition", c2);
		c2 = PbComposeUtil.readComposition(u, oidOf(c2), orgId);
		assertNull(when + ": s2 has no promptOverride", c2.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
		assertEquals(when + ": s2 is not locked", Boolean.FALSE, c2.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		assertNull(when + ": s2 has no configOverride", c2.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
	}

	// ─────────────────────────────── fixture: the limited user ───────────────────────────────

	/**
	 * {@code pbMigrateLimited}: Read (DATA + GROUP) on the book group, Read (DATA) on the book's world, and
	 * Update (DATA) on the BOOK RECORD itself - never on the group. Grants are applied by the organization's own
	 * admin as fixture setup; the entitlement cache is cleared so they are visible to the next evaluation.
	 */
	private BaseRecord grantedLimitedUser(BaseRecord book, long orgId) throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull(org);
		BaseRecord limited = ioContext.getFactory().getCreateUser(org.getAdminUser(), LIMITED_USER, orgId);
		assertNotNull(LIMITED_USER + " must be created", limited);
		BaseRecord olio = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal", olio);
		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord bookGroup = ioContext.getPathUtil().findPath(olio, ModelNames.MODEL_GROUP,
			PbBookUtil.bookGroupPath(slug), GroupEnumType.DATA.toString(), orgId);
		assertNotNull("book group " + PbBookUtil.bookGroupPath(slug), bookGroup);
		assertEquals(idOf(bookGroup), ((Number) book.get(FieldNames.FIELD_GROUP_ID)).longValue());
		ioContext.getAuthorizationUtil().setEntitlement(org.getAdminUser(), limited,
			new BaseRecord[] { bookGroup }, new String[] { "Read" },
			new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() });
		BaseRecord world = book.get(OlioFieldNames.FIELD_PB_WORLD);
		if(world != null) {
			ioContext.getAuthorizationUtil().setEntitlement(org.getAdminUser(), limited,
				new BaseRecord[] { world }, new String[] { "Read" }, new String[] { PermissionEnumType.DATA.toString() });
		}
		ioContext.getAuthorizationUtil().setEntitlement(org.getAdminUser(), limited,
			new BaseRecord[] { book }, new String[] { "Update" }, new String[] { PermissionEnumType.DATA.toString() });
		CacheUtil.clearCache();
		log("limited user " + limited.get(FieldNames.FIELD_NAME) + " granted Read(DATA,GROUP) on group " + idOf(bookGroup)
			+ (world != null ? ", Read(DATA) on world " + idOf(world) : " (no world)") + ", Update(DATA) on book " + idOf(book));
		return limited;
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private int countSceneCompositions(BaseRecord u, BaseRecord scene, long gid, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_SCENE, scene);
		q.field(FieldNames.FIELD_GROUP_ID, gid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_PB_PURPOSE });
		q.setCache(false);
		BaseRecord[] recs = ioContext.getAccessPoint().list(u, q).getResults();
		int n = 0;
		if(recs != null) {
			for(BaseRecord c : recs) {
				Object p = c.get(OlioFieldNames.FIELD_PB_PURPOSE);
				if(p != null && PbCompositionPurposeEnumType.SCENE.toString().equalsIgnoreCase(p.toString())) {
					n++;
				}
			}
		}
		return n;
	}

	private static int num(Map<?, ?> m, String key) {
		Object o = m.get(key);
		assertNotNull(key + " in " + m, o);
		return ((Number) o).intValue();
	}

	private AsyncJob awaitTerminalLoggingPhases(BaseRecord u, String jobId, long maxMs, String tag) throws InterruptedException {
		long start = System.currentTimeMillis();
		long deadline = start + maxMs;
		String lastPhase = null;
		AsyncJob job = null;
		while(System.currentTimeMillis() < deadline) {
			job = AsyncJobRegistry.get(u, jobId);
			assertNotNull("job " + jobId + " must stay visible to its owner", job);
			String phase = job.getProgress().getPhase();
			String key = job.getStatus() + " " + phase + " " + job.getProgress().getCurrent() + "/" + job.getProgress().getTotal();
			if(!key.equals(lastPhase)) {
				log(tag + " +" + (System.currentTimeMillis() - start) + " ms: " + key);
				lastPhase = key;
			}
			if(job.isTerminal()) {
				return job;
			}
			Thread.sleep(PHASE_POLL_MS);
		}
		fail("job " + jobId + " did not reach a terminal state within " + maxMs + "ms (last: " + lastPhase + ")");
		return null;
	}

	private void awaitTerminalQuietly(BaseRecord u, String jobId, long maxMs) {
		try {
			long deadline = System.currentTimeMillis() + maxMs;
			while(System.currentTimeMillis() < deadline) {
				AsyncJob job = AsyncJobRegistry.get(u, jobId);
				if(job == null || job.isTerminal()) {
					return;
				}
				Thread.sleep(POLL_MS);
			}
			logger.warn("cleanup: job " + jobId + " still live after " + maxMs + " ms");
		}
		catch(InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private BaseRecord liveConfig(BaseRecord u) {
		BaseRecord cfg = OlioTestUtil.getOllamaOpenAIConfig(u, CFG_NAME, testProperties);
		assertNotNull("chatConfig " + CFG_NAME, cfg);
		log("[p410-live] route=" + testProperties.getProperty("test.llm.route") + " tier=" + testProperties.getProperty("test.llm.resolvedTier")
			+ " server=" + testProperties.getProperty("test.llm.connection.server") + " model=" + (Object) cfg.get("model"));
		return cfg;
	}

	private BaseRecord readScene(BaseRecord u, String oid, long orgId) {
		BaseRecord s = PbBookUtil.readScene(u, oid, orgId);
		assertNotNull("scene " + oid, s);
		return s;
	}

	/// A scene with the fields the migration reads; nulls are left unset. Re-read as the user.
	private BaseRecord addScene(BaseRecord u, BaseRecord bk, int index, String title, String setting, String action, String mood,
			String description, String sdPrompt, Boolean promptLocked, String configOverride) throws Exception {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = PbBookUtil.createScene(u, bk, index, title, PbBookUtil.bookGroupPath(slug));
		assertNotNull("createScene " + index, scene);
		List<String> fields = new ArrayList<>();
		Map<String, Object> values = new LinkedHashMap<>();
		put(fields, values, OlioFieldNames.FIELD_PB_SETTING, setting);
		put(fields, values, OlioFieldNames.FIELD_PB_ACTION, action);
		put(fields, values, OlioFieldNames.FIELD_PB_MOOD, mood);
		put(fields, values, FieldNames.FIELD_DESCRIPTION, description);
		put(fields, values, OlioFieldNames.FIELD_CB_SD_PROMPT, sdPrompt);
		put(fields, values, OlioFieldNames.FIELD_PB_PROMPT_LOCKED, promptLocked);
		put(fields, values, OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, configOverride);
		if(!fields.isEmpty()) {
			BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, fields.toArray(new String[0]));
			for(Map.Entry<String, Object> e : values.entrySet()) {
				patch.set(e.getKey(), e.getValue());
			}
			assertNotNull("patch scene " + index, ioContext.getAccessPoint().update(u, patch));
		}
		BaseRecord fresh = PbBookUtil.readScene(u, oidOf(scene), orgOf(u));
		assertNotNull("scene " + index + " readable", fresh);
		return fresh;
	}

	private static void put(List<String> fields, Map<String, Object> values, String field, Object value) {
		if(value != null) {
			fields.add(field);
			values.put(field, value);
		}
	}

	private BaseRecord createCharPerson(BaseRecord u, String groupPath, String name, String firstName, String lastName, String gender) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, u, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set(FieldNames.FIELD_FIRST_NAME, firstName);
		if(lastName != null) {
			cp.set(FieldNames.FIELD_LAST_NAME, lastName);
		}
		cp.set(FieldNames.FIELD_GENDER, gender);
		BaseRecord created = ioContext.getAccessPoint().create(u, cp);
		assertNotNull("create charPerson " + name, created);
		cp.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
		cp.set(FieldNames.FIELD_OBJECT_ID, created.get(FieldNames.FIELD_OBJECT_ID));
		return cp;
	}

	private void enrol(BaseRecord u, BaseRecord scene, BaseRecord cp) {
		assertTrue("enrol " + cp.get(FieldNames.FIELD_NAME) + " in scene " + scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX),
			ioContext.getMemberUtil().member(u, scene, OlioFieldNames.FIELD_PB_CHARACTERS, cp, null, true));
	}

	private BaseRecord makeChapBook(BaseRecord u, BaseRecord bk) throws Exception {
		BaseRecord patch = PbGraphUtil.patchOf(bk, OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_BOOK_TYPE);
		patch.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.CHAPBOOK.toString());
		assertNotNull("bookType patch", ioContext.getAccessPoint().update(u, patch));
		BaseRecord fresh = PbBookUtil.readBook(u, oidOf(bk), orgOf(u));
		assertNotNull("re-read book", fresh);
		assertTrue("the book reads back as a ChapBook: " + enumWord(fresh, OlioFieldNames.FIELD_PB_BOOK_TYPE), PbNodeExecutor.isChapBook(fresh));
		return fresh;
	}

	private BaseRecord newBook(BaseRecord u, String tag) {
		String slug = ("p410-" + tag + "-" + shortId()).toLowerCase();
		BaseRecord bk = PbBookUtil.createBook(u, dataPath(), slug, "P4-10 " + tag + " " + slug);
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

	private void deleteConfig(BaseRecord u, BaseRecord cfg) {
		if(cfg == null) {
			return;
		}
		BaseRecord conn = cfg.get("connection");
		deleteQuietly(u, cfg);
		deleteQuietly(u, conn);
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

	private void log(String msg) {
		logger.info("[p410] " + msg);
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
}
