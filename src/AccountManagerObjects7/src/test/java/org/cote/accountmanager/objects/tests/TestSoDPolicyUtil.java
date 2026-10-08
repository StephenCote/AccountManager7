package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.policy.SoDPolicyUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.junit.Before;
import org.junit.Test;

/// SoDPolicyUtil (separation of duty): ALL and ANY must be distinct, explicit checks.
///
/// The AM7 port of this class was a stub ("Not converted", returned an empty list) carrying an AM6 note that
/// the check was "just ANY" while the commented body cleared the result unless every permission matched -
/// so a caller could not know which semantics it had, and PolicyEvaluator.evaluateSoD treated any non-empty
/// result as a match. This test builds a real activity (auth.group) with two granted permissions and
/// references holding none / a subset / all of them (directly and through a role) and proves:
///   - a subset holder passes ANY and fails ALL
///   - a full holder passes both; a non-holder passes neither
///   - an activity with no granted permissions is fail-closed for both
///
/// All records are owned by a fresh NON-admin user in its own development organization.
public class TestSoDPolicyUtil extends BaseTest {

	private BaseRecord user;
	private long orgId;
	private BaseRecord approvePerm;
	private BaseRecord submitPerm;
	private BaseRecord activity;
	private BaseRecord emptyActivity;
	private BaseRecord noneAccount;
	private BaseRecord subsetAccount;
	private BaseRecord fullAccount;
	private BaseRecord roleAccount;

	@Override
	@Before
	public void setup() {
		super.setup();
		OrganizationContext testOrgContext = getTestOrganization("/Development/SoDPolicy");
		user = getCreateUser("sodPolicyUser1", testOrgContext);
		assertNotNull("Test user is null", user);
		orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);

		String suffix = UUID.randomUUID().toString().substring(0, 8);

		approvePerm = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_PERMISSION, "~/Entitlements/SoD-" + suffix + "/Approve", PermissionEnumType.APPLICATION.toString(), orgId);
		submitPerm = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_PERMISSION, "~/Entitlements/SoD-" + suffix + "/Submit", PermissionEnumType.APPLICATION.toString(), orgId);
		assertNotNull("Approve permission is null", approvePerm);
		assertNotNull("Submit permission is null", submitPerm);

		activity = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Activities/Payments-" + suffix, GroupEnumType.DATA.toString(), orgId);
		emptyActivity = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Activities/Empty-" + suffix, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Activity group is null", activity);
		assertNotNull("Empty activity group is null", emptyActivity);

		noneAccount = ioContext.getFactory().getCreateDirectoryModel(user, ModelNames.MODEL_ACCOUNT, "None-" + suffix, "~/Accounts", orgId);
		subsetAccount = ioContext.getFactory().getCreateDirectoryModel(user, ModelNames.MODEL_ACCOUNT, "Subset-" + suffix, "~/Accounts", orgId);
		fullAccount = ioContext.getFactory().getCreateDirectoryModel(user, ModelNames.MODEL_ACCOUNT, "Full-" + suffix, "~/Accounts", orgId);
		roleAccount = ioContext.getFactory().getCreateDirectoryModel(user, ModelNames.MODEL_ACCOUNT, "Role-" + suffix, "~/Accounts", orgId);
		assertNotNull(noneAccount);
		assertNotNull(subsetAccount);
		assertNotNull(fullAccount);
		assertNotNull(roleAccount);

		BaseRecord submitters = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_ROLE, "~/Roles/Submitters-" + suffix, RoleEnumType.ACCOUNT.toString(), orgId);
		assertNotNull("Submitters role is null", submitters);

		/// subset: Approve only (direct)
		assertTrue(ioContext.getMemberUtil().member(user, activity, subsetAccount, approvePerm, true));
		/// full: Approve + Submit (direct)
		assertTrue(ioContext.getMemberUtil().member(user, activity, fullAccount, approvePerm, true));
		assertTrue(ioContext.getMemberUtil().member(user, activity, fullAccount, submitPerm, true));
		/// role: Approve direct, Submit through the Submitters role
		assertTrue(ioContext.getMemberUtil().member(user, activity, roleAccount, approvePerm, true));
		assertTrue(ioContext.getMemberUtil().member(user, activity, submitters, submitPerm, true));
		assertTrue(ioContext.getMemberUtil().member(user, submitters, roleAccount, null, true));
	}

	private long id(BaseRecord rec) {
		return rec.get(FieldNames.FIELD_ID);
	}

	@Test
	public void TestActivityPermissionsAreTheDistinctGrants() {
		List<Long> perms = SoDPolicyUtil.getActivityPermissions(activity);
		assertEquals("Activity has exactly two distinct permissions (Approve, Submit) regardless of how many grants carry them", 2, perms.size());
		assertTrue(perms.contains(id(approvePerm)));
		assertTrue(perms.contains(id(submitPerm)));

		/// by-urn overload resolves the same group
		String urn = activity.get(FieldNames.FIELD_URN);
		assertNotNull(urn);
		assertEquals(perms, SoDPolicyUtil.getActivityPermissions(urn));

		assertTrue("Activity with no grants has no permissions", SoDPolicyUtil.getActivityPermissions(emptyActivity).isEmpty());
	}

	@Test
	public void TestSubsetHolderPassesAnyFailsAll() {
		List<Long> held = SoDPolicyUtil.getActivityPermissionsForType(activity, subsetAccount);
		assertEquals("Subset holder holds exactly one permission", 1, held.size());
		assertEquals(id(approvePerm), (long)held.get(0));

		assertTrue("ANY must pass for a subset holder", SoDPolicyUtil.hasAnyActivityPermission(activity, subsetAccount));
		assertFalse("ALL must FAIL for a subset holder", SoDPolicyUtil.hasAllActivityPermissions(activity, subsetAccount));
	}

	@Test
	public void TestFullHolderPassesBoth() {
		List<Long> held = SoDPolicyUtil.getActivityPermissionsForType(activity, fullAccount);
		assertEquals(2, held.size());
		assertTrue("ANY must pass for a full holder", SoDPolicyUtil.hasAnyActivityPermission(activity, fullAccount));
		assertTrue("ALL must pass for a full holder", SoDPolicyUtil.hasAllActivityPermissions(activity, fullAccount));

		/// by-urn overloads
		String urn = activity.get(FieldNames.FIELD_URN);
		assertTrue(SoDPolicyUtil.hasAllActivityPermissions(urn, fullAccount));
		assertTrue(SoDPolicyUtil.hasAnyActivityPermission(urn, fullAccount));
	}

	@Test
	public void TestRoleGrantCountsTowardAll() {
		/// Approve is direct, Submit comes only through the Submitters role; ALL must still be satisfied.
		List<Long> held = SoDPolicyUtil.getActivityPermissionsForType(activity, roleAccount);
		assertEquals("Role-mediated grant must be counted", 2, held.size());
		assertTrue(SoDPolicyUtil.hasAllActivityPermissions(activity, roleAccount));
	}

	@Test
	public void TestNonHolderFailsBoth() {
		assertTrue(SoDPolicyUtil.getActivityPermissionsForType(activity, noneAccount).isEmpty());
		assertFalse("ANY must fail for a non-holder", SoDPolicyUtil.hasAnyActivityPermission(activity, noneAccount));
		assertFalse("ALL must fail for a non-holder", SoDPolicyUtil.hasAllActivityPermissions(activity, noneAccount));
	}

	@Test
	public void TestFailClosed() {
		/// An activity that defines no permissions can never be satisfied (ALL would otherwise be vacuously true).
		assertFalse(SoDPolicyUtil.hasAllActivityPermissions(emptyActivity, fullAccount));
		assertFalse(SoDPolicyUtil.hasAnyActivityPermission(emptyActivity, fullAccount));
		/// Unknown activity / null reference
		assertFalse(SoDPolicyUtil.hasAllActivityPermissions("urn:am7:no:such:activity:" + UUID.randomUUID(), fullAccount));
		assertFalse(SoDPolicyUtil.hasAnyActivityPermission(activity, null));
		assertTrue(SoDPolicyUtil.getActivityPermissionsForType((BaseRecord)null, fullAccount).isEmpty());
	}
}
