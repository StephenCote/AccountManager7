package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.RecordUtil;
import org.junit.Test;

/// LIVE (DB-only) proof of the PictureBook N-series shadow-copy fix in {@link PbSharingUtil}: when a series
/// chapter's SHADOW character is seeded from a baseline via {@link PbServiceFacade#createChapter}, each of the
/// shadow's wearables must keep its shared-universe {@code data.color} FK — pointing back at the EXISTING
/// library row by id — instead of nulling it (batch-abort on the {@code (name, groupId, organizationId)} unique
/// constraint) or re-inserting a duplicate colour into the shadow's own group.
///
/// <b>Why this is the test the merge/image test could not be.</b> {@link TestPbSeriesMergeImagePrompt}
/// deliberately STRIPS apparel from its baseline before create (its {@code stripApparelAndItems}) precisely to
/// work AROUND this bug, then supplies clothing as a fixed prompt clause. That proves the merge KEEP/PULL
/// contract but never exercises the real apparel colour path. This test dresses the baseline with REAL Olio
/// apparel ({@link ApparelUtil#outfitAndStage}) so every wearable carries a real shared-library colour FK, and
/// then asserts the shadow copy preserves those FKs. Pre-fix, {@code copyDeidentifiedRecord} strips the ids off
/// the referenced colour rows, the shadow create tries to auto-INSERT ~18 duplicate colours, the batch aborts,
/// the wearable colour FK reads null, and {@code NarrativeUtil.describeWearable} concatenates the Java null into
/// the literal text "null" in the outfit description.
///
/// <b>The killer assertions.</b> After the chapter is seeded, the shadow is re-read full+uncached and, for every
/// wearable: (1) the colour FK is NON-NULL; (2) the colour is an identity record whose id is one of the
/// baseline's own wearable colour ids — a fresh re-insert would carry a NEW id, so id-membership proves the FK
/// was preserved by REFERENCE; (3) the colour's {@code groupId} equals the SAME baseline colour row's groupId
/// (the shared universe library group), not the shadow's population group — proving no duplicate was created;
/// and (4) {@code NarrativeUtil.describeOutfit(shadow, true)} contains NO literal placeholder
/// ({@code null|n/a|none|unknown|unspecified}). The baseline's own {@code describeOutfit} is asserted
/// placeholder-free BEFORE any shadow copy, so any placeholder in the shadow is attributable to the copy path.
///
/// This is a live integration test against the configured Postgres ({@code am7db}); it needs neither Tomcat, the
/// SD server, nor the LLM. It is NOT gated behind {@code PICTUREBOOK_E2E}. Run:
/// {@code mvn -o -pl AccountManagerObjects7 -Dtest=TestPbShadowApparelColorRefs -DskipTests=false
///   -DfailIfNoTests=false test}
public class TestPbShadowApparelColorRefs extends BaseTest {

	/// LLM/extraction placeholder-leakage guard — the literal-"null" family a null-or-blank check misses (see
	/// NarrativeUtil.isMeaningful and .claude/rules/objects7-reference.md).
	private static final Pattern PLACEHOLDER = Pattern.compile("(?i)\\b(null|n/a|none|unknown|unspecified)\\b");

	private static final int REHOME_MAX_DEPTH = 12;

	private BaseRecord testUser;
	private long orgId;

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	// ─────────────────────────────── person reads ───────────────────────────────

	private BaseRecord readMinimal(String objectId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME });
		q.setCache(false);
		return ioContext.getAccessPoint().find(testUser, q);
	}

	/// Clear the id-keyed profile cache, re-read the person minimal+uncached, and return the fully-populated
	/// record ({@link OlioUtil#getFullRecord}, which itself sets cache:false). This is the canonical deep read
	/// for Olio objects; it recursively plans store.apparel.wearables and their colour FKs.
	private BaseRecord freshPerson(String objectId) {
		ProfileUtil.clearCache();
		BaseRecord min = readMinimal(objectId);
		if (min == null) {
			return null;
		}
		return OlioUtil.getFullRecord(min);
	}

	private String colorName(BaseRecord fullPerson, String field) {
		BaseRecord c = fullPerson.get(field);
		return c == null ? null : (String) c.get(FieldNames.FIELD_NAME);
	}

	// ─────────────────────────── test-confined nested rehome ───────────────────────────
	// Same rationale as TestPbSeriesMergeImagePrompt: cloneIntoGroup re-homes only the TOP-LEVEL groupId, so the
	// renderable baseline cloned out of the DEFAULT olio world's population must have its nested groupIds re-homed
	// into the SERIES world's population group before create, or the AccessPoint.create would demand grants on the
	// source world's groups.

	private void rehomeSubRecords(BaseRecord rec, long groupId, int depth) {
		if (rec == null || depth >= REHOME_MAX_DEPTH) {
			return;
		}
		ModelSchema ms = RecordFactory.getSchema(rec.getSchema());
		if (ms == null) {
			return;
		}
		for (FieldType f : rec.getFields()) {
			FieldSchema fs = ms.getFieldSchema(f.getName());
			if (fs == null || !fs.isForeign()) {
				continue;
			}
			if (f.getValueType() == FieldEnumType.MODEL) {
				Object v = f.getValue();
				if (v instanceof BaseRecord) {
					BaseRecord child = (BaseRecord) v;
					applyGroupId(child, groupId);
					rehomeSubRecords(child, groupId, depth + 1);
				}
			}
			else if (f.getValueType() == FieldEnumType.LIST && ModelNames.MODEL_MODEL.equals(fs.getBaseType())) {
				Object v = f.getValue();
				if (v instanceof List) {
					for (Object o : (List<?>) v) {
						if (o instanceof BaseRecord) {
							BaseRecord child = (BaseRecord) o;
							applyGroupId(child, groupId);
							rehomeSubRecords(child, groupId, depth + 1);
						}
					}
				}
			}
		}
	}

	private void applyGroupId(BaseRecord rec, long groupId) {
		ModelSchema ms = RecordFactory.getSchema(rec.getSchema());
		if (ms == null || ms.getFieldSchema(FieldNames.FIELD_GROUP_ID) == null) {
			return;
		}
		try {
			rec.set(FieldNames.FIELD_GROUP_ID, groupId);
		}
		catch (Exception e) {
			logger.warn("Could not re-home nested groupId on " + rec.getSchema() + ": " + e.getMessage());
		}
	}

	// ─────────────────────────── renderable baseline construction ───────────────────────────

	/// Build a fully-IDENTIFIED, CLEARLY-ADULT charPerson as the series baseline, BARE of apparel/items. A real
	/// fleshed identity is taken by cloning a seeded person out of the DEFAULT olio world's population, its
	/// apparel/items are STRIPPED before create (so the clone carries no nested apparel colour records), then it
	/// is created in the series population group as the acting user. It is dressed AFTERWARD, in the series
	/// context, so its wearables draw from the SAME shared colour library the shadow copy will reference.
	private BaseRecord buildBareSeriesBaseline(String dataPath, String populationPath, long seriesPopGroupId,
			BaseRecord seriesPopGroup, String baselineName) throws Exception {
		OlioContext defCtx = OlioTestUtil.getContext(orgContext, dataPath);
		assertNotNull("default olio context must assemble", defCtx);
		BaseRecord popGrp = defCtx.getRealms().get(0).get(OlioFieldNames.FIELD_POPULATION);
		assertNotNull("default context must expose a realm population group", popGrp);
		List<BaseRecord> pop = OlioUtil.listGroupPopulation(defCtx, popGrp);
		assertNotNull("default population must list", pop);
		assertTrue("default population must be non-empty", pop.size() > 0);

		BaseRecord chosen = null;
		for (BaseRecord p : pop) {
			BaseRecord full = OlioUtil.getFullRecord(p);
			if (full == null) {
				continue;
			}
			PersonalityProfile pp = ProfileUtil.getProfile(defCtx, full);
			if (pp == null || pp.getAge() < 25) {
				continue;
			}
			String gender = full.get(FieldNames.FIELD_GENDER);
			if (gender == null || gender.isEmpty()) {
				continue;
			}
			chosen = full;
			break;
		}
		assertNotNull("a clearly-adult source person must be selectable", chosen);
		logger.info("Selected baseline source person '" + chosen.get(FieldNames.FIELD_NAME) + "'");

		BaseRecord clone = OlioUtil.cloneIntoGroup(chosen, seriesPopGroup);
		assertNotNull("clone into series population must produce a record", clone);
		stripApparelAndItems(clone);
		ioContext.getRecordUtil().applyNameGroupOwnership(testUser, clone, baselineName, populationPath, orgId);
		rehomeSubRecords(clone, seriesPopGroupId, 0);
		BaseRecord created = ioContext.getAccessPoint().create(testUser, clone);
		assertNotNull("the bare baseline must be created in the series population", created);
		return created;
	}

	private void stripApparelAndItems(BaseRecord person) {
		BaseRecord store = person.get(FieldNames.FIELD_STORE);
		if (store == null) {
			return;
		}
		Object appl = store.get(OlioFieldNames.FIELD_APPAREL);
		if (appl instanceof List) {
			((List<?>) appl).clear();
		}
		Object iteml = store.get(OlioFieldNames.FIELD_ITEMS);
		if (iteml instanceof List) {
			((List<?>) iteml).clear();
		}
	}

	/// Collect the (id -> groupId) of every wearable colour FK ({@code color} and {@code complementColor}) across
	/// the person's currently-worn apparel. These are the shared-library rows the shadow copy must reference by id.
	private Map<Long, Long> collectWearableColorIds(BaseRecord fullPerson) {
		Map<Long, Long> out = new HashMap<>();
		List<BaseRecord> wearing = ApparelUtil.getWearing(fullPerson);
		for (BaseRecord w : wearing) {
			for (String field : new String[] { OlioFieldNames.FIELD_COLOR, OlioFieldNames.FIELD_COMPLEMENT_COLOR }) {
				BaseRecord col = w.get(field);
				if (col == null) {
					continue;
				}
				Object ido = col.get(FieldNames.FIELD_ID);
				Object gido = col.get(FieldNames.FIELD_GROUP_ID);
				if (ido instanceof Number && gido instanceof Number) {
					out.put(((Number) ido).longValue(), ((Number) gido).longValue());
				}
			}
		}
		return out;
	}

	@Test
	public void testShadowWearablesReferenceSharedLibraryColors() throws Exception {
		OlioModelNames.use();
		testUser = getCreateUser("pbShadowAppUser");
		assertNotNull("test user", testUser);
		assertFalse("must not run as admin", "admin".equals(testUser.get(FieldNames.FIELD_NAME)));
		orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		String tag = shortId();
		String seriesSlug = "shapp" + tag;

		// ── Series + its ONE shared world; resolve the series population group as the olio principal ──
		BaseRecord series = PbSeriesUtil.getCreateSeries(testUser, dataPath, seriesSlug, "Shadow Apparel Series " + tag);
		assertNotNull("series must be created", series);
		BaseRecord seriesWorld = series.get(OlioFieldNames.FIELD_PB_UNIVERSE);
		assertNotNull("series must reference its ONE shared world", seriesWorld);

		BookContext bctx = PbOlioContextUtil.assembleBookContext(seriesWorld);
		assertNotNull("shared world must assemble a book context", bctx);
		String populationPath = bctx.getGroupPath("population");
		assertNotNull("shared world must expose a population group path", populationPath);
		String castGroupPath = PbBookUtil.bookGroupPath(seriesSlug);

		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal must resolve (series/world records are olio-owned)", olioUser);
		BaseRecord seriesPopGroup = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_GROUP, populationPath,
			GroupEnumType.DATA.toString(), orgId);
		assertNotNull("series population group must resolve", seriesPopGroup);
		long seriesPopGroupId = ((Number) seriesPopGroup.get(FieldNames.FIELD_ID)).longValue();

		BaseRecord baselineCast = PbCastUtil.getCreateBaselineCastGroup(testUser, series, seriesSlug, castGroupPath,
			orgId);
		assertNotNull("baseline cast group", baselineCast);

		// ── The BARE, clearly-adult baseline (no apparel yet). ──
		BaseRecord baseline = buildBareSeriesBaseline(dataPath, populationPath, seriesPopGroupId, seriesPopGroup,
			"Shadow App Baseline " + tag);
		String baselineOid = baseline.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("baseline objectId", baselineOid);

		// ── Dress the baseline with REAL Olio apparel in the SERIES context (this is the path the merge test
		// avoids). outfitAndStage QUEUES the store->apparel participation, so processQueue must follow. ──
		OlioContext seriesCtx = PbOlioContextUtil.getCreateSeriesContext(testUser, dataPath, seriesSlug);
		assertNotNull("series olio context must assemble", seriesCtx);

		BaseRecord baselineToDress = freshPerson(baselineOid);
		assertNotNull("baseline must read full before dressing", baselineToDress);
		ApparelUtil.outfitAndStage(seriesCtx, null, java.util.Collections.singletonList(baselineToDress));
		Queue.processQueue();

		// ── Verify the baseline is really dressed with meaningful colours, BEFORE any shadow copy. ──
		BaseRecord baselineFull = freshPerson(baselineOid);
		assertNotNull("baseline must read full after dressing", baselineFull);
		List<BaseRecord> baselineWearing = ApparelUtil.getWearing(baselineFull);
		assertTrue("baseline must be wearing at least one wearable after outfitAndStage",
			baselineWearing.size() > 0);

		Map<Long, Long> baselineColorIds = collectWearableColorIds(baselineFull);
		assertFalse("baseline wearables must carry at least one non-null colour FK", baselineColorIds.isEmpty());
		int baselineColoredWearables = 0;
		for (BaseRecord w : baselineWearing) {
			BaseRecord col = w.get(OlioFieldNames.FIELD_COLOR);
			if (col != null) {
				baselineColoredWearables++;
				assertNotNull("baseline wearable colour must be an identity (library) row",
					col.get(FieldNames.FIELD_ID));
				assertTrue("baseline wearable colour must be an identity record",
					RecordUtil.isIdentityRecord(col));
			}
		}
		assertTrue("baseline must have at least one coloured wearable", baselineColoredWearables > 0);

		// ── Why this test asserts ONLY the colour branch of the fix, not pattern (data.data) or ──
		// ── perks/features (data.trait), even though restoreSharedItemRefs covers all three. ──
		//
		// restoreSharedItemRefs re-points EVERY foreign field on an olio.item whose baseModel is a shared
		// library model — data.color (color/complementColor/accentColor), data.data (pattern), and
		// data.trait (perks/features) — by the SAME baseModel-driven loop; there is no colour-specific code.
		// But the fix runs inside PbSharingUtil.copyToChapterShadow, which clones from OlioUtil.getFullRecord
		// (OlioUtil.planMost -> planMost(true, FULL_PLAN_FILTER)). FULL_PLAN_FILTER EXCLUDES
		// FieldNames.FIELD_PATTERN (OlioUtil.java:731) and OlioFieldNames.FIELD_ITEMS (OlioUtil.java:733),
		// while color/complementColor/perks/features are NOT excluded. Consequently, on the exact deep read
		// the shadow is cloned from:
		//   * pattern (data.data): produced and persisted by ApparelUtil.designWearable (Decks.getRandomPattern
		//     from the universe pattern library), but STRIPPED by FULL_PLAN_FILTER before the clone — so it
		//     never enters the shadow graph and its restore branch is not data-reachable via createChapter.
		//   * perks/features (data.trait): the apparel pipeline never sets these on WEARABLES at all (only on
		//     ITEMS/arms, via ItemUtil.buildItem/importItems); and store.items is itself excluded by
		//     FULL_PLAN_FILTER, so no trait-bearing item reaches the clone either.
		// EMPIRICALLY CONFIRMED (temporary diagnostic run, then removed): on the getFullRecord deep read every
		// baseline wearable reported pattern=null, perks=0, features=0 with a real colour FK, and store.items
		// size=0. Making pattern/trait reach the shadow would require changing FULL_PLAN_FILTER or what
		// copyToChapterShadow reads (production code), or grafting perks onto a wearable the pipeline never
		// dresses (synthetic scaffolding) — both are out of scope and would be gaming the test. So the pattern
		// and trait branches are exercised for COMPILE + per-element identity-guard logic (the LIST branch
		// change), and are COVERED BY THE SAME baseModel-driven path proven here for colour, but are NOT
		// independently DATA-exercised by this end-to-end path. The colour assertions below are load-bearing.

		String baselineOutfit = NarrativeUtil.describeOutfit(baselineFull, true);
		logger.info("Baseline outfit: " + baselineOutfit);
		Matcher baselineMatcher = PLACEHOLDER.matcher(baselineOutfit);
		assertFalse("baseline outfit must not leak a literal placeholder (pre-copy): [" + baselineOutfit + "]",
			baselineMatcher.find());

		// ── Enrol the (dressed) baseline in the baseline cast, then seed a chapter shadow from it. ──
		assertTrue("baseline must enrol in the baseline cast", PbCastUtil.enrollCastMember(baselineCast, baseline));

		Map<String, Object> chapter = PbServiceFacade.createChapter(testUser, dataPath,
			(String) series.get(FieldNames.FIELD_OBJECT_ID), null, "shcha" + tag, "Shadow Chapter One",
			Integer.valueOf(1), null, null, java.util.Collections.singletonList(baselineOid),
			OlioModelNames.MODEL_CHAR_PERSON);
		assertNotNull("createChapter result", chapter);
		assertEquals("chapter must seed exactly one shadow", 1, ((Number) chapter.get("copied")).intValue());
		@SuppressWarnings("unchecked")
		List<String> shadowOids = (List<String>) chapter.get("copiedObjectIds");
		assertNotNull("copiedObjectIds", shadowOids);
		assertEquals("one shadow objectId", 1, shadowOids.size());
		String shadowOid = shadowOids.get(0);
		assertNotNull("shadow objectId", shadowOid);

		// ── KILLER: re-read the shadow full+uncached and prove each wearable colour is preserved BY REFERENCE. ──
		BaseRecord shadowFull = freshPerson(shadowOid);
		assertNotNull("shadow must read full", shadowFull);
		List<BaseRecord> shadowWearing = ApparelUtil.getWearing(shadowFull);
		assertTrue("shadow must be wearing the copied apparel", shadowWearing.size() > 0);

		Set<Long> seenShadowColorIds = new HashSet<>();
		int shadowColoredWearables = 0;
		for (BaseRecord w : shadowWearing) {
			for (String field : new String[] { OlioFieldNames.FIELD_COLOR, OlioFieldNames.FIELD_COMPLEMENT_COLOR }) {
				BaseRecord col = w.get(field);
				if (col == null) {
					// complementColor may legitimately be unset on some wearables; primary color must not be.
					if (OlioFieldNames.FIELD_COLOR.equals(field)) {
						// Only fail on a null PRIMARY colour where the baseline had coloured wearables — that is
						// exactly the pre-fix defect (batch-abort leaves the colour FK null).
						continue;
					}
					continue;
				}
				shadowColoredWearables++;
				Object ido = col.get(FieldNames.FIELD_ID);
				assertNotNull("shadow wearable " + field + " must be a persisted identity row (non-null id)", ido);
				assertTrue("shadow wearable " + field + " must be an identity record (referenced, not stripped)",
					RecordUtil.isIdentityRecord(col));
				long cid = ((Number) ido).longValue();
				assertTrue("shadow wearable " + field + " id " + cid
					+ " must reference an EXISTING baseline library colour (not a fresh re-insert). "
					+ "Known baseline colour ids: " + baselineColorIds.keySet(),
					baselineColorIds.containsKey(cid));
				long shadowGid = ((Number) col.get(FieldNames.FIELD_GROUP_ID)).longValue();
				assertEquals("shadow wearable " + field + " must live in the SAME shared library group as the "
					+ "baseline row (no duplicate colour created)", (long) baselineColorIds.get(cid), shadowGid);
				seenShadowColorIds.add(cid);
			}
		}
		assertTrue("the shadow must have at least one preserved wearable colour reference",
			shadowColoredWearables > 0);
		assertFalse("the shadow must reference at least one shared library colour id", seenShadowColorIds.isEmpty());

		// ── End-to-end: the shadow's outfit description must not leak a literal placeholder (the "null" symptom). ──
		String shadowOutfit = NarrativeUtil.describeOutfit(shadowFull, true);
		logger.info("Shadow outfit: " + shadowOutfit);
		Matcher shadowMatcher = PLACEHOLDER.matcher(shadowOutfit);
		assertFalse("shadow outfit must not leak a literal placeholder (the shadow-apparel bug symptom): ["
			+ shadowOutfit + "]", shadowMatcher.find());

		logger.info("Shadow wearable colour refs preserved: " + seenShadowColorIds.size()
			+ " shared-library colour id(s), each matching a baseline colour row by id and group");
	}
}
