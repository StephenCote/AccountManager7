package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.UUID;

import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConnectionDialectEnumType;
import org.junit.Test;

/**
 * Phase B2: verifies the new {@code system.connection.dialect} enum field — that it persists,
 * round-trips through the lowercase-wire / UPPERCASE-Java {@code getEnum()} path, defaults to
 * {@code UNKNOWN} when omitted, and behaves correctly under a PATCH.
 *
 * <p>Runs as a NON-admin user (per project rules). All reads that verify a just-written value
 * re-query with {@code setCache(false)} so the search cache cannot mask a stale value.</p>
 */
public class TestConnectionDialect extends BaseTest {

	private BaseRecord createConnection(BaseRecord user, String name, ConnectionDialectEnumType dialect) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord c = ioContext.getFactory().newInstance(ModelNames.MODEL_CONNECTION, user, null, plist);
		c.set("serverUrl", "https://litellm.example.com");
		if (dialect != null) {
			c.set("dialect", dialect);
		}
		BaseRecord created = ioContext.getAccessPoint().create(user, c);
		assertNotNull("connection CREATE returned null", created);
		return created;
	}

	/// Fresh (uncached) re-read that includes the non-query 'dialect' field via planMost.
	private BaseRecord reread(BaseRecord user, String objectId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_CONNECTION, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.planMost(false);
		q.setCache(false);
		return ioContext.getAccessPoint().find(user, q);
	}

	@Test
	public void testDialectPersistsAndRoundTrips() throws Exception {
		BaseRecord user = getCreateUser("dialectUser1");
		String name = "compat-conn-" + UUID.randomUUID();
		BaseRecord created = createConnection(user, name, ConnectionDialectEnumType.OPENAI_COMPAT);

		BaseRecord fresh = reread(user, created.get(FieldNames.FIELD_OBJECT_ID));
		assertNotNull("re-read connection was null", fresh);
		/// UPPERCASE-in-Java round trip through getEnum().
		assertEquals(ConnectionDialectEnumType.OPENAI_COMPAT, fresh.getEnum("dialect"));
	}

	@Test
	public void testDialectDefaultsToUnknown() throws Exception {
		BaseRecord user = getCreateUser("dialectUser2");
		String name = "default-conn-" + UUID.randomUUID();
		/// Create WITHOUT setting dialect — model default is "UNKNOWN".
		BaseRecord created = createConnection(user, name, null);

		BaseRecord fresh = reread(user, created.get(FieldNames.FIELD_OBJECT_ID));
		assertNotNull("re-read connection was null", fresh);
		assertEquals(ConnectionDialectEnumType.UNKNOWN, fresh.getEnum("dialect"));
	}

	@Test
	public void testDialectPatchUpdates() throws Exception {
		BaseRecord user = getCreateUser("dialectUser3");
		String name = "patch-conn-" + UUID.randomUUID();
		BaseRecord created = createConnection(user, name, ConnectionDialectEnumType.UNKNOWN);
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);

		/// PATCH: materialise ONLY identity + validated name + the changed dialect field.
		/// name is included because common.nameId carries a \S validation rule that the writer
		/// enforces against the patch record itself (see model-api.md).
		BaseRecord patch = RecordFactory.newInstance(ModelNames.MODEL_CONNECTION,
				new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "dialect" });
		patch.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, oid);
		patch.set(FieldNames.FIELD_NAME, name);
		patch.set("dialect", ConnectionDialectEnumType.OPENAI);
		BaseRecord updated = ioContext.getAccessPoint().update(user, patch);
		assertNotNull("dialect PATCH (with name) update returned null", updated);

		BaseRecord fresh = reread(user, oid);
		assertNotNull("re-read connection was null", fresh);
		assertEquals(ConnectionDialectEnumType.OPENAI, fresh.getEnum("dialect"));
	}

	/**
	 * A minimal PATCH that OMITS 'name' entirely (materialises only id+objectId+dialect via the
	 * explicit field-name newInstance overload) succeeds and updates ONLY dialect. This is the
	 * correct minimal-patch semantics: only fields present on the patch record land in the SQL
	 * SET clause, so the absent 'name' is never validated and the stored name is untouched.
	 *
	 * <p>NOTE: this deliberately documents the boundary of the model-api.md name guardrail — the
	 * guardrail fires when 'name' is present-but-blank (see {@link #testPatchWithBlankNameRejected()}),
	 * NOT when 'name' is omitted. Verified empirically 2026-09-03: the omit-name patch was
	 * AUDIT PERMIT / MODIFY and the dialect change persisted.</p>
	 */
	@Test
	public void testMinimalPatchOmittingNamePersists() throws Exception {
		BaseRecord user = getCreateUser("dialectUser4");
		String name = "patch-noname-conn-" + UUID.randomUUID();
		BaseRecord created = createConnection(user, name, ConnectionDialectEnumType.OPENAI);
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord patch = RecordFactory.newInstance(ModelNames.MODEL_CONNECTION,
				new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "dialect" });
		patch.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, oid);
		patch.set("dialect", ConnectionDialectEnumType.OLLAMA);
		BaseRecord updated = ioContext.getAccessPoint().update(user, patch);
		assertNotNull("minimal (name-omitted) PATCH update returned null", updated);

		BaseRecord fresh = reread(user, oid);
		assertNotNull("re-read connection was null", fresh);
		assertEquals("minimal name-omitted PATCH should update dialect",
				ConnectionDialectEnumType.OLLAMA, fresh.getEnum("dialect"));
		/// And the original name must be intact (omitted field unchanged).
		assertEquals("omitted name must be unchanged", name, fresh.get(FieldNames.FIELD_NAME));
	}

	/**
	 * A PATCH that materialises 'name' but sets it BLANK ("") violates common.name's
	 * {@code $notEmpty} rule (allowNull=false, required). The writer validates the patch record
	 * itself, so the whole update must be rejected and the dialect change must NOT persist. This
	 * is the genuine common.nameId validation guardrail from model-api.md.
	 */
	@Test
	public void testPatchWithBlankNameRejected() throws Exception {
		BaseRecord user = getCreateUser("dialectUser5");
		String name = "patch-blankname-conn-" + UUID.randomUUID();
		BaseRecord created = createConnection(user, name, ConnectionDialectEnumType.OPENAI);
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord patch = RecordFactory.newInstance(ModelNames.MODEL_CONNECTION,
				new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "dialect" });
		patch.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, oid);
		patch.set(FieldNames.FIELD_NAME, "");
		patch.set("dialect", ConnectionDialectEnumType.OLLAMA);
		ioContext.getAccessPoint().update(user, patch);

		/// Blank name fails validation -> the record must be unchanged (dialect stays OPENAI).
		BaseRecord fresh = reread(user, oid);
		assertNotNull("re-read connection was null", fresh);
		assertEquals("blank-name PATCH must be rejected; dialect must remain OPENAI",
				ConnectionDialectEnumType.OPENAI, fresh.getEnum("dialect"));
	}
}
