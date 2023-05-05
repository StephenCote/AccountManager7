package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.FactType;
import org.cote.accountmanager.objects.generated.PolicyDefinitionType;
import org.cote.accountmanager.objects.generated.PolicyRequestType;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.policy.PolicyDefinitionUtil;
import org.cote.accountmanager.policy.PolicyEvaluator;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.junit.Test;

public class TestMembership extends BaseTest {
	/*
	@Test
	public void TestBlank() {
		logger.info("Blank test");
		logger.info("Use -DtrimStackTrace=false to find stackoverflow in mvn test");
	}
	*/
	/*
	@Test
	public void TestMemberIndex() {

		BaseRecord testMember1 = null;
		BaseRecord homeDir = null;
		BaseRecord testDat1 = null;
		Factory mf = ioContext.getFactory();
		try 
		{
			testMember1 = mf.getCreateUser(orgContext.getAdminUser(), "testMember1", orgContext.getOrganizationId());
			homeDir = ioContext.getReader().read(ModelNames.MODEL_GROUP, (long)testMember1.get(FieldNames.FIELD_HOME_DIRECTORY_FIELD_ID));
					
					//ioContext.getSearch().find(QueryUtil.createIdentityQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_ID, testMember1.get(FieldNames.FIELD_HOME_DIRECTORY_FIELD_ID)));
			
			testDat1 = getCreateData(testMember1, "Test Data - " + UUID.randomUUID().toString(), "text/plain", "This is the demo data".getBytes(), "~/Data/Membership Test", (long)orgContext.getOrganizationId());
		}
		catch(Exception e) {
			logger.error(e);
			
		}
		// logger.info("Has name: " + homeDir.hasField(FieldNames.FIELD_NAME));
		// logger.info(JSONUtil.exportObject(homeDir, RecordSerializerConfig.getUnfilteredModule()));
		// logger.info(JSONUtil.exportObject(testDat1, RecordSerializerConfig.getUnfilteredModule()));
	}
	*/
	
	@Test
	public void TestMemberIndex() {
		logger.error("**** REFACTOR FOR REVISED MEMBER UTIL");
		/*
		BaseRecord testMember1 = null;
		BaseRecord testMember2 = null;
		BaseRecord testMember3 = null;
		Factory mf = ioContext.getFactory();
		try 
		{
			
			testMember1 = mf.getCreateUser(orgContext.getAdminUser(), "testMember1", orgContext.getOrganizationId());
			testMember2 = mf.getCreateUser(orgContext.getAdminUser(), "testMember2", orgContext.getOrganizationId());
			testMember3 = mf.getCreateUser(orgContext.getAdminUser(), "testMember3", orgContext.getOrganizationId());
		}
		catch(Exception e) {
			logger.error(e);
			
		}
		
		
		BaseRecord dat = getCreateData(testMember1, "Test Membership Data 1", "text/plain", "This is the demo data".getBytes(), "~/Data/Membership Test", (long)orgContext.getOrganizationId());
		BaseRecord urole1 = mf.userRole(testMember1);
		BaseRecord uperm1 = mf.userPermission(testMember1);
		assertNotNull("User role is null", urole1);
		assertNotNull("User permission is null", uperm1);
		BaseRecord cperm1 = ioContext.getPathUtil().makePath(testMember1, ModelNames.MODEL_PERMISSION, "~/MemberRights/Read", "USER", orgContext.getOrganizationId());
		BaseRecord crole1 = ioContext.getPathUtil().makePath(testMember1, ModelNames.MODEL_ROLE, "~/MemberRoles/Reader", "USER", orgContext.getOrganizationId());
		BaseRecord crole2p = ioContext.getPathUtil().makePath(testMember1, ModelNames.MODEL_ROLE, "~/MemberRoles/Parent", "USER", orgContext.getOrganizationId());
		BaseRecord crole2c = ioContext.getPathUtil().makePath(testMember1, ModelNames.MODEL_ROLE, "~/MemberRoles/Parent/Child", "USER", orgContext.getOrganizationId());
		
		assertNotNull("Custom permission is null", cperm1);
		// logger.info(JSONUtil.exportObject(cperm1, RecordSerializerConfig.getUnfilteredModule()));
		
		logger.info("Add testMember2 to Reader role");
		ioContext.getMemberUtil().member(testMember1, crole1, testMember2, null, false);
		boolean addMember = ioContext.getMemberUtil().member(testMember1, crole1, testMember2, null, true);
		assertTrue("Expected member to be added", addMember);

		ioContext.getMemberUtil().member(testMember1, crole2p, testMember3, null, false);
		ioContext.getMemberUtil().member(testMember1, crole2p, testMember3, null, true);
		
		logger.info("Give Reader role Read permission to Test Data");
		ioContext.getMemberUtil().member(testMember1, dat, crole1, cperm1, false);
		boolean addRole = ioContext.getMemberUtil().member(testMember1, dat, crole1, cperm1, true);
		assertTrue("Expected role to be added", addRole);
		
		ioContext.getMemberUtil().member(testMember1, dat, crole2c, cperm1, false);
		ioContext.getMemberUtil().member(testMember1, dat, crole2c, cperm1, true);
		
		BaseRecord u2parts = null;
		BaseRecord r1parts = null;
		List<BaseRecord> u2parts2 = new ArrayList<>();
		try {
			u2parts = ioContext.getMemberUtil().getParticipants(testMember2);
			r1parts = ioContext.getMemberUtil().getMembers(crole1);
			u2parts2 = ioContext.getMemberUtil().findMembers(crole1, ModelNames.MODEL_USER, testMember2.get(FieldNames.FIELD_ID));
		} catch (IndexException | ReaderException e) {
			logger.error(e);
			
		}
		assertNotNull("Expected a parts list", u2parts);
		assertNotNull("Expected a parts list", r1parts);
		assertTrue("Expected one find result", u2parts2.size() == 1);
		
		List<BaseRecord> parts = u2parts.get(FieldNames.FIELD_PARTS);
		
		assertTrue("Expected 1 participant and received " + parts.size(), parts.size() == 1);
		BaseRecord part1 = parts.get(0);
		assertTrue("Expect part id to be the role id", part1.get(FieldNames.FIELD_PART_ID).equals(crole1.get(FieldNames.FIELD_ID)));

		List<BaseRecord> parts2 = r1parts.get(FieldNames.FIELD_PARTS);
		assertTrue("Expected 1 participant", parts2.size() == 1);
		BaseRecord part2 = parts2.get(0);
		assertTrue("Expect part id to be the role id", part2.get(FieldNames.FIELD_PART_ID).equals(testMember2.get(FieldNames.FIELD_ID)));
		//logger.info(JSONUtil.exportObject(part2, RecordSerializerConfig.getUnfilteredModule()));
		
		boolean mem1 = ioContext.getMemberUtil().isMember(testMember2, crole1);
		boolean mem2 = ioContext.getMemberUtil().isMember(testMember3, crole1);
		boolean mem3 = ioContext.getMemberUtil().isMember(testMember3, crole2c, true);
		assertTrue("Expected user to be a member", mem1);
		assertFalse("Didn't expect user to be a member", mem2);
		assertTrue("Expected user to be a member", mem3);
		
		boolean check = ioContext.getAuthorizationUtil().checkEntitlement(testMember2, cperm1, dat);
		logger.info("Entitlement check: " + check);
		*/
	}

	
	
	
}
