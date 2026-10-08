package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.AuthorizationService;
import org.cote.rest.services.CacheService;
import org.cote.rest.services.PrincipalService;
import org.cote.service.util.ServiceUtil;
import org.cote.accountmanager.data.security.UserPrincipal;
import org.junit.Before;
import org.junit.Test;

import jakarta.ws.rs.core.Response;

/// KI-77 (diagnosed under KI-4, "Correction, same day"): Service7 PrincipalService.profiles is a static per-URN
/// map of the whole application profile, filled on the first GET /rest/principal/application and read from
/// thereafter. Until 2026-10-07 nothing evicted it - CacheService.clearCaches() did not call
/// PrincipalService.clearCache(), and the membership endpoint did not evict the actor - so a role grant or
/// removal made after a user's first profile fetch was invisible to the UI (userRoles, hence every
/// page.context().roles gate) until Tomcat restarted.
///
/// This exercises the real service methods against the live database through the HttpServletRequestMock +
/// UserPrincipal pattern (TestAccessRequestService). The propagation bound being proven is IN-PROCESS: a
/// membership written through this Service7 instance evicts this instance's cache. A grant made by another
/// JVM (Console7, a direct Objects7 client) is not seen until /rest/cache/clearAll or a restart; nothing here
/// claims otherwise.
///
/// USERS. Two non-admin users in /Development. The org admin is only the setup actor that creates them and
/// grants the grantor AccountUsersReaders (needed to resolve the subject by objectId through the endpoint).
public class TestPrincipalProfileCache extends BaseTest {

	private static final String GRANTOR_NAME = "ppcGrantor";
	private static final String SUBJECT_NAME = "ppcSubject";
	private static final String ROLE_PATH = "~/ProfileCache/Watchers";

	private final PrincipalService principalService = new PrincipalService();
	private final AuthorizationService authorizationService = new AuthorizationService();

	private BaseRecord grantor;
	private BaseRecord subject;
	private BaseRecord role;
	private long orgId;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		orgId = orgContext.getOrganizationId();
		BaseRecord admin = orgContext.getAdminUser();

		grantor = getCreateUser(GRANTOR_NAME);
		subject = getCreateUser(SUBJECT_NAME);
		assertNotNull("Grantor is null", grantor);
		assertNotNull("Subject is null", subject);

		BaseRecord accountUsersReaders = AccessSchema.getSystemRole(AccessSchema.ROLE_ACCOUNT_USERS_READERS, RoleEnumType.USER.toString(), orgId);
		assertNotNull("AccountUsersReaders is null", accountUsersReaders);
		if(!ioContext.getMemberUtil().isMember(grantor, accountUsersReaders, null)) {
			assertTrue("Failed to grant AccountUsersReaders to the grantor", ioContext.getMemberUtil().member(admin, accountUsersReaders, grantor, null, true));
		}

		/// A user-defined role the grantor owns, so the membership write below is a plain PBAC-permitted
		/// update on the grantor's own object - no admin involved in anything under test.
		role = ioContext.getPathUtil().makePath(grantor, ModelNames.MODEL_ROLE, ROLE_PATH, RoleEnumType.USER.toString(), orgId);
		assertNotNull("Role was not created", role);

		/// Start from a known state regardless of what an earlier run left behind.
		if(ioContext.getMemberUtil().isMember(subject, role, null)) {
			assertTrue("Could not reset the subject's membership", ioContext.getMemberUtil().member(grantor, role, subject, null, false));
		}
		PrincipalService.clearCache();
	}

	private HttpServletRequestMock requestAs(BaseRecord user) {
		return new HttpServletRequestMock(new UserPrincipal((String) user.get(FieldNames.FIELD_NAME), organizationPath));
	}

	@SuppressWarnings("unchecked")
	private List<String> profileRoleObjectIds(BaseRecord user) {
		Response r = principalService.getApplicationProfile(requestAs(user));
		assertEquals(200, r.getStatus());
		assertNotNull("Profile body is null", r.getEntity());
		Map<String, Object> profile = JSONUtil.importObject(r.getEntity().toString(), LinkedHashMap.class);
		assertNotNull("Profile did not parse", profile);
		List<Map<String, Object>> userRoles = (List<Map<String, Object>>) profile.get(FieldNames.FIELD_USER_ROLES);
		assertNotNull("Profile has no userRoles", userRoles);
		return userRoles.stream().map(m -> (String) m.get(FieldNames.FIELD_OBJECT_ID)).collect(Collectors.toList());
	}

	private boolean setMembership(boolean enable) {
		Response r = authorizationService.enableMember(ModelNames.MODEL_ROLE, role.get(FieldNames.FIELD_OBJECT_ID), null,
			ModelNames.MODEL_USER, subject.get(FieldNames.FIELD_OBJECT_ID), enable, requestAs(grantor));
		assertEquals(200, r.getStatus());
		return Boolean.TRUE.equals(r.getEntity());
	}

	@Test
	public void TestProfileReflectsMembershipChangeWithoutRestart() {
		String roleObjectId = role.get(FieldNames.FIELD_OBJECT_ID);

		/// First fetch fills the cache and does not list the role.
		List<String> before = profileRoleObjectIds(subject);
		assertFalse("Precondition: the subject must not already hold the role", before.contains(roleObjectId));
		assertTrue("The first fetch must populate the profile cache", PrincipalService.isProfileCached(subject));

		/// Grant through the real endpoint as the role's owner. The write is PBAC-checked; the eviction is the fix.
		assertTrue("Grantor must be able to add the subject to a role the grantor owns", setMembership(true));
		assertTrue("Control: the participation must exist in the database", ioContext.getMemberUtil().isMember(subject, role, null));
		assertFalse("A membership change must evict the subject's cached profile", PrincipalService.isProfileCached(subject));

		/// The next fetch - same Tomcat lifetime, no restart, no clearAll - sees the grant.
		List<String> afterGrant = profileRoleObjectIds(subject);
		assertTrue("The profile fetched after the grant must list the new role (KI-7)", afterGrant.contains(roleObjectId));
		assertTrue(PrincipalService.isProfileCached(subject));

		/// And the removal.
		assertTrue("Grantor must be able to remove the subject", setMembership(false));
		assertFalse("A membership removal must evict the subject's cached profile", PrincipalService.isProfileCached(subject));
		List<String> afterRemove = profileRoleObjectIds(subject);
		assertFalse("The profile fetched after the removal must no longer list the role", afterRemove.contains(roleObjectId));
	}

	@Test
	public void TestClearAllDropsProfileCache() {
		profileRoleObjectIds(subject);
		assertTrue(PrincipalService.isProfileCached(subject));
		/// /rest/cache/clearAll is the documented remedy for a grant made outside this JVM; it must actually reach this map.
		CacheService.clearCaches();
		assertFalse("clearCaches() must drop the application profile cache", PrincipalService.isProfileCached(subject));
	}

	@Test
	public void TestEvictionTargetsOnlyTheAffectedUser() {
		profileRoleObjectIds(subject);
		profileRoleObjectIds(grantor);
		assertTrue(PrincipalService.isProfileCached(subject));
		assertTrue(PrincipalService.isProfileCached(grantor));
		PrincipalService.evictProfile(subject);
		assertFalse(PrincipalService.isProfileCached(subject));
		assertTrue("Evicting one user must not drop another user's profile", PrincipalService.isProfileCached(grantor));
		/// A null user is a no-op, not an NPE.
		PrincipalService.evictProfile(null);
	}
}
