package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
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
 * nullable {@code varchar(16)} and every pre-existing row reads SQL NULL (measured on am7db at
 * the time of writing: 161 rows, 13 non-null, so 148 NULL). {@code testLiveNullColumnRowsResolveFromDialect}
 * therefore reads ACTUAL am7db rows whose column is NULL - one per distinct dialect - and asserts
 * the resolution, instead of relying only on in-memory records where the schema default
 * materialises the literal 'UNKNOWN'. It is READ-ONLY: it selects ids and reads records, and
 * writes nothing.</p>
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

	/// (8) LIVE am7db, READ-ONLY: the SQL NULL column is the PRIMARY path, not an edge case.
	/// Selects one real row id per distinct dialect where `upstream IS NULL`, reads each through
	/// the SAME projection Chat.configureChat uses, and asserts the dialect-derived resolution.
	/// Writes nothing and resets nothing.
	@Test
	public void testLiveNullColumnRowsResolveFromDialect() throws Exception {
		String table = ioContext.getDbUtil().getTableName(ModelNames.MODEL_CONNECTION);
		/// One id per dialect value among rows whose upstream column is literally NULL.
		String sql = "SELECT dialect, min(id) AS mid, count(*) AS n FROM " + table
			+ " WHERE upstream IS NULL GROUP BY dialect ORDER BY dialect";
		List<Object[]> rows = new ArrayList<>();
		try (java.sql.Connection con = ioContext.getDbUtil().getDataSource().getConnection();
				Statement st = con.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			while (rs.next()) {
				rows.add(new Object[] { rs.getString("dialect"), rs.getLong("mid"), rs.getInt("n") });
			}
		}
		logger.info("[KI-72][NULL-COLUMN] am7db rows with upstream IS NULL, by dialect:");
		for (Object[] r : rows) {
			logger.info("[KI-72][NULL-COLUMN]   dialect=" + r[0] + " sampleId=" + r[1] + " count=" + r[2]);
		}
		assertFalse("No system.connection row in am7db has upstream IS NULL. The added column is"
			+ " nullable with no DDL default, so this test's subject - the primary path every"
			+ " pre-existing row takes - is not present in this database. Do not weaken this test;"
			+ " it means the fixture assumption changed.", rows.isEmpty());

		int checked = 0;
		for (Object[] r : rows) {
			String rawDialect = (String) r[0];
			long id = (Long) r[1];
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
		logger.info("[KI-72][NULL-COLUMN] PASS - " + checked + " real NULL-column rows resolved correctly.");
	}
}
