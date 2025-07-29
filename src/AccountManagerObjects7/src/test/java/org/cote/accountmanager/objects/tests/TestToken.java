package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.factory.CryptoFactory;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.security.TokenService;
import org.junit.Test;

public class TestToken extends BaseTest {
	
	@Test
	public void TestAuthToken() {
		Factory mf = ioContext.getFactory();

		BaseRecord org = mf.makeOrganization("/Development", OrganizationEnumType.DEVELOPMENT, 0L);
		OrganizationContext testContext = IOSystem.getActiveContext().findOrganizationContext(org);
		BaseRecord testUser = mf.getCreateUser(testContext.getAdminUser(), "testAuthTokenUser", org.get(FieldNames.FIELD_ID));

		String outToken = null;
		String outToken2 = null;
		try {
			outToken = TokenService.createJWTToken(testContext.getAdminUser(), testUser, UUID.randomUUID().toString(), TokenService.TOKEN_EXPIRY_1_WEEK);
			outToken2 = TokenService.createJWTToken(testUser, testUser, UUID.randomUUID().toString(), TokenService.TOKEN_EXPIRY_1_WEEK);
		} catch (ReaderException | IndexException e) {
			logger.error(e);
		}
		assertNotNull("Token is null", outToken);
		assertNotNull("Token 2 is null", outToken2);
		String subject = TokenService.validateTokenToSubject(outToken);
		String subject2 = TokenService.validateTokenToSubject(outToken2);
		assertNotNull("Subject is null", subject);
		assertNotNull("Subject 2 is null", subject2);
		assertTrue("Expected subject to match testUser", subject.equals(testUser.get(FieldNames.FIELD_NAME)));
		assertTrue("Expected subject to match testUser", subject2.equals(testUser.get(FieldNames.FIELD_NAME)));
	}
	
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
