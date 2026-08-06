package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.UUID;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.security.CredentialUtil;
import org.cote.accountmanager.tools.EmbeddingUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.accountmanager.util.SetupUtil;
import org.cote.accountmanager.util.ValidationUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;
import org.junit.Test;

/**
 * First-run setup feature tests: the DB-resident setup latch and its validators, plus the
 * embedding-width guard that keeps a wrong-width vector out of the fixed-width
 * common.vectorExt.embedding column.
 *
 * These are integration tests against the live dev database. They deliberately do NOT call
 * SetupUtil.runSetup(): runSetup would set the administrator password on /System, /Development and
 * /Public and write the .setupState marker, permanently latching the developer database. Every
 * term of the latch is exercised individually instead.
 */
public class TestSetupUtil extends BaseTest {

	private static final String VALID_PASSWORD = "Test-Passw0rd";

	/// ----------------------------------------------------------------------------------------
	/// 1. THE LATCH
	/// ----------------------------------------------------------------------------------------

	/// The shipped latch must be exactly `markerExists() || adminCredentialExists()` — two
	/// DB-resident terms and nothing else. This asserts the composite equals the OR of its terms
	/// as observed against the live database in the same JVM.
	@Test
	public void testLatchIsTheOrOfItsTwoTerms() {
		boolean marker = SetupUtil.markerExists();
		boolean cred = SetupUtil.adminCredentialExists();
		boolean complete = SetupUtil.isSetupComplete();
		logger.info("LATCH: markerExists=" + marker + " adminCredentialExists=" + cred + " isSetupComplete=" + complete);
		assertEquals("isSetupComplete() must be exactly markerExists() || adminCredentialExists()",
			(marker || cred), complete);
	}

	/// REGRESSION GUARD for the "allOrgsInitialized" term that was dead on arrival.
	///
	/// An organization-initialized term fails CLOSED on a database whose organizations exist but
	/// whose administrator has no password — the operator is then stranded with no way to set one.
	/// This asserts the latch does not consult isInitialized(): every default organization reports
	/// initialized, and the latch answer still tracks marker||credential rather than that fact.
	@Test
	public void testLatchDoesNotConsultOrganizationInitialization() {
		int initialized = 0;
		for(String org : OrganizationContext.DEFAULT_ORGANIZATIONS) {
			OrganizationContext octx = ioContext.getOrganizationContext(org,
				OrganizationEnumType.valueOf(org.substring(1).toUpperCase()));
			assertNotNull("Organization context for " + org + " should not be null", octx);
			logger.info("ORG " + org + " initialized=" + octx.isInitialized()
				+ " adminUser=" + (octx.getAdminUser() != null));
			if(octx.isInitialized()) {
				initialized++;
			}
		}
		assertEquals("All three default organizations should be initialized on this database",
			OrganizationContext.DEFAULT_ORGANIZATIONS.length, initialized);

		boolean marker = SetupUtil.markerExists();
		boolean cred = SetupUtil.adminCredentialExists();
		assertEquals("The latch must ignore organization initialization and track only marker||credential",
			(marker || cred), SetupUtil.isSetupComplete());
	}

	/// isRealCredential must reject the UNKNOWN-type row that CredentialFactory produces when a
	/// password/type pair was not supplied (CredentialFactory.java:48-61) — such a row has a null
	/// credential value and cannot authenticate anyone, so it must never satisfy the latch.
	/// The UNKNOWN row here is built by the REAL production factory route, not hand-forged, and is
	/// never persisted.
	@Test
	public void testIsRealCredentialRejectsUnknownType() {
		OrganizationContext octx = getTestOrganization("/Development/Setup");
		BaseRecord user = getCreateUser("setupCredProbe", octx);
		assertNotNull("Probe user should not be null", user);

		assertFalse("A null credential is not a real credential", SetupUtil.isRealCredential(null));

		BaseRecord unknown = null;
		try {
			/// Type supplied but NO password: CredentialFactory leaves the row at UNKNOWN.
			ParameterList plist = ParameterUtil.newParameterList(FieldNames.FIELD_TYPE,
				CredentialEnumType.HASHED_PASSWORD.toString().toLowerCase());
			unknown = ioContext.getFactory().newInstance(ModelNames.MODEL_CREDENTIAL, user, null, plist);
		}
		catch(Exception e) {
			fail("Failed to build an UNKNOWN credential via the production factory: " + e.getMessage());
		}
		assertNotNull("Factory should have produced a credential record", unknown);
		CredentialEnumType cet = unknown.getEnum(FieldNames.FIELD_TYPE);
		assertEquals("A credential built with no password must be UNKNOWN", CredentialEnumType.UNKNOWN, cet);
		/// It cannot authenticate anyone: no digest was computed and no salt was set.
		byte[] value = unknown.get(FieldNames.FIELD_CREDENTIAL);
		logger.info("UNKNOWN credential value length=" + (value == null ? -1 : value.length)
			+ " hash=" + unknown.get(FieldNames.FIELD_HASH));
		assertTrue("An UNKNOWN credential carries no usable credential value",
			value == null || value.length == 0);
		assertNull("An UNKNOWN credential has no hash/salt", unknown.get(FieldNames.FIELD_HASH));
		assertFalse("isRealCredential must REJECT an UNKNOWN-type credential row",
			SetupUtil.isRealCredential(unknown));
	}

	/// The positive half: a genuine HASHED_PASSWORD credential written through the bootstrap path,
	/// read back out of the database, satisfies isRealCredential.
	/// Also the drive-by denial-of-provisioning fix: createBootstrapCredential must refuse an empty
	/// password and leave NO credential row behind (CredentialFactory only checks `pwd != null`, so
	/// "" would otherwise become a real HASHED_PASSWORD over the empty string).
	@Test
	public void testBootstrapCredentialRejectsEmptyAndAcceptsValid() {
		OrganizationContext octx = getTestOrganization("/Development/Setup");
		String name = "setupUser" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
		BaseRecord user = getCreateUser(name, octx);
		assertNotNull("Fresh test user should not be null", user);
		assertNull("A brand new user must have no credential yet", CredentialUtil.getLatestCredential(user));

		assertFalse("An empty password must be refused", SetupUtil.createBootstrapCredential(user, ""));
		assertFalse("A whitespace-only password must be refused", SetupUtil.createBootstrapCredential(user, "     "));
		assertFalse("A null password must be refused", SetupUtil.createBootstrapCredential(user, null));
		assertFalse("A too-short password must be refused", SetupUtil.createBootstrapCredential(user, "Abc123!"));
		assertNull("No credential row may exist after the refused attempts — an empty password must"
			+ " not be able to latch setup closed", CredentialUtil.getLatestCredential(user));

		assertTrue("A valid password must produce a credential",
			SetupUtil.createBootstrapCredential(user, VALID_PASSWORD));
		BaseRecord cred = CredentialUtil.getLatestCredential(user);
		assertNotNull("The credential must be readable back out of the database", cred);
		assertEquals("The persisted credential must be HASHED_PASSWORD",
			CredentialEnumType.HASHED_PASSWORD, cred.getEnum(FieldNames.FIELD_TYPE));
		assertTrue("isRealCredential must ACCEPT a persisted HASHED_PASSWORD credential",
			SetupUtil.isRealCredential(cred));
	}

	/// ----------------------------------------------------------------------------------------
	/// 2. VALIDATORS
	/// ----------------------------------------------------------------------------------------

	@Test
	public void testValidatePassword() {
		assertNotNull("null must be rejected", SetupUtil.validatePassword(null));
		assertNotNull("empty must be rejected", SetupUtil.validatePassword(""));
		assertNotNull("whitespace-only must be rejected", SetupUtil.validatePassword("   "));
		assertNotNull("tab/newline-only must be rejected", SetupUtil.validatePassword("\t\n "));
		assertNotNull("1 char must be rejected", SetupUtil.validatePassword("a"));
		assertNotNull("7 chars must be rejected (minimum is " + SetupUtil.MIN_PASSWORD_LENGTH + ")",
			SetupUtil.validatePassword("Abc123!"));
		assertEquals("The boundary is 8", 8, SetupUtil.MIN_PASSWORD_LENGTH);
		assertNull("8 chars must be accepted", SetupUtil.validatePassword("Abc1234!"));
		assertNull("A normal password must be accepted", SetupUtil.validatePassword(VALID_PASSWORD));
	}

	/// THE TWO PASSWORD CONTRACTS ARE DELIBERATELY DIFFERENT, and both halves must hold.
	///
	/// The setup paths (first-run REST setup, repairProvisioning) impose an 8-character minimum
	/// introduced by this feature. `-addUser` is a day-2 CLI operation whose pre-existing contract
	/// had NO minimum, so imposing 8 there would itself be a compatibility break; it refuses only
	/// null/blank via validatePasswordPresent. A 6-character password is the discriminating case.
	@Test
	public void testAddUserThresholdIsWeakerThanTheSetupThreshold() {
		String sixChars = "abc123";
		assertNotNull("The SETUP paths must REJECT a 6-character password",
			SetupUtil.validatePassword(sixChars));
		assertNull("The -addUser path must ACCEPT a 6-character password (pre-existing contract)",
			SetupUtil.validatePasswordPresent(sixChars));

		/// Both contracts still refuse null/blank — that is the shared floor.
		for(String bad : new String[] { null, "", "   ", "	" }) {
			assertNotNull("validatePassword must refuse [" + bad + "]", SetupUtil.validatePassword(bad));
			assertNotNull("validatePasswordPresent must refuse [" + bad + "]",
				SetupUtil.validatePasswordPresent(bad));
		}
		/// And validatePasswordPresent imposes no length rule at all.
		assertNull("validatePasswordPresent must accept a 1-character password",
			SetupUtil.validatePasswordPresent("x"));
	}

	/// /System must be HARD-REJECTED for the initial user: Factory.setupUser grants every new user
	/// ROLE_ACCOUNT_USERS, and LibraryUtil.java:100-108 grants AccountUsers Create/Read/Update on
	/// /System's shared libraries — which is where this feature stores every global API key
	/// (/Library/Connections) and the prompt templates.
	@Test
	public void testValidateInitialUserOrganization() {
		assertNotNull("/System must be rejected",
			SetupUtil.validateInitialUserOrganization(OrganizationContext.SYSTEM_ORGANIZATION));
		assertNotNull("/System must be rejected even with surrounding whitespace",
			SetupUtil.validateInitialUserOrganization("  /System  "));
		assertNull("/Development must be accepted",
			SetupUtil.validateInitialUserOrganization(OrganizationContext.DEVELOPMENT_ORGANIZATION));
		assertNull("/Public must be accepted",
			SetupUtil.validateInitialUserOrganization(OrganizationContext.PUBLIC_ORGANIZATION));
		assertNull("A padded allowed value must be accepted", SetupUtil.validateInitialUserOrganization(" /Public "));
		assertNotNull("null must be rejected", SetupUtil.validateInitialUserOrganization(null));
		assertNotNull("empty must be rejected", SetupUtil.validateInitialUserOrganization(""));
		assertNotNull("An unknown organization must be rejected",
			SetupUtil.validateInitialUserOrganization("/Development/Setup"));
		assertNotNull("A case variant of /System must not slip through",
			SetupUtil.validateInitialUserOrganization("/system"));
		assertNotNull("A relative path must be rejected", SetupUtil.validateInitialUserOrganization("System"));
	}

	@Test
	public void testValidateUserName() {
		assertNull("A simple name must be accepted", SetupUtil.validateUserName("stephen"));
		assertNull("Exactly 5 characters must be accepted", SetupUtil.validateUserName("abcde"));
		assertNull("A padded name must be accepted", SetupUtil.validateUserName("  stephen  "));

		assertNotNull("null must be rejected", SetupUtil.validateUserName(null));
		assertNotNull("empty must be rejected", SetupUtil.validateUserName(""));
		assertNotNull("whitespace-only must be rejected", SetupUtil.validateUserName("     "));
		assertNotNull("4 characters must be rejected ($minLen5)", SetupUtil.validateUserName("abcd"));

		/// Reserved provisioning users — none of these names may be reused.
		for(String reserved : SetupUtil.SYSTEM_USER_NAMES) {
			assertNotNull("The reserved name '" + reserved + "' must be rejected",
				SetupUtil.validateUserName(reserved));
			assertNotNull("The reserved name '" + reserved + "' must be rejected case-insensitively",
				SetupUtil.validateUserName(reserved.toUpperCase()));
		}
		assertNotNull("'admin' must be rejected", SetupUtil.validateUserName(Factory.ADMIN_USER_NAME));

		/// Path-, URN- and shell-hostile characters: the name becomes /home/<name> groups, roles
		/// and permissions in Factory.setupUser.
		assertNotNull("A path separator must be rejected", SetupUtil.validateUserName("a/bcde"));
		assertNotNull("A backslash must be rejected", SetupUtil.validateUserName("a\\bcde"));
		assertNotNull("Path traversal must be rejected", SetupUtil.validateUserName("../etc"));
		assertNotNull("A space must be rejected", SetupUtil.validateUserName("abc def"));
		assertNotNull("A leading dot must be rejected", SetupUtil.validateUserName(".hidden"));
		assertNotNull("A leading dash must be rejected", SetupUtil.validateUserName("-abcde"));
		assertNotNull("A colon must be rejected", SetupUtil.validateUserName("abc:def"));
		assertNotNull("A quote must be rejected", SetupUtil.validateUserName("abc'def"));
		assertNotNull("A semicolon must be rejected", SetupUtil.validateUserName("abcde;rm"));
		assertNotNull("A percent must be rejected", SetupUtil.validateUserName("abcde%00"));
		assertNotNull("A newline must be rejected", SetupUtil.validateUserName("abcde\nx"));
		assertNotNull("A URN separator must be rejected", SetupUtil.validateUserName("urn:am7:abc"));

		StringBuilder sb = new StringBuilder();
		for(int i = 0; i < 64; i++) {
			sb.append("a");
		}
		assertNull("64 characters must be accepted", SetupUtil.validateUserName(sb.toString()));
		assertNotNull("65 characters must be rejected", SetupUtil.validateUserName(sb.toString() + "a"));
	}

	/// 'jane.doe' MUST be rejected — the platform itself refuses to create it (proved empirically
	/// by testMinLen5IsFiveConsecutiveAlphanumericsNotLength, where Factory.getCreateUser returns
	/// null for such a name), so accepting it in the validator would only move the failure later
	/// and hand the operator a null user.
	///
	/// What this pins is the MESSAGE. The rejection must state the real constraint — five
	/// consecutive alphanumerics / the first five characters — rather than blaming an opaque
	/// "$minLen5" rule, which reads as "must be at least 5 characters" to an operator whose name is
	/// 8 characters long.
	@Test
	public void testValidateUserNameRejectsDottedNamesWithAnActionableMessage() {
		String msg = SetupUtil.validateUserName("jane.doe");
		logger.info("validateUserName(\"jane.doe\") -> " + msg);
		assertNotNull("'jane.doe' must be rejected — the platform cannot create it", msg);
		assertFalse("The message must not blame an opaque '$minLen5' rule. Actual: " + msg,
			msg.contains("$minLen5"));
		String lower = msg.toLowerCase();
		assertTrue("The message must state the real constraint (five consecutive alphanumerics, or"
			+ " the first five characters). Actual: " + msg,
			lower.contains("five consecutive") || lower.contains("first five"));
		assertTrue("The message must be actionable — it must say what IS allowed. Actual: " + msg,
			lower.contains("letters") || lower.contains("digits"));
	}

	/// PINS A DELIBERATE ASYMMETRY that is otherwise undocumented and untested.
	///
	/// USER_NAME_PATTERN requires the FIRST FIVE characters to be alphanumeric, which is STRICTER
	/// than the platform's $minLen5 rule (five consecutive alphanumerics ANYWHERE). 'a.bcdef'
	/// satisfies $minLen5 via the run "bcdef" but is still refused by the validator.
	///
	/// The asymmetry is safe in this direction only: a validator stricter than the platform can
	/// never produce a null user, whereas a looser one can. This test exists so a later
	/// "simplification" of the pattern cannot silently loosen it back into null-user territory.
	@Test
	public void testUserNamePatternIsStricterThanMinLen5() {
		String name = "a.bcdef";

		/// The platform rule alone would ACCEPT this name.
		BaseRecord probe = null;
		boolean minLen5 = false;
		try {
			probe = RecordFactory.newInstance(ModelNames.MODEL_USER);
			probe.set(FieldNames.FIELD_NAME, name);
			minLen5 = ValidationUtil.validateFieldWithRule(probe, probe.getField(FieldNames.FIELD_NAME),
				ValidationUtil.getRule("$minLen5"));
		}
		catch(Exception e) {
			fail("Failed to evaluate $minLen5 directly: " + e.getMessage());
		}
		assertTrue("'" + name + "' satisfies $minLen5 (the run \"bcdef\")", minLen5);

		/// The validator refuses it anyway, because the first five characters are not alphanumeric.
		assertFalse("USER_NAME_PATTERN must require five leading alphanumerics",
			SetupUtil.USER_NAME_PATTERN.matcher(name).matches());
		assertNotNull("validateUserName must be STRICTER than $minLen5 and reject '" + name + "'",
			SetupUtil.validateUserName(name));

		/// Moving the separator past the fifth character makes it valid under both.
		assertNull("'abcde.f' must be accepted", SetupUtil.validateUserName("abcde.f"));
	}

	/// Characterizes the ACTUAL constraint, so the failure above is understood correctly.
	///
	/// $minLen5 is not a length check: the expression [A-Za-z0-9]{5} is evaluated with
	/// Matcher.find(), so it demands five CONSECUTIVE alphanumerics. That constraint is
	/// platform-wide (it lives on system.user.name in userModel.json), and the platform genuinely
	/// refuses to create a user whose longest run is shorter — so validateUserName is RIGHT to
	/// refuse, and its message must say why (see
	/// testValidateUserNameRejectsDottedNamesWithAnActionableMessage).
	@Test
	public void testMinLen5IsFiveConsecutiveAlphanumericsNotLength() {
		OrganizationContext octx = getTestOrganization("/Development/Setup");

		/// A dotted name WITH a five-character run: accepted by the validator and creatable.
		String ok = "smithy." + UUID.randomUUID().toString().substring(0, 4);
		assertNull("A dotted name containing a 5+ alphanumeric run must be accepted",
			SetupUtil.validateUserName(ok));
		BaseRecord created = getCreateUser(ok, octx);
		assertNotNull("The platform must create '" + ok + "'", created);
		assertEquals(ok, created.get(FieldNames.FIELD_NAME));

		/// A dotted name WITHOUT one: refused by the validator AND by the platform.
		String bad = "jd." + UUID.randomUUID().toString().substring(0, 4);
		assertNotNull("The validator refuses '" + bad + "'", SetupUtil.validateUserName(bad));
		assertNull("The platform also refuses to create '" + bad + "', so the validator is right to"
			+ " refuse it — only its message is wrong", getCreateUser(bad, octx));
	}

	/// ----------------------------------------------------------------------------------------
	/// 3. THE EMBEDDING WIDTH GUARD
	/// ----------------------------------------------------------------------------------------

	/// A correct-width vector must pass through UNCHANGED — value for value, not merely by length.
	@Test
	public void testEmbeddingWidthPassesCorrectWidth() {
		int dims = 768;
		float[] emb = new float[dims];
		for(int i = 0; i < dims; i++) {
			emb[i] = (float)(i * 0.001);
		}
		float[] expected = emb.clone();
		float[] out = EmbeddingUtil.validateEmbeddingWidth(emb, dims, "http://192.168.1.42:8123");
		assertNotNull("A correct-width vector must not be nulled", out);
		assertEquals("A correct-width vector must keep its length", dims, out.length);
		assertArrayEquals("A correct-width vector must pass through unchanged", expected, out, 0.0f);
	}

	/// A wrong-width vector must be REJECTED as float[0] — never truncated, never padded.
	@Test
	public void testEmbeddingWidthRejectsWrongWidth() {
		int dims = 768;

		float[] tooWide = new float[1536];
		for(int i = 0; i < tooWide.length; i++) {
			tooWide[i] = 1.0f;
		}
		float[] outWide = EmbeddingUtil.validateEmbeddingWidth(tooWide, dims, "http://bad-model:8123");
		assertNotNull("The guard must return an empty array, not null", outWide);
		assertEquals("A 1536-wide vector against a 768 column must be REJECTED as float[0]", 0, outWide.length);

		float[] tooNarrow = new float[767];
		float[] outNarrow = EmbeddingUtil.validateEmbeddingWidth(tooNarrow, dims, "http://bad-model:8123");
		assertEquals("An off-by-one narrow vector must be REJECTED as float[0]", 0, outNarrow.length);

		float[] one = new float[] { 0.5f };
		assertEquals("A single-element vector must be REJECTED as float[0]", 0,
			EmbeddingUtil.validateEmbeddingWidth(one, dims, "http://bad-model:8123").length);

		/// Explicitly: no silent truncation to the column width.
		assertFalse("The guard must not truncate to the expected width", outWide.length == dims);
	}

	/// BACKWARD COMPATIBILITY — a LEGACY deployment whose vector column is 1024 wide (created before
	/// vectorExtModel.json's maxLength changed 1024 -> 768 in 77f0fe8a; DBUtil never alters an
	/// existing column's type) must keep working. The guard is a CONSISTENCY check against the first
	/// width this process observes, not an absolute check against the schema value, so a
	/// self-consistent 1024 deployment is never rejected.
	@Test
	public void testLegacyConsistentWidthIsNotRejected() {
		EmbeddingUtil eu = new EmbeddingUtil(LLMServiceEnumType.LOCAL, "http://127.0.0.1:1", null);
		assertEquals("A fresh instance has observed nothing yet", 0, eu.getObservedEmbeddingWidth());
		assertNotEquals("This test is only meaningful when 1024 differs from the schema width",
			1024, eu.resolveExpectedDimensions());

		/// First vector seen is 1024 wide: it establishes the baseline and is ACCEPTED, even though
		/// the schema declares 768.
		int expected = eu.noteAndResolveExpectedWidth(1024);
		assertEquals("The first observed width becomes the baseline", 1024, expected);
		assertEquals("The baseline must be recorded", 1024, eu.getObservedEmbeddingWidth());
		float[] legacy = new float[1024];
		assertEquals("A legacy 1024-wide vector must NOT be rejected on a 1024 deployment",
			1024, EmbeddingUtil.validateEmbeddingWidth(legacy, expected, "http://legacy:8123").length);

		/// Every subsequent 1024 vector keeps passing.
		assertEquals(1024, eu.noteAndResolveExpectedWidth(1024));
		assertEquals("Repeated consistent widths must keep passing", 1024,
			EmbeddingUtil.validateEmbeddingWidth(new float[1024], eu.noteAndResolveExpectedWidth(1024),
				"http://legacy:8123").length);

		/// The threat this guard exists for: the embedding URL is repointed at a 768-dim model
		/// mid-process. The baseline still says 1024, so the mismatched vector is REJECTED.
		int afterSwap = eu.noteAndResolveExpectedWidth(768);
		assertEquals("The baseline must NOT drift to the new width", 1024, afterSwap);
		assertEquals("A width change mid-process must still be REJECTED", 0,
			EmbeddingUtil.validateEmbeddingWidth(new float[768], afterSwap, "http://swapped:8123").length);
	}

	/// The mirror case: a current 768 deployment behaves exactly as before.
	@Test
	public void testCurrentWidthBaselineStillRejectsMismatch() {
		EmbeddingUtil eu = new EmbeddingUtil(LLMServiceEnumType.LOCAL, "http://127.0.0.1:1", null);
		assertEquals("768 establishes the baseline", 768, eu.noteAndResolveExpectedWidth(768));
		assertEquals("A matching vector passes", 768,
			EmbeddingUtil.validateEmbeddingWidth(new float[768], 768, "http://x").length);
		assertEquals("The baseline does not drift", 768, eu.noteAndResolveExpectedWidth(1536));
		assertEquals("A 1536 vector against a 768 baseline is REJECTED", 0,
			EmbeddingUtil.validateEmbeddingWidth(new float[1536], eu.noteAndResolveExpectedWidth(1536),
				"http://x").length);
	}

	/// An empty result must never establish or disturb the baseline.
	@Test
	public void testEmptyResultDoesNotEstablishABaseline() {
		EmbeddingUtil eu = new EmbeddingUtil(LLMServiceEnumType.LOCAL, "http://127.0.0.1:1", null);
		assertEquals("An empty result resolves to 0 (pass through)", 0, eu.noteAndResolveExpectedWidth(0));
		assertEquals("No baseline may have been recorded", 0, eu.getObservedEmbeddingWidth());
		assertEquals("A negative length must not establish a baseline", 0, eu.noteAndResolveExpectedWidth(-1));
		assertEquals(0, eu.getObservedEmbeddingWidth());
		/// A real vector afterwards still gets to set the baseline.
		assertEquals(768, eu.noteAndResolveExpectedWidth(768));
	}

	/// Existing behavior that must NOT be masked by the guard.
	@Test
	public void testEmbeddingWidthPassesThroughUnknownCases() {
		float[] empty = new float[0];
		float[] outEmpty = EmbeddingUtil.validateEmbeddingWidth(empty, 768, "http://x");
		assertEquals("A transport error (length 0) must pass through unchanged", 0, outEmpty.length);

		float[] emb = new float[] { 1.0f, 2.0f, 3.0f };
		float[] outZero = EmbeddingUtil.validateEmbeddingWidth(emb, 0, "http://x");
		assertArrayEquals("expectedDimensions == 0 means 'let the model decide' — pass through unchanged",
			new float[] { 1.0f, 2.0f, 3.0f }, outZero, 0.0f);
		float[] outNeg = EmbeddingUtil.validateEmbeddingWidth(emb, -1, "http://x");
		assertArrayEquals("expectedDimensions < 0 must pass through unchanged",
			new float[] { 1.0f, 2.0f, 3.0f }, outNeg, 0.0f);

		float[] outNull = EmbeddingUtil.validateEmbeddingWidth(null, 768, "http://x");
		assertNotNull("null must become an empty array, never propagate as null", outNull);
		assertEquals("null must become float[0]", 0, outNull.length);
	}

	/// The guard compares against the fixed width of the common.vectorExt.embedding column, which
	/// is the constraint the database actually enforces.
	@Test
	public void testExpectedDimensionsIsTheColumnWidth() {
		EmbeddingUtil eu = new EmbeddingUtil(LLMServiceEnumType.LOCAL, "http://127.0.0.1:1", null);
		int dims = eu.resolveExpectedDimensions();
		logger.info("resolveExpectedDimensions=" + dims);
		assertEquals("The expected width must be the common.vectorExt.embedding column width (768)", 768, dims);
	}

	/// END-TO-END: an empty float[] coming out of the embedding layer must ABORT the vector store
	/// write, not persist anything.
	///
	/// The embedding endpoint is pointed at an unreachable port, so EmbeddingUtil.getEmbedding
	/// returns the empty array that validateEmbeddingWidth also returns on a width mismatch. The
	/// real VectorUtil.createVectorStore then runs and must throw a FieldException
	/// (VectorUtil.java:357-359) with nothing written.
	@Test
	public void testEmptyEmbeddingAbortsVectorStore() {
		OrganizationContext octx = getTestOrganization("/Development/Setup");
		BaseRecord user = getCreateUser("setupVectorUser", octx);
		assertNotNull("Test user should not be null", user);

		String dataName = "setup-vector-guard-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord data = getCreateData(user, dataName, "text/plain",
			"The quick brown fox jumps over the lazy dog and keeps on running.".getBytes(),
			"~/Data", octx.getOrganizationId());
		assertNotNull("Data record should not be null", data);

		VectorUtil vu = new VectorUtil(LLMServiceEnumType.LOCAL, "http://127.0.0.1:1", null);
		assertEquals("Precondition: no vectors for this brand new record", 0, vu.countVectorStore(data));

		boolean threw = false;
		try {
			vu.createVectorStore(data, "The quick brown fox jumps over the lazy dog and keeps on running.",
				ChunkEnumType.WORD, 8);
		}
		catch(FieldException e) {
			threw = true;
			logger.info("Expected rejection: " + e.getMessage());
		}
		assertTrue("An empty/rejected embedding must abort createVectorStore with a FieldException", threw);
		assertEquals("Nothing may be persisted when the embedding was rejected", 0, vu.countVectorStore(data));
	}
}
