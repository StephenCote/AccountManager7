
package org.cote.accountmanager.policy;

import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ScriptException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.IWriter;
import org.cote.accountmanager.policy.operation.IOperation;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.ConditionEnumType;
import org.cote.accountmanager.schema.type.FactEnumType;
import org.cote.accountmanager.schema.type.OperationEnumType;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.schema.type.PatternEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.schema.type.RuleEnumType;
import org.cote.accountmanager.security.AuthorizationUtil;
import org.cote.accountmanager.util.MemberUtil;
import org.cote.accountmanager.util.ScriptUtil;

public class PolicyEvaluator {

	public static final Logger logger = LogManager.getLogger(PolicyEvaluator.class);

	/*
	private static DatatypeFactory dtFactory = null;	
	static{
		try {
			dtFactory = DatatypeFactory.newInstance();
		} catch (DatatypeConfigurationException e) {
			
			logger.error(e);
		}
	}
	*/
	private IReader reader = null;
	private IWriter writer = null;
	private ISearch search = null;
	private FactUtil futil = null;
	private AuthorizationUtil authUtil = null;
	private MemberUtil memberUtil = null;
	private boolean trace = false;
	
	public PolicyEvaluator(IReader reader, IWriter writer, ISearch search, AuthorizationUtil authUtil, MemberUtil memUtil){
		this.reader = reader;
		this.writer = writer;
		this.search = search;
		futil = new FactUtil(reader, search);
		this.authUtil = authUtil; //IOFactory.getAuthorizationUtil(reader, writer, search);
		this.memberUtil = memUtil; //IOFactory.getMemberUtil(reader, writer, search);
	}
	
	public PolicyEvaluator(IOContext context){
		this(context.getReader(), context.getWriter(), context.getSearch(), context.getAuthorizationUtil(), context.getMemberUtil());
	}

	public boolean isTrace() {
		return trace;
	}



	public void setTrace(boolean trace) {
		authUtil.setTrace(trace);
		this.trace = trace;
	}



	public BaseRecord evaluatePolicyRequest(BaseRecord prt) throws FieldException, ModelNotFoundException, ValueException, ScriptException, IndexException, ReaderException, ModelException {


		logger.info("Evaluating Policy Request " + prt.get(FieldNames.FIELD_URN) + " in Organization " + prt.get(FieldNames.FIELD_ORGANIZATION_PATH));
		BaseRecord pol = getPolicyFromRequest(prt);
		return evaluatePolicyRequest(prt, pol);
	}
	public BaseRecord evaluatePolicyRequest(BaseRecord prt, BaseRecord pol) throws FieldException, ModelNotFoundException, ValueException, ScriptException, IndexException, ReaderException, ModelException {	
		BaseRecord prr = RecordFactory.model(ModelNames.MODEL_POLICY_RESPONSE).newInstance();
		if(pol == null) {
			if(prt.get(FieldNames.FIELD_URN) == null){
		
				logger.error("Policy Request Urn is null");
				prr.set(FieldNames.FIELD_TYPE, PolicyResponseEnumType.INVALID_ARGUMENT.toString());
				return prr;
			}
			
			pol = getPolicyFromRequest(prt);
			if(pol == null){
				logger.error("Failed to retrieve policy from urn '" + prt.get(FieldNames.FIELD_URN) + "'");
				prr.set(FieldNames.FIELD_TYPE, PolicyResponseEnumType.INVALID_ARGUMENT.toString());
				return prr;
			}

		}
		if((boolean)prt.get(FieldNames.FIELD_VERBOSE)) {
			prr.set(FieldNames.FIELD_DESCRIPTION, IOSystem.getActiveContext().getPolicyUtil().printPolicy(pol.toConcrete()));
		}
		
		String purn = prt.get(FieldNames.FIELD_URN);
		if(purn != null) {
			prr.set(FieldNames.FIELD_URN, purn);		
		}
		else {
			prr.set(FieldNames.FIELD_URN, pol.get(FieldNames.FIELD_URN));
		}

		GregorianCalendar cal = new GregorianCalendar();
	    cal.setTime(new Date());
		cal.add(GregorianCalendar.SECOND, ((Long)pol.get(FieldNames.FIELD_DECISION_AGE)).intValue());
		prr.set(FieldNames.FIELD_EXPIRY_DATE, cal.getTime());
		
		List<BaseRecord> facts = prt.get(FieldNames.FIELD_FACTS);
		boolean enabled = pol.get(FieldNames.FIELD_ENABLED);
		if(!enabled){
			prr.set(FieldNames.FIELD_TYPE, PolicyResponseEnumType.DISABLED.toString());
			prr.set(FieldNames.FIELD_MESSAGE, "Policy is disabled");
			logger.error("Policy is disabled");
		}
		else{
			evaluatePolicy(pol, facts, prt, prr);
		}
		
		return prr;
		
	}
	
	private BaseRecord getPolicyFromRequest(BaseRecord prt) {
		BaseRecord[] records = new BaseRecord[0];
		try {
			records = search.findByUrn(ModelNames.MODEL_POLICY, prt.get(FieldNames.FIELD_URN));
		} catch (IndexException | ReaderException e) {
			logger.error(e);
		}
		BaseRecord outRec = null;
		if(records.length > 0) {
			outRec = records[0];
			reader.populate(outRec);
		}
		return outRec;
	}

	private void evaluatePolicy(BaseRecord pol, List<BaseRecord> facts, BaseRecord prt, BaseRecord prr) throws FieldException, ValueException, ModelNotFoundException, ScriptException, IndexException, ReaderException, ModelException{
		List<BaseRecord> rules = pol.get(FieldNames.FIELD_RULES);
		int pass = 0;
		int size = rules.size();
		logger.debug("Evaluating Policy " + pol.get(FieldNames.FIELD_URN) + " " + pol.get(FieldNames.FIELD_CONDITION));
		ConditionEnumType cond = ConditionEnumType.valueOf(pol.get(FieldNames.FIELD_CONDITION));
		for(BaseRecord rule : rules) {
			reader.populate(rule);
			if(evaluateRule(rule, facts,prt, prr)){
				pass++;
				if(cond == ConditionEnumType.ANY){
					logger.debug("Breaking on Policy Condition " + cond);
					break;
				}
			}
		}
		boolean success = (
			(cond == ConditionEnumType.ANY && pass > 0)
			||
			(cond == ConditionEnumType.ALL && pass > 0 && pass == size)
			||
			(cond == ConditionEnumType.NONE && pass == 0)
		);
		
		if(success){
			int sumScore = (int)prr.get(FieldNames.FIELD_SCORE) + (int)pol.get(FieldNames.FIELD_SCORE);
			prr.set(FieldNames.FIELD_SCORE, sumScore);
			prr.set(FieldNames.FIELD_TYPE, PolicyResponseEnumType.PERMIT.toString());
		}
		else prr.set(FieldNames.FIELD_TYPE, PolicyResponseEnumType.DENY.toString());
		// logger.info("Policy " + pol.get(FieldNames.FIELD_URN) + ": " + prr.get(FieldNames.FIELD_TYPE));
	}
	
	private boolean evaluateRule(BaseRecord rule, List<BaseRecord> facts, BaseRecord prt, BaseRecord prr) throws FieldException, ValueException, ModelNotFoundException, ScriptException, IndexException, ReaderException, ModelException{
		ConditionEnumType rcond = ConditionEnumType.valueOf(rule.get(FieldNames.FIELD_CONDITION));
		RuleEnumType rtype = RuleEnumType.valueOf(rule.get(FieldNames.FIELD_TYPE));
		logger.debug("Evaluating Rule " + rule.get(FieldNames.FIELD_URN) + " " + rtype + " " + rcond.toString());
		int pass = 0;

		List<BaseRecord> patterns = rule.get(FieldNames.FIELD_PATTERNS);
		List<BaseRecord> rules = rule.get(FieldNames.FIELD_RULES);
		int size = (patterns.size() + rules.size());

		for(BaseRecord crule : rules) {
		//for(int i = 0; i < rules.size(); i++){
			//RuleType crule = rules.get(i);
			reader.populate(crule);
			boolean bRule = evaluateRule(crule, facts, prt, prr);
			if(bRule){
				pass++;
				if(rcond == ConditionEnumType.ANY){
					logger.debug("Breaking on " + crule.get(FieldNames.FIELD_URN) + " with rule " + rtype + " " + rcond.toString());
					break;
				}
			}
			else if(rcond == ConditionEnumType.ALL && !bRule){
				logger.debug("Breaking on " + crule.get(FieldNames.FIELD_URN) + " with rule " + rtype + " " + rcond.toString() + " failure");
				break;
				
			
			}
		}
		for(BaseRecord pat : patterns) {
			// logger.info(JSONUtil.exportObject(pat, RecordSerializerConfig.getFilteredModule()));
			reader.populate(pat);
			boolean bPat = evaluatePattern(pat, facts, prt, prr);
			
			if(
				bPat
			){
				pass++;
				if(rcond == ConditionEnumType.ANY){
					logger.debug("Breaking on " + pat.get(FieldNames.FIELD_URN) + " with rule " + rtype + " " + rcond);
					break;
				}
			}
			else if(rcond == ConditionEnumType.ALL && !bPat){
				logger.debug("Breaking on " + pat.get(FieldNames.FIELD_URN) + " with rule " + rtype + " " + rcond + " failure");
				break;
				
			}
		}
		logger.debug("Rule Result: " + rcond.toString() + " " + pass + ":" + size);
		boolean success = (
			(rcond == ConditionEnumType.ANY && pass > 0)
			||
			(rcond == ConditionEnumType.ALL && pass > 0 && pass == size)
			||
			(rcond == ConditionEnumType.NONE && pass == 0)
		);
		
		if(rtype == RuleEnumType.DENY){
			logger.debug("Inverting rule success for DENY condition");
			success = (!success);
		}

		if(success){
			int rscore = (int)prr.get(FieldNames.FIELD_SCORE) + (int)rule.get(FieldNames.FIELD_SCORE);
			prr.set(FieldNames.FIELD_SCORE, rscore);
		}
		return success;
	}
	private boolean evaluatePattern(BaseRecord pattern, List<BaseRecord> facts, BaseRecord prt, BaseRecord prr) throws ValueException, ScriptException, IndexException, ReaderException, FieldException, ModelNotFoundException, ModelException{
		PatternEnumType ptype = PatternEnumType.valueOf(pattern.get(FieldNames.FIELD_TYPE));
		
		if(trace) {
			logger.info("Evaluating Pattern " + pattern.get(FieldNames.FIELD_URN) + " " + ptype.toString());
		}
		
		BaseRecord fact = pattern.get(FieldNames.FIELD_FACT);
		BaseRecord mfact = pattern.get(FieldNames.FIELD_MATCH);

		BaseRecord pfact = fact;
		OperationResponseEnumType opr = OperationResponseEnumType.UNKNOWN;
		boolean outBool = false;
		if(fact == null){
			throw new ValueException("Pattern fact is null");
		}
		if(mfact == null){
			throw new ValueException("Match fact is null");
		}
		reader.populate(fact);
		reader.populate(mfact);
		FactEnumType mtype = FactEnumType.valueOf(mfact.get(FieldNames.FIELD_TYPE));
		pfact = getFactParameter(pfact, facts);
		reader.populate(pfact);
		
		List<String> chain = prr.get(FieldNames.FIELD_PATTERN_CHAIN);
		String purn = pattern.get(FieldNames.FIELD_URN);
		if(purn == null) {
			purn = pattern.get(FieldNames.FIELD_NAME);
		}
		chain.add(purn);
		
		if(ptype == PatternEnumType.PARAMETER){
			opr = OperationResponseEnumType.SUCCEEDED;
		}
		/// Operation - fork processing over to a custom-defined class or function
		///
		else if(ptype == PatternEnumType.OPERATION){
			// logger.warn(JSONUtil.exportObject(pattern, RecordSerializerConfig.getUnfilteredModule()));

			String cls = null;
			BaseRecord op = pattern.get(FieldNames.FIELD_OPERATION);
			if(op != null) {
				cls = op.get(FieldNames.FIELD_OPERATION);
			}
			opr = evaluateOperation(prt, prr, pattern, pfact, mfact, cls, pattern.get(FieldNames.FIELD_OPERATION_URN));
		}
		/// Expression - simple in-line expression/comparison
		else if(ptype == PatternEnumType.EXPRESSION){
			opr = evaluateExpression(prt,prr,pattern, pfact,mfact);
		}
		else if(ptype == PatternEnumType.AUTHORIZATION){
			opr = evaluateAuthorization(prt,prr,pattern, pfact, mfact);
		}
		else if(ptype == PatternEnumType.SEPARATION_OF_DUTY){
			opr = evaluateSoD(prt,prr,pattern, pfact, mfact);
		}
		else if(mtype == FactEnumType.OPERATION){
			opr = evaluateOperation(prt, prr, pattern, pfact, mfact, null, mfact.get(FieldNames.FIELD_SOURCE_URL));
		}
		else if(mtype == FactEnumType.FUNCTION) {
			opr = evaluateForFact(prt, prr, pattern, pfact, mfact);
		}
		
		else{
			logger.error("Pattern type not supported: " + pattern.get(FieldNames.FIELD_TYPE));
		}

		if(opr == OperationResponseEnumType.SUCCEEDED){
			outBool = true;
			prr.set(FieldNames.FIELD_SCORE, (int)prr.get(FieldNames.FIELD_SCORE) + (int)pattern.get(FieldNames.FIELD_SCORE));
		}

		return outBool;
	}
	private OperationResponseEnumType evaluateForFact(BaseRecord prt, BaseRecord prr, BaseRecord pattern, BaseRecord fact, BaseRecord matchFact) throws FieldException, ValueException, ModelNotFoundException, ModelException {
		OperationResponseEnumType outResponse;
		String mData = futil.getMatchFactValue(prt, prr, fact, matchFact);
		if(mData != null) {
			BaseRecord attr = RecordFactory.model(ModelNames.MODEL_ATTRIBUTE).newInstance();
			attr.set(FieldNames.FIELD_NAME,  matchFact.get(FieldNames.FIELD_NAME));
			attr.setString(FieldNames.FIELD_VALUE,  mData);
			List<BaseRecord> attrs = prr.get(FieldNames.FIELD_ATTRIBUTES);
			attrs.add(attr);
			if(mData.equals("UNKNOWN") || mData.equals("FAILED") || mData.equals("ERROR")) {
				outResponse = OperationResponseEnumType.valueOf(mData);
			}
			else{
				outResponse = OperationResponseEnumType.SUCCEEDED;
			}
			logger.info("Received: '" + mData + "'");
		}
		else{
			logger.error("No value returned from the function");
			outResponse = OperationResponseEnumType.FAILED;
		}
		return outResponse;
	}
	private OperationResponseEnumType evaluateExpression(BaseRecord prt,BaseRecord prr, BaseRecord pattern, BaseRecord fact, BaseRecord matchFact) throws FieldException, ValueException, ModelNotFoundException {
		OperationResponseEnumType outResponse;
		
		String chkData = futil.getFactValue(prt, prr, fact, matchFact);
		String mData = futil.getMatchFactValue(prt, prr,fact, matchFact);
		ComparatorEnumType comp = pattern.get(FieldNames.FIELD_COMPARATOR);
		if(RuleUtil.compareValue(chkData, comp, mData)) outResponse = OperationResponseEnumType.SUCCEEDED;
		else outResponse = OperationResponseEnumType.FAILED;
		return outResponse;
	}
	private OperationResponseEnumType evaluateSoD(BaseRecord prt,BaseRecord prr, BaseRecord pattern, BaseRecord fact, BaseRecord matchFact){
		OperationResponseEnumType outResponse;
		BaseRecord contextUser = prt.get(FieldNames.FIELD_CONTEXT_USER);
		BaseRecord p = futil.recordRead(contextUser, fact, matchFact);
		BaseRecord g = futil.recordRead(contextUser, matchFact, matchFact);
		if(p == null || g == null){
			// logger.error("The " + (g == null ? "match ":"") + "fact reference " + (g == null ? matchFact.get(FieldNames.FIELD_URN) : fact.get(FieldNames.FIELD_URN)) + " was null");
			return OperationResponseEnumType.ERROR;
		}

		if(!p.inherits(ModelNames.MODEL_ACCOUNT) && !p.inherits(ModelNames.MODEL_PERSON)){
			logger.error("Source fact of account or person is expected");
			return OperationResponseEnumType.ERROR;
		}
		if(!g.inherits(ModelNames.MODEL_GROUP)){
			logger.error("Match fact of group is expected");
			return OperationResponseEnumType.ERROR;			
		}
		List<Long> perms = SoDPolicyUtil.getActivityPermissionsForType(g.get(FieldNames.FIELD_URN), p);

		if(!perms.isEmpty()) outResponse = OperationResponseEnumType.SUCCEEDED;
		else outResponse = OperationResponseEnumType.FAILED;
		return outResponse;
	}	
	private OperationResponseEnumType evaluateAuthorization(BaseRecord prt, BaseRecord prr, BaseRecord pattern, BaseRecord fact, BaseRecord matchFact) throws NumberFormatException, ReaderException, IndexException {
		OperationResponseEnumType outResponse = OperationResponseEnumType.UNKNOWN;
		String ftype = fact.get(FieldNames.FIELD_MODEL_TYPE);
		String mtype = matchFact.get(FieldNames.FIELD_MODEL_TYPE);
		BaseRecord contextUser = prt.get(FieldNames.FIELD_CONTEXT_USER);
		FactEnumType matype = FactEnumType.valueOf(matchFact.get(FieldNames.FIELD_TYPE));
		if(ftype == null || mtype == null){
			logger.error("Expected both fact and match fact to define a factory type");
			return OperationResponseEnumType.ERROR;
		}
		BaseRecord p = futil.recordRead(contextUser, fact, matchFact);
		BaseRecord g = futil.recordRead(contextUser, matchFact, matchFact);
		if(p == null || g == null){
			logger.error("The " + (g == null ? "match ":"") + "fact reference " + (g == null ? matchFact.get(FieldNames.FIELD_URN) : fact.get(FieldNames.FIELD_URN)) + " was null");
			return OperationResponseEnumType.ERROR;
		}
		if(matype == FactEnumType.PERMISSION){
			
			BaseRecord perm = null;
			String fdata = matchFact.get(FieldNames.FIELD_FACT_DATA);
			if(fdata == null && mtype.equals(ModelNames.MODEL_PERMISSION)) {
				perm = g;
			}
			else if(fdata != null) {
				if(FactUtil.idPattern.matcher(fdata).matches()){
			
					perm = reader.read(matchFact.get(FieldNames.FIELD_FACT_DATA_TYPE), Long.parseLong(fdata));
				}
				else if(fdata.indexOf("/") > -1) {

					String fdtype = matchFact.get(FieldNames.FIELD_FACT_DATA_TYPE);
					if(fdtype != null && (fdtype.equals(ModelNames.MODEL_PERMISSION) ||  fdtype.equals(ModelNames.MODEL_ROLE))) {
						if(trace) {
							logger.info("Stipulating permission/role type '" + fdtype + "' relative to the actor type '" + ftype + "'.  This is likely from the internally generated policy");
						}
						fdtype = ftype;
					}
					if(trace) {
						logger.info("Find " + fdtype + " permission by path: " + fdata + " in " +  contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
					}
					perm = search.findByPath(contextUser, ModelNames.MODEL_PERMISSION, fdata, fdtype, contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
				}
				else{
					if(trace) {
						logger.info("Find perm by urn: " + fdata);
					}
					BaseRecord[] perms = search.findByUrn(matchFact.get(FieldNames.FIELD_FACT_DATA_TYPE), fdata);
					if(perms.length > 0) {
						perm = perms[0];
					}
				}
			}
			if(perm == null){
				logger.error("Permission reference does not exist");
				return OperationResponseEnumType.ERROR;
			}
			outResponse = evaluatePermissionAuthorization(prt, prr, pattern, p, g, perm);
		}
		else if(matype == FactEnumType.ROLE && mtype.equals(ModelNames.MODEL_ROLE)){
			outResponse = evaluateRoleAuthorization(prt, prr, pattern, p, g, g);
		}

		return outResponse;
	}
	private OperationResponseEnumType evaluateRoleAuthorization(BaseRecord prt,BaseRecord prr, BaseRecord pattern, BaseRecord src, BaseRecord targ, BaseRecord role) {
		OperationResponseEnumType outResponse = OperationResponseEnumType.UNKNOWN;
		boolean authZ = memberUtil.isMember(src, role, true);
		if(trace) {
			logger.info("Evaluate role authorization: Is " + src.get(FieldNames.FIELD_URN) + " in " + role.get(FieldNames.FIELD_URN) + " = " + authZ);
		}
		if(authZ){
			outResponse = OperationResponseEnumType.SUCCEEDED;
		}
		return outResponse;
	}
	private OperationResponseEnumType evaluatePermissionAuthorization(BaseRecord prt, BaseRecord prr, BaseRecord pattern, BaseRecord src, BaseRecord targ, BaseRecord permission) {
		OperationResponseEnumType outResponse = OperationResponseEnumType.UNKNOWN;
		boolean authZ = authUtil.checkEntitlement(src, permission, targ);
		if(trace) {
			// logger.info(src.toFullString());
			// logger.info(permission.toFullString());
			// logger.info(targ.toFullString());
			logger.info("Evaluate permission authorization: Does  " +  src.get(FieldNames.FIELD_URN) + " have " + permission.get(FieldNames.FIELD_URN) + " = " + authZ);
		}

		if(authZ){
			outResponse = OperationResponseEnumType.SUCCEEDED;
		}
		return outResponse;
	}
	

	private OperationResponseEnumType evaluateOperation(BaseRecord prt,BaseRecord prr, BaseRecord pattern, BaseRecord fact, BaseRecord matchFact, String operationClass, String operation) throws ScriptException, ValueException, IndexException, ReaderException {
		OperationResponseEnumType outResponse = OperationResponseEnumType.UNKNOWN;
		if(operationClass != null) {
			IOperation oper = OperationUtil.getOperationInstance(operationClass, reader, search);
			if(oper == null) outResponse = OperationResponseEnumType.ERROR;
			else outResponse = oper.operate(prt, prr, pattern, fact, matchFact);
		}
		else if(operation == null) {
			logger.error("Operation is null");
			outResponse = OperationResponseEnumType.ERROR;
		}
		else {
			logger.debug("Evaluating Operation: " + operation);
			BaseRecord[] ops = search.findByUrn(ModelNames.MODEL_OPERATION, operation);
			BaseRecord op = null;
			if(ops.length > 0) {
				op = ops[0];
			}
			else {
				throw new ValueException("Operation is null");
			}
			OperationEnumType opt = OperationEnumType.valueOf(op.get(FieldNames.FIELD_TYPE));
			String os = op.get(FieldNames.FIELD_OPERATION);
			switch(opt){
				case INTERNAL:
					IOperation oper = OperationUtil.getOperationInstance(os, reader, search);
					if(oper == null) outResponse = OperationResponseEnumType.ERROR;
					else outResponse = oper.operate(prt, prr, pattern, fact, matchFact);
					
					break;
				case FUNCTION:
					//logger.error("NEED TO REFACTOR. THIS IS ONLY AN INITIAL STUB");
					BaseRecord[] funcs = search.findByUrn(ModelNames.MODEL_OPERATION, os);
					BaseRecord func = null;
					if(funcs.length  > 0) {
						func = funcs[0];
					}
					else {
						throw new ValueException("Operation Function '" + os + "' is null");
					}
					Map<String,Object> params = ScriptUtil.getCommonParameterMap(null);
					params.put(FieldNames.FIELD_PATTERN, pattern);
					params.put(FieldNames.FIELD_FACT, fact);
					params.put(FieldNames.FIELD_MATCH, matchFact);
					// Object resp = BshService.run(null, params, func);
					outResponse = ScriptUtil.run(OperationResponseEnumType.class, params, func);
	
					break;
				default:
					logger.error("Unhandled operation type: " + opt);
			}
		}
		return outResponse;
	}
	
	private BaseRecord getFactParameter(BaseRecord fact, List<BaseRecord> facts){
		BaseRecord ofact = fact;
		FactEnumType ftype = FactEnumType.valueOf(fact.get(FieldNames.FIELD_TYPE));
		String urn = fact.get(FieldNames.FIELD_URN);
		List<BaseRecord> entries = facts.stream().filter(o -> {
			FactEnumType mtype = FactEnumType.valueOf(o.get(FieldNames.FIELD_TYPE));
			String murn = o.get(FieldNames.FIELD_URN);
			return (
				ftype == FactEnumType.PARAMETER && mtype == FactEnumType.PARAMETER
				&& murn.equals(urn)
			);
		}).collect(Collectors.toList());
		if(entries.size() > 0) {
			ofact = entries.get(0);
		}
		return ofact;
	}

	
}
