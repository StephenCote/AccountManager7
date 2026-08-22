package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbArtifactUtil;
import org.cote.accountmanager.olio.picturebook.PbGraphUtil;
import org.cote.accountmanager.olio.picturebook.PbNodeExecutor;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 6b exit criterion: end-to-end SwarmUI PORTRAIT node execution.
 * <p>
 * Searches all PB2 books in the org for a PORTRAIT node that carries a {@code scopeRef}
 * (charPerson objectId), drives it through SwarmUI via {@link PbNodeExecutor}, and verifies a new
 * artifact was created with non-empty bytes.
 * <p>
 * Skips automatically if: no swarm server is configured in test.properties, or no qualifying
 * PORTRAIT node exists. This test will never synthesize fake fixture data — run
 * {@code TestPbMigration} or the full picture-book pipeline first to create PB2 records.
 */
public class TestPbCanvas extends BaseTest {

	public static final Logger logger = LogManager.getLogger(TestPbCanvas.class);

	private static final String ORG_PATH = "/Development/PictureBook Custom Tests";
	private static final String TEST_USER = "pbCustomTestUser";

	@Before
	public void canvasSetup() {
		OlioContextUtil.clearCache();
		OlioModelNames.use();
	}

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG_PATH);
		BaseRecord u = ioContext.getFactory().getCreateUser(
			org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + TEST_USER, u);
		return u;
	}

	/** List all PB2 books the user can see in their org. */
	private BaseRecord[] listBooks(BaseRecord user) {
		long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_SLUG
		});
		q.setCache(false);
		q.setRequestRange(0, 20);
		return IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
	}

	/**
	 * Find [book, workflow, node] for the first PORTRAIT node (with scopeRef) across all PB2 books.
	 * Returns null if none is found so the test can skip.
	 */
	private BaseRecord[] findAnyPortraitNode(BaseRecord user) {
		BaseRecord[] books = listBooks(user);
		if(books == null || books.length == 0) {
			return null;
		}
		for(BaseRecord book : books) {
			if(book.get(FieldNames.FIELD_ID) == null) {
				continue;
			}
			BaseRecord workflow = PbGraphUtil.findWorkflow(user, book);
			if(workflow == null) {
				continue;
			}
			List<BaseRecord> nodes = PbGraphUtil.listNodes(user, workflow);
			for(BaseRecord n : nodes) {
				PbNodeTypeEnumType type = n.getEnum(OlioFieldNames.FIELD_PB_NODE_TYPE);
				if(type != PbNodeTypeEnumType.PORTRAIT) {
					continue;
				}
				String scopeRef = n.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
				if(scopeRef == null || scopeRef.trim().isEmpty()) {
					continue;
				}
				return new BaseRecord[] {book, workflow, n};
			}
		}
		return null;
	}

	@Test
	public void TestPortraitNodeExecution() {
		String swarmServer = testProperties.getProperty("test.swarm.server");
		assumeTrue("test.swarm.server must be set for this test",
			swarmServer != null && !swarmServer.trim().isEmpty());

		BaseRecord user = user();

		BaseRecord[] fixture = findAnyPortraitNode(user);
		assumeNotNull("A PORTRAIT node with scopeRef must exist in the org — "
			+ "run TestPbMigration or the full picture-book pipeline first to create PB2 records", fixture);

		BaseRecord book = fixture[0];
		BaseRecord workflow = fixture[1];
		BaseRecord node = fixture[2];

		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		String nodeHandle = node.get(OlioFieldNames.FIELD_PB_HANDLE);
		String scopeRef = node.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
		logger.info("Executing PORTRAIT node '{}' (scopeRef={}) for book '{}'", nodeHandle, scopeRef, slug);

		Map<String, Object> result = PbNodeExecutor.executeNode(user, book, workflow, node, swarmServer);
		assertNotNull("executeNode must return a result map", result);
		logger.info("executeNode result: {}", result);

		String artifactObjectId = (String) result.get("artifactObjectId");
		assertNotNull("Result must contain artifactObjectId", artifactObjectId);

		Number byteLength = (Number) result.get("byteLength");
		assertNotNull("Result must contain byteLength", byteLength);
		assertTrue("byteLength must be > 0, got " + byteLength, byteLength.longValue() > 0);

		String nodeStatus = (String) result.get("nodeStatus");
		assertEquals("Node status must be DONE_UNVERIFIED", "DONE_UNVERIFIED", nodeStatus);

		long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord artifact = PbArtifactUtil.readArtifact(user, artifactObjectId, orgId);
		assertNotNull("Artifact must be readable after creation", artifact);
		Boolean selected = artifact.get(OlioFieldNames.FIELD_PB_SELECTED);
		assertTrue("Artifact must be marked selected", selected != null && selected.booleanValue());

		logger.info("Portrait node '{}' executed: artifact={}, bytes={}, downstream={}",
			nodeHandle, artifactObjectId, byteLength, result.get("downstreamMarked"));
	}
}
