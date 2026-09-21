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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
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
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.FileUtil;
import org.junit.Test;

/// Image gap (PictureBook N-series, plan §4): the LIVE end-to-end proof that a series chapter's SHADOW
/// character, after {@link PbServiceFacade#mergeChapter}, renders with the baseline-PULLED scalar
/// ({@code hairStyle}) while KEEPING its chapter FOREIGN override ({@code eyeColor}) — and that the
/// ordinary (non-merged) baseline render is unregressed.
///
/// This is NOT a persistence/decode-only test. It generates REAL images against the live Swarm SD server
/// for the merged character BEFORE and AFTER the merge, plus a non-merged baseline regression render, and
/// emits every PNG + its prompt sidecar to {@code ./target/test-images/pictureBook-e2e/<ts>} so the images
/// can be inspected by eye — a passing decode assertion is not, by itself, proof the picture is right.
///
/// <b>⚠ Divergence from the plan's original wording, and why.</b> The plan sketched the kept foreign
/// override as "apparel via {@code NarrativeUtil.describeOutfit}". That mechanism is NOT usable here and is
/// NOT how production PictureBook clothes characters:
///   1. {@code ApparelUtil.outfitAndStage} auto-creates the ~18 nested {@code data.color} records of a
///      generated outfit into the SERIES world's SHARED, PRE-SEEDED colour library group. The
///      {@code (name, groupId, organizationId)} unique index rejects every duplicate ("Tea Green already
///      exists"), the whole colour batch fails (`Failed to auto create: data.color 18. Only created: 0`),
///      the wearable colour FKs read null, and {@code describeWearable} concatenates the Java null into the
///      literal text "null" — MEASURED, and out of the N-series' scope to fix in core Olio (flagged).
///   2. Production PictureBook does not dress characters from Olio wearables at all; it renders via
///      {@code PictureBookUtil.generateSceneImage} using LLM-extracted {@code narrative.sdPrompt} text
///      (see {@code TestPictureBookUtilE2E}). So the "describeOutfit" clothing path is a test artefact, not
///      a shipped behaviour.
///
/// The KEEP contract itself is unchanged and is proven MORE directly: {@code eyeColor} is a foreign
/// {@code data.color} MODEL FK, and {@link PbSharingUtil#mergeChapterShadows} (via {@code scalarPullFields},
/// which excludes {@code isForeign()}) pulls ONLY scalar column-backed fields and KEEPS every foreign
/// field. So a chapter-specific {@code eyeColor} override on the shadow must survive the merge while the
/// baseline's {@code hairStyle} scalar is pulled in. The override references an EXISTING series colour-
/// library entry by FK id (no colour record is created), so there is nothing to collide with. Clothing is
/// supplied by a fixed deterministic clause in the composed prompt, so the rendered subject is a clothed
/// adult without touching Olio apparel.
///
/// <b>Why the prompt is a sound assertion vehicle.</b> The render path is {@link SDUtil#createImage}, which
/// (SDUtil.java:1096-1102) uses {@code sdConfig.get("description")} VERBATIM as the SD prompt when set. This
/// test composes that description from the merged record's OWN freshly-re-read persisted fields — the
/// scalar {@code hairStyle} and the {@code eyeColor} FK's {@code name} — after guarding each with
/// {@link NarrativeUtil#isMeaningful} (the literal-"null"/"n/a"/"none" family a blank check misses). If the
/// merge failed to pull {@code hairStyle}, the re-read record would still hold H1 and the post-merge
/// "contains H2" assertion would fail; if the merge wrongly pulled {@code eyeColor}, the re-read FK name
/// would revert to the baseline's and the "keeps the override colour" assertion would fail. The image the
/// server returns is generated from the exact same description string this test asserts on and emits.
///
/// <b>Cache discipline.</b> {@link ProfileUtil#getProfile} caches the {@link PersonalityProfile} by numeric
/// id; the shadow keeps its id across a merge, so every capture clears that cache and re-reads the person
/// full+uncached ({@link OlioUtil#getFullRecord} sets {@code cache:false}) before composing — otherwise the
/// post-merge read could be the stale pre-merge graph.
///
/// <b>The hairStyle tokens are real, visually-distinct STYLE phrases with NO colour words</b> ({@link #H1}
/// "long flowing waves" vs {@link #H2} "short cropped pixie cut") so (a) the two renders show a visible hair
/// difference to the eye, and (b) each phrase is distinctive enough that substring present/absent AND the
/// equality-modulo-hair check {@code post.replace(H2,H1).equals(pre)} are robust.
///
/// Age: NO production age change (plan). This is a TEST-CONTENT caution only — the exercised character is
/// selected to be CLEARLY adult ({@code age >= 25}) and the fixed clothing clause keeps the render clothed,
/// so nothing is staged as a child.
///
/// Gated on env {@code PICTUREBOOK_E2E} (live SD). Run single-threaded, pointing at the SD host:
/// {@code PICTUREBOOK_E2E=1 mvn -o -pl AccountManagerObjects7 -Dtest=TestPbSeriesMergeImagePrompt
///   -DskipTests=false -DfailIfNoTests=false -Dtest.swarm.server=http://192.168.1.39:7801 test}
public class TestPbSeriesMergeImagePrompt extends BaseTest {

	/// Real, visually-distinct hair STYLE phrases (no colour words — colour is a separate prompt slot).
	private static final String H1 = "long flowing waves";
	private static final String H2 = "short cropped pixie cut";

	/// Fixed, concrete scene tokens make the composed prompt deterministic (only the hair phrase varies
	/// pre → post) and the render clothed.
	private static final String SETTING = "a sunlit garden courtyard";
	private static final String VERB = "walking";
	private static final String CLOTHING = "wearing a tailored charcoal three-piece business suit and polished dress shoes";
	private static final int STEPS = 30;
	private static final int BATCH = 1;
	private static final int SEED = 73540; // fixed positive seed: pre/post images differ only by hair

	/// LLM/extraction placeholder leakage guard — the literal-"null" family that a null-or-blank check misses
	/// (see NarrativeUtil.isMeaningful and .claude/rules/objects7-reference.md).
	private static final Pattern PLACEHOLDER = Pattern.compile("(?i)\\b(null|n/a|none|unknown|unspecified)\\b");

	/// Real EMIT_DIR convention (matches TestPictureBookUtilE2E) — a timestamped dir under the module target,
	/// never a random scratch folder.
	private static final String EMIT_DIR;
	static {
		String dir = new File("./target/test-images/pictureBook-e2e/" + System.currentTimeMillis()).getAbsolutePath();
		new File(dir).mkdirs();
		EMIT_DIR = dir;
	}

	private static final int REHOME_MAX_DEPTH = 12;

	private BaseRecord testUser;
	private long orgId;

	// The one reused sdConfig object — same object (model/steps/etc.) for every render; only the
	// "description" (the composed prompt) and seed change per render.
	private BaseRecord sharedSdConfig;

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	// ─────────────────────────────── person reads ───────────────────────────────

	/// Fresh, uncached scalar read of hairStyle by objectId. Used for the plain scalar assertions
	/// (pulled H1→H2), where a full graph isn't needed.
	private String readHairStyle(String objectId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			OlioFieldNames.FIELD_HAIR_STYLE });
		q.setCache(false);
		BaseRecord cp = ioContext.getAccessPoint().find(testUser, q);
		assertNotNull("charPerson " + objectId + " must be readable", cp);
		return cp.get(OlioFieldNames.FIELD_HAIR_STYLE);
	}

	private BaseRecord readMinimal(String objectId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME });
		q.setCache(false);
		return ioContext.getAccessPoint().find(testUser, q);
	}

	/// Clear the id-keyed profile cache, re-read the person minimal+uncached, and return the fully-populated
	/// record ({@link OlioUtil#getFullRecord}, which itself sets cache:false). Everything the composed prompt
	/// needs — statistics/gender (age), hairStyle, and the eyeColor FK's name — is on the returned record.
	private BaseRecord freshPerson(String objectId) {
		ProfileUtil.clearCache();
		BaseRecord min = readMinimal(objectId);
		if (min == null) {
			return null;
		}
		return OlioUtil.getFullRecord(min);
	}

	/// The name of a foreign {@code data.color} FK (eyeColor/hairColor) on a fully-populated person, or null
	/// when the FK is unset. Mirrors the read {@code NarrativeUtil.getColor} performs (NarrativeUtil.java:793).
	private String colorName(BaseRecord fullPerson, String field) {
		BaseRecord c = fullPerson.get(field);
		return c == null ? null : (String) c.get(FieldNames.FIELD_NAME);
	}

	// ─────────────────────────────── prompt composition ───────────────────────────────

	/// Compose the SD prompt VERBATIM from the person's own persisted fields: the {@code hairStyle} scalar
	/// (the PULL vehicle) and the {@code eyeColor} FK name (the KEEP vehicle), plus a clearly-adult gender/age
	/// descriptor and a fixed clothed clause. Each source value is guarded for the literal-placeholder family
	/// BEFORE it is used, so a merge that nulled a field fails here rather than shipping "null" into the image.
	private String composePrompt(BaseRecord fullPerson) {
		PersonalityProfile pp = ProfileUtil.getProfile(null, fullPerson);
		assertNotNull("a profile must build for the person", pp);
		int age = pp.getAge();
		String gender = pp.getGender();
		String hairStyle = fullPerson.get(OlioFieldNames.FIELD_HAIR_STYLE);
		String eyeColor = colorName(fullPerson, OlioFieldNames.FIELD_EYE_COLOR);

		assertTrue("subject must be clearly adult (age >= 25); was " + age, age >= 25);
		assertNotNull("gender must be set", gender);
		assertTrue("hairStyle must be meaningful (not a literal placeholder): [" + hairStyle + "]",
			NarrativeUtil.isMeaningful(hairStyle));
		assertTrue("eyeColor name must be meaningful (not a literal placeholder): [" + eyeColor + "]",
			NarrativeUtil.isMeaningful(eyeColor));

		String genderNoun = "male".equalsIgnoreCase(gender) ? "man" : "woman";
		return "professional full-body portrait photograph of a fully clothed adult " + genderNoun
			+ " (" + age + " years old), with (" + hairStyle + ") hair and (" + eyeColor.toLowerCase() + ") eyes, "
			+ CLOTHING + ", standing in " + SETTING + ", " + VERB
			+ ", 8k, highly detailed, ultra realistic, sharp focus, natural lighting";
	}

	/// Mirror of TestSD.getSwarmConfig — a real Swarm config with the configured model. The SD server URL and
	/// the model both honour a {@code -D} override before the checked-in resource.properties value (which is a
	/// local-WIP default that must NOT be edited), then a hard fallback.
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

	// ─────────────────────────── test-confined nested rehome ───────────────────────────
	// Reimplements PbSharingUtil's private rehomeSubRecords/applyGroupId (which OlioUtil.cloneIntoGroup does
	// NOT do — it re-homes only the top-level groupId, OlioUtil.java:592-598). Needed because the renderable
	// baseline is cloned out of the DEFAULT olio world's population (whose nested foreign records carry that
	// world's groupIds) into the SERIES world's population; without re-homing the nested groupIds the
	// AccessPoint.create would demand grants on the source world's groups.

	private void rehomeSubRecords(BaseRecord rec, long groupId, int depth) {
		if (rec == null || depth >= REHOME_MAX_DEPTH) {
			return;
		}
		ModelSchema ms = RecordFactoryGetSchema(rec.getSchema());
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
		ModelSchema ms = RecordFactoryGetSchema(rec.getSchema());
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

	private static ModelSchema RecordFactoryGetSchema(String model) {
		return org.cote.accountmanager.record.RecordFactory.getSchema(model);
	}

	// ─────────────────────────────── image emit ───────────────────────────────

	/// Emit the captured prompt + each generated PNG to the timestamped EMIT_DIR, and assert every PNG is a
	/// decodable, positive-dimension image. Bytes are read via {@link ByteModelUtil#getValue(BaseRecord)}, which
	/// returns the logical (decompressed/decrypted) bytes per the CLAUDE.md rule — never a raw byte_store get.
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

	/// Render via the collision-free {@link SDUtil#createImage} path: the composed prompt is placed on the
	/// shared config's {@code description} and used verbatim as the SD prompt (no Olio apparel, no colour
	/// record creation). Returns the created {@code data.data} image records.
	private List<BaseRecord> render(SDUtil sdu, String prompt, String name) {
		sharedSdConfig.setValue("description", prompt);
		sharedSdConfig.setValue("seed", SEED);
		return sdu.createImage(testUser, "~/Gallery", sharedSdConfig, name, BATCH, false, SEED);
	}

	// ─────────────────────────── renderable baseline construction ───────────────────────────

	/// Build a fully-IDENTIFIED, CLEARLY-ADULT charPerson as the series baseline, BARE of apparel/items.
	/// A real fleshed identity (statistics, gender, race, age, body, eye/hair colour) is taken by cloning a
	/// seeded person out of the DEFAULT olio world's population (the series world has no seeded population to
	/// draw a random person from), then its apparel/items are STRIPPED before create so the clone carries NO
	/// nested apparel {@code data.color} records — the create is clean (see the class javadoc for why the
	/// apparel colour auto-create collides). The identity {@code eyeColor}/{@code hairColor} FKs are kept:
	/// they deep-copy into the person's OWN population group (not the pre-seeded shared colour library), so
	/// they do not collide.
	///
	/// hairStyle is set to H1. Returns the created (bare) series-baseline record.
	private BaseRecord buildRenderableSeriesBaseline(String dataPath, String populationPath, long seriesPopGroupId,
			BaseRecord seriesPopGroup, String baselineName) throws Exception {
		OlioContext defCtx = OlioTestUtil.getContext(orgContext, dataPath);
		assertNotNull("default olio context must assemble", defCtx);
		BaseRecord popGrp = defCtx.getRealms().get(0).get(OlioFieldNames.FIELD_POPULATION);
		assertNotNull("default context must expose a realm population group", popGrp);
		List<BaseRecord> pop = OlioUtil.listGroupPopulation(defCtx, popGrp);
		assertNotNull("default population must list", pop);
		assertTrue("default population must be non-empty", pop.size() > 0);

		// Select the first CLEARLY-ADULT person whose gender, eye colour and hair are set and meaningful — the
		// three identity fields the composed prompt draws on. Apparel is irrelevant (stripped + prompt-supplied).
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
			String eye = colorName(full, OlioFieldNames.FIELD_EYE_COLOR);
			if (!NarrativeUtil.isMeaningful(eye)) {
				continue;
			}
			chosen = full;
			break;
		}
		assertNotNull("a clearly-adult source person with a meaningful eye colour must be selectable", chosen);
		logger.info("Selected renderable source person '" + chosen.get(FieldNames.FIELD_NAME) + "'");

		// Clone the identity graph into the series population group, STRIP apparel/items so no apparel colour
		// records are copied, re-home the top-level AND nested groupIds, set a distinctive name+owner and the
		// starting hair (H1), and create as the acting user. The bare graph carries no apparel colours => a
		// clean create.
		BaseRecord clone = OlioUtil.cloneIntoGroup(chosen, seriesPopGroup);
		assertNotNull("clone into series population must produce a record", clone);
		stripApparelAndItems(clone);
		ioContext.getRecordUtil().applyNameGroupOwnership(testUser, clone, baselineName, populationPath, orgId);
		rehomeSubRecords(clone, seriesPopGroupId, 0);
		clone.set(OlioFieldNames.FIELD_HAIR_STYLE, H1);
		BaseRecord created = ioContext.getAccessPoint().create(testUser, clone);
		assertNotNull("the renderable baseline must be created in the series population", created);
		return created;
	}

	/// Remove any apparel and items from a freshly-cloned person's store so the create copies NO nested
	/// apparel {@code data.color} records (see the class javadoc). The list getters return the live backing
	/// collections, so clearing them drops the deidentified wearable/colour copies before the graph is written.
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

	/// Pick an EXISTING colour from the SERIES universe's shared colour library (a real, seeded {@code
	/// data.color} row, so it carries an id) whose name is meaningful and differs from {@code excludeName}.
	/// Assigning it as an {@code eyeColor} FK stores only its id — no colour record is created, so there is
	/// nothing to collide with. This is the collision-free "chapter foreign override" vehicle.
	private BaseRecord pickLibraryColor(OlioContext seriesCtx, String excludeName) {
		BaseRecord universe = seriesCtx.getUniverse();
		assertNotNull("series universe must resolve for the colour library", universe);
		BaseRecord colorsGroup = universe.get(OlioFieldNames.FIELD_COLORS);
		assertNotNull("series universe colour-library group must resolve", colorsGroup);
		long gid = ((Number) colorsGroup.get(FieldNames.FIELD_ID)).longValue();

		Query cq = QueryUtil.createQuery(ModelNames.MODEL_COLOR, FieldNames.FIELD_GROUP_ID, gid);
		cq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		cq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME });
		cq.setRequestRange(0, 200);
		cq.setCache(false);
		BaseRecord[] colors = ioContext.getSearch().findRecords(cq);
		assertNotNull("colour-library query must return", colors);
		for (BaseRecord c : colors) {
			String nm = c.get(FieldNames.FIELD_NAME);
			if (NarrativeUtil.isMeaningful(nm) && !PLACEHOLDER.matcher(nm).find()
				&& (excludeName == null || !nm.trim().equalsIgnoreCase(excludeName.trim()))) {
				return c;
			}
		}
		return null;
	}

	@Test
	public void testMergedShadowRendersPulledHairKeepsEyeColorOverride() throws Exception {
		assumeTrue("PICTUREBOOK_E2E not set — skipping live-SD merge/render image test",
			System.getenv("PICTUREBOOK_E2E") != null);

		OlioModelNames.use();
		testUser = getCreateUser("pbSeriesImgUser");
		assertNotNull("test user", testUser);
		assertFalse("must not run as admin", "admin".equals(testUser.get(FieldNames.FIELD_NAME)));
		orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		// SD is the GTR9 Swarm at 192.168.1.39; the LLM box (.42) crashes under sustained SD load, so the
		// last-resort fallback must point at .39 (see reference-sd-llm-hardware). -D or resource.properties override.
		String swarmServer = System.getProperty("test.swarm.server",
			testProperties.getProperty("test.swarm.server", "http://192.168.1.39:7801"));
		assertNotNull("swarm server must resolve", swarmServer);
		this.sharedSdConfig = buildSwarmConfig();
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer);

		String tag = shortId();
		String seriesSlug = "imgmrg" + tag;

		// ── Series + its ONE shared world; resolve the series population group as the olio principal ──
		BaseRecord series = PbSeriesUtil.getCreateSeries(testUser, dataPath, seriesSlug, "Image Merge Series " + tag);
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

		// ── The BARE, clearly-adult baseline (hairStyle=H1, with a meaningful identity eye colour). ──
		BaseRecord baseline = buildRenderableSeriesBaseline(dataPath, populationPath, seriesPopGroupId,
			seriesPopGroup, "Img Baseline " + tag);
		String baselineOid = baseline.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("baseline objectId", baselineOid);
		assertTrue("baseline must enrol in the baseline cast", PbCastUtil.enrollCastMember(baselineCast, baseline));
		assertEquals("baseline must start with H1 hair", H1, readHairStyle(baselineOid));

		BaseRecord baselineFull0 = freshPerson(baselineOid);
		assertNotNull("baseline must read full", baselineFull0);
		String baselineEye = colorName(baselineFull0, OlioFieldNames.FIELD_EYE_COLOR);
		assertTrue("baseline eye colour must be meaningful: [" + baselineEye + "]",
			NarrativeUtil.isMeaningful(baselineEye));

		// ── Seed a chapter: one shadow, copied from the baseline. ──
		Map<String, Object> chapter = PbServiceFacade.createChapter(testUser, dataPath,
			(String) series.get(FieldNames.FIELD_OBJECT_ID), null, "imgcha" + tag, "Image Chapter One",
			Integer.valueOf(1), null, null, java.util.Collections.singletonList(baselineOid),
			OlioModelNames.MODEL_CHAR_PERSON);
		assertNotNull("createChapter result", chapter);
		assertEquals("chapter must seed exactly one shadow", 1, ((Number) chapter.get("copied")).intValue());
		String bookOid = (String) chapter.get("bookObjectId");
		assertNotNull("chapter bookObjectId", bookOid);
		@SuppressWarnings("unchecked")
		List<String> shadowOids = (List<String>) chapter.get("copiedObjectIds");
		assertEquals("one shadow objectId", 1, shadowOids.size());
		String shadowOid = shadowOids.get(0);
		assertEquals("shadow copied the baseline hair (H1)", H1, readHairStyle(shadowOid));

		// ── Give the SHADOW a chapter-specific FOREIGN override: a DIFFERENT eyeColor, referencing an existing
		// series colour-library entry by FK id (no colour record created => no collision). This is the override
		// the merge must KEEP. Done BEFORE any expensive SD render so a PBAC/patch failure fails fast. ──
		OlioContext seriesCtx = PbOlioContextUtil.getCreateSeriesContext(testUser, dataPath, seriesSlug);
		assertNotNull("series olio context must assemble", seriesCtx);
		BaseRecord overrideColor = pickLibraryColor(seriesCtx, baselineEye);
		assertNotNull("a distinct, meaningful library colour must be selectable for the override", overrideColor);
		String overrideName = overrideColor.get(FieldNames.FIELD_NAME);
		assertFalse("override colour must differ from the baseline eye colour",
			overrideName.trim().equalsIgnoreCase(baselineEye.trim()));

		BaseRecord shadowMin = readMinimal(shadowOid);
		assertNotNull("shadow must be readable to patch", shadowMin);
		BaseRecord eyePatch = PbGraphUtil.patchOf(shadowMin, OlioModelNames.MODEL_CHAR_PERSON,
			OlioFieldNames.FIELD_EYE_COLOR);
		eyePatch.set(OlioFieldNames.FIELD_EYE_COLOR, overrideColor);
		assertNotNull("overriding the shadow eyeColor (foreign FK) must succeed",
			ioContext.getAccessPoint().update(testUser, eyePatch));

		BaseRecord preShadow = freshPerson(shadowOid);
		assertNotNull("shadow must read full after the eyeColor override", preShadow);
		assertEquals("shadow eyeColor override must have taken", overrideName.trim().toLowerCase(),
			String.valueOf(colorName(preShadow, OlioFieldNames.FIELD_EYE_COLOR)).trim().toLowerCase());

		// ── (1) REGRESSION: the ordinary (non-merged) baseline render works and is well-formed ──
		BaseRecord baselineFull = freshPerson(baselineOid);
		String regressionPrompt = composePrompt(baselineFull);
		assertTrue("regression prompt must carry the baseline hair style (H1)", regressionPrompt.contains(H1));
		assertFalse("regression prompt must not leak a literal placeholder: [" + regressionPrompt + "]",
			PLACEHOLDER.matcher(regressionPrompt).find());
		emit("regression-baseline", render(sdu, regressionPrompt, "PB Series Regression " + tag), regressionPrompt);

		// ── (2) PRE-merge render of the shadow (still H1, override eye colour) ──
		String prePrompt = composePrompt(preShadow);
		assertTrue("pre-merge prompt must contain H1", prePrompt.contains(H1));
		assertFalse("pre-merge prompt must NOT yet contain H2", prePrompt.contains(H2));
		assertTrue("pre-merge prompt must carry the shadow's override eye colour",
			prePrompt.toLowerCase().contains(overrideName.toLowerCase()));
		assertFalse("pre-merge prompt must not leak a placeholder: [" + prePrompt + "]",
			PLACEHOLDER.matcher(prePrompt).find());
		emit("pre-merge-shadow", render(sdu, prePrompt, "PB Series Pre-Merge " + tag), prePrompt);

		// ── Change the shared SCALAR on the BASELINE (H1→H2). The shadow must not change yet. ──
		BaseRecord base = readMinimal(baselineOid);
		assertNotNull("baseline must be readable to patch", base);
		BaseRecord basePatch = PbGraphUtil.patchOf(base, OlioModelNames.MODEL_CHAR_PERSON,
			OlioFieldNames.FIELD_HAIR_STYLE);
		basePatch.set(OlioFieldNames.FIELD_HAIR_STYLE, H2);
		assertNotNull("patching the baseline hairStyle must succeed",
			ioContext.getAccessPoint().update(testUser, basePatch));
		assertEquals("baseline scalar must now be H2", H2, readHairStyle(baselineOid));
		assertEquals("shadow must still be H1 before merge (no auto-cascade)", H1, readHairStyle(shadowOid));

		// ── Merge: PULL the baseline scalar (hairStyle) into the shadow, KEEP the shadow's foreign (eyeColor)
		// override. ──
		Map<String, Object> merge = PbServiceFacade.mergeChapter(testUser, bookOid);
		assertNotNull("merge result", merge);
		assertTrue("merge must report at least one shadow merged", ((Number) merge.get("merged")).intValue() >= 1);
		assertEquals("merge must PULL the baseline scalar (H2) into the shadow", H2, readHairStyle(shadowOid));

		// ── (3) POST-merge render of the shadow (now H2, override eye colour KEPT), fresh re-read ──
		BaseRecord postShadow = freshPerson(shadowOid);
		assertNotNull("post-merge shadow must read full", postShadow);

		// Persisted-field proofs (independent of the prompt string): scalar PULLED, foreign KEPT.
		assertEquals("post-merge shadow hairStyle must be the PULLED baseline value (H2)", H2,
			(String) postShadow.get(OlioFieldNames.FIELD_HAIR_STYLE));
		String postEye = colorName(postShadow, OlioFieldNames.FIELD_EYE_COLOR);
		assertEquals("post-merge shadow must KEEP its eyeColor override (foreign not pulled)",
			overrideName.trim().toLowerCase(), String.valueOf(postEye).trim().toLowerCase());
		assertFalse("kept eyeColor must not have reverted to the baseline's (foreign not pulled)",
			postEye.trim().equalsIgnoreCase(baselineEye.trim()));

		String postPrompt = composePrompt(postShadow);
		assertTrue("post-merge prompt must contain the PULLED hair (H2)", postPrompt.contains(H2));
		assertFalse("post-merge prompt must NOT contain the old hair (H1)", postPrompt.contains(H1));
		assertTrue("post-merge prompt must still carry the KEPT override eye colour",
			postPrompt.toLowerCase().contains(overrideName.toLowerCase()));
		assertFalse("post-merge prompt must not leak a placeholder: [" + postPrompt + "]",
			PLACEHOLDER.matcher(postPrompt).find());
		// Equality-modulo-hair: the ONLY difference between the two deterministic shadow prompts is the hair.
		assertEquals("pre and post shadow prompts must differ ONLY by the hair style phrase", prePrompt,
			postPrompt.replace(H2, H1));

		emit("post-merge-shadow", render(sdu, postPrompt, "PB Series Post-Merge " + tag), postPrompt);

		logger.info("PICTUREBOOK_E2E merge/render images emitted to: " + EMIT_DIR);
	}
}
