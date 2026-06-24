package org.cote.accountmanager.iso42001.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.iso42001.service.ISO42001ServiceFacade;
import org.cote.accountmanager.mcp.server.IToolProvider;
import org.cote.accountmanager.mcp.server.McpJsonRpc;
import org.cote.accountmanager.mcp.server.McpSession;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

/**
 * MCP {@link IToolProvider} for ISO 42001 (design §1.3). Four tools that marshal a JSON-RPC tool call onto
 * the already-tested Track-A engine/factory methods (via {@link ISO42001ServiceFacade}) as the MCP session's
 * authenticated user — no business logic here.
 *
 * <ul>
 *   <li>{@code iso42001_run_test} — execute a bias test from a persisted {@code testConfig}.</li>
 *   <li>{@code iso42001_test_status} — current status + rollup of a test run.</li>
 *   <li>{@code iso42001_report_summary} — overall verdict + pass/flag/fail of a report.</li>
 *   <li>{@code iso42001_certify} — request certification signing for a report.</li>
 * </ul>
 */
public class ISO42001ToolProvider implements IToolProvider {

	private static final Logger logger = LogManager.getLogger(ISO42001ToolProvider.class);

	public static final String TOOL_RUN = "iso42001_run_test";
	public static final String TOOL_STATUS = "iso42001_test_status";
	public static final String TOOL_REPORT_SUMMARY = "iso42001_report_summary";
	public static final String TOOL_CERTIFY = "iso42001_certify";

	/** True if this provider owns the named tool. */
	public static boolean handles(String toolName) {
		return toolName != null && toolName.startsWith("iso42001_");
	}

	@Override
	public List<McpJsonRpc.Tool> listTools(McpSession session) {
		List<McpJsonRpc.Tool> tools = new ArrayList<>();
		Map<String, Object> runProps = new HashMap<>();
		runProps.put("testConfigId", prop("string", "objectId of the iso42001.testConfig to run"));
		tools.add(new McpJsonRpc.Tool(TOOL_RUN,
			"Execute an ISO 42001 bias test from a persisted test configuration (by testConfig objectId).",
			schema(runProps, "testConfigId")));

		Map<String, Object> statusProps = new HashMap<>();
		statusProps.put("testRunId", prop("string", "objectId of the iso42001.testRun"));
		tools.add(new McpJsonRpc.Tool(TOOL_STATUS,
			"Get the status and pass/flag/fail rollup of an ISO 42001 test run.",
			schema(statusProps, "testRunId")));

		Map<String, Object> summaryProps = new HashMap<>();
		summaryProps.put("reportId", prop("string", "objectId of the iso42001.report"));
		tools.add(new McpJsonRpc.Tool(TOOL_REPORT_SUMMARY,
			"Get the overall verdict and pass/flag/fail summary of an ISO 42001 compliance report.",
			schema(summaryProps, "reportId")));

		Map<String, Object> certifyProps = new HashMap<>();
		certifyProps.put("reportId", prop("string", "objectId of the iso42001.report to certify"));
		certifyProps.put("certifierId", prop("string", "optional objectId of the requested certifier (system.user)"));
		certifyProps.put("justification", prop("string", "justification text for the request"));
		tools.add(new McpJsonRpc.Tool(TOOL_CERTIFY,
			"Request certification signing for an ISO 42001 report (creates a certification request).",
			schema(certifyProps, "reportId")));
		return tools;
	}

	@Override
	public McpJsonRpc.ToolResult callTool(McpSession session, String toolName, Map<String, Object> arguments) {
		try {
			BaseRecord user = session.getUser();
			switch (toolName) {
				case TOOL_RUN:
					return runTest(user, arguments);
				case TOOL_STATUS:
					return testStatus(user, arguments);
				case TOOL_REPORT_SUMMARY:
					return reportSummary(user, arguments);
				case TOOL_CERTIFY:
					return certify(user, arguments);
				default:
					return McpJsonRpc.ToolResult.error("Unknown ISO 42001 tool: " + toolName);
			}
		}
		catch (Exception e) {
			logger.error("Error calling ISO 42001 tool: " + toolName, e);
			return McpJsonRpc.ToolResult.error("Tool execution failed: " + e.getMessage());
		}
	}

	private McpJsonRpc.ToolResult runTest(BaseRecord user, Map<String, Object> args) {
		String testConfigId = str(args, "testConfigId");
		if (testConfigId == null) {
			return McpJsonRpc.ToolResult.error("'testConfigId' is required");
		}
		BaseRecord run = ISO42001ServiceFacade.runFromConfig(user, testConfigId);
		if (run == null) {
			return McpJsonRpc.ToolResult.error("Run failed (config not found, endpoint unresolved, or access denied)");
		}
		return McpJsonRpc.ToolResult.success("Started/completed test run " + run.get(FieldNames.FIELD_OBJECT_ID)
			+ " status=" + run.get("status") + " pass=" + nz(run.get("passCount"))
			+ " flag=" + nz(run.get("flagCount")) + " fail=" + nz(run.get("failCount")));
	}

	private McpJsonRpc.ToolResult testStatus(BaseRecord user, Map<String, Object> args) {
		String testRunId = str(args, "testRunId");
		if (testRunId == null) {
			return McpJsonRpc.ToolResult.error("'testRunId' is required");
		}
		BaseRecord run = find(user, ISO42001ModelNames.MODEL_TEST_RUN, testRunId);
		if (run == null) {
			return McpJsonRpc.ToolResult.error("Test run not found (or access denied): " + testRunId);
		}
		return McpJsonRpc.ToolResult.success("Test run " + testRunId + " status=" + run.get("status")
			+ " totalTrials=" + nz(run.get("totalTrials")) + " pass=" + nz(run.get("passCount"))
			+ " flag=" + nz(run.get("flagCount")) + " fail=" + nz(run.get("failCount")));
	}

	private McpJsonRpc.ToolResult reportSummary(BaseRecord user, Map<String, Object> args) {
		String reportId = str(args, "reportId");
		if (reportId == null) {
			return McpJsonRpc.ToolResult.error("'reportId' is required");
		}
		BaseRecord report = find(user, ISO42001ModelNames.MODEL_REPORT, reportId);
		if (report == null) {
			return McpJsonRpc.ToolResult.error("Report not found (or access denied): " + reportId);
		}
		return McpJsonRpc.ToolResult.success("Report " + reportId + " verdict=" + report.get("overallVerdict")
			+ " status=" + report.get("status") + " pass=" + nz(report.get("passCount"))
			+ " flag=" + nz(report.get("flagCount")) + " fail=" + nz(report.get("failCount")));
	}

	private McpJsonRpc.ToolResult certify(BaseRecord user, Map<String, Object> args) {
		String reportId = str(args, "reportId");
		if (reportId == null) {
			return McpJsonRpc.ToolResult.error("'reportId' is required");
		}
		String certifierId = str(args, "certifierId");
		String justification = str(args, "justification");
		BaseRecord request = ISO42001ServiceFacade.requestCertification(user, reportId, certifierId,
			justification != null ? justification : "Certification requested via MCP");
		if (request == null) {
			return McpJsonRpc.ToolResult.error("Certification request failed (report not found or access denied)");
		}
		return McpJsonRpc.ToolResult.success("Created certification request "
			+ request.get(FieldNames.FIELD_OBJECT_ID) + " for report " + reportId);
	}

	private BaseRecord find(BaseRecord user, String model, String objectId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.planMost(true);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	// ── JSON-schema + argument helpers ───────────────────────────────────────

	private static String str(Map<String, Object> args, String key) {
		Object v = (args == null) ? null : args.get(key);
		if (v == null) {
			return null;
		}
		String s = v.toString().trim();
		return s.isEmpty() ? null : s;
	}

	private static String nz(Object v) {
		return v == null ? "0" : v.toString();
	}

	private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
		Map<String, Object> s = new HashMap<>();
		s.put("type", "object");
		s.put("properties", properties);
		if (required != null && required.length > 0) {
			s.put("required", new ArrayList<>(List.of(required)));
		}
		return s;
	}

	private static Map<String, Object> prop(String type, String description) {
		Map<String, Object> def = new HashMap<>();
		def.put("type", type);
		def.put("description", description);
		return def;
	}
}
