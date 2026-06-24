package org.cote.accountmanager.iso42001.reporting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.client.AccessPoint;
import org.cote.accountmanager.io.IOSystem;
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
 * <p><b>Control areas</b> are set to {@code A.5.4} (AI impact assessment) and {@code A.5.5} (the bias
 * suite's mapped controls) per the Phase-5 task scope. The report status starts at {@code DRAFT} and
 * the certification block is left unset (rendered "NOT CERTIFIED" until Phase 6).</p>
 */
public class ReportGenerator {

	private static final Logger logger = LogManager.getLogger(ReportGenerator.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String HASH_ALGORITHM = "SHA-256";

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
			report.set("controlAreas", new ArrayList<>(Arrays.asList("A.5.4", "A.5.5")));
			report.set("mitigationActions", ReportTemplates.mitigationActionsJson(data));
			if (testRuns != null && !testRuns.isEmpty()) {
				report.set("testRuns", new ArrayList<>(testRuns));
			}

			List<BaseRecord> sections = buildSections(data, name);
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

	/** Build the four ordered report sections; the RESULTS section carries the chartData JSON. */
	private List<BaseRecord> buildSections(ReportData data, String reportName) throws Exception {
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
		return sections;
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
