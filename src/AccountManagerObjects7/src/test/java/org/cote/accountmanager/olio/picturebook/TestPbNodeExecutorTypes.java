package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.junit.Before;
import org.junit.Test;

/// Audit 2026-10-07 item 6: PbNodeExecutor.executeNode threw a bare 501 for eleven of the seventeen node types (UNKNOWN included)
/// while the canvas offered "Test" on every unpinned node. The 501 is kept (those stages have no single-node
/// executor; they are produced by the whole-book pipeline) but it is now (a) decided by one declared set,
/// PbNodeExecutor.EXECUTABLE_TYPES, (b) raised BEFORE any IO, naming the supported types, and (c) published
/// per node as the graph DTO's `executable` flag so the canvas only offers "Test" where it can succeed.
/// Real DB for the DTO half (a book, its workflow, one node of every type); no LLM, no SD.
public class TestPbNodeExecutorTypes extends BaseTest {

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	@Before
	public void setupEach() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	/// Pure: the switch in executeNode and the declared set agree for EVERY enum value, and a
	/// non-executable type is refused with 501 before the executor touches user/book/workflow/server
	/// (all null here - any IO attempt would NPE instead of throwing the PictureBookException).
	@Test
	public void TestEveryNodeTypeIsEitherExecutableOrA501BeforeAnyIO() throws Exception {
		OlioModelNames.use();
		EnumSet<PbNodeTypeEnumType> expectedExecutable = EnumSet.of(
			PbNodeTypeEnumType.PORTRAIT, PbNodeTypeEnumType.LANDSCAPE, PbNodeTypeEnumType.SCENE_PROMPT,
			PbNodeTypeEnumType.LANDSCAPE_PROMPT, PbNodeTypeEnumType.REFERENCE_STRIP, PbNodeTypeEnumType.COMPOSITE,
			PbNodeTypeEnumType.RENDER_PLAN);
		assertEquals("the declared executable set is exactly the seven implemented stages", expectedExecutable, PbNodeExecutor.EXECUTABLE_TYPES);
		assertFalse("null is never executable", PbNodeExecutor.isExecutable((PbNodeTypeEnumType) null));
		assertFalse("a null node is never executable", PbNodeExecutor.isExecutable((BaseRecord) null));
		List<String> names = PbNodeExecutor.executableTypeNames();
		assertEquals(7, names.size());
		assertTrue(names.contains("PORTRAIT"));
		assertTrue(names.contains("RENDER_PLAN"));

		for(PbNodeTypeEnumType t : PbNodeTypeEnumType.values()) {
			BaseRecord node = RecordFactory.newInstance(OlioModelNames.MODEL_PB_NODE);
			node.set(OlioFieldNames.FIELD_PB_NODE_TYPE, t.toString());
			node.set(OlioFieldNames.FIELD_PB_HANDLE, "h-" + t.name().toLowerCase());
			assertEquals("isExecutable(record) agrees with isExecutable(type) for " + t,
				PbNodeExecutor.isExecutable(t), PbNodeExecutor.isExecutable(node));
			if(PbNodeExecutor.isExecutable(t)) {
				/// Executable types get past the 501 gate; with no server/book/user they fail on the NEXT gate
				/// (500 "SD server not configured" for the SD-backed types, 400 from openNodeGraph for the
				/// rest) - never a 501.
				try {
					PbNodeExecutor.executeNode(null, null, null, node, (String) null);
					fail("an executable type with no inputs must still fail its own guard: " + t);
				}
				catch(PictureBookException pbe) {
					assertTrue(t + " is executable, so its failure is its own guard, not the 501: " + pbe.getStatus()
						+ " " + pbe.getMessage(), pbe.getStatus() != 501);
					if(PbNodeExecutor.SD_BACKED_TYPES.contains(t)) {
						assertEquals("SD-backed " + t + " with no server is the SD gate", 500, pbe.getStatus());
						assertTrue(pbe.getMessage(), pbe.getMessage().contains("SD server not configured"));
					}
					else {
						assertEquals("non-SD " + t + " with no book/user is openNodeGraph's 400", 400, pbe.getStatus());
					}
				}
			}
			else {
				try {
					PbNodeExecutor.executeNode(null, null, null, node, (String) null);
					fail("non-executable type must be refused: " + t);
				}
				catch(PictureBookException pbe) {
					assertEquals("non-executable " + t + " is a 501", 501, pbe.getStatus());
					assertTrue("the 501 names the type: " + pbe.getMessage(), pbe.getMessage().contains(t.name()));
					assertTrue("the 501 names the supported types: " + pbe.getMessage(), pbe.getMessage().contains("PORTRAIT"));
				}
			}
		}
	}

	/// Live: a book with one node of every type; the graph DTO (what GET .../workflow serves the canvas)
	/// carries `executable` on each node, true exactly for EXECUTABLE_TYPES.
	@Test
	public void TestWorkflowViewPublishesExecutablePerNode() throws Exception {
		OlioModelNames.use();
		BaseRecord owner = getCreateUser("pbNodeTypeOwner");
		assertNotNull("owner", owner);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);
		String slug = "ntx" + shortId();
		BaseRecord book = PbBookUtil.createBook(owner, dataPath, slug, "Node Types " + slug);
		assertNotNull("book", book);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		try {
			String wfPath = PbBookUtil.workflowGroupPath(slug);
			BaseRecord wf = PbGraphUtil.getCreateWorkflow(owner, book, wfPath);
			assertNotNull("workflow", wf);
			int ordinal = 0;
			for(PbNodeTypeEnumType t : PbNodeTypeEnumType.values()) {
				BaseRecord node = PbGraphUtil.addNode(owner, wf, "n-" + t.name().toLowerCase() + "-" + slug, t, wfPath, ordinal++);
				assertNotNull("node " + t, node);
			}

			Map<String, Object> view = PbServiceFacade.workflowView(owner, bookOid);
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> nodes = (List<Map<String, Object>>) view.get("nodes");
			assertEquals("one node per type", PbNodeTypeEnumType.values().length, nodes.size());
			int executableCount = 0;
			for(Map<String, Object> n : nodes) {
				String typeName = (String) n.get("nodeType");
				assertNotNull("nodeType in DTO", typeName);
				PbNodeTypeEnumType t = PbNodeTypeEnumType.valueOf(typeName.toUpperCase());
				Object flag = n.get("executable");
				assertTrue("executable is a Boolean for " + t + ": " + flag, flag instanceof Boolean);
				assertEquals("executable flag for " + t, PbNodeExecutor.isExecutable(t), ((Boolean) flag).booleanValue());
				if(((Boolean) flag).booleanValue()) {
					executableCount++;
				}
				else {
					/// And the route the flag protects really does answer 501 for that node - before the SD
					/// gate, so a null backend is irrelevant here.
					try {
						PbServiceFacade.testNode(owner, bookOid, (String) n.get("objectId"), null, null);
						fail("testNode on a non-executable node must be a 501: " + t);
					}
					catch(PictureBookException pbe) {
						assertEquals("testNode 501 for " + t, 501, pbe.getStatus());
					}
				}
			}
			assertEquals("seven executable types", PbNodeExecutor.EXECUTABLE_TYPES.size(), executableCount);
		}
		finally {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(owner, bookOid);
			assertTrue("cleanup book: " + res.reason, res.deleted);
		}
	}
}
