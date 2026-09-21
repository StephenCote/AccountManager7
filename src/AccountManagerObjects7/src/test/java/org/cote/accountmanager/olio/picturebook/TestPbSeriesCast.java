package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.junit.Test;

/// Proof #3 (binding plan §4, Q6): the series/chapters cast tiers — a canonical baseline cast, per-chapter
/// SHADOW casts, copy/isolation, and the recopy/merge/delete sync ops — over a real series' ONE shared Olio
/// world. Real DB and a live embedding server (the charPerson VectorProvider fires on every create). Runs as
/// a dedicated test user (never admin); the series Writer role has CRUD on the shared world's nested
/// population/Book groups, so the acting user drives every op. Lives in the production package because
/// {@code assembleBookContext}, {@code PbGraphUtil.patchOf}, {@code PictureBookUtil.teardownBookWorld} and the
/// private resolver methods (reached by reflection in proof f) are package-private / private.
///
/// Two backend defects that earlier revisions of this test pinned down are now FIXED, and this class asserts
/// the corrected behaviour rather than printing it:
///   - Defect 1 (read helper): {@link PbCastUtil#listCastMembers}/{@link PbCastUtil#seriesCastNames} used
///     {@code MemberUtil.getMembers}, which filters the participation {@code participantModel} column on the
///     raw {@code "olio.charPerson"} while the {@code members} field declares
///     {@code participantModel:"pb.castGroup.member"} — so it returned 0 for a populated cast group. It now
///     uses {@code MemberUtil.findMembers} (the participantModel-override-aware read). Proof (a) asserts it.
///   - Defect 2 (shadow-seed unique-constraint collision): the shared world's population group was passed as
///     the shadow target, so a name-preserving clone collided with its baseline on
///     {@code (name, groupId, organizationId)}. Shadows now land in a SIBLING {@code "Chapter Population
///     {slug}"} group (distinct groupId, names preserved). Proof (b) asserts distinct copies + isolation.
///
/// Proofs:
///   (a) {@link #testBaselineCastTier()} — one canonical baseline charPerson per character in a
///       series-scoped baseline cast; the participation table AND the {@code PbCastUtil} helpers agree it
///       holds exactly the N baselines, and {@code seriesCastNames} returns the N names.
///   (b) {@link #testChapterShadowSeedingCreatesDistinctShadowsPerChapter()} — two chapters, each seeded
///       through the production {@link PbServiceFacade#createChapter} series path, produce N DISTINCT shadow
///       copies (copy, not reference); editing one chapter's shadow touches neither the other chapter's
///       shadow nor the baseline.
///   (c) {@link #testRecopyChapterDiscardsShadowEdits()} — after editing a chapter's shadow,
///       {@link PbServiceFacade#recopyChapter} overwrites it back to baseline (edit gone, fresh objectIds),
///       and the shadow cast still holds exactly the chapter's N members.
///   (d) {@link #testMergeChapterPullsBaselineScalarKeepsForeignOverride()} — after editing a chapter's
///       shadow FOREIGN override AND changing a shared SCALAR on the baseline,
///       {@link PbServiceFacade#mergeChapter} pulls the baseline scalar into the shadow while KEEPING the
///       chapter's foreign override.
///   (e) {@link #testChapterDeleteScopedToOwnShadows()} — {@link PictureBookUtil#teardownBookWorld} of one
///       chapter drops ONLY that chapter's shadow groups; the shared baseline and the OTHER chapter's
///       shadows survive, and the shared-series-world refusal is logged (branch proven taken).
///   (f) {@link #testResolveSceneCharacterPrefersChapterShadow()} — the private shadow-first scene resolver,
///       invoked through a clearly-stated REFLECTION seam (real production code, no LLM/SD render): the
///       ordered candidate groups are [chapter shadow, series baseline], and a character shared by both
///       resolves to the CHAPTER SHADOW instance.
///   (g) {@link #testRecopyAndMergeDenyReadOnlyUserAndDoNotMutate()} — the DENY side of the recopy/merge
///       authorization gate: a user granted READ but NOT UPDATE on a chapter book (proven to reach the 403
///       branch, not requireBook's 404) is refused by {@link PbServiceFacade#recopyChapter} and
///       {@link PbServiceFacade#mergeChapter} with 403, and the chapter's shadow cast is unchanged — the
///       destructive drop/reseed never ran.
public class TestPbSeriesCast extends BaseTest {

	private static final int N = 3;

	private BaseRecord testUser;
	private long orgId;

	/// The series-world fixture shared by the proofs: the series and its ONE shared world, the cast group
	/// path, the population group path, and the N enrolled baselines (objectIds + numeric ids).
	private static final class Fixture {
		BaseRecord series;
		String tag;
		String seriesSlug;
		String castGroupPath;
		String populationPath;
		BaseRecord baselineCast;
		List<String> baselineOids;
		Set<Long> baselineIds;
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	/// Read a single charPerson by objectId as the acting user, uncached, projecting the fields these proofs
	/// assert on: identity + name (patchOf needs name), the scalar {@code hairStyle}, {@code groupId} (shadow
	/// vs baseline group), and the foreign {@code narrative} FK (merge KEEP check).
	private BaseRecord readCharPerson(String objectId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			"hairStyle", FieldNames.FIELD_GROUP_ID, "narrative" });
		q.setCache(false);
		return ioContext.getAccessPoint().find(testUser, q);
	}

	private String readHairStyle(String objectId) {
		BaseRecord cp = readCharPerson(objectId);
		assertNotNull("charPerson " + objectId + " must be readable", cp);
		return cp.get("hairStyle");
	}

	private Long readGroupId(String objectId) {
		BaseRecord cp = readCharPerson(objectId);
		assertNotNull("charPerson " + objectId + " must be readable", cp);
		return Long.valueOf(((Number) cp.get(FieldNames.FIELD_GROUP_ID)).longValue());
	}

	/// The numeric ids of the charPersons enrolled in {@code castGroup}, read via the participation table
	/// (the correct read for the participantModel-override {@code members} field). Order-preserving, de-duped.
	private Set<Long> castMemberCharPersonIds(BaseRecord castGroup) throws Exception {
		List<BaseRecord> parts = IOSystem.getActiveContext().getMemberUtil()
			.findMembers(castGroup, OlioFieldNames.FIELD_PB_MEMBERS, OlioModelNames.MODEL_CHAR_PERSON, 0L);
		Set<Long> ids = new LinkedHashSet<>();
		if (parts != null) {
			for (BaseRecord p : parts) {
				Object pid = p.get(FieldNames.FIELD_PARTICIPANT_ID);
				if (pid instanceof Number) {
					ids.add(Long.valueOf(((Number) pid).longValue()));
				}
			}
		}
		return ids;
	}

	/// The numeric ids of a list of charPersons named by objectId (each read back uncached as the user).
	private Set<Long> numericIdsOf(List<String> objectIds) {
		Set<Long> ids = new LinkedHashSet<>();
		for (String oid : objectIds) {
			BaseRecord cp = readCharPerson(oid);
			assertNotNull("copy " + oid + " must be readable", cp);
			ids.add(Long.valueOf(((Number) cp.get(FieldNames.FIELD_ID)).longValue()));
		}
		return ids;
	}

	/// Seed a chapter's shadow cast from the baseline via the production series path and assert it copied N.
	private Map<String, Object> seedChapter(Fixture fx, String slug, String title, int chapterNum) {
		String seriesOid = fx.series.get(FieldNames.FIELD_OBJECT_ID);
		Map<String, Object> r = PbServiceFacade.createChapter(testUser,
			testProperties.getProperty("test.datagen.path"), seriesOid, null, slug, title,
			Integer.valueOf(chapterNum), null, null, fx.baselineOids, OlioModelNames.MODEL_CHAR_PERSON);
		assertNotNull("createChapter(" + slug + ") result", r);
		assertEquals("chapter " + slug + " must seed exactly N shadows", N, ((Number) r.get("copied")).intValue());
		return r;
	}

	@SuppressWarnings("unchecked")
	private static List<String> shadowOids(Map<String, Object> chapterResult) {
		return (List<String>) chapterResult.get("copiedObjectIds");
	}

	/// Build the shared fixture: a fresh series (its ONE shared world), then N baseline charPersons in the
	/// world's population group, each enrolled in the series-scoped baseline cast. Asserts #3(a) along the way
	/// (the baseline cast holds EXACTLY the N created baselines, via the participation table).
	private Fixture buildFixture() throws Exception {
		OlioModelNames.use();
		testUser = getCreateUser("pbSeriesCastUser");
		assertNotNull("test user", testUser);
		orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		Fixture fx = new Fixture();
		fx.tag = shortId();
		fx.seriesSlug = "castwld" + fx.tag;
		fx.series = PbSeriesUtil.getCreateSeries(testUser, dataPath, fx.seriesSlug, "Cast Series " + fx.tag);
		assertNotNull("series must be created", fx.series);

		BaseRecord seriesWorld = fx.series.get(OlioFieldNames.FIELD_PB_UNIVERSE);
		assertNotNull("series must reference its ONE shared world", seriesWorld);
		assertNotNull("the shared world FK must carry an organizationId to assemble its context",
			seriesWorld.get(FieldNames.FIELD_ORGANIZATION_ID));

		BookContext ctx = PbOlioContextUtil.assembleBookContext(seriesWorld);
		assertNotNull("the shared world must assemble a book context", ctx);
		fx.populationPath = ctx.getGroupPath("population");
		assertNotNull("the shared world must expose a population group path", fx.populationPath);
		fx.castGroupPath = PbBookUtil.bookGroupPath(fx.seriesSlug);

		fx.baselineCast = PbCastUtil.getCreateBaselineCastGroup(testUser, fx.series, fx.seriesSlug,
			fx.castGroupPath, orgId);
		assertNotNull("baseline cast group must be created and readable by the creator", fx.baselineCast);

		fx.baselineOids = new ArrayList<>();
		fx.baselineIds = new LinkedHashSet<>();
		for (int i = 0; i < N; i++) {
			BaseRecord cp = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON);
			ioContext.getRecordUtil().applyNameGroupOwnership(testUser, cp, "Baseline Char " + i + " " + fx.tag,
				fx.populationPath, orgId);
			cp.set("hairStyle", "baselinehair");
			BaseRecord created = ioContext.getAccessPoint().create(testUser, cp);
			assertNotNull("baseline charPerson " + i + " must be created in the shared world's population", created);
			String oid = created.get(FieldNames.FIELD_OBJECT_ID);
			assertNotNull("baseline charPerson objectId", oid);
			fx.baselineOids.add(oid);
			fx.baselineIds.add(Long.valueOf(((Number) created.get(FieldNames.FIELD_ID)).longValue()));
			assertTrue("baseline charPerson " + i + " must enrol in the baseline cast",
				PbCastUtil.enrollCastMember(fx.baselineCast, created));
		}
		assertEquals("the N baselines must be N DISTINCT records", N, fx.baselineIds.size());

		// #3(a): the baseline cast (read via the participation table) holds EXACTLY the N baselines.
		Set<Long> baselineCastIds = castMemberCharPersonIds(fx.baselineCast);
		assertEquals("baseline cast must hold exactly one charPerson per character (N)", N, baselineCastIds.size());
		assertEquals("baseline cast members must be EXACTLY the N baseline charPersons",
			fx.baselineIds, baselineCastIds);
		return fx;
	}

	/// Proof #3(a): the baseline cast tier works end to end, AND the production read helpers over the same
	/// populated cast group now agree with the participation table (Defect 1 fixed).
	@Test
	public void testBaselineCastTier() throws Exception {
		Fixture fx = buildFixture();

		Set<Long> baselineCastIds = castMemberCharPersonIds(fx.baselineCast);
		assertEquals("participation table must show the N baselines", N, baselineCastIds.size());

		List<BaseRecord> viaHelper = PbCastUtil.listCastMembers(testUser, fx.baselineCast);
		assertEquals("listCastMembers must return the N baselines (Defect 1 fixed)", N, viaHelper.size());
		Set<Long> helperIds = new LinkedHashSet<>();
		for (BaseRecord m : viaHelper) {
			helperIds.add(Long.valueOf(((Number) m.get(FieldNames.FIELD_ID)).longValue()));
		}
		assertEquals("listCastMembers must resolve EXACTLY the baseline charPersons the participation table has",
			baselineCastIds, helperIds);

		List<String> roster = PbCastUtil.seriesCastNames(testUser, fx.seriesSlug, fx.castGroupPath, orgId);
		assertEquals("seriesCastNames must return the N baseline character names", N, roster.size());
		Set<String> expectedNames = new HashSet<>();
		for (int i = 0; i < N; i++) {
			expectedNames.add("Baseline Char " + i + " " + fx.tag);
		}
		assertEquals("the roster must be EXACTLY the N baseline names", expectedNames, new HashSet<>(roster));
	}

	/// Proof #3(b)+(c): each chapter's shadow cast holds N DISTINCT copies of the baselines (copy, not
	/// reference), and editing one chapter's shadow touches neither the other chapter's shadow nor the
	/// baseline (Defect 2 fixed — shadows land in a sibling group, no unique-constraint collision).
	@Test
	public void testChapterShadowSeedingCreatesDistinctShadowsPerChapter() throws Exception {
		Fixture fx = buildFixture();
		String slug1 = "castcha" + fx.tag;
		String slug2 = "castchb" + fx.tag;

		Map<String, Object> r1 = seedChapter(fx, slug1, "Cast Chapter A", 1);
		Map<String, Object> r2 = seedChapter(fx, slug2, "Cast Chapter B", 2);

		List<String> ch1Shadows = shadowOids(r1);
		List<String> ch2Shadows = shadowOids(r2);
		assertEquals("chapter 1 copiedObjectIds count", N, ch1Shadows.size());
		assertEquals("chapter 2 copiedObjectIds count", N, ch2Shadows.size());

		// COPY, not reference: baseline + both chapters' shadows are all distinct (objectId AND id).
		Set<String> allOids = new HashSet<>();
		allOids.addAll(fx.baselineOids);
		allOids.addAll(ch1Shadows);
		allOids.addAll(ch2Shadows);
		assertEquals("baseline and both chapters' shadows must ALL be distinct records (copy, not reference)",
			3 * N, allOids.size());

		Set<Long> ch1Ids = numericIdsOf(ch1Shadows);
		Set<Long> ch2Ids = numericIdsOf(ch2Shadows);
		Set<Long> allIds = new HashSet<>();
		allIds.addAll(fx.baselineIds);
		allIds.addAll(ch1Ids);
		allIds.addAll(ch2Ids);
		assertEquals("baseline + both chapters' shadows are 3N distinct charPerson rows", 3 * N, allIds.size());

		// Each chapter's shadows live in a group distinct from the baseline population group.
		Long baselineGroupId = readGroupId(fx.baselineOids.get(0));
		Long ch1GroupId = readGroupId(ch1Shadows.get(0));
		Long ch2GroupId = readGroupId(ch2Shadows.get(0));
		assertFalse("chapter 1 shadows must NOT share the baseline population group",
			baselineGroupId.equals(ch1GroupId));
		assertFalse("chapter 2 shadows must NOT share the baseline population group",
			baselineGroupId.equals(ch2GroupId));
		assertFalse("the two chapters' shadows must live in DISTINCT groups", ch1GroupId.equals(ch2GroupId));

		// Each chapter's book-scoped shadow cast holds EXACTLY its N copies; the baseline is untouched.
		BaseRecord shadowCast1 = PbCastUtil.findCastGroup(testUser, PbCastUtil.shadowCastGroupName(slug1),
			fx.castGroupPath, orgId);
		BaseRecord shadowCast2 = PbCastUtil.findCastGroup(testUser, PbCastUtil.shadowCastGroupName(slug2),
			fx.castGroupPath, orgId);
		assertNotNull("chapter 1 shadow cast group", shadowCast1);
		assertNotNull("chapter 2 shadow cast group", shadowCast2);
		assertEquals("chapter 1's shadow cast must hold EXACTLY its N copies", ch1Ids,
			castMemberCharPersonIds(shadowCast1));
		assertEquals("chapter 2's shadow cast must hold EXACTLY its N copies", ch2Ids,
			castMemberCharPersonIds(shadowCast2));
		assertEquals("baseline cast must be UNCHANGED after seeding two chapters' shadows", fx.baselineIds,
			castMemberCharPersonIds(fx.baselineCast));

		// #3(c) ISOLATION: overwrite chapter 2's shadow of character 0; chapter 1's shadow and the baseline
		// must be unchanged, and chapter 2's shadow must actually have changed.
		String baseOid = fx.baselineOids.get(0);
		String ch1ShadowOid = ch1Shadows.get(0);
		String ch2ShadowOid = ch2Shadows.get(0);
		assertEquals("baseline char 0 starts with the baseline hair", "baselinehair", readHairStyle(baseOid));
		assertEquals("chapter 1 shadow copied the baseline hair", "baselinehair", readHairStyle(ch1ShadowOid));
		assertEquals("chapter 2 shadow copied the baseline hair", "baselinehair", readHairStyle(ch2ShadowOid));

		BaseRecord ch2Shadow = readCharPerson(ch2ShadowOid);
		assertNotNull("chapter 2 shadow must be readable to patch", ch2Shadow);
		BaseRecord patch = PbGraphUtil.patchOf(ch2Shadow, OlioModelNames.MODEL_CHAR_PERSON, "hairStyle");
		patch.set("hairStyle", "ch2onlymarker");
		assertNotNull("patching chapter 2's shadow must succeed",
			ioContext.getAccessPoint().update(testUser, patch));

		assertEquals("chapter 2 shadow must now carry the marker", "ch2onlymarker", readHairStyle(ch2ShadowOid));
		assertEquals("chapter 1's shadow of the SAME character must be UNCHANGED", "baselinehair",
			readHairStyle(ch1ShadowOid));
		assertEquals("the BASELINE of the same character must be UNCHANGED", "baselinehair",
			readHairStyle(baseOid));
	}

	/// Proof #3(c) recopy: editing a chapter's shadow then {@link PbServiceFacade#recopyChapter} discards the
	/// edit and reseeds the shadow wholesale from baseline — fresh objectIds, edit gone, the old shadow row
	/// deleted, the shadow cast still holds exactly the chapter's N members, and the baseline is untouched.
	@Test
	public void testRecopyChapterDiscardsShadowEdits() throws Exception {
		Fixture fx = buildFixture();
		String slug = "castrec" + fx.tag;

		Map<String, Object> r = seedChapter(fx, slug, "Recopy Chapter", 1);
		String bookOid = (String) r.get("bookObjectId");
		assertNotNull("chapter bookObjectId", bookOid);
		List<String> shadows = shadowOids(r);
		assertEquals("chapter seeded N shadows", N, shadows.size());

		// Edit shadow char 0.
		String editedOid = shadows.get(0);
		BaseRecord shadow0 = readCharPerson(editedOid);
		assertNotNull("shadow 0 must be readable to edit", shadow0);
		BaseRecord patch = PbGraphUtil.patchOf(shadow0, OlioModelNames.MODEL_CHAR_PERSON, "hairStyle");
		patch.set("hairStyle", "chapteredit");
		assertNotNull("editing the shadow must succeed", ioContext.getAccessPoint().update(testUser, patch));
		assertEquals("the shadow edit must be readable before recopy", "chapteredit", readHairStyle(editedOid));

		// Recopy discards the chapter's edits and reseeds from baseline.
		Map<String, Object> rr = PbServiceFacade.recopyChapter(testUser, bookOid);
		assertNotNull("recopy result", rr);
		assertEquals("recopy must reseed exactly the chapter's N members", N,
			((Number) rr.get("recopied")).intValue());
		List<String> recopied = shadowOids2(rr);
		assertEquals("recopiedObjectIds count", N, recopied.size());

		// Fresh clones: none of the recopied objectIds is a pre-recopy shadow, and the old edited row is gone.
		Set<String> before = new HashSet<>(shadows);
		for (String oid : recopied) {
			assertFalse("recopy must create FRESH shadow rows, not reuse old ones", before.contains(oid));
		}
		assertNull("the pre-recopy (edited) shadow row must be deleted by recopy", readCharPerson(editedOid));

		// The edit is gone: every reseeded shadow carries the baseline hair again.
		for (String oid : recopied) {
			assertEquals("recopy must overwrite chapter edits back to baseline", "baselinehair", readHairStyle(oid));
		}

		// The shadow cast holds EXACTLY the N reseeded members; the baseline is untouched.
		BaseRecord shadowCast = PbCastUtil.findCastGroup(testUser, PbCastUtil.shadowCastGroupName(slug),
			fx.castGroupPath, orgId);
		assertNotNull("shadow cast group after recopy", shadowCast);
		assertEquals("shadow cast must hold exactly the N reseeded shadows", numericIdsOf(recopied),
			castMemberCharPersonIds(shadowCast));
		assertEquals("baseline cast must be UNCHANGED by recopy", fx.baselineIds,
			castMemberCharPersonIds(fx.baselineCast));
		for (int i = 0; i < N; i++) {
			assertEquals("baseline character " + i + " must be untouched by recopy", "baselinehair",
				readHairStyle(fx.baselineOids.get(i)));
		}
	}

	@SuppressWarnings("unchecked")
	private static List<String> shadowOids2(Map<String, Object> recopyResult) {
		return (List<String>) recopyResult.get("recopiedObjectIds");
	}

	/// Proof #3(d) merge: edit a chapter shadow's FOREIGN override (attach a narrative FK), change a shared
	/// SCALAR on the baseline (hairStyle), then {@link PbServiceFacade#mergeChapter}. The baseline scalar is
	/// pulled into the shadow, while the chapter's foreign override is KEPT.
	@Test
	public void testMergeChapterPullsBaselineScalarKeepsForeignOverride() throws Exception {
		Fixture fx = buildFixture();
		String slug = "castmrg" + fx.tag;

		Map<String, Object> r = seedChapter(fx, slug, "Merge Chapter", 1);
		String bookOid = (String) r.get("bookObjectId");
		assertNotNull("chapter bookObjectId", bookOid);
		List<String> shadows = shadowOids(r);
		String shadow0Oid = shadows.get(0);
		String base0Oid = fx.baselineOids.get(0);

		// Chapter FOREIGN override: create a narrative in the chapter's own shadow group and attach it to the
		// shadow's narrative FK. narrative is a foreign model field on charPerson (baseModel olio.narrative).
		String shadowCharGroupPath = PbBookUtil.chapterShadowCharGroupPath(fx.populationPath, slug);
		assertNotNull("chapter shadow char group path", shadowCharGroupPath);
		BaseRecord nar = RecordFactory.newInstance(OlioModelNames.MODEL_NARRATIVE);
		ioContext.getRecordUtil().applyNameGroupOwnership(testUser, nar, "override-nar-" + fx.tag,
			shadowCharGroupPath, orgId);
		nar.set("sdPrompt", "chapter-override-prompt");
		BaseRecord createdNar = ioContext.getAccessPoint().create(testUser, nar);
		assertNotNull("the chapter's override narrative must be creatable by the acting user", createdNar);
		Long narId = Long.valueOf(((Number) createdNar.get(FieldNames.FIELD_ID)).longValue());

		BaseRecord shadow0 = readCharPerson(shadow0Oid);
		assertNotNull("shadow 0 must be readable to patch its narrative FK", shadow0);
		BaseRecord narPatch = PbGraphUtil.patchOf(shadow0, OlioModelNames.MODEL_CHAR_PERSON, "narrative");
		narPatch.set("narrative", createdNar);
		assertNotNull("attaching the shadow's narrative override must succeed",
			ioContext.getAccessPoint().update(testUser, narPatch));

		BaseRecord shadowAfterOverride = readCharPerson(shadow0Oid);
		BaseRecord narFk = shadowAfterOverride.get("narrative");
		assertNotNull("the shadow must carry a narrative FK after the override patch", narFk);
		assertEquals("the shadow's narrative FK must point at the chapter override narrative", narId,
			Long.valueOf(((Number) narFk.get(FieldNames.FIELD_ID)).longValue()));

		// Change a shared SCALAR on the baseline. The shadow must NOT change yet.
		BaseRecord base0 = readCharPerson(base0Oid);
		assertNotNull("baseline 0 must be readable to edit", base0);
		BaseRecord basePatch = PbGraphUtil.patchOf(base0, OlioModelNames.MODEL_CHAR_PERSON, "hairStyle");
		basePatch.set("hairStyle", "mergedbaselinehair");
		assertNotNull("editing the baseline scalar must succeed", ioContext.getAccessPoint().update(testUser, basePatch));
		assertEquals("baseline scalar must be updated", "mergedbaselinehair", readHairStyle(base0Oid));
		assertEquals("the shadow scalar must be UNCHANGED before merge", "baselinehair", readHairStyle(shadow0Oid));

		// Merge: pull baseline scalars into the shadows, keeping foreign overrides.
		Map<String, Object> mr = PbServiceFacade.mergeChapter(testUser, bookOid);
		assertNotNull("merge result", mr);
		assertTrue("merge must report at least one shadow merged", ((Number) mr.get("merged")).intValue() >= 1);

		BaseRecord shadowAfterMerge = readCharPerson(shadow0Oid);
		assertNotNull("shadow 0 must survive the merge", shadowAfterMerge);
		assertEquals("merge must PULL the baseline scalar into the shadow", "mergedbaselinehair",
			(String) shadowAfterMerge.get("hairStyle"));
		BaseRecord narFkAfter = shadowAfterMerge.get("narrative");
		assertNotNull("merge must KEEP the chapter's foreign (narrative) override", narFkAfter);
		assertEquals("the KEPT narrative override must be the SAME record, not the baseline's", narId,
			Long.valueOf(((Number) narFkAfter.get(FieldNames.FIELD_ID)).longValue()));
	}

	/// Proof #3(g) — the DENY side of the recopy/merge authorization gate ({@link PbServiceFacade#recopyChapter},
	/// gate at PbServiceFacade.java:607-610; {@link PbServiceFacade#mergeChapter}, gate at :709-712). The PERMIT
	/// (owner/entitled) path of both is already covered by proofs #3(c)/#3(d); this closes the coverage gap the
	/// verifier and architect flagged: the DENY path.
	///
	/// <b>Why a total-stranger test would NOT close it.</b> {@code requireBook} (PbServiceFacade.java:104-117)
	/// reads the book with {@code PbBookUtil.readBook} and 404s at :114 a book the caller cannot read — BEFORE
	/// the {@code canUpdate} 403 branch is reached. A user with NO access is therefore denied at 404 whether or
	/// not the {@code canUpdate} gate exists, so a stranger test passes even if a refactor deletes the gate. The
	/// regression to catch is exactly that refactor: "anyone who can READ the book can recopy/merge it." So the
	/// SUBJECT is a user granted READ but NOT UPDATE on the chapter book — the {@code canUpdate} gate is then the
	/// ONLY thing standing between them and the destructive {@code dropChapterShadowGroups} reseed.
	///
	/// The setup is PROVEN to reach the 403 branch (not the 404 one): {@code readBook(subject,...)} returns
	/// non-null AND {@code canUpdate(subject, subject, book)} is not PERMIT before the denied calls. The op is
	/// PROVEN non-destructive: the chapter's shadow cast group (same id) and its exact member set are unchanged
	/// after the denied recopy, read fresh/uncached as an ENTITLED reader (the owner) per the nested-FK
	/// cache-staleness rule, and every original shadow charPerson still exists.
	///
	/// Never runs as admin as the SUBJECT: the org admin is used ONLY to apply the Read grant (the sole admin
	/// use, mirroring production's {@code grantSeriesRolesOnChapterGroups}); the acting subject is a dedicated
	/// non-admin user.
	@Test
	public void testRecopyAndMergeDenyReadOnlyUserAndDoNotMutate() throws Exception {
		Fixture fx = buildFixture();
		String slug = "castden" + fx.tag;

		Map<String, Object> r = seedChapter(fx, slug, "Deny Chapter", 1);
		String bookOid = (String) r.get("bookObjectId");
		assertNotNull("chapter bookObjectId", bookOid);
		List<String> shadows = shadowOids(r);
		assertEquals("chapter seeded N shadows", N, shadows.size());

		/// The subject: a fresh user granted READ but NOT UPDATE on the chapter's Book group. Fresh users are
		/// auto-enrolled ONLY in AccountUsers, which holds nothing on this private chapter group, so the ONLY
		/// access this user has to the book is the Read grant applied below.
		BaseRecord readOnly = getCreateUser("pbSeriesCastReadOnly");
		assertNotNull("read-only subject user", readOnly);
		assertFalse("subject must be a DIFFERENT user from the owner",
			((Long) testUser.get(FieldNames.FIELD_ID)).equals((Long) readOnly.get(FieldNames.FIELD_ID)));

		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal", olioUser);
		BaseRecord bookGroup = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_GROUP,
			PbBookUtil.bookGroupPath(slug), GroupEnumType.DATA.toString(), orgId);
		assertNotNull("chapter Book group must be resolvable to grant READ on", bookGroup);

		/// Grant READ ONLY — never Update/Create/Delete. setEntitlement makes the subject a direct member of the
		/// book group with the Read permission (checkEntitlement matches a direct user grant, not only a role).
		/// The org admin performs the grant; it is NOT the acting subject.
		ioContext.getAuthorizationUtil().setEntitlement(orgContext.getAdminUser(), readOnly,
			new BaseRecord[] { bookGroup }, new String[] { "Read" },
			new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() });

		/// PROVE the setup reaches the 403 branch, not the 404 one: the subject CAN read the book but CANNOT
		/// update it. If this user could not read the book, requireBook would 404 and the canUpdate gate would
		/// never be exercised — the test would be worthless.
		BaseRecord asSubject = PbBookUtil.readBook(readOnly, bookOid, orgId);
		assertNotNull("subject MUST be able to READ the chapter book (else recopy 404s before the canUpdate gate)",
			asSubject);
		PolicyResponseType canUpd = ioContext.getAuthorizationUtil().canUpdate(readOnly, readOnly, asSubject);
		assertFalse("subject MUST NOT be able to UPDATE the chapter book (else the canUpdate gate is moot)",
			canUpd != null && canUpd.getType() == PolicyResponseEnumType.PERMIT);

		/// Capture the chapter's shadow cast + members as an ENTITLED reader (the owner), fresh/uncached.
		BaseRecord shadowCastBefore = PbCastUtil.findCastGroup(testUser, PbCastUtil.shadowCastGroupName(slug),
			fx.castGroupPath, orgId);
		assertNotNull("shadow cast must exist before the denied recopy", shadowCastBefore);
		Long shadowCastIdBefore = Long.valueOf(((Number) shadowCastBefore.get(FieldNames.FIELD_ID)).longValue());
		Set<Long> membersBefore = castMemberCharPersonIds(shadowCastBefore);
		assertEquals("shadow cast holds its N members before the denied recopy", N, membersBefore.size());

		// ── recopy DENY: the destructive op must be refused with 403 ──
		int recopyStatus = -1;
		String recopyMessage = null;
		try {
			PbServiceFacade.recopyChapter(readOnly, bookOid);
			fail("recopyChapter as a read-but-not-update user must throw PictureBookException(403)");
		}
		catch(PictureBookException e) {
			recopyStatus = e.getStatus();
			recopyMessage = e.getMessage();
		}
		assertEquals("recopyChapter must DENY a read-but-not-update user with 403 (message=" + recopyMessage + ")",
			403, recopyStatus);

		// ── merge DENY: the sibling gate must be refused with 403 too ──
		int mergeStatus = -1;
		String mergeMessage = null;
		try {
			PbServiceFacade.mergeChapter(readOnly, bookOid);
			fail("mergeChapter as a read-but-not-update user must throw PictureBookException(403)");
		}
		catch(PictureBookException e) {
			mergeStatus = e.getStatus();
			mergeMessage = e.getMessage();
		}
		assertEquals("mergeChapter must DENY a read-but-not-update user with 403 (message=" + mergeMessage + ")",
			403, mergeStatus);

		// ── the destructive side effect must NOT have occurred ──
		BaseRecord shadowCastAfter = PbCastUtil.findCastGroup(testUser, PbCastUtil.shadowCastGroupName(slug),
			fx.castGroupPath, orgId);
		assertNotNull("the shadow cast group must SURVIVE a denied recopy (nothing dropped)", shadowCastAfter);
		assertEquals("shadow cast id must be UNCHANGED (recopy must not have dropped+recreated it)",
			shadowCastIdBefore, Long.valueOf(((Number) shadowCastAfter.get(FieldNames.FIELD_ID)).longValue()));
		assertEquals("shadow cast members must be IDENTICAL after a denied recopy (no drop/reseed)",
			membersBefore, castMemberCharPersonIds(shadowCastAfter));
		for(String oid : shadows) {
			assertNotNull("each original shadow charPerson " + oid + " must still exist after a denied recopy",
				readCharPerson(oid));
		}

		// The shared baseline is untouched too.
		assertEquals("baseline cast must be UNCHANGED by a denied recopy/merge", fx.baselineIds,
			castMemberCharPersonIds(fx.baselineCast));
		for(int i = 0; i < N; i++) {
			assertEquals("baseline character " + i + " must be untouched by a denied recopy/merge", "baselinehair",
				readHairStyle(fx.baselineOids.get(i)));
		}
	}

	/// Proof #3(e): {@link PictureBookUtil#teardownBookWorld} of ONE chapter drops only that chapter's shadow
	/// groups (char group + shadow cast). The shared baseline and the OTHER chapter's shadows survive, and the
	/// shared-series-world refusal is logged so the branch is proven taken (not merely inferred from
	/// survival). Invoked as the acting user (series Writer → canDelete PERMIT on its own chapter groups);
	/// the physical deletes run internally as the olio principal.
	@Test
	public void testChapterDeleteScopedToOwnShadows() throws Exception {
		Fixture fx = buildFixture();
		String slug1 = "castdela" + fx.tag;
		String slug2 = "castdelb" + fx.tag;

		Map<String, Object> r1 = seedChapter(fx, slug1, "Delete Chapter A", 1);
		Map<String, Object> r2 = seedChapter(fx, slug2, "Delete Chapter B", 2);
		List<String> ch1Shadows = shadowOids(r1);
		List<String> ch2Shadows = shadowOids(r2);
		Set<Long> ch2Ids = numericIdsOf(ch2Shadows);

		BaseRecord shadowCast2Before = PbCastUtil.findCastGroup(testUser, PbCastUtil.shadowCastGroupName(slug2),
			fx.castGroupPath, orgId);
		assertNotNull("chapter 2 shadow cast must exist before the delete", shadowCast2Before);
		assertEquals("chapter 2 shadow cast holds its N members before the delete", ch2Ids,
			castMemberCharPersonIds(shadowCast2Before));

		BaseRecord book1 = PbBookUtil.findBookBySlug(testUser, slug1, orgId);
		assertNotNull("chapter 1 book must be resolvable for teardown", book1);

		CountingAppender app = new CountingAppender("seriesCastDelete");
		DeleteResult result = runWithAppenderOn(PictureBookUtil.class, app,
			() -> PictureBookUtil.teardownBookWorld(testUser, book1, orgId));

		assertNotNull("teardown returned a result", result);
		assertTrue("scoped teardown of a series chapter must succeed (deleted=true), reason=" + result.reason,
			result.deleted);
		boolean refused = app.messages.stream().anyMatch(m -> m != null
			&& m.contains("REFUSING to delete the shared series world"));
		assertTrue("teardown must log the shared-series-world refusal (branch proven taken)", refused);

		// Chapter 1's shadow cast group and shadow charPersons are GONE.
		assertNull("chapter 1's shadow cast group must be dropped",
			PbCastUtil.findCastGroup(testUser, PbCastUtil.shadowCastGroupName(slug1), fx.castGroupPath, orgId));
		for (String oid : ch1Shadows) {
			assertNull("chapter 1 shadow charPerson " + oid + " must be deleted", readCharPerson(oid));
		}

		// The shared baseline is UNCHANGED.
		assertEquals("baseline cast must be UNCHANGED by a chapter delete", fx.baselineIds,
			castMemberCharPersonIds(fx.baselineCast));
		for (int i = 0; i < N; i++) {
			assertEquals("baseline character " + i + " must survive the chapter delete", "baselinehair",
				readHairStyle(fx.baselineOids.get(i)));
		}

		// The OTHER chapter's shadows are UNCHANGED.
		BaseRecord shadowCast2After = PbCastUtil.findCastGroup(testUser, PbCastUtil.shadowCastGroupName(slug2),
			fx.castGroupPath, orgId);
		assertNotNull("chapter 2's shadow cast must survive the chapter-1 delete", shadowCast2After);
		assertEquals("chapter 2's shadow cast must be UNCHANGED", ch2Ids,
			castMemberCharPersonIds(shadowCast2After));
		for (String oid : ch2Shadows) {
			assertNotNull("chapter 2 shadow charPerson " + oid + " must survive the chapter-1 delete",
				readCharPerson(oid));
		}
	}

	/// Proof #3(f) — shadow-first scene resolver, via a REFLECTION seam. {@code findSceneCharacterGroups} and
	/// {@code resolveSceneCharacter} are private static in {@link PictureBookUtil}; the only in-process entry
	/// short of a full LLM/SD scene render is reflection, which invokes the REAL production methods (this is a
	/// clearly-stated seam, not a stub). Asserts (1) the ordered candidate groups for a series chapter are
	/// [chapter shadow group, series baseline population], and (2) a character shared by both baseline and
	/// shadow resolves to the CHAPTER SHADOW instance (shadow wins), with the baseline as the fallback tier.
	@Test
	public void testResolveSceneCharacterPrefersChapterShadow() throws Exception {
		Fixture fx = buildFixture();
		String slug = "castres" + fx.tag;

		Map<String, Object> r = seedChapter(fx, slug, "Resolver Chapter", 1);
		List<String> shadows = shadowOids(r);
		String shadow0Oid = shadows.get(0);
		String base0Oid = fx.baselineOids.get(0);

		BaseRecord base0 = readCharPerson(base0Oid);
		String char0Name = base0.get(FieldNames.FIELD_NAME);
		assertNotNull("baseline char 0 name", char0Name);
		Long shadow0Id = Long.valueOf(((Number) readCharPerson(shadow0Oid).get(FieldNames.FIELD_ID)).longValue());
		Long base0Id = Long.valueOf(((Number) base0.get(FieldNames.FIELD_ID)).longValue());
		Long shadowGroupId = readGroupId(shadow0Oid);
		Long baselineGroupId = readGroupId(base0Oid);

		// (1) Ordered candidate groups: [chapter shadow, series baseline population].
		Method findGroups = PictureBookUtil.class.getDeclaredMethod("findSceneCharacterGroups",
			BaseRecord.class, String.class, String.class);
		findGroups.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<BaseRecord> groups = (List<BaseRecord>) findGroups.invoke(null, testUser, null, slug);
		assertNotNull("resolver must return candidate groups", groups);
		assertEquals("a series chapter must resolve TWO ordered candidate groups (shadow, then baseline)",
			2, groups.size());
		assertEquals("candidate group #0 must be the CHAPTER SHADOW group", shadowGroupId,
			Long.valueOf(((Number) groups.get(0).get(FieldNames.FIELD_ID)).longValue()));
		assertEquals("candidate group #1 must be the SERIES BASELINE population group", baselineGroupId,
			Long.valueOf(((Number) groups.get(1).get(FieldNames.FIELD_ID)).longValue()));

		// (2) A character shared by both tiers resolves to the CHAPTER SHADOW instance.
		Map<String, Object> charItem = new HashMap<>();
		charItem.put("name", char0Name);
		Method resolve = PictureBookUtil.class.getDeclaredMethod("resolveSceneCharacter",
			BaseRecord.class, Object.class, String.class, String.class);
		resolve.setAccessible(true);
		Object resolved = resolve.invoke(null, testUser, charItem, null, slug);
		assertNotNull("the resolver must resolve the shared character", resolved);
		Field cpField = resolved.getClass().getDeclaredField("charPerson");
		cpField.setAccessible(true);
		BaseRecord resolvedCp = (BaseRecord) cpField.get(resolved);
		assertNotNull("the resolved ResolvedCharacter must carry a charPerson", resolvedCp);
		Long resolvedId = Long.valueOf(((Number) resolvedCp.get(FieldNames.FIELD_ID)).longValue());
		assertEquals("the resolver must return the CHAPTER SHADOW instance (shadow wins)", shadow0Id, resolvedId);
		assertFalse("the resolver must NOT return the baseline when a chapter shadow exists",
			base0Id.equals(resolvedId));
	}

	// ─────────────────────────────── log capture ───────────────────────────────

	private static final class CountingAppender extends AbstractAppender {
		final List<String> messages = Collections.synchronizedList(new ArrayList<String>());

		CountingAppender(String name) {
			super(name, null, null, true, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(LogEvent event) {
			messages.add(event.getMessage().getFormattedMessage());
		}
	}

	private interface ThrowingSupplier<T> {
		T get() throws Exception;
	}

	private static <T> T runWithAppenderOn(Class<?> loggerOwner, CountingAppender app, ThrowingSupplier<T> body)
			throws Exception {
		org.apache.logging.log4j.core.Logger target =
			(org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerOwner);
		app.start();
		target.addAppender(app);
		try {
			return body.get();
		} finally {
			target.removeAppender(app);
			app.stop();
		}
	}
}
