package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The boot-pinned {@link PbRenderPath} switch after Phase 10 P10-1 (§6.C 248 g): one value, {@code chat.renderPath},
 * default {@code v3} with {@code v1} as the chat rollback. The {@code picturebook.renderPath} /
 * {@code chapbook.renderPath} keys, the {@link PbServiceFacade} 409 gate they drove and the PB1 entry-point
 * refusal were removed with PB1 itself; both book products have exactly one render path, so the facade cases here
 * only prove that the former gate sites now fall straight through to their existence check (404, never 409).
 * <p>
 * The resolver cases are pure. The facade case runs against the shared {@code pb2c-graph} fixture book in
 * {@code am7db}. The switch is process-global: every case restores {@code v3} (what the test properties pin and
 * what {@code BaseTest.setup()} re-applies on every {@code @Before}) in {@code finally}.
 */
public class TestPbRenderPath extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SLUG = "pb2c-graph";

	@Before
	public void renderPathSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	@After
	public void restoreV3() {
		PbRenderPath.configure(PbRenderPath.V3);
	}

	// ─────────────────────────────── resolver ───────────────────────────────

	/** Absent (null / blank) pins the chat default - v3 since P10-A 0 (§6.C 249); the call never throws. */
	@Test
	public void case01_absentIsTheChatDefault() {
		assertEquals(PbRenderPath.V3, PbRenderPath.CHAT_DEFAULT);
		PbRenderPath.configure(null);
		assertEquals("absent pins the chat default v3", PbRenderPath.V3, PbRenderPath.chat());
		assertTrue(PbRenderPath.isChatV3());
		PbRenderPath.configure("   ");
		assertEquals("blank pins the chat default v3", PbRenderPath.V3, PbRenderPath.chat());
		assertTrue(PbRenderPath.isChatV3());
	}

	/** The value is trimmed and case-insensitive; an explicit v1 is honoured as the rollback and absent restores v3. */
	@Test
	public void case02_normalisedAndV1IsRollback() {
		PbRenderPath.configure(" V3 ");
		assertEquals(PbRenderPath.V3, PbRenderPath.chat());
		PbRenderPath.configure("V1");
		assertEquals("an explicit v1 is honoured (rollback)", PbRenderPath.V1, PbRenderPath.chat());
		assertFalse(PbRenderPath.isChatV3());
		PbRenderPath.configure(null);
		assertEquals("absent after a rollback restores the chat default", PbRenderPath.V3, PbRenderPath.chat());
		assertTrue(PbRenderPath.isChatV3());
	}

	/** An unrecognised value pins the default (a typo must never silently roll chat back) and never throws. */
	@Test
	public void case03_unrecognisedIsTheChatDefault() {
		PbRenderPath.configure("v2");
		assertEquals("a misspelling must not roll chat back", PbRenderPath.V3, PbRenderPath.chat());
		PbRenderPath.configure("V1");
		assertEquals(PbRenderPath.V1, PbRenderPath.chat());
		PbRenderPath.configure("graph");
		assertEquals("a bad value after a rollback pins the default, not the previous value", PbRenderPath.V3, PbRenderPath.chat());
	}

	/** The test properties pin chat to v3 and BaseTest re-applies it, so after setup chat reads v3. */
	@Test
	public void case04_testPropertiesPinV3() {
		assertEquals(PbRenderPath.V3, testProperties.getProperty(PbRenderPath.CHAT_CONFIG_KEY));
		assertEquals(PbRenderPath.V3, PbRenderPath.chat());
		assertTrue(PbRenderPath.isChatV3());
	}

	// ─────────────────────────────── facade ───────────────────────────────

	/**
	 * P10-1: the facade has no render-path gate any more. The entries that used to answer 409 under
	 * {@code picturebook.renderPath=v1} now pass straight to their existence check: reads answer, and an unknown id
	 * reaches the 404 gate with no render-path text in the message - whatever the chat switch says.
	 */
	@Test
	public void case05_facadeHasNoRenderPathGate() {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		String bid = bk.get(FieldNames.FIELD_OBJECT_ID);
		String none = "no-such-" + System.nanoTime();
		try {
			PbRenderPath.configure(PbRenderPath.V1);
			Map<String, Object> aa = PbServiceFacade.getAutoApprove(u, bid);
			assertNotNull(aa);
			assertEquals(bid, aa.get("bookObjectId"));
			assertNotNull("listReviewQueue", PbServiceFacade.listReviewQueue(u, bid, null));
			assertNotNull("workflowView", PbServiceFacade.workflowView(u, bid));
			for(String call : new String[] { "getRun", "approveArtifact", "cancelRun", "nodeView", "artifactView" }) {
				try {
					switch(call) {
						case "getRun": PbServiceFacade.getRun(u, bid, none); break;
						case "approveArtifact": PbServiceFacade.approveArtifact(u, bid, none); break;
						case "cancelRun": PbServiceFacade.cancelRun(u, bid, none); break;
						case "nodeView": PbServiceFacade.nodeView(u, bid, none); break;
						default: PbServiceFacade.artifactView(u, bid, none); break;
					}
					fail(call + " with an unknown id must be 404");
				}
				catch(PictureBookException e) {
					assertEquals(call + " must reach the existence gate", 404, e.getStatus());
					assertFalse(call + " must not carry render-path text: " + e.getMessage(),
						e.getMessage() != null && e.getMessage().contains("renderPath"));
				}
			}
			/// A missing book is still 404 first.
			try {
				PbServiceFacade.getRun(u, none, none);
				fail("unknown book must be 404");
			}
			catch(PictureBookException e) {
				assertEquals(404, e.getStatus());
			}
		}
		finally {
			PbRenderPath.configure(PbRenderPath.V3);
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull("organization " + ORG_A, org);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("user " + TEST_USER, u);
		return u;
	}

	private long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	private String dataPath() {
		String p = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", p);
		return p;
	}

	private BaseRecord book(BaseRecord u) {
		BaseRecord bk = PbBookUtil.findBookBySlug(u, SLUG, orgOf(u));
		if(bk == null) {
			bk = PbBookUtil.createBook(u, dataPath(), SLUG, "Phase 2c graph fixture");
		}
		assertNotNull("book " + SLUG, bk);
		/// The facade's workflow gate needs the graph to exist; the sibling tests create it the same way.
		assertNotNull("workflow", PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(SLUG)));
		return bk;
	}
}
