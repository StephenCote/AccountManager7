package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.type.ConditionEnumType;
import org.cote.accountmanager.util.ClassGenerator;
import org.junit.Test;

public class TestGenerator extends BaseTest{
	@Test
	public void TestGenerate() {

		try {
			ClassGenerator.generateClass(RecordFactory.GENERATED_PACKAGE_NAME, "function");
			ClassGenerator.generateClass(RecordFactory.GENERATED_PACKAGE_NAME, "operation");
			ClassGenerator.generateClass(RecordFactory.GENERATED_PACKAGE_NAME, "policy");
			ClassGenerator.generateClass(RecordFactory.GENERATED_PACKAGE_NAME, "policyDefinition");
			ClassGenerator.generateClass(RecordFactory.GENERATED_PACKAGE_NAME, "policyRequest");
			ClassGenerator.generateClass(RecordFactory.GENERATED_PACKAGE_NAME, "policyResponse");
		} catch (Exception e) {
			
		}

	}
	
	@Test
	public void TestGenerated() {
		
		resetIO(null);
		BaseRecord policy = null;
		try {
			policy = ioContext.getPolicyUtil().getResourcePolicy("systemReadObject", orgContext.getAdminUser(), null, orgContext.getAdminUser());
		} catch (ReaderException e) {
			logger.error(e);
		}
		assertNotNull("Policy is null", policy);
		PolicyType pol = new PolicyType(policy);
		
		logger.info("Test: " + pol.getName() + " / " + pol.getCondition());
		
		PolicyType pol2 = new PolicyType();
		pol2.setName("Demo concrete policy construct");
		pol2.setCondition(ConditionEnumType.ANY);
	}
}
