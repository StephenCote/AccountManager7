package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConnectionDialectEnumType;
import org.cote.accountmanager.schema.type.ConnectionUpstreamEnumType;
import org.junit.Test;

/**
 * KI-72: locks down {@code ChatUtil.resolveUpstream(connection)} and
 * {@code ChatUtil.inferUpstream(LLMServiceEnumType)} - the resolution of the UPSTREAM
 * MODEL-SERVER FAMILY, the axis that keys the Ollama extension parameters
 * ({@code num_ctx, top_k, repeat_penalty, typical_p, min_p, repeat_last_n, num_gpu, think})
 * and the memory-extraction token floor.
 *
 * <p><b>THE ONE CASE THIS CLASS EXISTS FOR</b> is
 * {@link #testOpenAiCompatDialectNeverInfersOllama()}: a connection whose dialect is
 * {@code OPENAI_COMPAT} with {@code upstream} unset must resolve to {@code UNKNOWN} and
 * <b>NEVER</b> to {@code OLLAMA}. That dialect also fronts Azure OpenAI and every other
 * OpenAI-compatible endpoint, which would then be sent Ollama-only parameters. "Widen the
 * dialect test to include OPENAI_COMPAT" is the obvious-looking one-line edit that
 * reintroduces the defect KI-72 exists to prevent, and it is one careless edit away at all
 * times - hence a dedicated test rather than a row in a table.</p>
 *
 * <p>{@code resolveUpstream} is package-private {@code static}; it is invoked here via
 * reflection - the same idiom {@link TestDialectResolution} uses for
 * {@code resolveServiceType}. This drives the REAL production methods against real
 * {@code system.connection} {@link BaseRecord}s. No live LLM, no GPU.</p>
 *
 * <p><b>THE SQL NULL PATH IS THE PRIMARY PATH.</b> {@code DBUtil.generateSchemaLine} emits no
 * DDL {@code default} clause for enum/string fields, so the added {@code upstream} column is a
 * nullable {@code varchar(16)} and every pre-existing row read SQL NULL (measured on am7db when
 * the field shipped: 161 rows, 13 non-null, so 148 NULL). {@code testLiveNullColumnRowsResolveFromDialect}
 * therefore reads ACTUAL am7db rows whose column is NULL and asserts the resolution, instead of
 * relying only on in-memory records where the schema default materialises the literal 'UNKNOWN'.</p>
 *
 * <p><b>The live case SEEDS its own NULL-column rows (2026-10-12).</b> The backfill has since
 * completed - am7db had 0 of 142 rows with a NULL {@code upstream} on 2026-10-09 - so a purely
 * read-only live case no longer has a subject. It is not retired: a NULL column is still exactly
 * what every row written before the field existed, or written by any writer that omits the field,
 * looks like, and the in-memory siblings above cannot produce it (an instantiated record always
 * materialises the literal 'UNKNOWN'). So the test now creates one owned, uniquely-named
 * {@code system.connection} per dialect AS THE TEST USER through {@code AccessPoint.create},
 * with the {@code upstream} field REMOVED from the record before the write: the DB writer's INSERT
 * lists only the fields PRESENT on the record ({@code StatementUtil.getInsertTemplate} iterates
 * {@code record.getFields()}, and {@code RecordWriter.prepareTranscription} auto-inserts only
 * IDENTITY fields on CREATE), so the omitted column lands as SQL NULL. The test then PROVES the
 * seed is NULL with a raw {@code upstream IS NULL} select before asserting anything about
 * resolution, and deletes the seeds in {@code finally}. Any genuine NULL rows that still exist in
 * the database are checked as well (one per dialect), so the original read-only coverage is kept.</p>
 */
public class TestUpstreamResolution extends BaseTest {

	private ConnectionUpstreamEnumType resolve(BaseRecord connection) {
		try {
			Method m = ChatUtil.class.getDeclaredMethod("resolveUpstream", BaseRecord.class);
			m.setAccessible(true);
			return (ConnectionUpstreamEnumType) m.invoke(null, new Object[] { connection });
		} catch (Exception e) {
			throw new RuntimeException("reflective resolveUpstream invocation failed", e);
		}
	}

	/// A full connection record: every field materialised, so `upstream` carries the schema
	/// default 'UNKNOWN' unless explicitly set. This is the shape an in-memory factory record has.
	private BaseRecord connection(ConnectionDialectEnumType dialect, ConnectionUpstreamEnumType upstream) {
		try {
			BaseRecord c = RecordFactory.model(ModelNames.MODEL_CONNECTION).newInstance();
			if (dialect != null) {
				c.set(FieldNames.FIELD_DIALECT, dialect);
			}
			if (upstream != null) {
				c.set(FieldNames.FIELD_UPSTREAM, upstream);
			}
			return c;
		} catch (Exception e) {
			throw new RuntimeException("failed to build system.connection", e);
		}
	}

	/// (1) connection == null -> UNKNOWN. Nothing is asserted, so nothing may be inferred.
	@Test
	public void testNullConnectionResolvesUnknown() {
		assertEquals("A null connection must resolve UNKNOWN, not throw and not guess",
			ConnectionUpstreamEnumType.UNKNOWN, resolve(null));
	}

	/// (2) THE KI-72 PROHIBITION. OPENAI_COMPAT + upstream unset -> UNKNOWN, NEVER OLLAMA.
	@Test
	public void testOpenAiCompatDialectNeverInfersOllama() {
		ConnectionUpstreamEnumType r = resolve(
			connection(ConnectionDialectEnumType.OPENAI_COMPAT, null));
		assertFalse("KI-72 PROHIBITION VIOLATED: dialect OPENAI_COMPAT with `upstream` unset inferred"
			+ " OLLAMA. That dialect also fronts Azure OpenAI and any other OpenAI-compatible endpoint,"
			+ " which would then be sent Ollama-only parameters (num_ctx/top_k/repeat_penalty/think)."
			+ " Asserting OLLAMA behind a proxy is an explicit operator act via"
			+ " system.connection.upstream - it must NEVER be inferred from the dialect.",
			r == ConnectionUpstreamEnumType.OLLAMA);
		assertEquals("dialect OPENAI_COMPAT with `upstream` unset must resolve UNKNOWN",
			ConnectionUpstreamEnumType.UNKNOWN, r);

		/// Same prohibition through the public LLMServiceEnumType-keyed fallback.
		ConnectionUpstreamEnumType i = ChatUtil.inferUpstream(LLMServiceEnumType.OPENAI_COMPAT);
		assertFalse("KI-72 PROHIBITION VIOLATED in inferUpstream(OPENAI_COMPAT)",
			i == ConnectionUpstreamEnumType.OLLAMA);
		assertEquals("inferUpstream(OPENAI_COMPAT) must be UNKNOWN",
			ConnectionUpstreamEnumType.UNKNOWN, i);
	}

	/// (3) An EXPLICIT operator assertion is the primary source and wins over any inference -
	/// including the OPENAI_COMPAT case above, which is the whole point of the new field.
	@Test
	public void testExplicitUpstreamWinsOverDialectInference() {
		assertEquals("upstream=OLLAMA on an OPENAI_COMPAT connection is the KI-72 fix - it must win",
			ConnectionUpstreamEnumType.OLLAMA,
			resolve(connection(ConnectionDialectEnumType.OPENAI_COMPAT, ConnectionUpstreamEnumType.OLLAMA)));
		assertEquals("upstream=OPENAI must win over an OLLAMA dialect",
			ConnectionUpstreamEnumType.OPENAI,
			resolve(connection(ConnectionDialectEnumType.OLLAMA, ConnectionUpstreamEnumType.OPENAI)));
		assertEquals("upstream=OLLAMA must win over an OPENAI dialect",
			ConnectionUpstreamEnumType.OLLAMA,
			resolve(connection(ConnectionDialectEnumType.OPENAI, ConnectionUpstreamEnumType.OLLAMA)));
		/// An explicit UNKNOWN is NOT an assertion: resolveUpstream treats it as "unset" and falls
		/// through to the dialect inference. Documented behaviour of the null/UNKNOWN test.
		assertEquals("an explicitly-set UNKNOWN must fall through to the dialect inference",
			ConnectionUpstreamEnumType.OLLAMA,
			resolve(connection(ConnectionDialectEnumType.OLLAMA, ConnectionUpstreamEnumType.UNKNOWN)));
	}

	/// (4) Dialect inference table for an unset `upstream` (the schema-default 'UNKNOWN' shape).
	@Test
	public void testDialectInferenceTableWhenUpstreamUnset() {
		assertEquals("OLLAMA dialect -> OLLAMA upstream (the native path must keep behaving natively)",
			ConnectionUpstreamEnumType.OLLAMA,
			resolve(connection(ConnectionDialectEnumType.OLLAMA, null)));
		assertEquals("OPENAI dialect -> OPENAI upstream",
			ConnectionUpstreamEnumType.OPENAI,
			resolve(connection(ConnectionDialectEnumType.OPENAI, null)));
		assertEquals("OPENAI_COMPAT dialect -> UNKNOWN (KI-72 prohibition)",
			ConnectionUpstreamEnumType.UNKNOWN,
			resolve(connection(ConnectionDialectEnumType.OPENAI_COMPAT, null)));
		assertEquals("UNKNOWN dialect -> UNKNOWN upstream",
			ConnectionUpstreamEnumType.UNKNOWN,
			resolve(connection(ConnectionDialectEnumType.UNKNOWN, null)));
		/// A default connection record: both fields at their schema default 'UNKNOWN'.
		assertEquals("a default connection record -> UNKNOWN",
			ConnectionUpstreamEnumType.UNKNOWN,
			resolve(connection(null, null)));
	}

	/// (5) A record on which `upstream` was never MATERIALISED at all - i.e. a read whose
	/// projection omitted the field. resolveUpstream must still infer from the dialect rather than
	/// collapsing to UNKNOWN through its exception branch, or a native-Ollama connection loaded by
	/// any caller with an older projection silently loses every extension parameter.
	@Test
	public void testUnmaterialisedUpstreamFieldStillInfersFromDialect() throws Exception {
		BaseRecord partial = RecordFactory.newInstance(ModelNames.MODEL_CONNECTION,
			new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_DIALECT });
		assertFalse("fixture precondition: `upstream` must NOT be materialised on this record",
			partial.hasField(FieldNames.FIELD_UPSTREAM));
		partial.set(FieldNames.FIELD_DIALECT, ConnectionDialectEnumType.OLLAMA);
		assertEquals("an unprojected `upstream` must still infer OLLAMA from the OLLAMA dialect",
			ConnectionUpstreamEnumType.OLLAMA, resolve(partial));

		BaseRecord partial2 = RecordFactory.newInstance(ModelNames.MODEL_CONNECTION,
			new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_DIALECT });
		partial2.set(FieldNames.FIELD_DIALECT, ConnectionDialectEnumType.OPENAI_COMPAT);
		assertEquals("an unprojected `upstream` on an OPENAI_COMPAT connection must be UNKNOWN",
			ConnectionUpstreamEnumType.UNKNOWN, resolve(partial2));
	}

	/// (6) inferUpstream(LLMServiceEnumType) - the FALLBACK used where there is no
	/// system.connection to read (LOCAL, and a bare `new Chat(); setServiceType(...)`).
	@Test
	public void testInferUpstreamFromServiceTypeTable() {
		assertEquals(ConnectionUpstreamEnumType.OLLAMA, ChatUtil.inferUpstream(LLMServiceEnumType.OLLAMA));
		assertEquals(ConnectionUpstreamEnumType.OPENAI, ChatUtil.inferUpstream(LLMServiceEnumType.OPENAI));
		assertEquals("OPENAI_COMPAT -> UNKNOWN (KI-72 prohibition)",
			ConnectionUpstreamEnumType.UNKNOWN, ChatUtil.inferUpstream(LLMServiceEnumType.OPENAI_COMPAT));
		assertEquals("LOCAL has no upstream model server -> UNKNOWN",
			ConnectionUpstreamEnumType.UNKNOWN, ChatUtil.inferUpstream(LLMServiceEnumType.LOCAL));
		assertEquals(ConnectionUpstreamEnumType.UNKNOWN, ChatUtil.inferUpstream(LLMServiceEnumType.UNKNOWN));
		assertEquals("a null service type must resolve UNKNOWN, not throw",
			ConnectionUpstreamEnumType.UNKNOWN, ChatUtil.inferUpstream(null));
	}

	/// (7) Chat.getUpstream() is inference-aware, so every pre-existing
	/// `new Chat(); setServiceType(OLLAMA)` instance (of which there are many in the tests and in
	/// non-chatConfig callers) still behaves natively even though its `upstream` field was never set.
	@Test
	public void testChatGetUpstreamIsInferenceAware() {
		Chat nativeChat = new Chat();
		nativeChat.setServiceType(LLMServiceEnumType.OLLAMA);
		assertEquals("a bare Chat with serviceType=OLLAMA must still report an OLLAMA upstream",
			ConnectionUpstreamEnumType.OLLAMA, nativeChat.getUpstream());

		Chat compat = new Chat();
		compat.setServiceType(LLMServiceEnumType.OPENAI_COMPAT);
		assertEquals("a bare Chat with serviceType=OPENAI_COMPAT must report UNKNOWN (KI-72)",
			ConnectionUpstreamEnumType.UNKNOWN, compat.getUpstream());

		/// An explicit assertion still wins on a bare instance.
		compat.setUpstream(ConnectionUpstreamEnumType.OLLAMA);
		assertEquals("an explicitly set upstream must win over the serviceType inference",
			ConnectionUpstreamEnumType.OLLAMA, compat.getUpstream());

		/// A Chat on which NOTHING was configured. Chat.java:120 defaults the serviceType field to
		/// OPENAI (not UNKNOWN), so the inference yields upstream OPENAI. Behaviourally OPENAI and
		/// UNKNOWN are identical today - both SUPPRESS the Ollama extensions - so the safety property
		/// is what matters and is asserted explicitly: an unconfigured Chat must never be treated as
		/// an Ollama upstream. (I first asserted UNKNOWN here; that was my expectation, not the
		/// production contract, and the run said OPENAI. Corrected to the real default.)
		Chat unset = new Chat();
		assertFalse("an unconfigured Chat must NEVER report an OLLAMA upstream",
			unset.getUpstream() == ConnectionUpstreamEnumType.OLLAMA);
		assertEquals("an unconfigured Chat inherits the serviceType field default (OPENAI), which"
			+ " suppresses the Ollama extensions", ConnectionUpstreamEnumType.OPENAI, unset.getUpstream());
	}

	/// The dialects the live case seeds one NULL-column row for. OPENAI_COMPAT is the KI-72 case
	/// itself; OLLAMA proves the inference actually FIRES on a NULL column (not just "UNKNOWN in,
	/// UNKNOWN out"); OPENAI covers the remaining branch of the inference table.
	private static final ConnectionDialectEnumType[] SEED_DIALECTS = new ConnectionDialectEnumType[] {
		ConnectionDialectEnumType.OLLAMA, ConnectionDialectEnumType.OPENAI, ConnectionDialectEnumType.OPENAI_COMPAT
	};

	/// Create an owned system.connection AS THE TEST USER whose `upstream` column is SQL NULL.
	///
	/// The factory instance is a FULL record, so `upstream` is materialised at its schema default
	/// 'UNKNOWN' and would be written as that literal. Removing the field from the record before
	/// the write is the one way to get a NULL through the normal AccessPoint path: the INSERT lists
	/// only present fields (StatementUtil.getInsertTemplate) and CREATE auto-inserts only identity
	/// fields (RecordWriter.prepareTranscription). Setting the field to null would NOT work -
	/// EnumValueType.setValue(null) rewrites null to the literal "UNKNOWN".
	private BaseRecord seedNullUpstreamConnection(BaseRecord user, ConnectionDialectEnumType dialect) throws Exception {
		String name = "ki72-null-upstream-" + dialect.name().toLowerCase() + "-" + UUID.randomUUID();
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord c = ioContext.getFactory().newInstance(ModelNames.MODEL_CONNECTION, user, null, plist);
		c.set("serverUrl", "http://ki72-null-upstream.invalid");
		c.set(FieldNames.FIELD_DIALECT, dialect);
		assertTrue("fixture precondition: the factory record must carry `upstream` before it is stripped",
			c.hasField(FieldNames.FIELD_UPSTREAM));
		List<FieldType> stripped = c.getFields().stream()
			.filter(f -> !FieldNames.FIELD_UPSTREAM.equals(f.getName()))
			.collect(Collectors.toList());
		c.setFields(stripped);
		assertFalse("fixture precondition: `upstream` must be ABSENT from the record handed to create",
			c.hasField(FieldNames.FIELD_UPSTREAM));
		BaseRecord created = ioContext.getAccessPoint().create(user, c);
		assertNotNull("could not create the NULL-upstream seed connection '" + name + "' as the test user", created);
		long id = created.get(FieldNames.FIELD_ID);
		assertTrue("seed connection '" + name + "' has no id after create", id > 0L);
		logger.info("[KI-72][NULL-COLUMN] seeded system.connection id=" + id + " dialect=" + dialect + " name=" + name);
		return created;
	}

	/// (8) LIVE am7db: the SQL NULL column is the PRIMARY path, not an edge case.
	/// Seeds one owned connection per dialect with `upstream` omitted from the write (so the column
	/// is literally NULL), PROVES the seed is NULL with a raw select, then reads every NULL-column
	/// row under test - the seeds plus one genuine pre-existing row per dialect, if any remain -
	/// through the SAME projection Chat.configureChat uses and asserts the dialect-derived
	/// resolution. The seeds are deleted in `finally`; nothing is reset.
	@Test
	public void testLiveNullColumnRowsResolveFromDialect() throws Exception {
		BaseRecord user = getCreateUser("ki72NullUpstreamUser");
		assertNotNull("test user", user);
		String table = ioContext.getDbUtil().getTableName(ModelNames.MODEL_CONNECTION);

		List<BaseRecord> seeds = new ArrayList<>();
		try {
			for (ConnectionDialectEnumType d : SEED_DIALECTS) {
				seeds.add(seedNullUpstreamConnection(user, d));
			}
			String seedIds = seeds.stream()
				.map(s -> Long.toString((long) s.get(FieldNames.FIELD_ID)))
				.collect(Collectors.joining(", "));

			/// Every row whose upstream column is literally NULL: the seeds, by id, plus the
			/// oldest genuine row per dialect (none expected since the backfill, but still checked).
			/// LinkedHashMap keeps the id order stable for the log.
			Map<Long, String> rows = new LinkedHashMap<>();
			String seedSql = "SELECT id, dialect FROM " + table
				+ " WHERE upstream IS NULL AND id IN (" + seedIds + ") ORDER BY id";
			String censusSql = "SELECT dialect, min(id) AS mid, count(*) AS n FROM " + table
				+ " WHERE upstream IS NULL AND id NOT IN (" + seedIds + ") GROUP BY dialect ORDER BY dialect";
			try (java.sql.Connection con = ioContext.getDbUtil().getDataSource().getConnection();
					Statement st = con.createStatement()) {
				try (ResultSet rs = st.executeQuery(seedSql)) {
					while (rs.next()) {
						rows.put(rs.getLong("id"), rs.getString("dialect"));
					}
				}
				int seededNull = rows.size();
				logger.info("[KI-72][NULL-COLUMN] " + seededNull + " of " + seeds.size()
					+ " seeded rows have upstream IS NULL (ids " + seedIds + ")");
				try (ResultSet rs = st.executeQuery(censusSql)) {
					while (rs.next()) {
						logger.info("[KI-72][NULL-COLUMN] pre-existing: dialect=" + rs.getString("dialect")
							+ " sampleId=" + rs.getLong("mid") + " count=" + rs.getInt("n"));
						rows.put(rs.getLong("mid"), rs.getString("dialect"));
					}
				}
				/// The fixture must actually produce the subject. If this fails, the writer or the
				/// schema has started materialising `upstream` on an omitted-field create, which
				/// is a product change worth knowing about - do not weaken this test to pass it.
				assertEquals("The seeded connections (ids " + seedIds + ") were expected to have a SQL NULL"
					+ " upstream column because the field was omitted from the create, but only "
					+ seededNull + " of " + seeds.size() + " do. The writer or schema now materialises"
					+ " `upstream` on create; the NULL-column path can no longer be produced this way.",
					seeds.size(), seededNull);
			}
			assertFalse("No system.connection row in am7db has upstream IS NULL - not even the seeds."
				+ " Do not weaken this test; it means the fixture assumption changed.", rows.isEmpty());

			int checked = 0;
			for (Map.Entry<Long, String> r : rows.entrySet()) {
				long id = r.getKey();
				String rawDialect = r.getValue();
				Query cq = QueryUtil.createQuery(ModelNames.MODEL_CONNECTION, FieldNames.FIELD_ID, id);
				/// EXACTLY the projection Chat.configureChat / ChatUtil.loadProjectedConnection use.
				cq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID,
					FieldNames.FIELD_DIALECT, FieldNames.FIELD_UPSTREAM });
				cq.setCache(false);
				BaseRecord rec = ioContext.getSearch().findRecord(cq);
				assertNotNull("could not read system.connection id=" + id, rec);

				ConnectionUpstreamEnumType read = rec.getEnum(FieldNames.FIELD_UPSTREAM);
				assertTrue("a SQL NULL upstream column must read back as null or UNKNOWN, got " + read,
					read == null || read == ConnectionUpstreamEnumType.UNKNOWN);

				ConnectionUpstreamEnumType resolved = resolve(rec);
				ConnectionUpstreamEnumType expected;
				if ("OLLAMA".equals(rawDialect)) {
					expected = ConnectionUpstreamEnumType.OLLAMA;
				} else if ("OPENAI".equals(rawDialect)) {
					expected = ConnectionUpstreamEnumType.OPENAI;
				} else {
					/// OPENAI_COMPAT, UNKNOWN and a NULL dialect column all resolve UNKNOWN.
					expected = ConnectionUpstreamEnumType.UNKNOWN;
				}
				logger.info("[KI-72][NULL-COLUMN] id=" + id + " dialect=" + rawDialect
					+ " upstream(col)=NULL -> resolved=" + resolved);
				if ("OPENAI_COMPAT".equals(rawDialect)) {
					assertFalse("KI-72 PROHIBITION VIOLATED on a REAL am7db row (id=" + id + "):"
						+ " an OPENAI_COMPAT connection with a NULL upstream column inferred OLLAMA",
						resolved == ConnectionUpstreamEnumType.OLLAMA);
				}
				assertEquals("real am7db row id=" + id + " (dialect=" + rawDialect
					+ ", upstream column NULL) resolved wrongly", expected, resolved);
				checked++;
			}
			/// The three seeds cover OLLAMA, OPENAI and OPENAI_COMPAT; the row set can only be larger.
			assertTrue("expected at least " + SEED_DIALECTS.length + " NULL-column rows to be checked, got " + checked,
				checked >= SEED_DIALECTS.length);
			logger.info("[KI-72][NULL-COLUMN] PASS - " + checked + " real NULL-column rows resolved correctly.");
		}
		finally {
			for (BaseRecord s : seeds) {
				boolean deleted = ioContext.getAccessPoint().delete(user, s);
				logger.info("[KI-72][NULL-COLUMN] cleanup: deleted seed id=" + s.get(FieldNames.FIELD_ID) + " -> " + deleted);
			}
		}
	}
}
