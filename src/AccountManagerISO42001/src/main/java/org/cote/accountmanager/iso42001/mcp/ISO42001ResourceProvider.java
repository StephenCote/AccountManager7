package org.cote.accountmanager.iso42001.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.mcp.server.IResourceProvider;
import org.cote.accountmanager.mcp.server.McpJsonRpc;
import org.cote.accountmanager.mcp.server.McpSession;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

/**
 * MCP {@link IResourceProvider} for ISO 42001 audit artifacts (design §1.3). Exposes reports, test runs,
 * and signed certifications as {@code am7://{org}/iso42001/{kind}/{objectId}} resources so external audit
 * tools / agents can read them over the MCP protocol.
 *
 * <p><b>Thin transport only.</b> Every read resolves through {@link org.cote.accountmanager.client.AccessPoint}
 * as the MCP session's authenticated user — so the same model-level RBAC that guards the REST/Track-A paths
 * guards these reads; an unauthorized user gets nothing back. No business logic lives here.</p>
 *
 * <p>The {@code am7://} scheme's generic {@code Am7Uri} parser treats dotted segments as the model type, but
 * the ISO resource URIs use a compound {@code iso42001/{kind}} path that is not dotted, so this provider parses
 * the ISO URIs itself (anchored on the {@code iso42001} segment) and maps {@code kind} to the model name.</p>
 */
public class ISO42001ResourceProvider implements IResourceProvider {

	private static final Logger logger = LogManager.getLogger(ISO42001ResourceProvider.class);
	private static final String SCHEME_PREFIX = "am7://";
	private static final String ISO_SEGMENT = "iso42001";
	private static final int PAGE_SIZE = 50;

	/** Resource path-kind → ISO model name. */
	private static String modelForKind(String kind) {
		if (kind == null) {
			return null;
		}
		switch (kind.toLowerCase()) {
			case "report":
				return ISO42001ModelNames.MODEL_REPORT;
			case "testrun":
				return ISO42001ModelNames.MODEL_TEST_RUN;
			case "certification":
				return ISO42001ModelNames.MODEL_CERTIFICATION;
			default:
				return null;
		}
	}

	/** True if this provider owns the URI (contains the {@code /iso42001/} segment). */
	public static boolean handles(String uri) {
		return uri != null && uri.startsWith(SCHEME_PREFIX) && uri.contains("/" + ISO_SEGMENT + "/");
	}

	@Override
	public Map<String, Object> listResources(McpSession session, String cursor) {
		Map<String, Object> result = new HashMap<>();
		List<McpJsonRpc.Resource> resources = new ArrayList<>();
		try {
			BaseRecord user = session.getUser();
			long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
			String orgPath = user.get(FieldNames.FIELD_ORGANIZATION_PATH);
			long start = (cursor != null && !cursor.isEmpty()) ? Long.parseLong(cursor) : 0L;

			// Reports are the primary audit artifact; list a bounded page of them for the org.
			Query q = QueryUtil.createQuery(ISO42001ModelNames.MODEL_REPORT);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.setRequestRange(start, PAGE_SIZE);
			q.setRequest(new String[] { FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
				FieldNames.FIELD_ORGANIZATION_ID, "reportType", "overallVerdict" });
			QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
			BaseRecord[] records = (qr != null) ? qr.getResults() : new BaseRecord[0];
			for (BaseRecord rec : records) {
				String objectId = rec.get(FieldNames.FIELD_OBJECT_ID);
				String uri = buildUri(orgPath, "report", objectId);
				String name = rec.get(FieldNames.FIELD_NAME);
				resources.add(new McpJsonRpc.Resource(uri, name, "ISO 42001 compliance report", "application/json"));
			}
			result.put("resources", resources);
			if (records.length >= PAGE_SIZE) {
				result.put("nextCursor", String.valueOf(start + PAGE_SIZE));
			}
		}
		catch (Exception e) {
			logger.error("Error listing ISO 42001 resources", e);
			result.put("resources", resources);
		}
		return result;
	}

	@Override
	public List<McpJsonRpc.ResourceContent> readResource(McpSession session, String uri) {
		List<McpJsonRpc.ResourceContent> contents = new ArrayList<>();
		try {
			String[] kindId = parse(uri);
			if (kindId == null) {
				logger.warn("Not an ISO 42001 resource URI: " + uri);
				return contents;
			}
			String model = modelForKind(kindId[0]);
			String objectId = kindId[1];
			if (model == null || objectId == null || objectId.isEmpty()) {
				logger.warn("ISO 42001 URI missing kind or id: " + uri);
				return contents;
			}
			BaseRecord user = session.getUser();
			Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
			q.planMost(true);
			BaseRecord record = IOSystem.getActiveContext().getAccessPoint().find(user, q);
			if (record == null) {
				logger.warn("ISO 42001 resource not found (or access denied): " + uri);
				return contents;
			}
			contents.add(McpJsonRpc.ResourceContent.text(uri, "application/json", record.toFullString()));
		}
		catch (Exception e) {
			logger.error("Error reading ISO 42001 resource: " + uri, e);
		}
		return contents;
	}

	@Override
	public List<McpJsonRpc.ResourceTemplate> listTemplates(McpSession session) {
		List<McpJsonRpc.ResourceTemplate> templates = new ArrayList<>();
		templates.add(new McpJsonRpc.ResourceTemplate(
			"am7://{organization}/iso42001/report/{objectId}",
			"ISO 42001 Report", "Read a compliance report (rollups, sections, verdict, hash)", "application/json"));
		templates.add(new McpJsonRpc.ResourceTemplate(
			"am7://{organization}/iso42001/testrun/{objectId}",
			"ISO 42001 Test Run", "Read a bias test run with its embedded results", "application/json"));
		templates.add(new McpJsonRpc.ResourceTemplate(
			"am7://{organization}/iso42001/certification/{objectId}",
			"ISO 42001 Certification", "Read a signed certification", "application/json"));
		return templates;
	}

	/** Parse {@code am7://{org}/iso42001/{kind}/{id}} → {@code [kind, id]}, or {@code null} if it doesn't match. */
	static String[] parse(String uri) {
		if (!handles(uri)) {
			return null;
		}
		String remainder = uri.substring(SCHEME_PREFIX.length());
		String[] parts = remainder.split("/");
		for (int i = 0; i < parts.length - 2; i++) {
			if (ISO_SEGMENT.equals(parts[i])) {
				return new String[] { parts[i + 1], parts[i + 2] };
			}
		}
		return null;
	}

	static String buildUri(String orgPath, String kind, String objectId) {
		String org = (orgPath == null || orgPath.isEmpty()) ? "default" : orgPath;
		if (org.startsWith("/")) {
			org = org.substring(1);
		}
		return SCHEME_PREFIX + org + "/" + ISO_SEGMENT + "/" + kind + "/" + objectId;
	}
}
