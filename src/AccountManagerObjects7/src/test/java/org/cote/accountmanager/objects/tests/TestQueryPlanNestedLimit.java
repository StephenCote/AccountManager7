package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryPlan;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.RecordUtil;
import org.junit.Test;

/// Regression for GET /rest/model/{type}/{objectId}/full (ModelService.getFullModelByObjectId):
/// planMost(true) on a record whose nested foreign model is wider than 49 column-backed fields
/// (olio.llm.chatConfig, olio.sd.config) generated a JSON_BUILD_OBJECT with more than 100 arguments,
/// PostgreSQL rejected it, DBSearch returned null and the route 404'd. The plan now reduces such a
/// nested model to its common fields so the parent query succeeds.
public class TestQueryPlanNestedLimit extends BaseTest {

	private static final String CHAT_CONFIG = OlioModelNames.MODEL_CHAT_CONFIG;
	private static final String CHAT_REQUEST = OlioModelNames.MODEL_CHAT_REQUEST;

	private BaseRecord newDirectoryRecord(BaseRecord user, String model, String path, String name) {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, path);
		plist.parameter(FieldNames.FIELD_NAME, name);
		try {
			return ioContext.getFactory().newInstance(model, user, null, plist);
		} catch (FactoryException e) {
			logger.error(e);
		}
		return null;
	}

	@Test
	public void testWideNestedModelIsReducedToCommonFields() {
		List<String> most = RecordUtil.getMostRequestFields(CHAT_CONFIG);
		int wide = QueryPlan.countMaterializedFields(RecordFactory.getSchema(CHAT_CONFIG), most, CHAT_REQUEST);
		assertTrue("Precondition: " + CHAT_CONFIG + " must be wider than the nested limit to exercise the reduction (counted " + wide + ")", wide > QueryPlan.MAX_NESTED_PLAN_FIELDS);

		Query q = QueryUtil.createQuery(CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, "any");
		q.planMost(true);

		QueryPlan cfgPlan = q.getPlan("chatConfig");
		assertNotNull("chatConfig should still be planned", cfgPlan);
		List<String> common = Arrays.asList(RecordUtil.getCommonFields(CHAT_CONFIG));
		assertEquals("Over-budget nested model is planned with exactly its common fields", common.size(), cfgPlan.getPlanFields().size());
		assertTrue(cfgPlan.getPlanFields().containsAll(common));
		assertTrue("Reduced plan must fit the PostgreSQL argument limit", QueryPlan.countMaterializedFields(RecordFactory.getSchema(CHAT_CONFIG), cfgPlan.getPlanFields(), CHAT_REQUEST) <= QueryPlan.MAX_NESTED_PLAN_FIELDS);

		/// A nested model under the budget keeps its full MOST projection.
		QueryPlan promptPlan = q.getPlan("promptConfig");
		assertNotNull("promptConfig should be planned", promptPlan);
		int promptMost = RecordUtil.getMostRequestFields(OlioModelNames.MODEL_PROMPT_CONFIG).size();
		assertTrue("Under-budget nested model keeps most fields (" + promptPlan.getPlanFields().size() + " of " + promptMost + ")", promptPlan.getPlanFields().size() > common.size());
	}

	@Test
	public void testFullFindSucceedsWithWideNestedModel() {
		BaseRecord user = getCreateUser("queryPlanLimitUser");
		assertNotNull("Test user", user);
		String suffix = Long.toString(System.currentTimeMillis());

		BaseRecord cfg = newDirectoryRecord(user, CHAT_CONFIG, "~/Chat", "nested-limit-cfg-" + suffix);
		assertNotNull(cfg);
		cfg.setValue("model", "qwen3:8b");
		BaseRecord createdCfg = ioContext.getAccessPoint().create(user, cfg);
		assertNotNull("Chat config should be created", createdCfg);

		BaseRecord creq = newDirectoryRecord(user, CHAT_REQUEST, "~/ChatRequests", "nested-limit-req-" + suffix);
		assertNotNull(creq);
		creq.setValue("chatConfig", createdCfg);
		BaseRecord createdReq = ioContext.getAccessPoint().create(user, creq);
		assertNotNull("Chat request should be created", createdReq);
		String reqOid = createdReq.get(FieldNames.FIELD_OBJECT_ID);

		/// Same shape as ModelService.getFullModelByObjectId
		Query q = QueryUtil.createQuery(CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, reqOid);
		q.planMost(true);
		q.setCache(false);
		BaseRecord full = ioContext.getAccessPoint().find(user, q);
		assertNotNull("planMost(true) find must succeed when a nested model is wider than the argument limit", full);

		BaseRecord nestedCfg = full.get("chatConfig");
		assertNotNull("chatConfig should be populated", nestedCfg);
		assertEquals((long)createdCfg.get(FieldNames.FIELD_ID), (long)nestedCfg.get(FieldNames.FIELD_ID));
		assertEquals("nested-limit-cfg-" + suffix, nestedCfg.get(FieldNames.FIELD_NAME));

		/// The wide record itself is still fully readable as a top-level query.
		Query cq = QueryUtil.createQuery(CHAT_CONFIG, FieldNames.FIELD_OBJECT_ID, createdCfg.get(FieldNames.FIELD_OBJECT_ID));
		cq.planMost(true);
		cq.setCache(false);
		BaseRecord fullCfg = ioContext.getAccessPoint().find(user, cq);
		assertNotNull(fullCfg);
		assertEquals("qwen3:8b", fullCfg.get("model"));

		assertTrue(ioContext.getAccessPoint().delete(user, createdReq));
		assertTrue(ioContext.getAccessPoint().delete(user, createdCfg));
	}
}
