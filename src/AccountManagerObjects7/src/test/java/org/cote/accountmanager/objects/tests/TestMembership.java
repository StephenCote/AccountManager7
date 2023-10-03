package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.junit.Test;

public class TestMembership extends BaseTest {
	
	@Test
	public void TestMembershipList() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Test Membership");
		
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord testUser2 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser2", testOrgContext.getOrganizationId());
		BaseRecord testUser3 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser3", testOrgContext.getOrganizationId());
		setupAccountReader(testUser1);
		setupAccountReader(testUser3);
		BaseRecord crole1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_ROLE, "~/MemberRoles/Lister", "USER", testOrgContext.getOrganizationId());
		BaseRecord crole3 = ioContext.getPathUtil().makePath(testUser3, ModelNames.MODEL_ROLE, "~/MemberRoles/Lister", "USER", testOrgContext.getOrganizationId());
		
		BaseRecord rec = IOSystem.getActiveContext().getPathUtil().findPath(testUser1, ModelNames.MODEL_ROLE, "~/", RoleEnumType.USER.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Role is null", rec);
		logger.info(rec.toFullString());
		boolean canRead = ioContext.getPolicyUtil().readPermitted(testUser1, testUser1, null, rec);
		logger.info("Can read: " + canRead);
		Query qp = QueryUtil.buildQuery(testUser1, ModelNames.MODEL_ROLE, rec.get(FieldNames.FIELD_OBJECT_ID), null, 0L, 0);
		assertNotNull("Query is null", qp);

		ioContext.getAccessPoint().member(testUser1, crole1, testUser2, null, false);
		boolean mem1 = ioContext.getAccessPoint().member(testUser1, crole1, testUser2, null, true);
		assertTrue("Expected to set membership", mem1);

		/// Cycling through membership by first returning to false - when checking the return value of the member function, it only returns true when the value changed, not an indication of status
		///
		ioContext.getAccessPoint().member(testUser3, crole3, testUser1, null, false);
		ioContext.getAccessPoint().member(testUser3, crole3, testUser1, null, true);
		ioContext.getAccessPoint().member(testUser3, crole3, testUser2, null, false);
		ioContext.getAccessPoint().member(testUser3, crole3, testUser2, null, true);
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_USER);
		q.filterParticipation(crole1, null, ModelNames.MODEL_USER, null);
		logger.info(q.key());
		
		QueryResult qr = ioContext.getAccessPoint().list(testUser1, q);
		assertNotNull("Result was null", qr);
		logger.info("Test direct list of query - result count: " + qr.getResults().length);
		assertTrue("Expected one member", qr.getResults().length == 1);
		
		logger.info("Test listMembers interface, which constructs the query");
		int count = ioContext.getAccessPoint().countMembers(testUser1, crole1, ModelNames.MODEL_USER, null);
		logger.info("Count: " + count);
		assertTrue("Expected count to be 1", count == 1);
		List<BaseRecord> recs = ioContext.getAccessPoint().listMembers(testUser1, crole1, ModelNames.MODEL_USER, null, 0, 10);
		assertTrue("Expected one member", recs.size() == 1);
	}
	
	private void setupAccountReader(BaseRecord user) {
		OrganizationContext ctx = ioContext.getOrganizationContext(user.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		BaseRecord urole = AccessSchema.getSystemRole(AccessSchema.ROLE_ACCOUNT_USERS_READERS, RoleEnumType.USER.toString(), ctx.getOrganizationId());
		assertNotNull("User Reader role is null", urole);
		ioContext.getAccessPoint().member(
				ctx.getAdminUser(),
				urole,
				user, null, true
		);
		assertTrue("Expected user to be a member of the role", IOSystem.getActiveContext().getAccessPoint().isMember(ctx.getAdminUser(), urole, user));
	}

	@Test
	public void TestMemberIndex() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Test Membership");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord testUser2 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser2", testOrgContext.getOrganizationId());
		BaseRecord testUser3 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser3", testOrgContext.getOrganizationId());

		setupAccountReader(testUser1);

		/*
		BaseRecord urole = AccessSchema.getSystemRole(AccessSchema.ROLE_ACCOUNT_USERS_READERS, RoleEnumType.USER.toString(), testOrgContext.getOrganizationId());
		assertNotNull("User Reader role is null", urole);
		ioContext.getAccessPoint().member(
				testOrgContext.getAdminUser(),
				urole,
				testUser1, null, true
		);
		assertTrue("Expected user to be a member of the role", IOSystem.getActiveContext().getAccessPoint().isMember(testOrgContext.getAdminUser(), urole, testUser1));
		*/
		
		BaseRecord crole1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_ROLE, "~/MemberRoles/Reader", "USER", testOrgContext.getOrganizationId());
		BaseRecord crole2p = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_ROLE, "~/MemberRoles/Parent", "USER", testOrgContext.getOrganizationId());
		BaseRecord crole2c = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_ROLE, "~/MemberRoles/Parent/Child", "USER", testOrgContext.getOrganizationId());
		assertNotNull("Role is null", crole2c);
		
		PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canRead(testUser1, testUser1, testUser2);
		assertTrue("Expected user1 to be able to read user2", prr.getType() == PolicyResponseEnumType.PERMIT);

		PolicyResponseType prr2 = IOSystem.getActiveContext().getAuthorizationUtil().canRead(testUser2, testUser2, testUser1);
		assertTrue("Expected user2 to not be able to read user1", prr2.getType() == PolicyResponseEnumType.DENY);

		
		ioContext.getAccessPoint().member(testUser1, crole2c, testUser2, null, false);
		ioContext.getAccessPoint().member(testUser1, crole2c, testUser3, null, false);
		boolean mem1 = ioContext.getAccessPoint().member(testUser1, crole2c, testUser2, null, true);
		assertTrue("Expected to set membership", mem1);
		mem1 = ioContext.getAccessPoint().member(testUser1, crole2c, testUser3, null, true);
		assertTrue("Expected to set membership", mem1);
		
		List<BaseRecord> mems = new ArrayList<>();
		try {
			mems = ioContext.getMemberUtil().findMembers(crole2c, null, ModelNames.MODEL_USER, 0L);
		} catch (IndexException | ReaderException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		assertTrue("Expected 2 members", mems.size() == 2);
	
	}


	
	
	
}
