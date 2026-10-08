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
			PbNodeTypeEnumType.LANDSCAPE_PROMPT, PbNodeTypeEnumType.REFERENCE_STRIP, PbNodeTypeEnumType.COMPOSITE);
		assertEquals("the declared executable set is exactly the six implemented stages", expectedExecutable, PbNodeExecutor.EXECUTABLE_TYPES);
		assertFalse("null is never executable", PbNodeExecutor.isExecutable((PbNodeTypeEnumType) null));
		assertFalse("a null node is never executable", PbNodeExecutor.isExecutable((BaseRecord) null));
		List<String> names = PbNodeExecutor.executableTypeNames();
		assertEquals(6, names.size());
		assertTrue(names.contains("PORTRAIT"));

		for(PbNodeTypeEnumType t : PbNodeTypeEnumType.values()) {
			BaseRecord node = RecordFactory.newInstance(OlioModelNames.MODEL_PB_NODE);
			node.set(OlioFieldNames.FIELD_PB_NODE_TYPE, t.toString());
			node.set(OlioFieldNames.FIELD_PB_HANDLE, "h-" + t.name().toLowerCase());
			assertEquals("isExecutable(record) agrees with isExecutable(type) for " + t,
				PbNodeExecutor.isExecutable(t), PbNodeExecutor.isExecutable(node));
			if(PbNodeExecutor.isExecutable(t)) {
				/// Executable types proceed into their executor; with no server/scopeRef they fail on THEIR
				/// own first guard (503 no SD server, 400 no scopeRef, 400 no bindings) - never a 501.
				try {
					PbNodeExecutor.executeNode(null, null, null, node, null);
					fail("an executable type with no inputs must still fail its own guard: " + t);
				}
				catch(PictureBookException pbe) {
					assertTrue(t + " is executable, so its failure is its own guard, not the 501: " + pbe.getStatus()
						+ " " + pbe.getMessage(), pbe.getStatus() != 501);
				}
				catch(NullPointerException npe) {
					/// REFERENCE_STRIP has no pre-IO guard (it lists bindings first) - reaching IO with a null
					/// user is still proof the 501 gate let it through.
					assertEquals("only REFERENCE_STRIP reaches IO before a guard", PbNodeTypeEnumType.REFERENCE_STRIP, t);
				}
			}
			else {
				try {
					PbNodeExecutor.executeNode(null, null, null, node, null);
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
					/// And the route the flag protects really does answer 501 for that node.
					try {
						PbServiceFacade.testNode(owner, bookOid, (String) n.get("objectId"));
						fail("testNode on a non-executable node must be a 501: " + t);
					}
					catch(PictureBookException pbe) {
						assertEquals("testNode 501 for " + t, 501, pbe.getStatus());
					}
				}
			}
			assertEquals("six executable types", PbNodeExecutor.EXECUTABLE_TYPES.size(), executableCount);
		}
		finally {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(owner, bookOid);
			assertTrue("cleanup book: " + res.reason, res.deleted);
		}
	}
}
