package org.cote.accountmanager.olio.sd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Date;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.illustration.CalibrationSample;
import org.cote.accountmanager.olio.illustration.RenderCalibration;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 5, P5-2 T7: {@code olio.sd.calibration} and {@link PbCalibrationUtil} against the live {@code am7db}.
 * The read path is find-only (a user with no {@code ~/Illustration/Calibration} gets the capabilities seed and
 * nothing is created); the write path creates the group and one row per server + family + stage as the
 * requesting user; a second sample EWMAs (alpha 0.3) with {@code seeded=false}; ACTOR_REGION lands on its own
 * row; non-positive inputs are refused before anything is touched.
 * <p>
 * Runs as a user of its own ({@code p52CalibrationUser}) in the PictureBook custom test org, so that every row
 * and group under that user's {@code ~/Illustration} is this test's to delete - a shared user's calibration rows
 * may hold real measurements from a live executor run, and c01's "nothing exists yet" precondition could not be
 * guaranteed for one.
 */
public class TestSdCalibrationRecord extends BaseTest {

	private static final String ORG = "/Development/PictureBook Custom Tests";
	private static final String USER = "p52CalibrationUser";
	private static final String SERVER = "http://192.168.1.39:7801";
	private static final String HOST_PORT = "192.168.1.39:7801";
	private static final String FAMILY = "flux2*";
	private static final String PARENT_PATH = "~/Illustration";
	private static final double DELTA = 1e-9;

	@Before
	public void calibrationSetup() {
		RenderCalibration.clear();
		cleanup(user());
	}

	@After
	public void calibrationTeardown() {
		cleanup(user());
		RenderCalibration.clear();
	}

	// ───────── c01: a read never creates ─────────

	@Test
	public void c01_sampleOnUnmeasuredUserReturnsSeedAndCreatesNothing() {
		BaseRecord u = user();
		assertTrue("The model must have its table", ioContext.getDbUtil().haveTable(OlioModelNames.MODEL_SD_CALIBRATION));
		assertEquals("A7_olio_sd_calibration_0_1", ioContext.getDbUtil().getTableName(OlioModelNames.MODEL_SD_CALIBRATION));

		assertNull("No calibration group before", findGroup(u));
		assertEquals("No rows under this owner before", 0, countByOwner(u));

		CalibrationSample base = PbCalibrationUtil.sample(u, SERVER, FAMILY, RenderPlan.STAGE_BASE);
		assertNotNull("flux2* carries a BASE seed", base);
		assertTrue(base.isSeed());
		assertEquals(9.2, base.getSecondsPerStepPerMegapixel(), DELTA);
		assertEquals(CalibrationSample.SOURCE_SEED, base.getSource());
		assertEquals(0, base.getSampleCount());

		CalibrationSample region = PbCalibrationUtil.sample(u, SERVER, FAMILY, RenderPlan.STAGE_ACTOR_REGION);
		assertNotNull("flux2* carries an ACTOR_REGION seed", region);
		assertTrue(region.isSeed());
		assertEquals(13.8, region.getSecondsPerStepPerMegapixel(), DELTA);

		assertNull("kontext carries no seed", PbCalibrationUtil.sample(u, SERVER, "kontext*", RenderPlan.STAGE_BASE));
		assertNull("an unknown family has no figure anywhere", PbCalibrationUtil.sample(u, SERVER, "nosuchfamily*", RenderPlan.STAGE_BASE));

		assertNull("A read must not create the group", findGroup(u));
		assertNull("...nor the parent", findParent(u));
		assertNull("find-only lookup is null", PbCalibrationUtil.findRow(u, SERVER, FAMILY, RenderPlan.STAGE_BASE));
		assertEquals("No rows under this owner after", 0, countByOwner(u));
		assertNull("A seed never primes the in-JVM table", RenderCalibration.sample(SERVER, FAMILY, RenderPlan.STAGE_BASE));
	}

	// ───────── c02: the first write creates the group and one named row ─────────

	@Test
	public void c02_firstSampleCreatesGroupAndRow() {
		BaseRecord u = user();
		Date before = new Date(System.currentTimeMillis() - 1000L);

		assertTrue(PbCalibrationUtil.recordSample(u, SERVER, FAMILY, RenderPlan.STAGE_BASE, "hash-c02", 38600L, 4, 1024, 1024, 0, "0.9.6"));

		BaseRecord dir = findGroup(u);
		assertNotNull("The write path creates ~/Illustration/Calibration", dir);
		assertEquals("...owned by the requesting user", idOf(u), (long) dir.get(FieldNames.FIELD_OWNER_ID));

		BaseRecord row = PbCalibrationUtil.findRow(u, SERVER, FAMILY, RenderPlan.STAGE_BASE);
		assertNotNull("The row is readable by name", row);
		assertEquals("calibration:flux2*:BASE:" + HOST_PORT, row.get(FieldNames.FIELD_NAME));
		assertEquals(idOf(u), (long) row.get(FieldNames.FIELD_OWNER_ID));
		assertEquals((long) dir.get(FieldNames.FIELD_ID), (long) row.get(FieldNames.FIELD_GROUP_ID));
		assertEquals(orgOf(u), (long) row.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertEquals(HOST_PORT, row.get(OlioFieldNames.FIELD_SD_CAL_SERVER));
		assertEquals(FAMILY, row.get(OlioFieldNames.FIELD_SD_CAL_FAMILY));
		assertEquals(RenderPlan.STAGE_BASE, row.get(OlioFieldNames.FIELD_SD_CAL_STAGE));
		double expected = (38600.0 / 1000.0) / 4 / ((1024.0 * 1024.0) / 1_000_000.0);
		assertEquals(expected, (double) row.get(OlioFieldNames.FIELD_SD_CAL_SECONDS_PER_STEP_PER_MEGAPIXEL), DELTA);
		assertEquals(1, (int) row.get(OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT));
		assertFalse((boolean) row.get(OlioFieldNames.FIELD_SD_CAL_SEEDED));
		assertEquals(38600L, (long) row.get(OlioFieldNames.FIELD_SD_CAL_LAST_DURATION_MS));
		assertEquals(4, (int) row.get(OlioFieldNames.FIELD_SD_CAL_LAST_STEPS));
		assertEquals(1024, (int) row.get(OlioFieldNames.FIELD_SD_CAL_LAST_WIDTH));
		assertEquals(1024, (int) row.get(OlioFieldNames.FIELD_SD_CAL_LAST_HEIGHT));
		assertEquals(0, (int) row.get(OlioFieldNames.FIELD_SD_CAL_LAST_REFERENCES));
		assertEquals("hash-c02", row.get(OlioFieldNames.FIELD_SD_CAL_GRAPH_HASH));
		assertEquals("0.9.6", row.get(OlioFieldNames.FIELD_SD_CAL_SWARM_VERSION));
		Date at = row.get(OlioFieldNames.FIELD_SD_CAL_LAST_SAMPLE_AT);
		assertNotNull(at);
		assertFalse("lastSampleAt is the sample time", at.before(before));
		assertEquals("Exactly one row under this owner", 1, countByOwner(u));

		/// In this JVM the figure is the measurement, not the row.
		CalibrationSample inProc = PbCalibrationUtil.sample(u, SERVER, FAMILY, RenderPlan.STAGE_BASE);
		assertNotNull(inProc);
		assertFalse(inProc.isSeed());
		assertEquals(expected, inProc.getSecondsPerStepPerMegapixel(), DELTA);
		assertEquals(CalibrationSample.SOURCE_MEASURED + " (1 samples since JVM start)", inProc.getSource());

		/// A fresh JVM (simulated by forgetting the table) reads the row back as the owner's persisted figure
		/// and primes the table as persisted-origin - never "measured in-process".
		RenderCalibration.clear();
		CalibrationSample persisted = PbCalibrationUtil.sample(u, SERVER, FAMILY, RenderPlan.STAGE_BASE);
		assertNotNull(persisted);
		assertFalse(persisted.isSeed());
		assertEquals(expected, persisted.getSecondsPerStepPerMegapixel(), DELTA);
		assertEquals(1, persisted.getSampleCount());
		assertTrue(persisted.getSource(), persisted.getSource().startsWith(CalibrationSample.SOURCE_PERSISTED_OWN + HOST_PORT + " (1 samples, last "));
		RenderCalibration.Sample primed = RenderCalibration.sample(SERVER, FAMILY, RenderPlan.STAGE_BASE);
		assertNotNull("The row primes the in-JVM table", primed);
		assertEquals(RenderCalibration.ORIGIN_PERSISTED, primed.getOrigin());
		assertFalse(primed.isMeasured());

		/// Another user in the same org sees neither the group nor the row - the figure is the owner's.
		BaseRecord other = user(USER + "2");
		assertNull(PbCalibrationUtil.findRow(other, SERVER, FAMILY, RenderPlan.STAGE_BASE));
		assertEquals(0, countByOwner(other));
	}

	// ───────── c03: the second sample EWMAs ─────────

	@Test
	public void c03_secondSampleFoldsWithAlpha() {
		BaseRecord u = user();
		assertTrue(PbCalibrationUtil.recordSample(u, SERVER, FAMILY, RenderPlan.STAGE_BASE, "hash-1", 38600L, 4, 1024, 1024, 0, "0.9.6"));
		assertTrue(PbCalibrationUtil.recordSample(u, SERVER, FAMILY, RenderPlan.STAGE_BASE, "hash-2", 50000L, 4, 1024, 1024, 2, "0.9.7"));

		double mp = (1024.0 * 1024.0) / 1_000_000.0;
		double v1 = 38.6 / 4 / mp;
		double v2 = 50.0 / 4 / mp;
		double expected = v1 + RenderCalibration.ALPHA * (v2 - v1);

		BaseRecord row = PbCalibrationUtil.findRow(u, SERVER, FAMILY, RenderPlan.STAGE_BASE);
		assertNotNull(row);
		assertEquals(expected, (double) row.get(OlioFieldNames.FIELD_SD_CAL_SECONDS_PER_STEP_PER_MEGAPIXEL), DELTA);
		assertEquals(2, (int) row.get(OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT));
		assertFalse((boolean) row.get(OlioFieldNames.FIELD_SD_CAL_SEEDED));
		assertEquals(50000L, (long) row.get(OlioFieldNames.FIELD_SD_CAL_LAST_DURATION_MS));
		assertEquals(2, (int) row.get(OlioFieldNames.FIELD_SD_CAL_LAST_REFERENCES));
		assertEquals("hash-2", row.get(OlioFieldNames.FIELD_SD_CAL_GRAPH_HASH));
		assertEquals("0.9.7", row.get(OlioFieldNames.FIELD_SD_CAL_SWARM_VERSION));
		assertEquals("Still one row - the second sample updated, not created", 1, countByOwner(u));

		RenderCalibration.Sample held = RenderCalibration.sample(SERVER, FAMILY, RenderPlan.STAGE_BASE);
		assertNotNull(held);
		assertEquals("The in-JVM fold agrees with the row", expected, held.getSecondsPerStepPerMegapixel(), DELTA);
		assertEquals(2, held.getCount());

		/// A third sample after a "restart": the row's figure is the baseline, the count continues from the row.
		RenderCalibration.clear();
		assertTrue(PbCalibrationUtil.recordSample(u, SERVER, FAMILY, RenderPlan.STAGE_BASE, "hash-3", 40000L, 4, 1024, 1024, 0, "0.9.7"));
		double v3 = 40.0 / 4 / mp;
		double expected3 = expected + RenderCalibration.ALPHA * (v3 - expected);
		row = PbCalibrationUtil.findRow(u, SERVER, FAMILY, RenderPlan.STAGE_BASE);
		assertEquals(expected3, (double) row.get(OlioFieldNames.FIELD_SD_CAL_SECONDS_PER_STEP_PER_MEGAPIXEL), DELTA);
		assertEquals(3, (int) row.get(OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT));
	}

	// ───────── c04: stages are rows of their own ─────────

	@Test
	public void c04_actorRegionLandsOnItsOwnRow() {
		BaseRecord u = user();
		assertTrue(PbCalibrationUtil.recordSample(u, SERVER, FAMILY, RenderPlan.STAGE_BASE, "hash-b", 38600L, 4, 1024, 1024, 1, "0.9.6"));
		assertTrue(PbCalibrationUtil.recordSample(u, SERVER, FAMILY, RenderPlan.STAGE_ACTOR_REGION, "hash-r", 20000L, 5, 1024, 1024, 1, "0.9.6"));

		assertEquals("Two rows: one per stage", 2, countByOwner(u));
		BaseRecord base = PbCalibrationUtil.findRow(u, SERVER, FAMILY, RenderPlan.STAGE_BASE);
		BaseRecord region = PbCalibrationUtil.findRow(u, SERVER, FAMILY, RenderPlan.STAGE_ACTOR_REGION);
		assertNotNull(base);
		assertNotNull(region);
		assertEquals("calibration:flux2*:ACTOR_REGION:" + HOST_PORT, region.get(FieldNames.FIELD_NAME));
		assertEquals(RenderPlan.STAGE_ACTOR_REGION, region.get(OlioFieldNames.FIELD_SD_CAL_STAGE));
		assertEquals(1, (int) base.get(OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT));
		assertEquals(1, (int) region.get(OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT));
		double mp = (1024.0 * 1024.0) / 1_000_000.0;
		assertEquals(38.6 / 4 / mp, (double) base.get(OlioFieldNames.FIELD_SD_CAL_SECONDS_PER_STEP_PER_MEGAPIXEL), DELTA);
		assertEquals(20.0 / 5 / mp, (double) region.get(OlioFieldNames.FIELD_SD_CAL_SECONDS_PER_STEP_PER_MEGAPIXEL), DELTA);
		assertEquals(5, (int) region.get(OlioFieldNames.FIELD_SD_CAL_LAST_STEPS));

		CalibrationSample r = PbCalibrationUtil.sample(u, SERVER, FAMILY, RenderPlan.STAGE_ACTOR_REGION);
		assertEquals(RenderPlan.STAGE_ACTOR_REGION, r.getStage());
		assertEquals(20.0 / 5 / mp, r.getSecondsPerStepPerMegapixel(), DELTA);
		CalibrationSample b = PbCalibrationUtil.sample(u, SERVER, FAMILY, RenderPlan.STAGE_BASE);
		assertEquals(RenderPlan.STAGE_BASE, b.getStage());
		assertEquals(38.6 / 4 / mp, b.getSecondsPerStepPerMegapixel(), DELTA);

		/// A blank stage is BASE, on the wire and in the key.
		assertTrue(PbCalibrationUtil.recordSample(u, SERVER, FAMILY, null, "hash-b2", 38600L, 4, 1024, 1024, 0, "0.9.6"));
		assertEquals("A null stage folds into BASE, not a third row", 2, countByOwner(u));
		assertEquals(2, (int) PbCalibrationUtil.findRow(u, SERVER, FAMILY, RenderPlan.STAGE_BASE).get(OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT));
	}

	// ───────── c05: non-positive inputs are refused before anything is touched ─────────

	@Test
	public void c05_nonPositiveInputsAreRefused() {
		BaseRecord u = user();
		assertFalse("zero duration", PbCalibrationUtil.recordSample(u, SERVER, FAMILY, RenderPlan.STAGE_BASE, "h", 0L, 4, 1024, 1024, 0, "v"));
		assertFalse("zero steps", PbCalibrationUtil.recordSample(u, SERVER, FAMILY, RenderPlan.STAGE_BASE, "h", 38600L, 0, 1024, 1024, 0, "v"));
		assertFalse("zero width", PbCalibrationUtil.recordSample(u, SERVER, FAMILY, RenderPlan.STAGE_BASE, "h", 38600L, 4, 0, 1024, 0, "v"));
		assertFalse("negative height", PbCalibrationUtil.recordSample(u, SERVER, FAMILY, RenderPlan.STAGE_BASE, "h", 38600L, 4, 1024, -1, 0, "v"));
		assertFalse("blank family", PbCalibrationUtil.recordSample(u, SERVER, " ", RenderPlan.STAGE_BASE, "h", 38600L, 4, 1024, 1024, 0, "v"));
		assertFalse("no user folds in-JVM but persists nothing", PbCalibrationUtil.recordSample(null, SERVER, FAMILY, RenderPlan.STAGE_BASE, "h", 38600L, 4, 1024, 1024, 0, "v"));

		assertNull("Nothing was created for the refused samples", findGroup(u));
		assertEquals(0, countByOwner(u));
		assertNull(RenderCalibration.sample(SERVER, FAMILY, RenderPlan.STAGE_ACTOR_REGION));
		RenderCalibration.Sample held = RenderCalibration.sample(SERVER, FAMILY, RenderPlan.STAGE_BASE);
		assertNotNull("The user-less call still measured in-JVM", held);
		assertEquals(1, held.getCount());
	}

	// ───────── c06: keys ─────────

	@Test
	public void c06_keysNormaliseTheServer() {
		assertEquals(HOST_PORT, PbCalibrationUtil.hostPort("http://192.168.1.39:7801"));
		assertEquals(HOST_PORT, PbCalibrationUtil.hostPort("http://192.168.1.39:7801/"));
		assertEquals(HOST_PORT, PbCalibrationUtil.hostPort(" https://192.168.1.39:7801/API "));
		assertEquals(HOST_PORT, PbCalibrationUtil.hostPort("192.168.1.39:7801"));
		assertEquals("swarm.local", PbCalibrationUtil.hostPort("http://swarm.local"));
		assertEquals("", PbCalibrationUtil.hostPort(null));
		assertEquals("calibration:flux2*:BASE:" + HOST_PORT, PbCalibrationUtil.rowName(SERVER, FAMILY, null));
		assertEquals("calibration:flux2*:ACTOR_REGION:" + HOST_PORT, PbCalibrationUtil.rowName(SERVER + "/", " flux2* ", RenderPlan.STAGE_ACTOR_REGION));
	}

	// ───────── fixture ─────────

	private BaseRecord user() {
		return user(USER);
	}

	private BaseRecord user(String name) {
		OrganizationContext org = getTestOrganization(ORG);
		assertNotNull("Failed to resolve " + ORG, org);
		BaseRecord u = getCreateUser(name, org);
		assertNotNull("Failed to resolve " + name, u);
		return u;
	}

	private static long idOf(BaseRecord rec) {
		return (long) rec.get(FieldNames.FIELD_ID);
	}

	private static long orgOf(BaseRecord rec) {
		return (long) rec.get(FieldNames.FIELD_ORGANIZATION_ID);
	}

	private BaseRecord findGroup(BaseRecord u) {
		return IOSystem.getActiveContext().getPathUtil().findPath(u, ModelNames.MODEL_GROUP, PbCalibrationUtil.CALIBRATION_PATH, GroupEnumType.DATA.toString(), orgOf(u));
	}

	private BaseRecord findParent(BaseRecord u) {
		return IOSystem.getActiveContext().getPathUtil().findPath(u, ModelNames.MODEL_GROUP, PARENT_PATH, GroupEnumType.DATA.toString(), orgOf(u));
	}

	private Query byOwner(BaseRecord u) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_SD_CALIBRATION, FieldNames.FIELD_OWNER_ID, idOf(u));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID});
		q.setCache(false);
		return q;
	}

	private int countByOwner(BaseRecord u) {
		return IOSystem.getActiveContext().getAccessPoint().count(u, byOwner(u));
	}

	/// Deletes only what this test can have created: the rows this user owns, then the user's own
	/// ~/Illustration/Calibration and ~/Illustration groups.
	private void cleanup(BaseRecord u) {
		QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(u, byOwner(u));
		if(qr != null && qr.getResults() != null) {
			for(BaseRecord row : qr.getResults()) {
				assertTrue("cleanup: delete " + row.get(FieldNames.FIELD_NAME), IOSystem.getActiveContext().getAccessPoint().delete(u, row));
			}
		}
		BaseRecord dir = findGroup(u);
		if(dir != null) {
			assertTrue("cleanup: delete " + PbCalibrationUtil.CALIBRATION_PATH, IOSystem.getActiveContext().getAccessPoint().delete(u, dir));
		}
		BaseRecord parent = findParent(u);
		if(parent != null) {
			assertTrue("cleanup: delete " + PARENT_PATH, IOSystem.getActiveContext().getAccessPoint().delete(u, parent));
		}
	}
}
