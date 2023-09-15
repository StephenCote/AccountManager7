package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.cote.accountmanager.factory.CryptoFactory;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.security.TokenService;
import org.junit.Test;

public class TestToken extends BaseTest {
	@Test
	public void TestJWTToken() {
		Factory mf = ioContext.getFactory();
		boolean error = false;
		try {
			BaseRecord org = mf.makeOrganization("/Development", OrganizationEnumType.DEVELOPMENT, 0L);
			String key = CryptoFactory.getInstance().randomKey(16);
			BaseRecord testMember = mf.getCreateUser(orgContext.getAdminUser(), "testMember - " + key, org.get(FieldNames.FIELD_ID));
			CryptoBean bean = TokenService.getCreateCipher(testMember);
			assertNotNull("Bean is null", bean);
			logger.info(bean.toFullString());

		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
			error = true;
		}
		assertFalse("An error was encountered", error);

	}
	
}
