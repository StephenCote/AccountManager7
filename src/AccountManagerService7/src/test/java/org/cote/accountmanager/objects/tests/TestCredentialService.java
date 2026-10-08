package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.VerificationEnumType;
import org.cote.accountmanager.security.CredentialUtil;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.rest.services.CredentialService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

/// Regression test for KI-14 / KI-15 (aiDocs/KnownIssues.md), exercised through the real
/// CredentialService.newPrimaryCredential with the HttpServletRequestMock + UserPrincipal pattern
/// (same as TestFeatureConfigService / TestISO42001Service): real service method, real Objects7
/// factory/CredentialUtil, real database; only the HTTP transport is absent.
///
/// KI-14: the endpoint hardcoded the new password to the literal "password" and ignored the request.
/// KI-15: the "replace" branch set a field that does not exist on auth.credential (logged a stack
///        trace), and the non-admin branch read checkCredential from the STORED credential record (no
///        such field there either), so a plain user could never replace their own password.
///
/// The acting user is a fresh NON-admin user; the organization admin is never the subject. Verification
/// uses Factory.verify against CredentialUtil.getLatestCredential, which is exactly the check
/// AM7LoginModule.login performs for a container login - so "authenticates" below means the JAAS
/// login path would accept that password.
public class TestCredentialService extends BaseTest {

	private final CredentialService service = new CredentialService();
	private BaseRecord user;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		/// Fresh user per run so the test never depends on a credential left by a previous run.
		/// $minLen5 = five consecutive alphanumerics, which "credsvc" satisfies.
		String name = "credsvc" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		user = getCreateUser(name);
		assertNotNull("Test user is null", user);
		assertNull("A brand new user must have no credential", CredentialUtil.getLatestCredential(user));
	}

	private HttpServletRequestMock requestAs(BaseRecord u) {
		return new HttpServletRequestMock(new UserPrincipal((String)u.get(FieldNames.FIELD_NAME), organizationPath));
	}

	private String authRequestJson(String newPassword, String currentPassword) {
		StringBuilder sb = new StringBuilder();
		sb.append("{\"schema\":\"").append(ModelNames.MODEL_AUTHENTICATION_REQUEST).append("\"");
		if(newPassword != null) {
			sb.append(",\"credential\":\"").append(BinaryUtil.toBase64Str(newPassword.getBytes())).append("\"");
		}
		sb.append(",\"credentialType\":\"hashed_password\"");
		if(currentPassword != null) {
			sb.append(",\"checkCredential\":\"").append(BinaryUtil.toBase64Str(currentPassword.getBytes())).append("\"");
			sb.append(",\"checkCredentialType\":\"hashed_password\"");
		}
		sb.append("}");
		return sb.toString();
	}

	private boolean call(String newPassword, String currentPassword) {
		String objectId = user.get(FieldNames.FIELD_OBJECT_ID);
		return service.newPrimaryCredential(ModelNames.MODEL_USER, objectId, authRequestJson(newPassword, currentPassword), requestAs(user));
	}

	/// The same verify call AM7LoginModule.login makes.
	private VerificationEnumType authenticate(String password) throws FactoryException {
		BaseRecord cred = CredentialUtil.getLatestCredential(user);
		assertNotNull("Latest credential is null", cred);
		return IOSystem.getActiveContext().getFactory().verify(user, cred, ParameterUtil.newParameterList(FieldNames.FIELD_PASSWORD, password));
	}

	@Test
	public void TestCreateUsesRequestedPasswordNotLiteral() throws FactoryException {
		String first = "Firstpw-" + UUID.randomUUID().toString().substring(0, 6);
		assertTrue("Initial credential create should succeed", call(first, null));

		assertEquals("Requested password must authenticate", VerificationEnumType.VERIFIED, authenticate(first));
		/// KI-14 regression guard: the literal must NOT authenticate.
		assertEquals("Literal \"password\" must not authenticate", VerificationEnumType.NOT_VERIFIED, authenticate("password"));
	}

	@Test
	public void TestNonAdminReplaceRequiresAndHonorsCurrentPassword() throws FactoryException {
		String first = "Firstpw-" + UUID.randomUUID().toString().substring(0, 6);
		String second = "Secondpw-" + UUID.randomUUID().toString().substring(0, 6);
		assertTrue("Initial credential create should succeed", call(first, null));
		assertEquals(VerificationEnumType.VERIFIED, authenticate(first));
		long firstCredId = CredentialUtil.getLatestCredential(user).get(FieldNames.FIELD_ID);

		/// No current credential supplied: must be refused and must not change anything.
		assertFalse("Replace without the current credential must fail", call(second, null));
		assertEquals("Old password must still authenticate after refused replace", VerificationEnumType.VERIFIED, authenticate(first));

		/// Wrong current credential: refused, unchanged.
		assertFalse("Replace with a wrong current credential must fail", call(second, "not-the-password"));
		assertEquals("Old password must still authenticate after wrong-check replace", VerificationEnumType.VERIFIED, authenticate(first));
		assertEquals("New password must not authenticate after refused replace", VerificationEnumType.NOT_VERIFIED, authenticate(second));

		/// Correct current credential: replaced. New authenticates, old and the literal do not.
		assertTrue("Replace with the correct current credential should succeed", call(second, first));
		assertEquals("New password must authenticate", VerificationEnumType.VERIFIED, authenticate(second));
		assertEquals("Old password must no longer authenticate", VerificationEnumType.NOT_VERIFIED, authenticate(first));
		assertEquals("Literal \"password\" must not authenticate", VerificationEnumType.NOT_VERIFIED, authenticate("password"));

		/// KI-15 is covered by the successful replace above: the branch that previously set a nonexistent
		/// field on the stored credential (and NPE'd reading checkCredential from it) now completes and
		/// the new credential is the latest one.
		BaseRecord latest = CredentialUtil.getLatestCredential(user);
		assertNotNull(latest);
		assertTrue("Latest credential must be the replacement, not the original", (long)latest.get(FieldNames.FIELD_ID) != firstCredId);
	}

	@Test
	public void TestMissingCredentialIsRejected() throws FactoryException {
		assertFalse("Empty credential must be rejected", call(null, null));
		assertNull("No credential row may be created for an empty request", CredentialUtil.getLatestCredential(user));

		assertFalse("Non-authenticationRequest body must be rejected", service.newPrimaryCredential(ModelNames.MODEL_USER,
			user.get(FieldNames.FIELD_OBJECT_ID), "{\"schema\":\"system.user\",\"name\":\"x\"}", requestAs(user)));
		assertNull(CredentialUtil.getLatestCredential(user));
	}
}
