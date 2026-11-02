package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbCoverUtil.CoverResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbLocationKindEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * P9-7 (§6.C 237 l): the wiring of {@link PbServiceFacade#scrubHosts(String)} into a node DTO, against
 * {@code am7db} with no renderer. A node is failed with a message quoting an SD server URL the way
 * {@code PbPipelineUtil.failNode} stores it; {@code nodeView} must hand back the message with the
 * authority replaced, while the stored field still carries the host (the DTO scrubs, the row is never
 * rewritten). The scrub itself is covered by {@link TestPbLastErrorScrub}.
 */
public class TestPbLastErrorDtoOffline extends BaseTest {

	private static final String HOST = "10.0.0.5:7801";
	private static final String STORED = "Render of scene 0 failed: UNREACHABLE: Connection refused to http://" + HOST + "/API/GenerateText2Image (plan artifact p97)";

	@Before
	public void offlineSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	@Test
	public void nodeViewScrubsTheHostAndTheRowKeepsIt() throws Exception {
		BaseRecord owner = getCreateUser("pbPlanOwner");
		assertNotNull("owner", owner);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);
		String slug = "p97err" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		BaseRecord book = PbBookUtil.createBook(owner, dataPath, slug, "Offline lastError " + slug);
		assertNotNull("createBook", book);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		try {
			BaseRecord scene = PbBookUtil.createScene(owner, book, 0, "Scene 0", PbBookUtil.bookGroupPath(slug));
			assertNotNull("scene", scene);
			String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
			String wfPath = PbBookUtil.workflowGroupPath(slug);
			BaseRecord workflow = PbGraphUtil.getCreateWorkflow(owner, book, wfPath);
			assertNotNull("workflow", workflow);
			BaseRecord node = PbGraphUtil.addNode(owner, workflow, PbPipelineUtil.renderPlanHandle(sceneOid), PbNodeTypeEnumType.RENDER_PLAN, wfPath, 0);
			assertNotNull("node", node);
			String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
			long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

			PbPipelineUtil.failNode(owner, PbGraphUtil.readNode(owner, nodeOid, orgId), STORED);

			BaseRecord stored = PbGraphUtil.readNode(owner, nodeOid, orgId);
			assertNotNull("re-read node", stored);
			assertEquals("the row keeps the message as written", STORED, stored.get(OlioFieldNames.FIELD_PB_LAST_ERROR));

			Map<String, Object> view = PbServiceFacade.nodeView(owner, bookOid, nodeOid);
			assertNotNull("nodeView", view);
			Object err = view.get("lastError");
			assertTrue("lastError on the DTO is text: " + err, err instanceof String);
			String dto = (String) err;
			assertFalse("the DTO carries no host: " + dto, dto.contains(HOST));
			assertTrue("the DTO carries the marker: " + dto, dto.contains(PbServiceFacade.SD_SERVER_MARKER));
			assertEquals(PbServiceFacade.scrubHosts(STORED), dto);
			assertTrue("the rest of the message survives: " + dto, dto.startsWith("Render of scene 0 failed: UNREACHABLE: Connection refused to "));

			/// The other two DTOs that copy lastError (§6.C 246 1): a LOCATION node through getLocation, a cover's
			/// RENDER_PLAN node through coverView. Same stored text, same scrub, the rows keep the host.
			BaseRecord freshBook = PbBookUtil.readBook(owner, bookOid, orgId);
			assertNotNull("book re-read", freshBook);
			BaseRecord loc = PbLocationUtil.getCreateLocation(owner, freshBook, "Harbour steps " + slug, "a flight of wet stone steps down to the quay",
				PbLocationKindEnumType.EXTERIOR, List.of("the steps"));
			assertNotNull("location", loc);
			String locOid = loc.get(FieldNames.FIELD_OBJECT_ID);
			BaseRecord locNode = PbGraphUtil.addNode(owner, workflow, PbPipelineUtil.locationHandle(locOid), PbNodeTypeEnumType.LOCATION, wfPath, 0);
			assertNotNull("location node", locNode);
			PbPipelineUtil.failNode(owner, PbGraphUtil.readNode(owner, locNode.get(FieldNames.FIELD_OBJECT_ID), orgId), STORED);
			assertEquals("the location node row keeps the message", STORED,
				PbGraphUtil.readNode(owner, locNode.get(FieldNames.FIELD_OBJECT_ID), orgId).get(OlioFieldNames.FIELD_PB_LAST_ERROR));
			Map<String, Object> locView = PbServiceFacade.getLocation(owner, bookOid, locOid);
			assertNotNull("getLocation", locView);
			Object locNodeDto = locView.get("node");
			assertTrue("the location DTO carries its node: " + locNodeDto, locNodeDto instanceof Map);
			Object locErr = ((Map<?, ?>) locNodeDto).get("lastError");
			assertEquals("locationNodeDto scrubs", PbServiceFacade.scrubHosts(STORED), locErr);
			assertFalse("the location DTO carries no host: " + locErr, String.valueOf(locErr).contains(HOST));

			CoverResult cr = PbCoverUtil.getCreate(owner, PbBookUtil.readBook(owner, bookOid, orgId), PbCompositionPurposeEnumType.COVER, PbCoverUtil.Mode.MANUAL, null);
			assertNotNull("cover getCreate", cr);
			assertTrue("MANUAL cover written: " + cr.failureReason, cr.isWritten());
			String compOid = cr.composition.get(FieldNames.FIELD_OBJECT_ID);
			BaseRecord coverNode = PbGraphUtil.addNode(owner, workflow, PbPipelineUtil.compositionPlanHandle(compOid), PbNodeTypeEnumType.RENDER_PLAN, wfPath, 0);
			assertNotNull("cover plan node", coverNode);
			PbPipelineUtil.failNode(owner, PbGraphUtil.readNode(owner, coverNode.get(FieldNames.FIELD_OBJECT_ID), orgId), STORED);
			assertEquals("the cover node row keeps the message", STORED,
				PbGraphUtil.readNode(owner, coverNode.get(FieldNames.FIELD_OBJECT_ID), orgId).get(OlioFieldNames.FIELD_PB_LAST_ERROR));
			Map<String, Object> coverView = PbServiceFacade.coverView(owner, bookOid);
			assertNotNull("coverView", coverView);
			Object covers = coverView.get("covers");
			assertTrue("covers map: " + covers, covers instanceof Map);
			Object entry = ((Map<?, ?>) covers).get(PbCompositionPurposeEnumType.COVER.name());
			assertTrue("the COVER entry: " + entry, entry instanceof Map);
			assertEquals("the entry is the failed node", coverNode.get(FieldNames.FIELD_OBJECT_ID), ((Map<?, ?>) entry).get("nodeObjectId"));
			Object coverErr = ((Map<?, ?>) entry).get("lastError");
			assertEquals("coverEntryDto scrubs", PbServiceFacade.scrubHosts(STORED), coverErr);
			assertFalse("the cover DTO carries no host: " + coverErr, String.valueOf(coverErr).contains(HOST));
		}
		finally {
			try {
				PbDeleteUtil.deleteBookComplete(owner, bookOid);
			}
			catch(Exception e) {
				logger.warn("Cleanup of " + slug + ": " + e.getMessage());
			}
		}
	}
}
