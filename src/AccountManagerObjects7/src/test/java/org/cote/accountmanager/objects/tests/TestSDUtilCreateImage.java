package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Assume;
import org.junit.Test;

/**
 * PictureBook V3 Phase 0, Slice A: {@link SDUtil#createImage(BaseRecord, String, BaseRecord, String, int, boolean, int)}
 * against the LIVE Swarm server ({@code test.swarm.server}) and the live Objects7 test DB.
 *
 * <p>Every render test sends a real request, stores a real {@code data.data} record, then RE-READS that
 * record from the database (fresh, uncached, explicit projection) and inspects the {@code s2i} attribute
 * - the exact {@link SWTxt2Img} that was sent - plus the PNG bytes via {@code ByteModelUtil.getValue}.
 * Nothing is asserted on the in-memory record createImage hands back, so the persistence path (P0-7:
 * ByteModelUtil.setValue + attribute cascade on create) is exercised too.
 *
 * <p>Render timings are logged ({@code durationMs}) and never asserted; the JUnit timeouts are ceilings
 * measured in many minutes so a slow node fails only on a genuine hang.
 *
 * <p>Runs as a non-admin user ({@code BaseTest.getCreateUser}). Records are left in that user's gallery.
 */
public class TestSDUtilCreateImage extends BaseTest {

	private static final String OUTPUT_DIR = "./target/sdutil-test-output";
	private static final long RENDER_TIMEOUT_MS = 20L * 60L * 1000L;
	private static final byte[] PNG_MAGIC = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 };

	/// ── fixtures ──────────────────────────────────────────────────────────────

	private BaseRecord renderUser() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		BaseRecord user = getCreateUser("testUser1", testOrgContext);
		assertNotNull("Non-admin test user", user);
		return user;
	}

	private SDUtil liveSwarm() {
		String server = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		SdTestGate.requireSwarmReachable(server);
		return new SDUtil(SDAPIEnumType.SWARM, server);
	}

	/// The SDXL checkpoint this node actually lists (SdTestGate resolved it into test.swarm.model in setup).
	private String installedSdxlModel() {
		String model = testProperties.getProperty(SdTestGate.PROP_SWARM_MODEL);
		Assume.assumeTrue("SKIPPED: no SDXL-family checkpoint resolved for " + testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER)
			+ ". This is NOT a pass.", model != null && !model.isBlank());
		return model;
	}

	/// The refiner checkpoint EXACTLY as the server lists it (with folder/extension), so the request names
	/// something Swarm cannot refuse. SDUtil sends refinerModel as-is - it never appends .safetensors.
	private String installedRefinerModel(SDUtil sdu) {
		String wanted = testProperties.getProperty(SdTestGate.PROP_SWARM_REFINER);
		Assume.assumeTrue("SKIPPED: test.swarm.refinerModel is not set/installed on this node. This is NOT a pass.",
			wanted != null && !wanted.isBlank());
		List<String> installed = sdu.listModels();
		for (String m : installed) {
			if (m == null) continue;
			String bare = m.endsWith(".safetensors") ? m.substring(0, m.length() - 12) : m;
			if (m.equalsIgnoreCase(wanted) || bare.equalsIgnoreCase(wanted)) {
				logger.info("Refiner checkpoint '" + wanted + "' is listed by the server as '" + m + "'");
				return m;
			}
		}
		Assume.assumeTrue("SKIPPED: refiner '" + wanted + "' is not in the server's list " + installed + ". This is NOT a pass.", false);
		return null;
	}

	/// A cheap, fully-populated SDXL config: small step count, batch 1, explicit prompt via description.
	private BaseRecord cheapConfig(String model, String description) throws Exception {
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		cfg.set("model", model);
		cfg.set("description", description);
		cfg.set("steps", 8);
		cfg.set("refinerSteps", 8);
		cfg.set("cfg", 5);
		cfg.set("scheduler", "karras");
		cfg.set("sampler", "dpmpp_2m");
		cfg.set("negativePrompt", "text, watermark, blurry");
		return cfg;
	}

	private String galleryPath(String tag) {
		return "~/Gallery/SDUtilCreateImage-" + tag + "-" + UUID.randomUUID();
	}

	/// ── fresh-read helpers ────────────────────────────────────────────────────

	/**
	 * Re-read the stored data.data by objectId: uncached, explicit projection carrying the attributes
	 * (referenced storage) and every column ByteModelUtil.getValue consults. The in-memory record
	 * createImage returned is deliberately not used for any assertion.
	 */
	private BaseRecord freshRead(BaseRecord user, String objectId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ATTRIBUTES,
			FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_DATA_HASH, FieldNames.FIELD_SIZE,
			FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_ENCIPHERED,
			FieldNames.FIELD_VAULTED, FieldNames.FIELD_KEY_ID, FieldNames.FIELD_VAULT_ID
		});
		q.setCache(false);
		BaseRecord fresh = ioContext.getAccessPoint().find(user, q);
		assertNotNull("Stored image record " + objectId + " must be readable by its owner", fresh);
		return fresh;
	}

	/** The recorded request, parsed both as the typed SWTxt2Img and as a raw map (to see exact keys). */
	private static final class SentRequest {
		final String json;
		final SWTxt2Img typed;
		final Map<String, Object> raw;
		SentRequest(String json) {
			this.json = json;
			this.typed = JSONUtil.importObject(json, SWTxt2Img.class);
			@SuppressWarnings("unchecked")
			Map<String, Object> m = JSONUtil.importObject(json, Map.class);
			this.raw = m;
		}
	}

	private SentRequest sentRequestOf(BaseRecord fresh) throws Exception {
		String s2iJson = AttributeUtil.getAttributeValue(fresh, "s2i", (String) null);
		assertNotNull("Stored record must carry the 's2i' attribute (the exact request sent)", s2iJson);
		SentRequest sr = new SentRequest(s2iJson);
		assertNotNull("s2i attribute must parse as SWTxt2Img: " + s2iJson, sr.typed);
		assertNotNull("s2i attribute must parse as a map: " + s2iJson, sr.raw);
		logger.info("s2i as stored: " + s2iJson.replaceAll("\\s+", " "));
		return sr;
	}

	private byte[] assertPngAndEmit(BaseRecord fresh, String fileTag) throws Exception {
		byte[] bytes = ByteModelUtil.getValue(fresh);
		assertNotNull("ByteModelUtil.getValue must return bytes", bytes);
		assertTrue("Image bytes must be non-empty", bytes.length > 0);
		assertTrue("Stored bytes must be a PNG (magic 89 50 4E 47), got "
			+ String.format("%02X %02X %02X %02X", bytes[0], bytes[1], bytes[2], bytes[3]),
			bytes.length >= 4 && bytes[0] == PNG_MAGIC[0] && bytes[1] == PNG_MAGIC[1] && bytes[2] == PNG_MAGIC[2] && bytes[3] == PNG_MAGIC[3]);
		String path = OUTPUT_DIR + "/" + fileTag + ".png";
		assertTrue("Could not write " + path, FileUtil.emitFile(path, bytes));
		logger.info("PNG written: " + new File(path).getAbsolutePath() + " (" + bytes.length + " bytes)");
		return bytes;
	}

	private List<BaseRecord> timedCreate(SDUtil sdu, BaseRecord user, String path, BaseRecord cfg, String name, boolean hires, int seed) {
		long start = System.currentTimeMillis();
		List<BaseRecord> out = sdu.createImage(user, path, cfg, name, 1, hires, seed);
		long durationMs = System.currentTimeMillis() - start;
		logger.info("createImage[" + name + "] hires=" + hires + " seed=" + seed + " -> " + (out == null ? "null" : out.size())
			+ " record(s), durationMs=" + durationMs);
		return out;
	}

	private BaseRecord singleStored(List<BaseRecord> out, SDUtil sdu, String model, String what) {
		assertNotNull("createImage returned null", out);
		if (out.isEmpty()) {
			SdTestGate.emptyResultIsSkipOrFailure(sdu, testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER), model, what);
		}
		assertEquals("batch=1 must yield exactly one record", 1, out.size());
		String oid = out.get(0).get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Created record must carry an objectId", oid);
		return out.get(0);
	}

	/// ── render tests ──────────────────────────────────────────────────────────

	@Test(timeout = RENDER_TIMEOUT_MS)
	public void createImageHonoursWidthHeightAndSeed() throws Exception {
		SDUtil sdu = liveSwarm();
		String model = installedSdxlModel();
		BaseRecord user = renderUser();

		BaseRecord cfg = cheapConfig(model, "a red wooden barn in a green field under a blue sky");
		cfg.set("width", 768);
		cfg.set("height", 512);
		cfg.set("seed", 999);   /// must LOSE to the argument
		cfg.set("hires", true); /// must LOSE to the argument (hires=false below)

		List<BaseRecord> out = timedCreate(sdu, user, galleryPath("dims"), cfg, "DimsSeed", false, 12345);
		BaseRecord created = singleStored(out, sdu, model, "width/height/seed render");

		BaseRecord fresh = freshRead(user, created.get(FieldNames.FIELD_OBJECT_ID));
		SentRequest sr = sentRequestOf(fresh);
		assertEquals("width from config", 768, sr.typed.getWidth());
		assertEquals("height from config", 512, sr.typed.getHeight());
		assertEquals("seed argument wins over config seed", 12345, sr.typed.getSeed());
		assertEquals("hires=false argument: refiner control must be 0.0", 0.0, sr.typed.getRefinerControlPercentage(), 0.0);
		assertTrue("refinercontrolpercentage must be present in the stored JSON", sr.raw.containsKey("refinercontrolpercentage"));
		assertEquals("refinercontrolpercentage raw", 0.0, ((Number) sr.raw.get("refinercontrolpercentage")).doubleValue(), 0.0);
		assertNull("hires=false: no refinermodel on the wire", sr.typed.getRefinerModel());
		assertEquals("model", model, sr.typed.getModel());
		assertEquals("steps", 8, sr.typed.getSteps());
		Object seedAttr = AttributeUtil.getAttributeValue(fresh, "seed", (Object) null);
		assertNotNull("Stored record must carry the 'seed' attribute", seedAttr);
		assertTrue("seed attribute must be numeric, got " + seedAttr.getClass(), seedAttr instanceof Number);
		assertEquals("seed attribute", 12345, ((Number) seedAttr).intValue());

		assertPngAndEmit(fresh, "createImageHonoursWidthHeightAndSeed");
	}

	@Test(timeout = RENDER_TIMEOUT_MS)
	public void createImageHiresWithRefinerModelSetsRefinerFields() throws Exception {
		SDUtil sdu = liveSwarm();
		String model = installedSdxlModel();
		String refiner = installedRefinerModel(sdu);
		BaseRecord user = renderUser();

		BaseRecord cfg = cheapConfig(model, "a lighthouse on a rocky coast at sunset");
		cfg.set("width", 512);
		cfg.set("height", 512);
		cfg.set("refinerModel", refiner);
		cfg.set("refinerControlPercentage", 0.3);
		cfg.set("refinerUpscale", 2);
		cfg.set("hires", false); /// must LOSE to the argument (hires=true below)

		List<BaseRecord> out = timedCreate(sdu, user, galleryPath("hires"), cfg, "HiresRefiner", true, 777);
		BaseRecord created = singleStored(out, sdu, model, "hires+refiner render");

		BaseRecord fresh = freshRead(user, created.get(FieldNames.FIELD_OBJECT_ID));
		SentRequest sr = sentRequestOf(fresh);
		assertEquals("refinerModel on the wire", refiner, sr.typed.getRefinerModel());
		assertTrue("refinerControlPercentage > 0 when hires && refinerModel, got " + sr.typed.getRefinerControlPercentage(),
			sr.typed.getRefinerControlPercentage() > 0.0);
		assertEquals("refinerControlPercentage from config", 0.3, sr.typed.getRefinerControlPercentage(), 0.0001);
		assertEquals("refinerSteps from config", Integer.valueOf(8), sr.typed.getRefinerSteps());
		assertEquals("refinerUpscale from config", Integer.valueOf(2), sr.typed.getRefinerUpscale());
		assertNotNull("refinerMethod set", sr.typed.getRefinerMethod());
		assertEquals("seed argument", 777, sr.typed.getSeed());
		assertEquals("width", 512, sr.typed.getWidth());
		assertEquals("height", 512, sr.typed.getHeight());

		assertPngAndEmit(fresh, "createImageHiresWithRefinerModelSetsRefinerFields");
	}

	@Test(timeout = RENDER_TIMEOUT_MS)
	public void createImageHiresWithoutRefinerModelSkipsRefiner() throws Exception {
		SDUtil sdu = liveSwarm();
		String model = installedSdxlModel();
		BaseRecord user = renderUser();

		BaseRecord cfg = cheapConfig(model, "a bowl of oranges on a wooden table, still life");
		cfg.set("width", 512);
		cfg.set("height", 512);
		cfg.set("refinerModel", null);
		cfg.set("refinerControlPercentage", 0.4); /// must NOT reach the wire: no refiner model
		assertNull("precondition: refinerModel is null", cfg.get("refinerModel"));

		List<BaseRecord> out = timedCreate(sdu, user, galleryPath("norefiner"), cfg, "HiresNoRefiner", true, 4242);
		BaseRecord created = singleStored(out, sdu, model, "hires-without-refiner render");

		BaseRecord fresh = freshRead(user, created.get(FieldNames.FIELD_OBJECT_ID));
		SentRequest sr = sentRequestOf(fresh);
		assertEquals("no refinerModel => refiner skipped => control 0.0", 0.0, sr.typed.getRefinerControlPercentage(), 0.0);
		assertTrue("refinercontrolpercentage must be present in the stored JSON", sr.raw.containsKey("refinercontrolpercentage"));
		assertNull("no refinermodel on the wire", sr.typed.getRefinerModel());
		assertNull("no refinersteps on the wire", sr.typed.getRefinerSteps());
		assertFalse("no refinermodel key at all", sr.raw.containsKey("refinermodel"));
		assertEquals("seed argument", 4242, sr.typed.getSeed());

		assertPngAndEmit(fresh, "createImageHiresWithoutRefinerModelSkipsRefiner");
	}

	@Test(timeout = RENDER_TIMEOUT_MS)
	public void createImageSparseConfigDoesNotThrow() throws Exception {
		SDUtil sdu = liveSwarm();
		String model = installedSdxlModel();
		BaseRecord user = renderUser();

		/// Field PRESENT but NULL on every INT/BOOLEAN the method reads - the shape that used to NPE
		/// outside createImage's try block.
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		cfg.set("model", model);
		cfg.set("description", "a simple grey pebble on white sand");
		for (String f : new String[] { "steps", "seed", "hires", "width", "height", "cfg" }) {
			cfg.set(f, null);
			assertNull("precondition: " + f + " nulled", cfg.get(f));
		}
		assertTrue("precondition: the nulled fields are still PRESENT on the record", cfg.hasField("steps") && cfg.hasField("hires"));

		List<BaseRecord> out;
		long start = System.currentTimeMillis();
		try {
			out = sdu.createImage(user, galleryPath("sparse"), cfg, "SparseDefaults", 1, false, -1);
		} catch (RuntimeException e) {
			fail("createImage must not throw on a sparse config (field present but null): " + e);
			return;
		}
		long durationMs = System.currentTimeMillis() - start;
		assertNotNull("createImage must return a list, never null", out);
		logger.info("sparse createImage -> " + out.size() + " record(s), durationMs=" + durationMs);

		/// Swarm is live (liveSwarm() above) and the helper defaults are a valid SDXL request, so an empty
		/// result means the defaults were rejected — that IS the regression this test exists to catch.
		assertFalse("sparse config must render with the helper defaults; Swarm returned no image", out.isEmpty());
		BaseRecord fresh = freshRead(user, out.get(0).get(FieldNames.FIELD_OBJECT_ID));
		SentRequest sr = sentRequestOf(fresh);
		assertEquals("null steps -> helper default 20", 20, sr.typed.getSteps());
		assertEquals("null width -> SWTxt2Img default 1024", 1024, sr.typed.getWidth());
		assertEquals("null height -> SWTxt2Img default 1024", 1024, sr.typed.getHeight());
		assertEquals("null cfg -> helper default 7", 7.0, sr.typed.getCfgScale(), 0.0);
		assertEquals("seed arg -1 and null config seed -> -1 (random)", -1, sr.typed.getSeed());
		assertEquals("hires arg false -> refiner control 0.0", 0.0, sr.typed.getRefinerControlPercentage(), 0.0);
		assertPngAndEmit(fresh, "createImageSparseConfigDoesNotThrow");
	}

	/// ── pure tests (no render) ────────────────────────────────────────────────

	@Test
	public void applyConfigStyleAppendsOnce() throws Exception {
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		cfg.set("style", "custom");
		cfg.set("customPrompt", "watercolor painting with pastel colors");
		String clause = SDUtil.getSDConfigPrompt(cfg);
		assertEquals("custom style clause", "(watercolor painting with pastel colors).", clause);

		assertEquals("blank prompt -> clause", clause, SDUtil.applyConfigStyle("", cfg));
		assertEquals("null prompt -> clause", clause, SDUtil.applyConfigStyle(null, cfg));
		assertEquals("plain prompt -> prompt + '. ' + clause", "a cat on a mat. " + clause, SDUtil.applyConfigStyle("a cat on a mat", cfg));
		assertEquals("prompt ending in '.' -> prompt + ' ' + clause", "a cat on a mat. " + clause, SDUtil.applyConfigStyle("a cat on a mat.", cfg));
		assertEquals("prompt ending in ',' -> prompt + ' ' + clause", "a cat on a mat, " + clause, SDUtil.applyConfigStyle("a cat on a mat,", cfg));
		assertEquals("prompt is trimmed", "a cat on a mat. " + clause, SDUtil.applyConfigStyle("  a cat on a mat  ", cfg));

		String once = SDUtil.applyConfigStyle("a cat on a mat", cfg);
		String twice = SDUtil.applyConfigStyle(once, cfg);
		assertEquals("applying twice is idempotent", once, twice);
		assertEquals("clause appears exactly once", once.indexOf(clause), once.lastIndexOf(clause));

		/// Null config: getSDConfigPrompt falls back to its professional-photograph clause, still once.
		String nullCfgClause = SDUtil.getSDConfigPrompt(null);
		assertEquals("null cfg -> fallback clause appended once", "x. " + nullCfgClause, SDUtil.applyConfigStyle("x", null));
		assertEquals("null cfg idempotent", "x. " + nullCfgClause, SDUtil.applyConfigStyle("x. " + nullCfgClause, null));
	}

	@Test
	public void hiresOfIsNullSafe() throws Exception {
		assertFalse("null record -> false", SDUtil.hiresOf(null));

		BaseRecord loose = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG, new String[] { "model" });
		assertFalse("precondition: loose record has no hires field", loose.hasField("hires"));
		assertFalse("loose record without hires -> false", SDUtil.hiresOf(loose));

		BaseRecord present = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		present.set("hires", null);
		assertTrue("precondition: hires present", present.hasField("hires"));
		assertFalse("hires present but null -> false", SDUtil.hiresOf(present));

		present.set("hires", false);
		assertFalse("hires=false -> false", SDUtil.hiresOf(present));

		present.set("hires", true);
		assertTrue("hires=true -> true", SDUtil.hiresOf(present));

		BaseRecord schemaDefault = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		assertTrue("schema default hires=true reads true", SDUtil.hiresOf(schemaDefault));
	}
}
