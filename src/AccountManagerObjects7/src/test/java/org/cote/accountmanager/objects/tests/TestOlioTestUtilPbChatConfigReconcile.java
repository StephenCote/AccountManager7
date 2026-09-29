package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/**
 * {@link OlioTestUtil#getPbChatConfig} must RECONCILE an existing chatConfig row of the same name into
 * the picture-book shape (stream=false, chatOptions.think=false, chatOptions.temperature=0.3), not just
 * create it that way. A row first created as an analysis config (or edited by an earlier run) would
 * otherwise keep stream/think on and hand {@code <think>} blocks to the extraction parsers.
 * <p>
 * DB-only: no LLM call is made. The row is forced back into the analysis shape at the start of every
 * run so the reconcile branch is exercised each time, and persistence is checked with an independent
 * {@code cache:false} re-read, not the record the utility hands back. Non-admin user throughout; the
 * admin user only provisions it.
 */
public class TestOlioTestUtilPbChatConfigReconcile extends BaseTest {

	private static final String ORG_PATH = "/Development/ChapBook Tests";
	private static final String CFG_NAME = "pbReconcileProbeLlm";

	@Test
	public void reconcilesExistingAnalysisRowIntoPbShape() throws Exception {
		OrganizationContext ctx = getTestOrganization(ORG_PATH);
		BaseRecord user = ioContext.getFactory().getCreateUser(ctx.getAdminUser(), "pbReconcileProbeUser", ctx.getOrganizationId());
		assertNotNull("pbReconcileProbeUser must be created", user);

		BaseRecord cfg = OlioTestUtil.getOllamaOpenAIConfig(user, CFG_NAME, testProperties);
		assertNotNull("analysis chatConfig must exist", cfg);
		long id = cfg.get(FieldNames.FIELD_ID);

		BaseRecord opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
		opts.set("think", true);
		opts.set("temperature", 1.0);
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_CONFIG,
			new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "stream", "chatOptions"});
		patch.set(FieldNames.FIELD_ID, id);
		patch.set(FieldNames.FIELD_OBJECT_ID, cfg.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_NAME, cfg.get(FieldNames.FIELD_NAME));
		patch.set("stream", true);
		patch.set("chatOptions", opts);
		assertNotNull("forcing the analysis shape must succeed", IOSystem.getActiveContext().getAccessPoint().update(user, patch));

		BaseRecord baseline = readFresh(user, id);
		assertEquals("baseline stream", Boolean.TRUE, baseline.get("stream"));
		BaseRecord baseOpts = baseline.get("chatOptions");
		assertNotNull("baseline chatOptions", baseOpts);
		assertEquals("baseline think", Boolean.TRUE, baseOpts.get("think"));
		assertEquals("baseline temperature", 1.0, ((Number) baseOpts.get("temperature")).doubleValue(), 1e-9);

		/// The record the utility hands back is read with OlioUtil.planMost, whose FULL_PLAN_FILTER drops
		/// FIELD_STREAM (it is aimed at data.data's byte stream), so `stream` is asserted on the
		/// independent plain-planMost re-read below, and chatOptions on both.
		BaseRecord pb = OlioTestUtil.getPbChatConfig(user, CFG_NAME, testProperties);
		assertNotNull("getPbChatConfig must return the reconciled row", pb);
		assertEquals("same row, not a duplicate", id, ((Number) pb.get(FieldNames.FIELD_ID)).longValue());
		assertPbOptions("returned record", pb);
		assertEquals("model reconciled to test.llm.model.pb", testProperties.getProperty("test.llm.model.pb"), pb.get("model"));

		BaseRecord back = readFresh(user, id);
		assertEquals("persisted stream", Boolean.FALSE, back.get("stream"));
		assertPbOptions("independent cache:false re-read", back);

		BaseRecord again = OlioTestUtil.getPbChatConfig(user, CFG_NAME, testProperties);
		assertEquals("second call is idempotent on the same row", id, ((Number) again.get(FieldNames.FIELD_ID)).longValue());
		assertPbOptions("second call", again);
		assertEquals("stream after second call", Boolean.FALSE, readFresh(user, id).get("stream"));
	}

	private static void assertPbOptions(String label, BaseRecord cfg) {
		BaseRecord opts = cfg.get("chatOptions");
		assertNotNull(label + ": chatOptions", opts);
		assertEquals(label + ": think", Boolean.FALSE, opts.get("think"));
		Object temp = opts.get("temperature");
		assertNotNull(label + ": temperature", temp);
		assertEquals(label + ": temperature", 0.3, ((Number) temp).doubleValue(), 1e-9);
	}

	private static BaseRecord readFresh(BaseRecord user, long id) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_ID, id);
		q.setCache(false);
		q.planMost(true);
		BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		assertNotNull("chatConfig id=" + id + " must be readable", rec);
		assertTrue("stream must be projected by planMost(true)", rec.hasField("stream"));
		return rec;
	}
}
