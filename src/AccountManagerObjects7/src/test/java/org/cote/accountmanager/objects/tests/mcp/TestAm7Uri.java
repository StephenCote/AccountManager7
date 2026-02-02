package org.cote.accountmanager.objects.tests.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.mcp.Am7Uri;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.junit.Test;

public class TestAm7Uri extends BaseTest {

	public static final Logger logger = LogManager.getLogger(TestAm7Uri.class);

	// --- VALID PARSING ---

	@Test
	public void TestParseMinimalUri() {
		logger.info("TestParseMinimalUri");
		Am7Uri uri = Am7Uri.parse("am7://org/type/id");
		assertNotNull("URI should not be null", uri);
		assertEquals("org", uri.getOrganization());
		assertEquals("type", uri.getType());
		assertEquals("id", uri.getId());
	}

	@Test
	public void TestParseDottedModelType() {
		logger.info("TestParseDottedModelType");
		Am7Uri uri = Am7Uri.parse("am7://default/olio.llm.chatConfig/abc-123");
		assertNotNull("URI should not be null", uri);
		assertEquals("olio.llm.chatConfig", uri.getType());
		assertEquals("abc-123", uri.getId());
	}

	@Test
	public void TestParseUuidObjectId() {
		logger.info("TestParseUuidObjectId");
		String uuid = "550e8400-e29b-41d4-a716-446655440000";
		Am7Uri uri = Am7Uri.parse("am7://default/data.data/" + uuid);
		assertNotNull("URI should not be null", uri);
		assertEquals(uuid, uri.getId());
	}

	@Test
	public void TestParseWithQueryParams() {
		logger.info("TestParseWithQueryParams");
		Am7Uri uri = Am7Uri.parse("am7://default/vector/search?q=hello+world&limit=10&threshold=0.7");
		assertNotNull("URI should not be null", uri);
		assertEquals("hello+world", uri.getQueryParam("q"));
		assertEquals("10", uri.getQueryParam("limit"));
		assertEquals("0.7", uri.getQueryParam("threshold"));
	}

	@Test
	public void TestParseMediaUri() {
		logger.info("TestParseMediaUri");
		Am7Uri uri = Am7Uri.parse("am7://default/media/data.data/img-456");
		assertNotNull("URI should not be null", uri);
		assertTrue("Should be media URI", uri.isMedia());
		assertEquals("data.data", uri.getMediaType());
		assertEquals("img-456", uri.getId());
	}

	@Test
	public void TestParseMediaUriWithSizeParam() {
		logger.info("TestParseMediaUriWithSizeParam");
		Am7Uri uri = Am7Uri.parse("am7://default/media/data.data/img-456?size=256");
		assertNotNull("URI should not be null", uri);
		assertTrue("Should be media URI", uri.isMedia());
		assertEquals("256", uri.getQueryParam("size"));
	}

	// --- INVALID INPUT ---

	@Test(expected = IllegalArgumentException.class)
	public void TestParseNullThrows() {
		logger.info("TestParseNullThrows");
		Am7Uri.parse(null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void TestParseEmptyThrows() {
		logger.info("TestParseEmptyThrows");
		Am7Uri.parse("");
	}

	@Test(expected = IllegalArgumentException.class)
	public void TestParseWrongSchemeThrows() {
		logger.info("TestParseWrongSchemeThrows");
		Am7Uri.parse("http://default/system.user/abc");
	}

	@Test(expected = IllegalArgumentException.class)
	public void TestParseMissingOrgThrows() {
		logger.info("TestParseMissingOrgThrows");
		Am7Uri.parse("am7:///system.user/abc");
	}

	@Test(expected = IllegalArgumentException.class)
	public void TestParseMissingTypeThrows() {
		logger.info("TestParseMissingTypeThrows");
		Am7Uri.parse("am7://default//abc");
	}

	@Test(expected = IllegalArgumentException.class)
	public void TestParseMissingIdThrows() {
		logger.info("TestParseMissingIdThrows");
		Am7Uri.parse("am7://default/system.user/");
	}

	@Test(expected = IllegalArgumentException.class)
	public void TestParseTraversalAttackThrows() {
		logger.info("TestParseTraversalAttackThrows");
		Am7Uri.parse("am7://default/../../../etc/passwd");
	}

	@Test(expected = IllegalArgumentException.class)
	public void TestParseInjectionAttackThrows() {
		logger.info("TestParseInjectionAttackThrows");
		Am7Uri.parse("am7://default/system.user/'; DROP TABLE users;--");
	}

	// --- BUILDER ---

	@Test
	public void TestBuilderAllFields() {
		logger.info("TestBuilderAllFields");
		String uri = Am7Uri.builder()
			.organization("myorg")
			.type("data.data")
			.id("doc-789")
			.build();
		assertEquals("am7://myorg/data.data/doc-789", uri);
	}

	@Test
	public void TestBuilderVectorSearch() {
		logger.info("TestBuilderVectorSearch");
		String uri = Am7Uri.builder()
			.organization("default")
			.vectorSearch()
			.queryParam("q", "search term")
			.queryParam("limit", "10")
			.build();
		assertTrue("Should start with am7://default/vector/search", uri.startsWith("am7://default/vector/search?"));
		assertTrue("Should contain encoded query", uri.contains("q=search+term"));
	}

	@Test(expected = IllegalStateException.class)
	public void TestBuilderMissingOrgThrows() {
		logger.info("TestBuilderMissingOrgThrows");
		Am7Uri.builder().type("data.data").id("abc").build();
	}

	@Test(expected = IllegalStateException.class)
	public void TestBuilderMissingTypeThrows() {
		logger.info("TestBuilderMissingTypeThrows");
		Am7Uri.builder().organization("default").id("abc").build();
	}

	// --- EQUALITY & HASHING ---

	@Test
	public void TestParsedUriEquality() {
		logger.info("TestParsedUriEquality");
		Am7Uri a = Am7Uri.parse("am7://default/system.user/abc-123");
		Am7Uri b = Am7Uri.parse("am7://default/system.user/abc-123");
		assertEquals("Same URIs should be equal", a, b);
		assertEquals("Same URIs should have same hashCode", a.hashCode(), b.hashCode());
	}

	@Test
	public void TestDifferentUrisNotEqual() {
		logger.info("TestDifferentUrisNotEqual");
		Am7Uri a = Am7Uri.parse("am7://default/system.user/abc-123");
		Am7Uri b = Am7Uri.parse("am7://default/system.user/xyz-456");
		assertNotEquals("Different URIs should not be equal", a, b);
	}

	@Test
	public void TestToStringRoundTrip() {
		logger.info("TestToStringRoundTrip");
		String original = "am7://default/olio.llm.chatConfig/abc-123";
		Am7Uri uri = Am7Uri.parse(original);
		assertEquals("toString should match original", original, uri.toString());
	}

	// --- BaseRecord ROUND-TRIP ---

	@Test
	public void TestBaseRecordToUri() {
		logger.info("TestBaseRecordToUri");
		boolean error = false;
		try {
			BaseRecord user = RecordFactory.newInstance(ModelNames.MODEL_USER);
			user.set(FieldNames.FIELD_OBJECT_ID, "user-abc-123");
			user.set(FieldNames.FIELD_ORGANIZATION_PATH, "default");

			String uri = Am7Uri.toUri(user);
			assertNotNull("URI should not be null", uri);
			assertTrue("Should start with am7://", uri.startsWith("am7://"));
			assertTrue("Should contain objectId", uri.contains("user-abc-123"));
			assertTrue("Should contain schema", uri.contains("system.user"));

			Am7Uri parsed = Am7Uri.parse(uri);
			assertEquals("user-abc-123", parsed.getId());
			assertEquals("default", parsed.getOrganization());
		} catch (Exception e) {
			logger.error(e);
			error = true;
		}
		assertTrue("Should not encounter errors", !error);
	}

	@Test
	public void TestNullObjectIdReturnsNull() {
		logger.info("TestNullObjectIdReturnsNull");
		boolean error = false;
		try {
			BaseRecord rec = RecordFactory.newInstance(ModelNames.MODEL_USER);
			String uri = Am7Uri.toUri(rec);
			assertNull("URI should be null when objectId is not set", uri);
		} catch (Exception e) {
			logger.error(e);
			error = true;
		}
		assertTrue("Should not encounter errors", !error);
	}

	@Test
	public void TestNullRecordReturnsNull() {
		logger.info("TestNullRecordReturnsNull");
		assertNull("Null record should produce null URI", Am7Uri.toUri(null));
	}

	@Test
	public void TestDataRecordToUri() {
		logger.info("TestDataRecordToUri");
		boolean error = false;
		try {
			BaseRecord data = RecordFactory.newInstance(ModelNames.MODEL_DATA);
			data.set(FieldNames.FIELD_OBJECT_ID, "doc-456");
			data.set(FieldNames.FIELD_ORGANIZATION_PATH, "Development");

			String uri = Am7Uri.toUri(data);
			assertNotNull("URI should not be null", uri);
			assertTrue("Should contain data.data type", uri.contains("data.data"));
			assertTrue("Should contain objectId", uri.contains("doc-456"));

			Am7Uri parsed = Am7Uri.parse(uri);
			assertEquals("doc-456", parsed.getId());
			assertEquals("Development", parsed.getOrganization());
		} catch (Exception e) {
			logger.error(e);
			error = true;
		}
		assertTrue("Should not encounter errors", !error);
	}

	@Test
	public void TestOrganizationPathNormalization() {
		logger.info("TestOrganizationPathNormalization");
		boolean error = false;
		try {
			BaseRecord rec = RecordFactory.newInstance(ModelNames.MODEL_USER);
			rec.set(FieldNames.FIELD_OBJECT_ID, "test-123");
			rec.set(FieldNames.FIELD_ORGANIZATION_PATH, "/Development");

			String uri = Am7Uri.toUri(rec);
			assertNotNull("URI should not be null", uri);
			assertTrue("Leading slash should be stripped", uri.startsWith("am7://Development/"));
		} catch (Exception e) {
			logger.error(e);
			error = true;
		}
		assertTrue("Should not encounter errors", !error);
	}

	// --- MULTIPLE MODEL TYPES ---

	@Test
	public void TestGroupRecordUri() {
		logger.info("TestGroupRecordUri");
		boolean error = false;
		try {
			BaseRecord group = RecordFactory.newInstance(ModelNames.MODEL_GROUP);
			group.set(FieldNames.FIELD_OBJECT_ID, "grp-789");
			group.set(FieldNames.FIELD_ORGANIZATION_PATH, "default");

			String uri = Am7Uri.toUri(group);
			assertNotNull("URI should not be null", uri);
			assertTrue("Should contain group schema", uri.contains("auth.group"));
		} catch (Exception e) {
			logger.error(e);
			error = true;
		}
		assertTrue("Should not encounter errors", !error);
	}

	@Test
	public void TestRoleRecordUri() {
		logger.info("TestRoleRecordUri");
		boolean error = false;
		try {
			BaseRecord role = RecordFactory.newInstance(ModelNames.MODEL_ROLE);
			role.set(FieldNames.FIELD_OBJECT_ID, "role-001");
			role.set(FieldNames.FIELD_ORGANIZATION_PATH, "default");

			String uri = Am7Uri.toUri(role);
			assertNotNull("URI should not be null", uri);
			assertTrue("Should contain role schema", uri.contains("auth.role"));
		} catch (Exception e) {
			logger.error(e);
			error = true;
		}
		assertTrue("Should not encounter errors", !error);
	}
}
