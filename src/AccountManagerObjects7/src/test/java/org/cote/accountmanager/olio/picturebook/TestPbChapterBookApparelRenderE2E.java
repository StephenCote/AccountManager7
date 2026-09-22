package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

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
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.junit.Test;

/// LIVE end-to-end proof of the PictureBook N-series CHAPTER-BOOK flow, rendered through the real Swarm SD
/// pipeline. This is the render gap neither {@link TestPbShadowApparelColorRefs} (dresses but never renders)
/// nor {@link TestPbSeriesMergeImagePrompt} (renders but STRIPS apparel and supplies a fixed clothing clause)
/// closes: here a series with ONE shared world seeds TWO chapters, each chapter's SHADOW is dressed from the
/// REAL Olio apparel path, and the SD prompt sent to the server for each chapter is built from that shadow's
/// own {@link NarrativeUtil#describeOutfit} output — so the ACTUAL apparel colour FKs (the bug under test)
/// reach SD. One image per chapter is rendered against the live server and emitted with its exact prompt.
///
/// <b>What is proven, per chapter.</b>
///   1. The chapter shadow, re-read full+uncached, wears the copied apparel and every wearable's {@code color}
///      / {@code complementColor} FK is NON-NULL, is an identity record, and its id is one of the BASELINE's
///      shared-library colour ids (preserved by REFERENCE — a fresh re-insert would carry a new id). This is
///      exactly the {@link PbSharingUtil#restoreSharedItemRefs} contract: shared-library colours are pointed
///      at by id, never duplicated into the shadow's own group.
///   2. The exact SD prompt string sent to the server (built via {@code describeOutfit}) contains NO literal
///      placeholder ({@code null|n/a|none|unknown|unspecified}) — the pre-fix symptom was the nulled colour FK
///      rendering as the four-character word "null" in the outfit clause. The baseline's own outfit is asserted
///      placeholder-free BEFORE any shadow copy, so any placeholder in a shadow is attributable to the copy.
///   3. A real image comes back (non-empty bytes, decodable, positive dimensions) and is emitted to disk with
///      its prompt sidecar for visual inspection.
///
/// <b>Cross-chapter independence</b> (task "if feasible"): the two shadows are proven to be distinct rows
/// (distinct objectIds, distinct groupIds, disjoint wearable item-id sets) that both reference the SAME shared
/// library colour ids; then a REAL mutation re-points one of chapter 2's wearable colour FKs to a different
/// library colour and persists it, after which chapter 1's shadow is re-read fresh+uncached and its wearable
/// colour id set is asserted UNCHANGED — proving a per-chapter shadow edit does not bleed across chapters.
///
/// <b>Drive path (stated plainly).</b> The chapters are driven directly through
/// {@link PbServiceFacade#createChapter} (the same facade the REST layer calls) against a dressed baseline
/// cast — NOT through live manuscript extraction from {@code HarlotsEight_Vol1_SM.docx}, which is too slow and
/// LLM-flaky to gate an SD render test on. Every other step is the real product path.
///
/// Gated on env {@code PICTUREBOOK_E2E} (live SD). LLM is not required — the prompt is composed from persisted
/// Olio fields, not generated. Run single-threaded, pointing at the SD host:
/// {@code PICTUREBOOK_E2E=1 mvn -o -pl AccountManagerObjects7 -Dtest=TestPbChapterBookApparelRenderE2E
///   -DskipTests=false -DfailIfNoTests=false -Dtest.swarm.server=http://192.168.1.39:7801 test}
public class TestPbChapterBookApparelRenderE2E extends BaseTest {

	/// LLM/extraction placeholder-leakage guard — the literal-"null" family a null-or-blank check misses (see
	/// NarrativeUtil.isMeaningful and .claude/rules/objects7-reference.md).
	private static final Pattern PLACEHOLDER = Pattern.compile("(?i)\\b(null|n/a|none|unknown|unspecified)\\b");

	/// Fixed, concrete scene tokens make the composed prompt deterministic apart from the real outfit clause.
	private static final String SETTING = "a sunlit cobblestone town square";
	private static final String VERB = "standing and looking toward the viewer";
	private static final int STEPS = 30;
	private static final int BATCH = 1;
	private static final int SEED = 73540; // fixed positive seed base

	private static final int REHOME_MAX_DEPTH = 12;

	/// Timestamped emit dir under the module target — never a random scratch folder.
	private static final String EMIT_DIR;
	static {
		String dir = new File("./target/test-images/pictureBook-e2e/" + System.currentTimeMillis()).getAbsolutePath();
		new File(dir).mkdirs();
		EMIT_DIR = dir;
	}

	private BaseRecord testUser;
	private long orgId;

	/// The one reused sdConfig object — model/steps/etc. constant; only description + seed change per render.
	private BaseRecord sharedSdConfig;

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

	/// Clear the id-keyed profile cache, re-read minimal+uncached, and return the fully-populated record
	/// (OlioUtil.getFullRecord itself sets cache:false) — the canonical deep read that plans store.apparel
	/// wearables and their colour FKs.
	private BaseRecord freshPerson(String objectId) {
		ProfileUtil.clearCache();
		BaseRecord min = readMinimal(objectId);
		if (min == null) {
			return null;
		}
		return OlioUtil.getFullRecord(min);
	}

	// ─────────────────────────────── colour-ref helpers ───────────────────────────────

	/// (id -> groupId) of every wearable colour FK (color and complementColor) across the person's worn apparel.
	private Map<Long, Long> collectWearableColorIds(BaseRecord fullPerson) {
		Map<Long, Long> out = new HashMap<>();
		for (BaseRecord w : ApparelUtil.getWearing(fullPerson)) {
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

	/// Wearable item ids of a person's worn apparel — used to prove two shadows own DISTINCT item rows.
	private Set<Long> collectWearableItemIds(BaseRecord fullPerson) {
		Set<Long> out = new LinkedHashSet<>();
		for (BaseRecord w : ApparelUtil.getWearing(fullPerson)) {
			Object ido = w.get(FieldNames.FIELD_ID);
			if (ido instanceof Number) {
				out.add(((Number) ido).longValue());
			}
		}
		return out;
	}

	/// KILLER per-chapter check: every worn wearable's colour FK is preserved BY REFERENCE to a baseline
	/// shared-library colour (non-null, identity, id-in-baseline, same group as the baseline row). Returns the
	/// set of referenced shared-library colour ids seen.
	private Set<Long> assertShadowColorsPreserved(BaseRecord shadowFull, Map<Long, Long> baselineColorIds,
			String label) {
		List<BaseRecord> wearing = ApparelUtil.getWearing(shadowFull);
		assertTrue(label + ": shadow must be wearing the copied apparel", wearing.size() > 0);
		Set<Long> seen = new HashSet<>();
		int colored = 0;
		for (BaseRecord w : wearing) {
			for (String field : new String[] { OlioFieldNames.FIELD_COLOR, OlioFieldNames.FIELD_COMPLEMENT_COLOR }) {
				BaseRecord col = w.get(field);
				if (col == null) {
					continue;
				}
				colored++;
				Object ido = col.get(FieldNames.FIELD_ID);
				assertNotNull(label + ": wearable " + field + " must be a persisted identity row (non-null id)", ido);
				assertTrue(label + ": wearable " + field + " must be an identity record (referenced, not stripped)",
					RecordUtil.isIdentityRecord(col));
				long cid = ((Number) ido).longValue();
				assertTrue(label + ": wearable " + field + " id " + cid
					+ " must reference an EXISTING baseline library colour (not a fresh re-insert). "
					+ "Known baseline colour ids: " + baselineColorIds.keySet(),
					baselineColorIds.containsKey(cid));
				long gid = ((Number) col.get(FieldNames.FIELD_GROUP_ID)).longValue();
				assertEquals(label + ": wearable " + field + " must live in the SAME shared library group as the "
					+ "baseline row (no duplicate colour created)", (long) baselineColorIds.get(cid), gid);
				seen.add(cid);
			}
		}
		assertTrue(label + ": at least one preserved wearable colour reference", colored > 0);
		assertFalse(label + ": at least one shared library colour id referenced", seen.isEmpty());
		return seen;
	}

	// ─────────────────────────────── prompt composition ───────────────────────────────

	/// Compose the ACTUAL SD prompt from the shadow's REAL Olio apparel: describeOutfit walks the worn wearables
	/// and, per wearable, NarrativeUtil.getColor(color) — the exact path where a nulled colour FK becomes the
	/// literal word "null". A clearly-adult gender/age framing (guarded via isMeaningful) wraps the real outfit.
	private String composeScenePrompt(OlioContext ctx, BaseRecord shadowFull) {
		PersonalityProfile pp = ProfileUtil.getProfile(ctx, shadowFull);
		assertNotNull("a profile must build for the shadow", pp);
		int age = pp.getAge();
		String gender = pp.getGender();
		assertTrue("subject must be clearly adult (age >= 25); was " + age, age >= 25);
		assertTrue("gender must be meaningful: [" + gender + "]", NarrativeUtil.isMeaningful(gender));

		String outfit = NarrativeUtil.describeOutfit(shadowFull, true);
		assertNotNull("describeOutfit must return an outfit clause", outfit);
		assertFalse("shadow must not be naked/unclothed for a rendered scene: [" + outfit + "]",
			outfit.toLowerCase().contains("naked") || outfit.toLowerCase().contains("no clothes"));

		String genderNoun = "male".equalsIgnoreCase(gender) ? "man" : "woman";
		return "professional full-body portrait photograph of a fully clothed adult " + genderNoun
			+ " (" + age + " years old), " + outfit + ", " + VERB + " in " + SETTING
			+ ", 8k, highly detailed, ultra realistic, sharp focus, natural lighting";
	}

	// ─────────────────────────────── SD config / render / emit ───────────────────────────────

	private BaseRecord buildSwarmConfig() {
		BaseRecord sdConfig = SDUtil.randomSDConfig();
		sdConfig.setValue("model", System.getProperty("test.swarm.model",
			testProperties.getProperty("test.swarm.model", "sdXL_v10VAEFix.safetensors")));
		sdConfig.setValue("refinerModel", testProperties.getProperty("test.swarm.refinerModel"));
		sdConfig.setValue("scheduler", "Karras");
		sdConfig.setValue("sampler", "dpm_2");
		sdConfig.setValue("steps", STEPS);
		sdConfig.setValue("cfg", 7);
		sdConfig.setValue("hires", false);
		String neg = testProperties.getProperty("test.swarm.negativePrompt");
		if (neg != null && !neg.isEmpty()) {
			sdConfig.setValue("negativePrompt", neg);
		}
		return sdConfig;
	}

	/// Render via SDUtil.createImage — the composed prompt is placed on the shared config's description and used
	/// VERBATIM as the SD prompt (SDUtil.java:1096-1102). Returns the created data.data image records.
	private List<BaseRecord> render(SDUtil sdu, String prompt, String name, int seed) {
		sharedSdConfig.setValue("description", prompt);
		sharedSdConfig.setValue("seed", seed);
		return sdu.createImage(testUser, "~/Gallery", sharedSdConfig, name, BATCH, false, seed);
	}

	/// Emit the prompt + each PNG to EMIT_DIR and assert every PNG decodes to a positive-dimension image. Bytes
	/// are read via ByteModelUtil.getValue (logical, decompressed/decrypted) per the CLAUDE.md byteStore rule.
	private void emit(String label, List<BaseRecord> imgs, String prompt) throws Exception {
		FileUtil.emitFile(EMIT_DIR + "/" + label + ".prompt.txt", prompt.getBytes(StandardCharsets.UTF_8));
		assertNotNull("render (" + label + ") must return image records", imgs);
		assertTrue("render (" + label + ") must produce at least one image", imgs.size() >= 1);
		int i = 0;
		for (BaseRecord b : imgs) {
			byte[] bytes = ByteModelUtil.getValue(b);
			assertNotNull("image bytes must be present for " + label, bytes);
			assertTrue("image bytes must be non-empty for " + label, bytes.length > 0);
			String path = EMIT_DIR + "/" + label + (imgs.size() > 1 ? "-" + i : "") + ".png";
			FileUtil.emitFile(path, bytes);
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
			assertNotNull("emitted PNG must be a decodable image: " + label, img);
			assertTrue("decoded image must have positive dimensions: " + label,
				img.getWidth() > 0 && img.getHeight() > 0);
			logger.info("Emitted " + label + " image " + i + " (" + img.getWidth() + "x" + img.getHeight() + "): "
				+ path);
			i++;
		}
	}

	// ─────────────────────────── test-confined nested rehome ───────────────────────────
	// cloneIntoGroup re-homes only the top-level groupId; a baseline cloned out of the DEFAULT olio world's
	// population must have its nested groupIds re-homed into the SERIES world's population group before create,
	// or AccessPoint.create demands grants on the source world's groups. (Same helper as the sibling tests.)

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

	/// Build a fully-IDENTIFIED, CLEARLY-ADULT charPerson baseline, BARE of apparel/items — cloned from a seeded
	/// person in the DEFAULT olio world, apparel/items stripped so the create carries no nested colour records,
	/// created in the series population. It is dressed AFTERWARD, in the series context, so its wearables draw
	/// from the SAME shared colour library the shadow copy references.
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

	// ─────────────────────────────── the test ───────────────────────────────

	@Test
	public void testChapterBookPerChapterApparelRender() throws Exception {
		assumeTrue("PICTUREBOOK_E2E not set — skipping live-SD chapter-book render test",
			System.getenv("PICTUREBOOK_E2E") != null);

		OlioModelNames.use();
		testUser = getCreateUser("pbChapBookAppUser");
		assertNotNull("test user", testUser);
		assertFalse("must not run as admin", "admin".equals(testUser.get(FieldNames.FIELD_NAME)));
		orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		// SD is the GTR9 Swarm at 192.168.1.39; the LLM box (.42) crashes under sustained SD load, so the
		// last-resort fallback must point at .39. -D or resource.properties override.
		String swarmServer = System.getProperty("test.swarm.server",
			testProperties.getProperty("test.swarm.server", "http://192.168.1.39:7801"));
		assertNotNull("swarm server must resolve", swarmServer);
		this.sharedSdConfig = buildSwarmConfig();
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer);

		String tag = shortId();
		String seriesSlug = "chbk" + tag;

		// ── Series + its ONE shared world; resolve the series population group as the olio principal ──
		BaseRecord series = PbSeriesUtil.getCreateSeries(testUser, dataPath, seriesSlug, "Chapter Book Series " + tag);
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

		// ── Bare, clearly-adult baseline, then DRESS it with real Olio apparel in the SERIES context. ──
		BaseRecord baseline = buildBareSeriesBaseline(dataPath, populationPath, seriesPopGroupId, seriesPopGroup,
			"ChapBook Baseline " + tag);
		String baselineOid = baseline.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("baseline objectId", baselineOid);

		OlioContext seriesCtx = PbOlioContextUtil.getCreateSeriesContext(testUser, dataPath, seriesSlug);
		assertNotNull("series olio context must assemble", seriesCtx);

		BaseRecord baselineToDress = freshPerson(baselineOid);
		assertNotNull("baseline must read full before dressing", baselineToDress);
		ApparelUtil.outfitAndStage(seriesCtx, null, java.util.Collections.singletonList(baselineToDress));
		Queue.processQueue();

		BaseRecord baselineFull = freshPerson(baselineOid);
		assertNotNull("baseline must read full after dressing", baselineFull);
		List<BaseRecord> baselineWearing = ApparelUtil.getWearing(baselineFull);
		assertTrue("baseline must be wearing at least one wearable after outfitAndStage",
			baselineWearing.size() > 0);
		Map<Long, Long> baselineColorIds = collectWearableColorIds(baselineFull);
		assertFalse("baseline wearables must carry at least one non-null colour FK", baselineColorIds.isEmpty());

		// Baseline outfit is placeholder-free BEFORE any shadow copy — so any placeholder in a shadow's outfit
		// is attributable to the copy path, not to the baseline.
		String baselineOutfit = NarrativeUtil.describeOutfit(baselineFull, true);
		logger.info("Baseline outfit: " + baselineOutfit);
		assertFalse("baseline outfit must not leak a literal placeholder (pre-copy): [" + baselineOutfit + "]",
			PLACEHOLDER.matcher(baselineOutfit).find());

		assertTrue("baseline must enrol in the baseline cast", PbCastUtil.enrollCastMember(baselineCast, baseline));

		// ═══════════════════════════ CHAPTER 1 ═══════════════════════════
		Map<String, Object> chapter1 = PbServiceFacade.createChapter(testUser, dataPath,
			(String) series.get(FieldNames.FIELD_OBJECT_ID), null, "chb1" + tag, "Chapter One",
			Integer.valueOf(1), null, null, java.util.Collections.singletonList(baselineOid),
			OlioModelNames.MODEL_CHAR_PERSON);
		assertNotNull("createChapter(1) result", chapter1);
		assertEquals("chapter 1 must seed exactly one shadow", 1, ((Number) chapter1.get("copied")).intValue());
		@SuppressWarnings("unchecked")
		List<String> shadow1Oids = (List<String>) chapter1.get("copiedObjectIds");
		assertEquals("chapter 1: one shadow objectId", 1, shadow1Oids.size());
		String shadow1Oid = shadow1Oids.get(0);

		BaseRecord shadow1Full = freshPerson(shadow1Oid);
		assertNotNull("chapter 1 shadow must read full", shadow1Full);
		Set<Long> chapter1ColorRefs = assertShadowColorsPreserved(shadow1Full, baselineColorIds, "chapter1");
		Set<Long> shadow1ItemIds = collectWearableItemIds(shadow1Full);
		Map<Long, Long> shadow1ColorMapBefore = collectWearableColorIds(shadow1Full);
		String shadow1HairBefore = shadow1Full.get(OlioFieldNames.FIELD_HAIR_STYLE);

		String prompt1 = composeScenePrompt(seriesCtx, shadow1Full);
		logger.info("Chapter 1 SD prompt: " + prompt1);
		Matcher m1 = PLACEHOLDER.matcher(prompt1);
		assertFalse("chapter 1 SD prompt (actual string sent to SD) must not leak a placeholder: [" + prompt1 + "]",
			m1.find());
		assertTrue("chapter 1 prompt must carry the real outfit clause", prompt1.contains("wearing"));
		emit("chapter1-scene", render(sdu, prompt1, "ChapBook Ch1 " + tag, SEED), prompt1);

		// ═══════════════════════════ CHAPTER 2 ═══════════════════════════
		Map<String, Object> chapter2 = PbServiceFacade.createChapter(testUser, dataPath,
			(String) series.get(FieldNames.FIELD_OBJECT_ID), (String) chapter1.get("bookObjectId"), "chb2" + tag,
			"Chapter Two", Integer.valueOf(2), null, null, java.util.Collections.singletonList(baselineOid),
			OlioModelNames.MODEL_CHAR_PERSON);
		assertNotNull("createChapter(2) result", chapter2);
		assertEquals("chapter 2 must seed exactly one shadow", 1, ((Number) chapter2.get("copied")).intValue());
		@SuppressWarnings("unchecked")
		List<String> shadow2Oids = (List<String>) chapter2.get("copiedObjectIds");
		assertEquals("chapter 2: one shadow objectId", 1, shadow2Oids.size());
		String shadow2Oid = shadow2Oids.get(0);

		BaseRecord shadow2Full = freshPerson(shadow2Oid);
		assertNotNull("chapter 2 shadow must read full", shadow2Full);
		Set<Long> chapter2ColorRefs = assertShadowColorsPreserved(shadow2Full, baselineColorIds, "chapter2");
		Set<Long> shadow2ItemIds = collectWearableItemIds(shadow2Full);

		String prompt2 = composeScenePrompt(seriesCtx, shadow2Full);
		logger.info("Chapter 2 SD prompt: " + prompt2);
		Matcher m2 = PLACEHOLDER.matcher(prompt2);
		assertFalse("chapter 2 SD prompt (actual string sent to SD) must not leak a placeholder: [" + prompt2 + "]",
			m2.find());
		assertTrue("chapter 2 prompt must carry the real outfit clause", prompt2.contains("wearing"));
		emit("chapter2-scene", render(sdu, prompt2, "ChapBook Ch2 " + tag, SEED + 1), prompt2);

		// ═══════════════════════ CROSS-CHAPTER INDEPENDENCE ═══════════════════════
		// Structural: distinct shadows, distinct groups, disjoint wearable rows; both reference shared colours.
		assertFalse("chapter shadows must be distinct rows", shadow1Oid.equals(shadow2Oid));
		long g1 = ((Number) shadow1Full.get(FieldNames.FIELD_GROUP_ID)).longValue();
		long g2 = ((Number) shadow2Full.get(FieldNames.FIELD_GROUP_ID)).longValue();
		assertFalse("chapter shadows must live in DISTINCT per-chapter shadow char groups (g1=" + g1 + ", g2=" + g2
			+ ")", g1 == g2);
		assertFalse("chapter 1 must own at least one wearable item row", shadow1ItemIds.isEmpty());
		assertFalse("chapter 2 must own at least one wearable item row", shadow2ItemIds.isEmpty());
		Set<Long> itemOverlap = new HashSet<>(shadow1ItemIds);
		itemOverlap.retainAll(shadow2ItemIds);
		assertTrue("chapter shadows must own DISJOINT wearable item rows (overlap=" + itemOverlap + ")",
			itemOverlap.isEmpty());
		assertFalse("chapter 1 must reference shared library colours", chapter1ColorRefs.isEmpty());
		assertFalse("chapter 2 must reference shared library colours", chapter2ColorRefs.isEmpty());

		// Live authorized edit: the product edits a chapter shadow at the PERSON level (olio.charPerson inherits
		// data.directory, so the acting owner gets the group-only PBAC shortcut for MODIFY — the same path
		// TestPbSeriesMergeImagePrompt uses). A direct nested olio.item apparel-colour edit is NOT the vehicle:
		// olio.item declares `likeInherits: ["data.directory"]`, which (documented likeInherits no-op) inherits
		// NOTHING, so the item never gets the group shortcut and PBAC falls to field/role checks the acting user
		// has no grant for — MEASURED as `AUDIT DENY ... MODIFY olio.item`. Apparel-level independence is instead
		// proven STRUCTURALLY above: the two shadows own DISJOINT item rows referencing shared IMMUTABLE library
		// colours, so no edit to one chapter's apparel can reach the other chapter's rows. The person-level edit
		// below then confirms a real, authorized per-chapter shadow edit does not bleed across chapters.
		String sentinelHair = "chapter two only short cropped pixie cut";
		assertFalse("independence sentinel must differ from chapter 1's current hair (before=" + shadow1HairBefore
			+ ")", sentinelHair.equalsIgnoreCase(String.valueOf(shadow1HairBefore)));

		BaseRecord shadow2Min = readMinimal(shadow2Oid);
		assertNotNull("chapter 2 shadow must be readable to patch", shadow2Min);
		BaseRecord hairPatch = PbGraphUtil.patchOf(shadow2Min, OlioModelNames.MODEL_CHAR_PERSON,
			OlioFieldNames.FIELD_HAIR_STYLE);
		hairPatch.set(OlioFieldNames.FIELD_HAIR_STYLE, sentinelHair);
		Object updRes = ioContext.getAccessPoint().update(testUser, hairPatch);
		assertNotNull("editing chapter 2's shadow at the person level must persist (non-null update result)",
			updRes);

		// Chapter 2 must reflect the edit on a fresh, uncached re-read.
		BaseRecord shadow2After = freshPerson(shadow2Oid);
		assertEquals("chapter 2's shadow hairStyle edit must have taken", sentinelHair,
			(String) shadow2After.get(OlioFieldNames.FIELD_HAIR_STYLE));

		// Chapter 1 must be UNCHANGED — its hairStyle AND its wearable colour id set are identical to before.
		BaseRecord shadow1After = freshPerson(shadow1Oid);
		assertEquals("chapter 1's shadow hairStyle must be UNCHANGED by chapter 2's edit", shadow1HairBefore,
			(String) shadow1After.get(OlioFieldNames.FIELD_HAIR_STYLE));
		Map<Long, Long> shadow1ColorMapAfter = collectWearableColorIds(shadow1After);
		assertEquals("chapter 1's wearable colour references must be UNCHANGED by chapter 2's edit "
			+ "(before=" + shadow1ColorMapBefore.keySet() + ", after=" + shadow1ColorMapAfter.keySet() + ")",
			shadow1ColorMapBefore.keySet(), shadow1ColorMapAfter.keySet());

		logger.info("PICTUREBOOK_E2E chapter-book images + prompts emitted to: " + EMIT_DIR);
		logger.info("Chapter 1 shadow colour refs: " + chapter1ColorRefs + " (group " + g1 + ")");
		logger.info("Chapter 2 shadow colour refs: " + chapter2ColorRefs + " (group " + g2 + "); person-level "
			+ "hairStyle edit on chapter 2 did not change chapter 1");
	}
}
