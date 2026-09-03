package org.cote.accountmanager.iso42001.reporting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.client.AccessPoint;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.iso42001.metrics.LangfuseMetrics;
import org.cote.accountmanager.iso42001.metrics.LangfuseMetricsClient;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.CryptoUtil;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Aggregates one or more {@code iso42001.testRun} records (with their embedded
 * {@code iso42001.testResult} rows) into a persisted {@code iso42001.report} (design §2.5, §4.1,
 * §11 Phase 4 / task Phase 5).
 *
 * <p>Pipeline (all deterministic from the input runs — no LLM, no extra DB reads of the runs):</p>
 * <ol>
 *   <li>Flatten runs → {@link ReportData}; roll up pass/flag/fail counts and derive the overall
 *       verdict (FAIL &gt; FLAG &gt; PASS), the distinct {@code modelsEvaluated}, and the FLAG/FAIL
 *       {@code mitigationActions}.</li>
 *   <li>Build the four ordered sections (EXECUTIVE_SUMMARY, METHODOLOGY, RESULTS, MITIGATION) via
 *       {@link ReportTemplates}; the RESULTS section carries the {@link ChartGenerator} chartData JSON.</li>
 *   <li>Compute a SHA-256 content hash over the canonical report JSON ({@code crypto.hashExt.hash})
 *       so Phase 6 can sign it.</li>
 *   <li>Persist the report (group/org/owner from the caller) through {@link AccessPoint} as the
 *       supplied user (the {@code ISO42001Reporters} create role).</li>
 * </ol>
 *
 * <p><b>Control areas</b> are derived from the test modules actually under report (each result's
 * {@code testModule} and each run's {@code testConfig.moduleId}), mapped to Annex-A controls per the
 * ISO 42001 control catalog ({@code iso42001-bias.md}, {@code iso42001.md} §2.2): the {@code BIAS}
 * suite covers {@code A.5.4} (impact on individuals/groups) and {@code A.5.5} (societal impacts). When
 * no module is derivable, the bias-suite default is used. The report status starts at {@code DRAFT}
 * and the certification block is left unset (rendered "NOT CERTIFIED" until Phase 6).</p>
 */
public class ReportGenerator {

	private static final Logger logger = LogManager.getLogger(ReportGenerator.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String HASH_ALGORITHM = "SHA-256";

	/** Annex-A controls satisfied by the BIAS suite (iso42001-bias.md; iso42001.md §2.2). */
	private static final List<String> BIAS_CONTROL_AREAS =
		Collections.unmodifiableList(Arrays.asList("A.5.4", "A.5.5"));

	/**
	 * Prefix marking a module id that has NO Annex-A control mapping in {@link #MODULE_CONTROL_AREAS}.
	 * A present-but-unknown module is recorded honestly as {@code UNMAPPED:<moduleId>} rather than
	 * silently defaulting to the bias controls (A.5.4/A.5.5) — a non-BIAS module must never be labeled
	 * a bias control. Public so a unit test can assert the non-default behavior.
	 */
	public static final String UNMAPPED_PREFIX = "UNMAPPED:";

	/** {@code moduleId} → Annex-A control areas, per the ISO 42001 control catalog (iso42001.md §2.2). */
	private static final Map<String, List<String>> MODULE_CONTROL_AREAS;
	static {
		Map<String, List<String>> m = new LinkedHashMap<>();
		m.put("BIAS", BIAS_CONTROL_AREAS);
		MODULE_CONTROL_AREAS = Collections.unmodifiableMap(m);
	}

	private final BaseRecord user;

	public ReportGenerator(BaseRecord user) {
		this.user = user;
	}

	/**
	 * Build the {@link ReportData} aggregate from the supplied runs. The runs must already carry their
	 * embedded {@code results} (read with {@code planMost}); no further DB access is performed here.
	 */
	public ReportData aggregate(List<BaseRecord> testRuns) {
		ReportData data = new ReportData();
		if (testRuns == null) {
			return data;
		}
		for (BaseRecord run : testRuns) {
			if (run == null) {
				continue;
			}
			data.incrementRunCount();
			String modelEndpoint = run.get("modelEndpoint");
			List<BaseRecord> results = run.get("results");
			if (results == null) {
				continue;
			}
			for (BaseRecord res : results) {
				if (res == null) {
					continue;
				}
				ReportData.Row row = new ReportData.Row();
				row.testModule = res.get("testModule");
				row.testId = res.get("testId");
				row.protectedClass = res.get("protectedClass");
				row.verdict = res.get("verdict");
				row.effectSize = dbl(res, "effectSize");
				row.effectSizeType = res.get("effectSizeType");
				row.correctedPValue = dbl(res, "correctedPValue");
				row.testStatistic = res.get("testStatistic");
				row.notes = res.get("notes");
				row.modelEndpoint = modelEndpoint;
				data.addRow(row);
			}
		}
		return data;
	}

	/**
	 * Generate and persist a report. Returns the in-memory report record (with its server-assigned
	 * {@code id}/{@code objectId} stamped and the content {@code hash} set), or {@code null} on a hard
	 * create failure (e.g. RBAC denial).
	 *
	 * @param name      report name (unique per group/org)
	 * @param reportType COMPLIANCE / BIAS / etc. (defaults to COMPLIANCE when null)
	 * @param testRuns  the runs to aggregate (with embedded results)
	 * @param groupId   placement group
	 * @param orgId     organization
	 * @param ownerId   owning user id
	 */
	public BaseRecord generate(String name, String reportType, List<BaseRecord> testRuns,
			long groupId, long orgId, long ownerId) {
		AccessPoint ap = IOSystem.getActiveContext().getAccessPoint();
		ReportData data = aggregate(testRuns);

		BaseRecord report;
		try {
			report = RecordFactory.model(ISO42001ModelNames.MODEL_REPORT).newInstance();
			report.set(FieldNames.FIELD_NAME, name);
			report.set(FieldNames.FIELD_GROUP_ID, groupId);
			report.set(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			report.set(FieldNames.FIELD_OWNER_ID, ownerId);
			report.set("reportType", reportType != null ? reportType : "COMPLIANCE");
			report.set("reportVersion", 1);
			report.set("status", "DRAFT");
			report.set("overallVerdict", data.overallVerdict());
			report.set("passCount", data.getPassCount());
			report.set("flagCount", data.getFlagCount());
			report.set("failCount", data.getFailCount());
			report.set("modelsEvaluated", data.getModels());
			report.set("controlAreas", deriveControlAreas(data, testRuns));
			report.set("mitigationActions", ReportTemplates.mitigationActionsJson(data));
			if (testRuns != null && !testRuns.isEmpty()) {
				report.set("testRuns", new ArrayList<>(testRuns));
			}

			/// Pull LLM operational metrics (Langfuse) for the run sessions BEFORE building sections so the
			/// optional metrics section can be appended. Wrapped so report generation NEVER fails on
			/// Langfuse being down/unconfigured — an unavailable result simply omits the section, which
			/// keeps a metrics-free report byte-identical (same 4 sections, same hash) to before.
			LangfuseMetrics metrics = fetchRunMetrics(testRuns);
			List<BaseRecord> sections = buildSections(data, name, metrics);
			report.set("sections", sections);

			byte[] hash = computeReportHash(report);
			report.set("hash", hash);
		} catch (Exception e) {
			logger.error("Failed to build report record", e);
			return null;
		}

		BaseRecord created = ap.create(user, report);
		if (created == null) {
			logger.error("report CREATE returned null (RBAC?) for user " + user.get(FieldNames.FIELD_NAME));
			return null;
		}
		/// Stamp the server-assigned identity back onto the in-memory record so the caller can re-read
		/// it AND still inspect the (restricted) hash it carries.
		try {
			report.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
			report.set(FieldNames.FIELD_OBJECT_ID, created.get(FieldNames.FIELD_OBJECT_ID));
		} catch (Exception e) {
			logger.warn("Could not stamp identity onto report; returning created record", e);
			return created;
		}
		return report;
	}

	/**
	 * Derive the ISO 42001 Annex-A control areas covered by this report from the test modules
	 * actually under report — never hardcoded. Distinct module ids are collected from each
	 * aggregated result's {@code testModule} and each input run's {@code testConfig.moduleId}, then
	 * mapped to Annex-A controls via {@link #MODULE_CONTROL_AREAS} (ISO 42001 control catalog,
	 * {@code iso42001-bias.md} / {@code iso42001.md} §2.2). Order-preserving, de-duplicated.
	 *
	 * <p><b>Fallback rule (P2-4c).</b> The bias-suite default ({@link #BIAS_CONTROL_AREAS}, i.e.
	 * A.5.4/A.5.5) is used <b>only</b> when <em>no module is derivable at all</em> (the module set is
	 * empty). A present-but-unknown module id ({@code TRANS}, a future non-BIAS suite, …) is never
	 * defaulted to the bias controls: it yields its mapped controls, or — when unmapped — an explicit
	 * {@code UNMAPPED:<moduleId>} marker ({@link #UNMAPPED_PREFIX}). This keeps the {@code BIAS} behavior
	 * identical ({@code BIAS} maps to A.5.4/A.5.5 via {@link #MODULE_CONTROL_AREAS}) while fixing the
	 * old {@code controls.isEmpty()} fallthrough that mislabeled every non-BIAS module as a bias
	 * control.</p>
	 */
	public static List<String> deriveControlAreas(ReportData data, List<BaseRecord> testRuns) {
		Set<String> modules = new LinkedHashSet<>();
		if (data != null) {
			for (ReportData.Row row : data.getRows()) {
				if (row != null && row.testModule != null && !row.testModule.isEmpty()) {
					modules.add(row.testModule.trim().toUpperCase());
				}
			}
		}
		if (testRuns != null) {
			for (BaseRecord run : testRuns) {
				String moduleId = configModuleId(run);
				if (moduleId != null && !moduleId.isEmpty()) {
					modules.add(moduleId.trim().toUpperCase());
				}
			}
		}

		/// No module derivable at all → fall back to the documented bias-suite default (unchanged).
		if (modules.isEmpty()) {
			return new ArrayList<>(BIAS_CONTROL_AREAS);
		}

		Set<String> controls = new LinkedHashSet<>();
		for (String moduleId : modules) {
			List<String> mapped = MODULE_CONTROL_AREAS.get(moduleId);
			if (mapped != null) {
				controls.addAll(mapped);
			} else {
				/// Present but unmapped module: record it explicitly rather than defaulting to the
				/// bias controls (A.5.4/A.5.5). NEVER bias by default for a non-BIAS module.
				controls.add(UNMAPPED_PREFIX + moduleId);
			}
		}
		return new ArrayList<>(controls);
	}

	/** Safely read {@code testConfig.moduleId} off a run (the testConfig may be absent/unplanned). */
	private static String configModuleId(BaseRecord run) {
		if (run == null) {
			return null;
		}
		try {
			BaseRecord tc = run.get("testConfig");
			return tc == null ? null : tc.get("moduleId");
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Build the ordered report sections; the RESULTS section carries the chartData JSON. The optional
	 * LLM_METRICS section (order 4) is appended <b>only</b> when {@code metrics.hasData()} — i.e. Langfuse
	 * was configured, reachable, AND returned traces for the run sessions. When metrics are unavailable
	 * (no Langfuse config, unreachable, or no traces yet) the four base sections are produced unchanged,
	 * so a metrics-free report is identical (section count and content hash) to before this feature.
	 */
	private List<BaseRecord> buildSections(ReportData data, String reportName, LangfuseMetrics metrics)
			throws Exception {
		ChartGenerator charts = new ChartGenerator();
		String chartData = charts.buildChartData(data);

		List<BaseRecord> sections = new ArrayList<>();
		sections.add(section(ReportTemplates.EXECUTIVE_SUMMARY, 0,
			ReportTemplates.executiveSummary(data, reportName), null));
		sections.add(section(ReportTemplates.METHODOLOGY, 1,
			ReportTemplates.methodology(data), null));
		sections.add(section(ReportTemplates.RESULTS, 2,
			ReportTemplates.results(data), chartData));
		sections.add(section(ReportTemplates.MITIGATION, 3,
			ReportTemplates.mitigation(data), null));
		if (metrics != null && metrics.hasData()) {
			sections.add(section(ReportTemplates.LLM_METRICS, 4,
				ReportTemplates.llmMetrics(metrics), ReportTemplates.llmMetricsChartJson(metrics)));
		}
		return sections;
	}

	/**
	 * Aggregate LLM operational metrics across every run session in the report, via
	 * {@link LangfuseMetricsClient} (the ONLY place Langfuse HTTP/parsing lives — never Objects7/Service7).
	 * Each run's {@code objectId} is the tracing {@code session_id} the ISO engine stamped onto its LLM
	 * calls. Best-effort and total-failure-safe: any exception, or Langfuse being unconfigured/unreachable,
	 * yields {@link LangfuseMetrics#unavailable()} so report generation proceeds without the metrics section.
	 */
	private LangfuseMetrics fetchRunMetrics(List<BaseRecord> testRuns) {
		if (testRuns == null || testRuns.isEmpty()) {
			return LangfuseMetrics.unavailable();
		}
		try {
			LangfuseMetricsClient client = new LangfuseMetricsClient();
			LangfuseMetrics agg = LangfuseMetrics.empty();
			boolean any = false;
			for (BaseRecord run : testRuns) {
				if (run == null) {
					continue;
				}
				String sessionId = run.get(FieldNames.FIELD_OBJECT_ID);
				LangfuseMetrics m = client.fetchSessionMetrics(sessionId);
				if (m != null && m.isAvailable()) {
					agg.merge(m);
					any = true;
				}
			}
			return any ? agg.finish() : LangfuseMetrics.unavailable();
		} catch (Exception e) {
			logger.warn("LLM operational metrics unavailable; omitting metrics section: " + e.getMessage());
			return LangfuseMetrics.unavailable();
		}
	}

	private BaseRecord section(String type, int order, String content, String chartData) throws Exception {
		BaseRecord s = RecordFactory.model(ISO42001ModelNames.MODEL_REPORT_SECTION).newInstance();
		s.set(FieldNames.FIELD_NAME, type);
		s.set("sectionType", type);
		s.set("sectionOrder", order);
		s.set("content", content);
		if (chartData != null) {
			/// chartData column is bounded (maxLength 8192); keep within bounds defensively.
			s.set("chartData", chartData.length() > 8192 ? chartData.substring(0, 8192) : chartData);
		}
		return s;
	}

	/**
	 * Compute the SHA-256 content hash over the canonical report JSON. This is the value Phase-6
	 * certification signs (task: "the report.hash ... is the value to sign") and the value Phase-6
	 * verification recomputes to detect tampering — so it MUST be a pure, deterministic function of the
	 * <b>persisted</b> report record, computable identically at generate time (in-memory record) and at
	 * verify time (re-read record).
	 *
	 * <p>To guarantee generate==verify regardless of how the DB returns list/section order, the inputs
	 * are normalized: sections are sorted by {@code sectionOrder} and only the fields that are confirmed
	 * to round-trip through {@code AccessPoint} (asserted by {@code TestISO42001Report}) participate:
	 * {@code reportType}, {@code reportVersion}, {@code overallVerdict}, the three counts, and each
	 * section's {@code sectionType}/{@code sectionOrder}/{@code content}. Field insertion order is fixed;
	 * timestamps/ids/chartData are excluded (nondeterministic or bounded-truncated).</p>
	 *
	 * <p><b>⚠ Judgment call (flagged):</b> the Phase-5 canonical basis previously also folded in the
	 * flattened per-result rows + {@code modelsEvaluated}/{@code controlAreas} (sourced from the input
	 * testRuns, which are not re-read at verify time and whose list order is not guaranteed to
	 * round-trip). Those are dropped here so verification can recompute deterministically from the report
	 * alone. The substance of the results is still covered: the RESULTS/EXECUTIVE_SUMMARY section
	 * {@code content} (hashed) embeds the per-test verdicts, the models evaluated, and the control areas.
	 * {@code TestISO42001Report} asserts only that the hash is 32 bytes (not a specific value), so this
	 * refinement does not regress Phase 5.</p>
	 */
	public static byte[] computeReportHash(BaseRecord report) {
		try {
			ObjectNode root = MAPPER.createObjectNode();
			root.put("reportType", str(report.get("reportType")));
			root.put("reportVersion", intval(report, "reportVersion"));
			root.put("overallVerdict", str(report.get("overallVerdict")));
			root.put("passCount", intval(report, "passCount"));
			root.put("flagCount", intval(report, "flagCount"));
			root.put("failCount", intval(report, "failCount"));

			List<BaseRecord> sections = report.get("sections");
			if (sections == null) {
				sections = new ArrayList<>();
			} else {
				sections = new ArrayList<>(sections);
			}
			sections.sort(Comparator.comparingInt(s -> intval(s, "sectionOrder")));

			ArrayNode secs = root.putArray("sections");
			for (BaseRecord s : sections) {
				ObjectNode o = secs.addObject();
				o.put("sectionType", str(s.get("sectionType")));
				o.put("sectionOrder", intval(s, "sectionOrder"));
				o.put("content", str(s.get("content")));
			}
			return CryptoUtil.getDigest(HASH_ALGORITHM, MAPPER.writeValueAsBytes(root), new byte[0]);
		} catch (Exception e) {
			logger.error("Failed to compute canonical report hash; returning empty digest", e);
			return new byte[0];
		}
	}

	private static int intval(BaseRecord r, String field) {
		try {
			Object v = r.get(field);
			if (v instanceof Number) {
				return ((Number) v).intValue();
			}
		} catch (Exception e) {
			/* default */
		}
		return 0;
	}

	private static String str(Object o) {
		return o == null ? "" : String.valueOf(o);
	}

	private static double dbl(BaseRecord r, String field) {
		try {
			Object v = r.get(field);
			if (v instanceof Number) {
				return ((Number) v).doubleValue();
			}
		} catch (Exception e) {
			/* default */
		}
		return 0.0;
	}
}
