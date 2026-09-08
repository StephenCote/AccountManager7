package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.FeatureConfigUtil;
import org.cote.accountmanager.util.SetupUtil;
import org.junit.Test;

/// Integration tests for SetupUtil.applyInitialFeatures - the persistence step that first-run setup
/// runs to seed an organization's initial UX feature profile. These exercise the util directly against
/// the live database through IOSystem; NO Tomcat/Service7 is involved.
///
/// WHY THIS DOES NOT CALL runSetup: runSetup writes the irreversible one-shot setup marker (and the
/// admin-credential latch), so it can only run once on a given database. Re-running it on the already
/// initialized development DB is impossible without a schema reset, which is forbidden. The feature
/// write was therefore factored out of createInitialUserStep into the package-testable
/// SetupUtil.applyInitialFeatures(orgAdmin, features), which is what these tests drive - identical to
/// the production call, minus the latch.
///
/// THE ADMIN USER IS THE WRITE ACTOR ON PURPOSE, NOT A SHORTCUT. createInitialUserStep persists the
/// profile as the INITIAL USER'S ORG ADMIN (userOctx.getAdminUser()), never the /System admin and never
/// the freshly-created interactive user (which holds only AccountUsers+Requesters). The helper's whole
/// contract is "given the org admin, write the org's feature config", so the org admin necessarily
/// appears here as the write actor, exactly as TestFeatureConfigUtil documents for its own setup actor.
/// The ASSERTION SUBJECT is never the admin: every "the profile took effect" assertion reads through a
/// DIFFERENT, plain non-admin user in the same organization, which is the only thing that proves the
/// write is org-scoped and visible to a normal user (llm-conduct rule 3).
///
/// A DEDICATED STABLE SUB-ORGANIZATION (/Development/SetupFeatures) isolates these feature-config writes
/// from the shared /Development org that TestFeatureConfigUtil and others use. It is created once and
/// reused on subsequent runs.
public class TestSetupInitialFeatures extends BaseTest {

	private static final String FEATURE_ORG_PATH = "/Development/SetupFeatures";
	/// "setupfeatreader" carries a long alphanumeric run so the system.user $minLen5 rule (which is a
	/// "five consecutive alphanumerics" check, not a length check) passes and getCreateUser is non-null.
	private static final String READER_NAME = "setupfeatreader";

	/// Mirrors FeatureConfigUtil.READ_REQUEST (private there). compressionType is load-bearing: without
	/// it ByteModelUtil.getValue cannot know a payload was gzipped.
	private static final String[] READ_REQUEST = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
		FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
		FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_BYTE_STORE
	};

	private OrganizationContext featureOrg() {
		OrganizationContext octx = getTestOrganization(FEATURE_ORG_PATH);
		assertNotNull("Could not resolve the feature test organization " + FEATURE_ORG_PATH, octx);
		assertTrue("Feature test organization is not initialized", octx.isInitialized());
		return octx;
	}

	/// The organization admin - the production write actor for applyInitialFeatures. Setup actor only.
	private BaseRecord orgAdmin() {
		BaseRecord admin = featureOrg().getAdminUser();
		assertNotNull("Feature test organization has no administrator", admin);
		return admin;
	}

	/// A plain non-admin user in the same organization. Auto-enrolled in AccountUsers at creation, which
	/// is the role FeatureConfigUtil's /Library/Configuration read grant keys on. This is the assertion
	/// subject: if IT sees the saved profile, the write is genuinely org-scoped.
	private BaseRecord reader() {
		BaseRecord user = getCreateUser(READER_NAME, featureOrg());
		assertNotNull("Reader user is null - check the $minLen5 rule against '" + READER_NAME + "'", user);
		assertNotNull("Reader has no organizationId", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		return user;
	}

	private long orgId() {
		return orgAdmin().get(FieldNames.FIELD_ORGANIZATION_ID);
	}

	private BaseRecord findConfigDir(BaseRecord user) {
		return IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_GROUP,
			FeatureConfigUtil.LIBRARY_PATH_CONFIGURATION, GroupEnumType.DATA.toString(),
			user.get(FieldNames.FIELD_ORGANIZATION_ID));
	}

	private BaseRecord findConfigRecord(BaseRecord user) {
		BaseRecord dir = findConfigDir(user);
		if(dir == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_NAME, FeatureConfigUtil.CONFIG_NAME);
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, (long)user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(READ_REQUEST);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/// Return the org to a "no stored feature config" state and invalidate the per-org cache, so a test
	/// controls its own precondition regardless of execution order. Deletes as the org admin (only the
	/// admin/owner may delete). The /Library/Configuration group may already exist from a prior test -
	/// only the record is removed, which is exactly the "absent record" state.
	private void clearConfig() {
		BaseRecord admin = orgAdmin();
		BaseRecord existing = findConfigRecord(admin);
		if(existing != null) {
			assertTrue("Failed to delete the existing feature configuration record",
				IOSystem.getActiveContext().getAccessPoint().delete(admin, existing));
		}
		FeatureConfigUtil.invalidate(orgId());
		assertNull("The feature configuration record still exists after clear", findConfigRecord(admin));
	}

	/// (a) A real features list, applied as the org admin, is resolved (core forced, deps closed, unknown
	/// dropped) and persisted org-wide, visible to a DIFFERENT plain user.
	///
	/// The input deliberately: (1) omits a dependency (cardGame without its chat/core deps), (2) includes
	/// "iso42001" as a plain OPAQUE string (nothing in SetupUtil branches on it), and (3) includes a bogus
	/// id. resolveFeatures must force core, close the chat dep, keep the known ids, and drop the bogus one.
	@Test
	public void TestAppliesResolvedFeatureProfile() {
		BaseRecord admin = orgAdmin();
		BaseRecord reader = reader();
		clearConfig();

		List<String> input = Arrays.asList("cardGame", "iso42001", "bogusFeatureXYZ");
		List<String> expected = FeatureConfigUtil.resolveFeatures(input);

		/// Prove the input actually exercises the three behaviors before asserting persistence.
		assertTrue("core must be force-included", expected.contains(FeatureConfigUtil.FEATURE_CORE));
		assertTrue("the chat dependency of cardGame must be closed", expected.contains("chat"));
		assertTrue("cardGame must survive", expected.contains("cardGame"));
		assertTrue("iso42001 is an opaque known id and must survive", expected.contains("iso42001"));
		assertFalse("an unknown id must be dropped", expected.contains("bogusFeatureXYZ"));
		assertFalse("the resolved set must differ from the default profile or this test is vacuous",
			expected.equals(FeatureConfigUtil.getDefaultFeatures()));

		assertTrue("applyInitialFeatures must return true when the org admin persists a real profile",
			SetupUtil.applyInitialFeatures(admin, input));

		/// A DIFFERENT, plain non-admin user in the same org must see exactly the resolved set.
		assertEquals("The saved initial feature profile was not visible org-wide to a plain user",
			expected, FeatureConfigUtil.getEnabledFeatures(reader));

		clearConfig();
	}

	/// (b) An empty or null features list is a clean no-op: the helper returns true, writes NO record, and
	/// the organization keeps resolving to the default profile.
	@Test
	public void TestEmptyOrNullFeaturesIsCleanNoOp() {
		BaseRecord admin = orgAdmin();
		BaseRecord reader = reader();
		clearConfig();

		assertTrue("an empty features list must be a clean no-op returning true",
			SetupUtil.applyInitialFeatures(admin, new ArrayList<String>()));
		assertNull("an empty features list must not write a feature configuration record",
			findConfigRecord(admin));

		assertTrue("a null features list must be a clean no-op returning true",
			SetupUtil.applyInitialFeatures(admin, null));
		assertNull("a null features list must not write a feature configuration record",
			findConfigRecord(admin));

		/// With no record written, resolution for a plain user is the default profile.
		FeatureConfigUtil.invalidate(orgId());
		assertEquals("with no stored record the organization must resolve to the default profile",
			FeatureConfigUtil.getDefaultFeatures(), FeatureConfigUtil.getEnabledFeatures(reader));
	}

	/// (c) The helper is non-fatal by construction and gates the write on having a real org admin. A null
	/// admin with a non-empty list returns false (never throws), and a null admin with an empty list still
	/// short-circuits to the no-op true. This is the testable slice of "only run when a user was actually
	/// created": createInitialUserStep never reaches applyInitialFeatures unless a user was created, and
	/// even a null actor here can only produce a warning, never an exception that could abort setup.
	@Test
	public void TestNullOrgAdminIsNonFatal() {
		assertFalse("a null org admin with a real features list must return a non-fatal false, never throw",
			SetupUtil.applyInitialFeatures(null, Arrays.asList("core", "chat")));
		assertTrue("an empty features list short-circuits to a no-op even with a null admin",
			SetupUtil.applyInitialFeatures(null, new ArrayList<String>()));
		assertTrue("a null features list short-circuits to a no-op even with a null admin",
			SetupUtil.applyInitialFeatures(null, null));
	}
}
