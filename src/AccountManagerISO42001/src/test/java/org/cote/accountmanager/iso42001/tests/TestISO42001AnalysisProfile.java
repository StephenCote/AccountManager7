package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import java.util.function.Consumer;

import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.iso42001.engine.ScoringConfig;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.iso42001.util.ScoringConfigMapper;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/**
 * Phase 3a: live-DB exercise of the {@code iso42001.analysisProfile} model (design §2.9)
 * and its binding to the Phase-2 {@code engine.ScoringConfig} via {@link ScoringConfigMapper}.
 *
 * <p>This is the FIRST live-DB exercise of the analysisProfile model + the new
 * {@code testConfig.analysisProfile} foreign column. Schema auto-emits on first IO open
 * (additive, {@code generateNewSchemaOnly}) — no DB change is made here.</p>
 *
 * <p>Org/role/user/shared-group scaffolding, the file-base pointing, and CRUD helpers come
 * from {@link ISO42001BaseTest}. Admin is used only there (to create role users + roles +
 * the shared group); every assertion below runs as a non-admin role user (isoTester /
 * isoReader), including a negative-RBAC denial.</p>
 */
public class TestISO42001AnalysisProfile extends ISO42001BaseTest {

	/** Create + persist an analysisProfile owned by isoTester in the shared group. */
	private BaseRecord createProfile(String name, Consumer<BaseRecord> fieldSetter) {
		BaseRecord rec = newRec(ISO42001ModelNames.MODEL_ANALYSIS_PROFILE);
		set(rec, FieldNames.FIELD_NAME, name);
		set(rec, FieldNames.FIELD_GROUP_ID, sharedGroupId);
		set(rec, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		set(rec, FieldNames.FIELD_OWNER_ID, (long) isoTester.get(FieldNames.FIELD_ID));
		if (fieldSetter != null) {
			fieldSetter.accept(rec);
		}
		BaseRecord created = ioContext.getAccessPoint().create(isoTester, rec);
		assertNotNull("analysisProfile CREATE as isoTester returned null", created);
		return created;
	}

	/** Create + persist a testConfig owned by isoTester, optionally referencing a profile. */
	private BaseRecord createTestConfig(String name, BaseRecord analysisProfile) {
		BaseRecord tc = newRec(ISO42001ModelNames.MODEL_TEST_CONFIG);
		set(tc, FieldNames.FIELD_NAME, name);
		set(tc, FieldNames.FIELD_GROUP_ID, sharedGroupId);
		set(tc, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		set(tc, FieldNames.FIELD_OWNER_ID, (long) isoTester.get(FieldNames.FIELD_ID));
		set(tc, "moduleId", "BIAS");
		if (analysisProfile != null) {
			set(tc, "analysisProfile", analysisProfile);
		}
		BaseRecord created = ioContext.getAccessPoint().create(isoTester, tc);
		assertNotNull("testConfig CREATE as isoTester returned null", created);
		return created;
	}

	// ─────────────────────────────────────────────────────────────────────────
	// 1) CRUD + negative RBAC on iso42001.analysisProfile
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	public void testAnalysisProfileCrudAndRbac() {
		String model = ISO42001ModelNames.MODEL_ANALYSIS_PROFILE;
		String name = "analysisProfile-" + UUID.randomUUID();

		/// CREATE as isoTester (authorized; not owner of the shared group).
		BaseRecord created = createProfile(name, rec -> {
			set(rec, ScoringConfigMapper.FIELD_ALPHA, 0.01);
			set(rec, ScoringConfigMapper.FIELD_BONFERRONI_ENABLED, false);
		});
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("created objectId is null", oid);

		/// READ as the creator (owner-based).
		BaseRecord readByOwner = findByObjectId(isoTester, model, oid);
		assertNotNull("READ as isoTester failed", readByOwner);

		/// READ as isoReader purely via the model-level read role (isoReader owns nothing).
		BaseRecord readByReader = findByObjectId(isoReader, model, oid);
		assertNotNull("READ via ISO42001Readers role failed (model-level RBAC)", readByReader);

		/// PATCH a model field as isoTester. Re-read with a common plan so the record carries
		/// the group/owner context the update policy needs.
		Query pq = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, oid);
		pq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		pq.planCommon(true);
		BaseRecord toPatch = ioContext.getAccessPoint().find(isoTester, pq);
		assertNotNull("re-read for PATCH failed", toPatch);
		set(toPatch, ScoringConfigMapper.FIELD_EFFECT_MEDIUM, 0.7);
		BaseRecord updated = ioContext.getAccessPoint().update(isoTester, toPatch);
		assertNotNull("PATCH as isoTester failed", updated);
		BaseRecord afterPatch = findByObjectId(isoTester, model, oid);
		assertEquals("PATCH did not persist effectMedium", 0.7, (double) afterPatch.get(ScoringConfigMapper.FIELD_EFFECT_MEDIUM), 1e-9);

		/// LIST as isoReader via the coarse model-level read (constrain by name, not groupId).
		Query lq = QueryUtil.createQuery(model, FieldNames.FIELD_NAME, name);
		lq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		QueryResult qr = ioContext.getAccessPoint().list(isoReader, lq);
		assertTrue("LIST returned no records", qr != null && qr.getResults().length > 0);

		/// NEGATIVE RBAC: isoReader (no create role) is denied create.
		BaseRecord bad = newRec(model);
		set(bad, FieldNames.FIELD_NAME, name + "-denied");
		set(bad, FieldNames.FIELD_GROUP_ID, sharedGroupId);
		set(bad, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		set(bad, FieldNames.FIELD_OWNER_ID, (long) isoReader.get(FieldNames.FIELD_ID));
		BaseRecord badCreated = ioContext.getAccessPoint().create(isoReader, bad);
		assertNull("CREATE by unauthorized isoReader MUST be denied", badCreated);

		/// DELETE as the creator (owner-based; a non-admin role user).
		boolean deleted = ioContext.getAccessPoint().delete(isoTester, readByOwner);
		assertTrue("DELETE as isoTester failed", deleted);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// 2) testConfig round-trips its analysisProfile foreign reference
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	public void testTestConfigReferencesAnalysisProfile() {
		String profName = "profile-ref-" + UUID.randomUUID();
		BaseRecord profile = createProfile(profName, rec -> {
			set(rec, ScoringConfigMapper.FIELD_ALPHA, 0.02);
			set(rec, ScoringConfigMapper.FIELD_SCALE_MAX, 100.0);
		});
		String profOid = profile.get(FieldNames.FIELD_OBJECT_ID);
		/// Re-read fully so the foreign reference carries a resolvable id.
		BaseRecord profFull = findByObjectId(isoTester, ISO42001ModelNames.MODEL_ANALYSIS_PROFILE, profOid);
		assertNotNull("profile re-read is null", profFull);

		/// Create a testConfig that references the profile (campaign-wide default).
		BaseRecord tcCreated = createTestConfig("testConfig-" + UUID.randomUUID(), profFull);
		String tcOid = tcCreated.get(FieldNames.FIELD_OBJECT_ID);

		/// Read back with planMost(true) so the foreign analysisProfile is dereferenced.
		BaseRecord tcFull = findByObjectId(isoTester, ISO42001ModelNames.MODEL_TEST_CONFIG, tcOid);
		assertNotNull("testConfig re-read is null", tcFull);
		BaseRecord deref = tcFull.get("analysisProfile");
		assertNotNull("testConfig.analysisProfile foreign reference did not round-trip", deref);
		assertEquals("dereferenced profile objectId mismatch", profOid, deref.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals("dereferenced profile alpha mismatch", 0.02, (double) deref.get(ScoringConfigMapper.FIELD_ALPHA), 1e-9);

		/// Cleanup (owner-based deletes, non-admin).
		ioContext.getAccessPoint().delete(isoTester, tcFull);
		ioContext.getAccessPoint().delete(isoTester, profFull);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// 3) fromRecord(...) reproduces stored values; defaults for unset fields
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	public void testFromRecordReproducesStoredValues() {
		String name = "profile-fields-" + UUID.randomUUID();
		/// Persist a profile with all-NON-default values, then read it back from the DB.
		BaseRecord created = createProfile(name, rec -> {
			set(rec, ScoringConfigMapper.FIELD_ALPHA, 0.01);
			set(rec, ScoringConfigMapper.FIELD_BONFERRONI_ENABLED, false);
			set(rec, ScoringConfigMapper.FIELD_EFFECT_SMALL, 0.3);
			set(rec, ScoringConfigMapper.FIELD_EFFECT_MEDIUM, 0.7);
			set(rec, ScoringConfigMapper.FIELD_ODDS_RATIO_SMALL, 2.0);
			set(rec, ScoringConfigMapper.FIELD_ODDS_RATIO_MEDIUM, 4.0);
			set(rec, ScoringConfigMapper.FIELD_SCALE_MIN, 0.0);
			set(rec, ScoringConfigMapper.FIELD_SCALE_MAX, 100.0);
		});
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord stored = findByObjectId(isoTester, ISO42001ModelNames.MODEL_ANALYSIS_PROFILE, oid);
		assertNotNull("stored profile re-read is null", stored);

		ScoringConfig cfg = ScoringConfigMapper.fromRecord(stored);
		assertEquals(0.01, cfg.getAlpha(), 1e-9);
		assertFalse(cfg.isBonferroniEnabled());
		assertEquals(0.3, cfg.getEffectSmall(), 1e-9);
		assertEquals(0.7, cfg.getEffectMedium(), 1e-9);
		assertEquals(2.0, cfg.getOddsRatioSmall(), 1e-9);
		assertEquals(4.0, cfg.getOddsRatioMedium(), 1e-9);
		assertEquals(0.0, cfg.getScaleMin(), 1e-9);
		assertEquals(100.0, cfg.getScaleMax(), 1e-9);

		ioContext.getAccessPoint().delete(isoTester, stored);

		/// Unset-field path: a sparse record carrying ONLY alpha. Every absent field must
		/// fall back to ScoringConfig.defaults() (model JSON defaults == spec defaults).
		ScoringConfig def = ScoringConfig.defaults();
		BaseRecord sparse;
		try {
			sparse = RecordFactory.model(ISO42001ModelNames.MODEL_ANALYSIS_PROFILE)
				.newInstance(new String[] { ScoringConfigMapper.FIELD_ALPHA });
		} catch (Exception e) {
			throw new RuntimeException("sparse newInstance failed", e);
		}
		assertFalse("sparse record should NOT carry effectMedium", sparse.hasField(ScoringConfigMapper.FIELD_EFFECT_MEDIUM));
		set(sparse, ScoringConfigMapper.FIELD_ALPHA, 0.123);

		ScoringConfig sparseCfg = ScoringConfigMapper.fromRecord(sparse);
		assertEquals("set field should be reproduced", 0.123, sparseCfg.getAlpha(), 1e-9);
		assertEquals(def.isBonferroniEnabled(), sparseCfg.isBonferroniEnabled());
		assertEquals(def.getEffectSmall(), sparseCfg.getEffectSmall(), 1e-9);
		assertEquals(def.getEffectMedium(), sparseCfg.getEffectMedium(), 1e-9);
		assertEquals(def.getOddsRatioSmall(), sparseCfg.getOddsRatioSmall(), 1e-9);
		assertEquals(def.getOddsRatioMedium(), sparseCfg.getOddsRatioMedium(), 1e-9);
		assertEquals(def.getScaleMin(), sparseCfg.getScaleMin(), 1e-9);
		assertEquals(def.getScaleMax(), sparseCfg.getScaleMax(), 1e-9);

		/// Null profile → spec defaults.
		ScoringConfig nullCfg = ScoringConfigMapper.fromRecord(null);
		assertEquals(def.getAlpha(), nullCfg.getAlpha(), 1e-9);
		assertEquals(def.getEffectMedium(), nullCfg.getEffectMedium(), 1e-9);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// 4) Resolver: referenced profile when set, spec defaults when null
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	public void testResolverEffectiveConfig() {
		ScoringConfig def = ScoringConfig.defaults();

		/// (a) testConfig WITH a referenced profile → resolver returns that profile's config.
		String profName = "resolver-profile-" + UUID.randomUUID();
		BaseRecord profile = createProfile(profName, rec -> {
			set(rec, ScoringConfigMapper.FIELD_ALPHA, 0.005);
			set(rec, ScoringConfigMapper.FIELD_ODDS_RATIO_MEDIUM, 3.5);
		});
		BaseRecord profFull = findByObjectId(isoTester, ISO42001ModelNames.MODEL_ANALYSIS_PROFILE,
			(String) profile.get(FieldNames.FIELD_OBJECT_ID));

		BaseRecord tcCreated = createTestConfig("resolver-tc-" + UUID.randomUUID(), profFull);
		BaseRecord tcWith = findByObjectId(isoTester, ISO42001ModelNames.MODEL_TEST_CONFIG,
			(String) tcCreated.get(FieldNames.FIELD_OBJECT_ID));

		ScoringConfig resolved = ScoringConfigMapper.resolve(tcWith);
		assertEquals("resolver should use referenced profile alpha", 0.005, resolved.getAlpha(), 1e-9);
		assertEquals("resolver should use referenced profile oddsRatioMedium", 3.5, resolved.getOddsRatioMedium(), 1e-9);

		/// (b) testConfig WITHOUT a profile → resolver returns spec defaults.
		BaseRecord tc2Created = createTestConfig("resolver-tc-noprofile-" + UUID.randomUUID(), null);
		BaseRecord tcWithout = findByObjectId(isoTester, ISO42001ModelNames.MODEL_TEST_CONFIG,
			(String) tc2Created.get(FieldNames.FIELD_OBJECT_ID));

		ScoringConfig resolvedDefault = ScoringConfigMapper.resolve(tcWithout);
		assertNull("control: testConfig.analysisProfile should be unset", tcWithout.get("analysisProfile"));
		assertEquals("resolver should fall back to spec default alpha", def.getAlpha(), resolvedDefault.getAlpha(), 1e-9);
		assertEquals("resolver should fall back to spec default effectMedium", def.getEffectMedium(), resolvedDefault.getEffectMedium(), 1e-9);

		/// Null testConfig → spec defaults.
		assertEquals(def.getAlpha(), ScoringConfigMapper.resolve(null).getAlpha(), 1e-9);

		/// Cleanup.
		ioContext.getAccessPoint().delete(isoTester, tcWith);
		ioContext.getAccessPoint().delete(isoTester, tcWithout);
		ioContext.getAccessPoint().delete(isoTester, profFull);
	}
}
