package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ScriptException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.objects.generated.FactType;
import org.cote.accountmanager.objects.generated.OperationType;
import org.cote.accountmanager.objects.generated.PatternType;
import org.cote.accountmanager.objects.generated.PolicyRequestType;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.objects.generated.RuleType;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConditionEnumType;
import org.cote.accountmanager.schema.type.FactEnumType;
import org.cote.accountmanager.schema.type.OperationEnumType;
import org.cote.accountmanager.schema.type.PatternEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.schema.type.RuleEnumType;
import org.junit.Test;

public class TestChatPolicy extends BaseTest {


	private boolean recreate = false;
	
	@Test
	public void TestChatPolicyRewriteRequest() {
		logger.info("Test Chat Policy - Rewrite The User Request");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Olio LLM Policies");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		String chatCfgName = "Open AI Policy Chat - " + UUID.randomUUID().toString();
		BaseRecord cfg = OlioTestUtil.getChatConfig(testUser1, LLMServiceEnumType.valueOf(testProperties.getProperty("test.llm.type").toUpperCase()), testProperties.getProperty("test.llm.type").toUpperCase() + " " + chatCfgName, testProperties);
		
		BaseRecord pcfg = OlioTestUtil.getObjectPromptConfig(testUser1, "Policy Genesis Prompt");
		List<String> system = pcfg.get("system");
		system.clear();
		system.add("You aggregate and organize data across multiple prompt chains.");
		IOSystem.getActiveContext().getAccessPoint().update(testUser1, pcfg);

		
		BaseRecord pcfg2 = OlioTestUtil.getObjectPromptConfig(testUser1, "Policy Fact Prompt 1");
		List<String> system2 = pcfg2.get("system");
		system2.clear();
		
		String unnecessarilyPCPromptPolicePrompt = "You are the PROMPT POLICE!" + System.lineSeparator()
			+ "Your job is to REWRITE the user request to ensure that it is unnecessarily woke and politically correct." + System.lineSeparator()
			+ "RESPOND WITH YOUR REWRITE OF THE USER REQUEST." + System.lineSeparator()
			+ "DO NOT ASK QUESTIONS, RESPOND TO, OR CONTINUE THE USER QUESTION." + System.lineSeparator()
			+ "Example: " + System.lineSeparator()
			+ "User: Why is the sky blue?" + System.lineSeparator()
			+ "You: Why is the sky blue? Include a description of all light and color related references that would make sense to visually impaired individuals."
		;
		system2.add(unnecessarilyPCPromptPolicePrompt);
		IOSystem.getActiveContext().getAccessPoint().update(testUser1, pcfg2);

		PolicyType chatPol = getChatPolicy(testUser1, cfg, pcfg2);
		assertNotNull("Policy is null", chatPol);
		cfg.setValue("policy", chatPol);
		Queue.queueUpdate(cfg, new String[] {"policy"});
		Queue.processQueue();

		
		String chatName = "Policy Chat Test " + UUID.randomUUID().toString();
		BaseRecord creq = ChatUtil.getCreateChatRequest(testUser1, chatName, cfg, pcfg);
		assertNotNull("Chat request is null", creq);

		OpenAIRequest req = ChatUtil.getChatSession(testUser1, chatName, cfg, pcfg);

		Chat chat = new Chat(testUser1, cfg, pcfg);
		
		PolicyRequestType prt = IOSystem.getActiveContext().getPolicyUtil().getPolicyRequest(chatPol, testUser1);
		assertTrue("Expected one parameter", prt.getFacts().size() == 1);
		String uquest = "What's the weather like today?";
		prt.getFacts().get(0).setFactData(uquest);
		prt.getFacts().get(0).setValue("factReference", req);
		PolicyResponseType prr = null;
		try {
			// IOSystem.getActiveContext().getPolicyUtil().setTrace(true);
			prr = ioContext.getPolicyEvaluator().evaluatePolicyRequest(prt).toConcrete();
			// IOSystem.getActiveContext().getPolicyUtil().setTrace(false);
		} catch (NullPointerException | FieldException | ModelNotFoundException | ValueException | ScriptException | IndexException | ReaderException | ModelException e) {
			e.printStackTrace();
		}
		assertNotNull("Policy response is null", prr);
		logger.info(prr.toFullString());
		assertTrue("Expected policy to be permitted", prr.getType() == PolicyResponseEnumType.PERMIT);
		
		String revisedQuest = prt.getFacts().get(0).getFactData();
		assertFalse("Question was not revised", uquest.equals(revisedQuest));
		
		logger.info("Revised question: " + revisedQuest);
	}
	
	public PolicyType getChatPolicy(BaseRecord user, BaseRecord cfg, BaseRecord pcfg) {
		FactType pfact = getCreateFact(user, "Genesis Param Fact");
		pfact.setType(FactEnumType.PARAMETER);
		
		FactType mfact = getCreateFact(user, "Genesis Chat Fact");
		mfact.setType(FactEnumType.STATIC);
		mfact.setValue("chatConfig", cfg);
		mfact.setValue("promptConfig", pcfg);

		Queue.queue(pfact);
		Queue.queue(mfact);
		
		PatternType pat = getCreatePattern(user, "Genesis Pat");
		pat.setFact(pfact);
		pat.setMatch(mfact);
		Queue.queue(pat);
		
		Queue.processQueue(user);
		
		RuleType rul = getCreateRule(user, "Genesis Rule");
		IOSystem.getActiveContext().getMemberUtil().member(user, rul, pat, null, true);

		PolicyType pol = getCreatePolicy(user, "Genesis Policy");
		IOSystem.getActiveContext().getMemberUtil().member(user, pol, rul, null, true);
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_POLICY, FieldNames.FIELD_OBJECT_ID, pol.get(FieldNames.FIELD_OBJECT_ID));
		q.planMost(true);
		QueryResult qr = null;
		try {
			qr = IOSystem.getActiveContext().getSearch().find(q);
		} catch (ReaderException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		if(qr != null && qr.getResults().length > 0) {
			return new PolicyType(qr.getResults()[0]);
		}
		return null;
	}

	public PolicyType getCreatePolicy(BaseRecord user, String policyName) {
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Policies", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		PolicyType pol = null;
		BaseRecord[] upol = new BaseRecord[0];
		try {
			upol = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_POLICY, dir.get(FieldNames.FIELD_ID), policyName);
			if(upol.length > 0) {
				if(recreate) {
					IOSystem.getActiveContext().getRecordUtil().deleteRecord(upol[0]);
				} else {
					return new PolicyType(upol[0]);
				}
			}
			
			pol = new PolicyType();
			pol.setEnabled(true);
			pol.setCondition(ConditionEnumType.ALL);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, pol, policyName, "~/Policies", user.get(FieldNames.FIELD_ORGANIZATION_ID));
			IOSystem.getActiveContext().getRecordUtil().createRecord(pol);
		} catch (ReaderException | FieldException | ValueException | ModelNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return pol;
	}
	
	public RuleType getCreateRule(BaseRecord user, String ruleName) {
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Rules", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		RuleType rul = null;
		BaseRecord[] urul = new BaseRecord[0];
		try {
			urul = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_RULE, dir.get(FieldNames.FIELD_ID), ruleName);
			if(urul.length > 0) {
				if(recreate) {
					IOSystem.getActiveContext().getRecordUtil().deleteRecord(urul[0]);
				} else {
					return new RuleType(urul[0]);
				}
			}
			
			rul = new RuleType();
			rul.setType(RuleEnumType.TRANSFORM);
			rul.setCondition(ConditionEnumType.ALL);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, rul, ruleName, "~/Rules", user.get(FieldNames.FIELD_ORGANIZATION_ID));
			IOSystem.getActiveContext().getRecordUtil().createRecord(rul);
		} catch (ReaderException | FieldException | ValueException | ModelNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return rul;
	}
	
	public PatternType getCreatePattern(BaseRecord user, String patternName) {
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Patterns", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		PatternType pat = null;
		BaseRecord[] upat = new BaseRecord[0];
		try {
			upat = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_PATTERN, dir.get(FieldNames.FIELD_ID), patternName);
			if(upat.length > 0) {
				if(recreate) {
					IOSystem.getActiveContext().getRecordUtil().deleteRecord(upat[0]);
				} else {
					return new PatternType(upat[0]);
				}
			}
			
			pat = new PatternType();
			pat.setType(PatternEnumType.OPERATION);
			pat.setOperation(getCreateOperation(user, patternName + " Operation"));
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, pat, patternName, "~/Patterns", user.get(FieldNames.FIELD_ORGANIZATION_ID));
			IOSystem.getActiveContext().getRecordUtil().createRecord(pat);
		} catch (ReaderException | FieldException | ValueException | ModelNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return pat;
	}
	
	public OperationType getCreateOperation(BaseRecord user, String opName) {
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Operations", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		OperationType ope = null;
		BaseRecord[] uope = new BaseRecord[0];
		try {
			uope = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_OPERATION, dir.get(FieldNames.FIELD_ID), opName);
			if(uope.length > 0) {
				if(recreate) {
					IOSystem.getActiveContext().getRecordUtil().deleteRecord(uope[0]);
				} else {
					return new OperationType(uope[0]);
				}
			}
			
			ope = new OperationType();
			ope.setType(OperationEnumType.INTERNAL);
			ope.setOperation("org.cote.accountmanager.olio.operation.ChatOperation");

			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, ope, opName, "~/Operations", user.get(FieldNames.FIELD_ORGANIZATION_ID));
			IOSystem.getActiveContext().getRecordUtil().createRecord(ope);
		} catch (ReaderException | FieldException | ValueException | ModelNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return ope;
	}
	
	public FactType getCreateFact(BaseRecord user, String factName) {
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Facts", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		FactType fac = null;
		BaseRecord[] ufac = new BaseRecord[0];
		try {
			ufac = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_FACT, dir.get(FieldNames.FIELD_ID), factName);
			if(ufac.length > 0) {
				if(recreate) {
					IOSystem.getActiveContext().getRecordUtil().deleteRecord(ufac[0]);
				} else {
					return new FactType(ufac[0]);
				}
			}
			
			fac = new FactType();
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, fac, factName, "~/Facts", user.get(FieldNames.FIELD_ORGANIZATION_ID));
			IOSystem.getActiveContext().getRecordUtil().createRecord(fac);
		} catch (ReaderException | FieldException | ValueException | ModelNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return fac;
	}

	public void addMessage(BaseRecord req, String role, String message) {
		BaseRecord aimsg = null;
		try {
			aimsg = RecordFactory.newInstance(OlioModelNames.MODEL_OPENAI_MESSAGE);
			aimsg.set("role", role);
			aimsg.set("content", message);
			List<BaseRecord> msgs = req.get("messages");
			msgs.add(aimsg);

		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}

	}


}
