package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.service.util.ServiceUtil;
import org.junit.Test;

/// Regression test for the cross-organization principal cache defect (confirmed live 2026-10-05):
/// UserPrincipal.equals/hashCode compared the user NAME only, and ServiceUtil.principalCache is keyed
/// by UserPrincipal, so once /Development/admin had been resolved, a subsequent /System/admin login
/// authenticated correctly but every REST call was served as the /Development user.
///
/// Two layers are covered:
///   1. Pure identity: two principals with the same name and different organizationPath are not equal
///      and do not collide in a HashMap/HashSet (no IO needed beyond schema load).
///   2. The actual cache: ServiceUtil.getPrincipalUser resolves two same-named NON-admin users in two
///      organizations to two distinct user records, in either call order, and still returns the cached
///      record for a repeat lookup of the same (org, name).
public class TestUserPrincipalIdentity extends BaseTest {

	private static final String SHARED_NAME = "pcacheuser1";
	private static final String SECOND_ORG_PATH = "/PrincipalCacheTest";

	@Test
	public void TestSameNameDifferentOrgIsNotEqual() {
		UserPrincipal dev = new UserPrincipal(SHARED_NAME, "/Development");
		UserPrincipal sys = new UserPrincipal(SHARED_NAME, "/System");
		UserPrincipal devAgain = new UserPrincipal(SHARED_NAME, "/Development");
		UserPrincipal devWithId = new UserPrincipal(42L, SHARED_NAME, "/Development");

		assertNotEquals("Same name in different orgs must not be equal", dev, sys);
		assertNotEquals("Same name in different orgs must not be equal (reverse)", sys, dev);
		assertEquals("Same (org, name) must be equal", dev, devAgain);
		assertEquals("Same (org, name) must be equal regardless of id being set", dev, devWithId);
		assertEquals("Equal principals must hash equal", dev.hashCode(), devAgain.hashCode());
		assertNotEquals("Different-org principals must not be equal", dev, new UserPrincipal(SHARED_NAME));

		Map<UserPrincipal, String> map = new HashMap<>();
		map.put(dev, "dev");
		map.put(sys, "sys");
		assertEquals("Map must hold two distinct entries", 2, map.size());
		assertEquals("dev", map.get(devAgain));
		assertEquals("sys", map.get(new UserPrincipal(SHARED_NAME, "/System")));

		Set<UserPrincipal> set = new HashSet<>();
		set.add(dev);
		set.add(sys);
		set.add(devAgain);
		assertEquals("Set must dedupe only the same (org, name)", 2, set.size());
	}

	@Test
	public void TestNullSafeIdentity() {
		UserPrincipal blank = new UserPrincipal();
		UserPrincipal blank2 = new UserPrincipal();
		/// Name unset on both; organizationPath defaults to /Public. Must not NPE.
		assertEquals(blank, blank2);
		assertEquals(blank.hashCode(), blank2.hashCode());
		assertNotEquals(blank, new UserPrincipal(SHARED_NAME));
		assertFalse(blank.equals(null));
		assertFalse(blank.equals("not a principal"));
	}

	@Test
	public void TestPrincipalCacheResolvesPerOrganization() {
		ServiceUtil.clearCache();

		OrganizationContext devOrg = orgContext;
		OrganizationContext otherOrg = getTestOrganization(SECOND_ORG_PATH);
		assertNotNull("Second organization context is null", otherOrg);
		assertTrue("Second organization is not initialized", otherOrg.isInitialized());
		assertNotEquals("Test needs two distinct organizations", devOrg.getOrganizationId(), otherOrg.getOrganizationId());

		/// Non-admin users with the SAME name in two organizations. The org admins act only as creators.
		BaseRecord devUser = getCreateUser(SHARED_NAME, devOrg);
		assertNotNull("Dev user is null", devUser);
		BaseRecord otherUser = getCreateUser(SHARED_NAME, otherOrg);
		assertNotNull("Other-org user is null", otherUser);
		assertNotEquals("Users must be distinct records", (long)devUser.get(FieldNames.FIELD_ID), (long)otherUser.get(FieldNames.FIELD_ID));

		/// Order A: dev first, then other. Before the fix the second call was a cache hit on the first.
		BaseRecord r1 = ServiceUtil.getPrincipalUser(new HttpServletRequestMock(new UserPrincipal(SHARED_NAME, organizationPath)));
		BaseRecord r2 = ServiceUtil.getPrincipalUser(new HttpServletRequestMock(new UserPrincipal(SHARED_NAME, SECOND_ORG_PATH)));
		assertNotNull("Resolved dev user is null", r1);
		assertNotNull("Resolved other-org user is null", r2);
		assertEquals("Dev principal resolved to the wrong user", (long)devUser.get(FieldNames.FIELD_ID), (long)r1.get(FieldNames.FIELD_ID));
		assertEquals("Other-org principal resolved to the wrong user", (long)otherUser.get(FieldNames.FIELD_ID), (long)r2.get(FieldNames.FIELD_ID));
		assertEquals(devOrg.getOrganizationId(), (long)r1.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertEquals(otherOrg.getOrganizationId(), (long)r2.get(FieldNames.FIELD_ORGANIZATION_ID));

		/// Repeat lookups are cache hits for the SAME (org, name) and still return the right record.
		BaseRecord r1b = ServiceUtil.getPrincipalUser(new HttpServletRequestMock(new UserPrincipal(SHARED_NAME, organizationPath)));
		BaseRecord r2b = ServiceUtil.getPrincipalUser(new HttpServletRequestMock(new UserPrincipal(SHARED_NAME, SECOND_ORG_PATH)));
		assertSame("Repeat dev lookup should be served from cache", r1, r1b);
		assertSame("Repeat other-org lookup should be served from cache", r2, r2b);

		/// Order B: clear and resolve the other org first, then dev. Must still be two distinct users.
		ServiceUtil.clearCache();
		BaseRecord s1 = ServiceUtil.getPrincipalUser(new HttpServletRequestMock(new UserPrincipal(SHARED_NAME, SECOND_ORG_PATH)));
		BaseRecord s2 = ServiceUtil.getPrincipalUser(new HttpServletRequestMock(new UserPrincipal(SHARED_NAME, organizationPath)));
		assertNotNull(s1);
		assertNotNull(s2);
		assertEquals((long)otherUser.get(FieldNames.FIELD_ID), (long)s1.get(FieldNames.FIELD_ID));
		assertEquals((long)devUser.get(FieldNames.FIELD_ID), (long)s2.get(FieldNames.FIELD_ID));
		assertNotEquals("Two orgs must never share a cached principal", (long)s1.get(FieldNames.FIELD_ID), (long)s2.get(FieldNames.FIELD_ID));
	}
}
