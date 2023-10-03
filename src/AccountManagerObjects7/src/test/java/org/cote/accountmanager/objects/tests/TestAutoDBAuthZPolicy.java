package org.cote.accountmanager.objects.tests;

import org.cote.accountmanager.policy.PolicyUtil;
import org.junit.Test;

public class TestAutoDBAuthZPolicy extends BaseTest{

	@Test
	public void TestAuthZGen() {
		String policyBase = ioContext.getPolicyUtil().getPolicyBase(PolicyUtil.POLICY_SYSTEM_READ_OBJECT);
		logger.info(policyBase);
	}
	
}
