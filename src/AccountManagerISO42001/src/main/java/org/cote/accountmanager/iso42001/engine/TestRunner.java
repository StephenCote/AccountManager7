package org.cote.accountmanager.iso42001.engine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.client.AccessPoint;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.iso42001.util.NameBank;
import org.cote.accountmanager.olio.llm.OllamaModelUtil;
import org.cote.accountmanager.iso42001.util.ScoringConfigMapper;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Orchestrates a bias-test run from an {@code iso42001.testConfig} (design §11 Phase 3):
 * resolves the effective {@link ScoringConfig}, builds the seeded {@link TrialPlan}, creates
 * the {@code iso42001.testRun} and walks its lifecycle (PENDING → RUNNING → COMPLETED/FAILED),
 * records {@code randomSeedUsed}, captures the verbatim raw log to a {@code data.data} object
 * referenced by {@code rawLogRef}, embeds the verdicted {@code testResult}, and rolls up
 * {@code passCount}/{@code flagCount}/{@code failCount}/{@code totalTrials}.
 *
 * <p>A run targets one endpoint (the {@code chatConfig} handed to the constructor). Running a
 * mix of models = multiple runs/configs; cross-model aggregation is Phase 5.</p>
 */
public class TestRunner {

	private static final Logger logger = LogManager.getLogger(TestRunner.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final BaseRecord user;
	private final BaseRecord chatConfig;

	public TestRunner(BaseRecord user, BaseRecord chatConfig) {
		this.user = user;
		this.chatConfig = chatConfig;
	}

	/**
	 * Run one bias module from the given config. Returns the persisted, re-readable
	 * {@code testRun} (terminal state), or {@code null} on a hard failure to create it.
	 *
	 * @param testConfig the run configuration (supplies analysisProfile, samplesPerGroup,
	 *                   tier, randomSeed, group/org placement)
	 * @param module     the bias module to execute
	 * @param bank       the name bank the module samples from
	 */
	public BaseRecord run(BaseRecord testConfig, BiasModule module, NameBank bank) {
		AccessPoint ap = IOSystem.getActiveContext().getAccessPoint();
		ScoringConfig cfg = ScoringConfigMapper.resolve(testConfig);

		int perGroup = intField(testConfig, "samplesPerGroup", 30);
		int tierField = intField(testConfig, "tier", 0);
		int tier = (tierField == 2) ? 2 : 1;     // 0 (both) currently executes Tier 1
		long seedField = longField(testConfig, "randomSeed", 0L);
		long seed = (seedField != 0L) ? seedField : System.nanoTime();

		TrialPlan plan = TrialPlan.build(module, bank, perGroup, tier, seed);

		long groupId = longField(testConfig, FieldNames.FIELD_GROUP_ID, 0L);
		long orgId = longField(testConfig, FieldNames.FIELD_ORGANIZATION_ID, 0L);
		long ownerId = longField(testConfig, FieldNames.FIELD_OWNER_ID, 0L);

		/// --- Create testRun: PENDING (in memory) → persist as RUNNING ---
		BaseRecord run;
		try {
			run = RecordFactory.model(ISO42001ModelNames.MODEL_TEST_RUN).newInstance();
			run.set(FieldNames.FIELD_NAME, module.testId() + "-run-" + UUID.randomUUID());
			run.set(FieldNames.FIELD_GROUP_ID, groupId);
			run.set(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			run.set(FieldNames.FIELD_OWNER_ID, ownerId);
			run.set("testConfig", testConfig);
			run.set("status", "PENDING");
			run.set("modelEndpoint", chatConfig != null ? chatConfig.get("model") : null);
			run.set("randomSeedUsed", seed);
			setTimestampQuiet(run, "startTime");
			run.set("status", "RUNNING");
		} catch (Exception e) {
			logger.error("Failed to build testRun", e);
			return null;
		}
		BaseRecord created = ap.create(user, run);
		if (created == null) {
			logger.error("testRun CREATE returned null (RBAC?) for user " + user.get(FieldNames.FIELD_NAME));
			return null;
		}
		String runOid = created.get(FieldNames.FIELD_OBJECT_ID);

		/// --- Execute trials against the live endpoint ---
		BiasTestExecutor exec = new BiasTestExecutor(user, chatConfig);
		BaseRecord result;
		String terminalStatus;
		try {
			result = exec.execute(module, plan, cfg);
			terminalStatus = "COMPLETED";
		} catch (Exception e) {
			logger.error("Bias execution failed for " + module.testId(), e);
			result = null;
			terminalStatus = "FAILED";
		}
		// This run just made many LLM calls, one per trial — flush idle Ollama models once here
		// rather than per-trial (per-trial would just force an immediate reload for the next one).
		OllamaModelUtil.unloadAll();

		/// --- Capture verbatim raw log to a data.data; reference via rawLogRef ---
		String rawLogRef = persistRawLog(ap, module, runOid, ownerId, orgId, exec.getRawLog());

		/// --- Roll up + finalize: re-read, set terminal fields + embedded result, update ---
		int pass = 0;
		int flag = 0;
		int fail = 0;
		if (result != null) {
			String v = result.get("verdict");
			if ("PASS".equals(v)) {
				pass = 1;
			} else if ("FLAG".equals(v)) {
				flag = 1;
			} else if ("FAIL".equals(v)) {
				fail = 1;
			}
		}

		try {
			Query q = QueryUtil.createQuery(ISO42001ModelNames.MODEL_TEST_RUN, FieldNames.FIELD_OBJECT_ID, runOid);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.planMost(true);
			BaseRecord terminal = ap.find(user, q);
			if (terminal == null) {
				logger.error("Failed to re-read testRun " + runOid + " for finalize");
				return created;
			}
			terminal.set("status", terminalStatus);
			terminal.set("passCount", pass);
			terminal.set("flagCount", flag);
			terminal.set("failCount", fail);
			terminal.set("totalTrials", exec.getAttemptedCalls());
			if (rawLogRef != null) {
				terminal.set("rawLogRef", rawLogRef);
			}
			setTimestampQuiet(terminal, "endTime");
			if (result != null) {
				List<BaseRecord> results = terminal.get("results");
				if (results == null) {
					results = new ArrayList<>();
				}
				results.add(result);
				terminal.set("results", results);
			}
			BaseRecord updated = ap.update(user, terminal);
			if (updated == null) {
				logger.warn("testRun finalize UPDATE returned null for " + runOid);
			}
		} catch (Exception e) {
			logger.error("Failed to finalize testRun " + runOid, e);
		}

		/// Return the re-readable terminal record.
		Query rq = QueryUtil.createQuery(ISO42001ModelNames.MODEL_TEST_RUN, FieldNames.FIELD_OBJECT_ID, runOid);
		rq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		rq.planMost(true);
		return ap.find(user, rq);
	}

	/**
	 * Serialize the verbatim raw log to JSON bytes and store as a {@code data.data} object in
	 * the user's own home (the run user owns it, so create succeeds without ISO roles on the
	 * shared group). Returns the object's {@code objectId} for {@code testRun.rawLogRef}.
	 */
	private String persistRawLog(AccessPoint ap, BiasModule module, String runOid,
			long ownerId, long orgId, List<?> rawLog) {
		try {
			String json = MAPPER.writeValueAsString(rawLog);
			byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

			BaseRecord logGroup = IOSystem.getActiveContext().getPathUtil()
				.makePath(user, ModelNames.MODEL_GROUP, "~/ISO42001RawLogs", "DATA", orgId);
			long logGroupId = logGroup.get(FieldNames.FIELD_ID);

			BaseRecord data = RecordFactory.model(ModelNames.MODEL_DATA).newInstance();
			data.set(FieldNames.FIELD_NAME, module.testId() + "-rawlog-" + runOid);
			data.set(FieldNames.FIELD_GROUP_ID, logGroupId);
			data.set(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			data.set(FieldNames.FIELD_OWNER_ID, ownerId);
			data.set(FieldNames.FIELD_CONTENT_TYPE, "application/json");
			data.set(FieldNames.FIELD_BYTE_STORE, bytes);
			BaseRecord createdData = ap.create(user, data);
			if (createdData == null) {
				logger.warn("rawLog data.data CREATE returned null");
				return null;
			}
			return createdData.get(FieldNames.FIELD_OBJECT_ID);
		} catch (Exception e) {
			logger.error("Failed to persist raw log", e);
			return null;
		}
	}

	// ------------------------------------------------------------------

	private static int intField(BaseRecord r, String field, int dflt) {
		try {
			Object v = r.get(field);
			if (v instanceof Number) {
				return ((Number) v).intValue();
			}
		} catch (Exception e) {
			/* default */
		}
		return dflt;
	}

	private static long longField(BaseRecord r, String field, long dflt) {
		try {
			Object v = r.get(field);
			if (v instanceof Number) {
				return ((Number) v).longValue();
			}
		} catch (Exception e) {
			/* default */
		}
		return dflt;
	}

	private static void setTimestampQuiet(BaseRecord r, String field) {
		try {
			r.set(field, new java.util.Date());
		} catch (Exception e) {
			/// timestamp type mismatch — non-critical, skip.
		}
	}
}
