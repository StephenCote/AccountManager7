
package org.cote.accountmanager.policy;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.FactEnumType;
import org.cote.accountmanager.schema.type.PatternEnumType;
import org.cote.accountmanager.schema.type.PolicyRequestEnumType;
import org.cote.accountmanager.util.ByteModelUtil;

public class PolicyDefinitionUtil {
	public static final Logger logger = LogManager.getLogger(PolicyDefinitionUtil.class);
	private IReader reader = null;
	private FactUtil factUtil = null;

	public PolicyDefinitionUtil() {

	}

	public PolicyDefinitionUtil(IReader reader, ISearch search) {
		this.reader = reader;
		this.factUtil = new FactUtil(reader, search);
	}
	
	public PolicyDefinitionUtil(IOContext context) {
		this(context.getReader(), context.getSearch());
	}

	public BaseRecord generatePolicyRequest(BaseRecord pdt) throws FieldException, ModelNotFoundException, ValueException {
		BaseRecord prt = RecordFactory.model(ModelNames.MODEL_POLICY_REQUEST).newInstance();
		prt.set(FieldNames.FIELD_TYPE, PolicyRequestEnumType.DECIDE.toString());
		prt.set(FieldNames.FIELD_ORGANIZATION_PATH, pdt.get(FieldNames.FIELD_ORGANIZATION_PATH));
		prt.set(FieldNames.FIELD_URN, pdt.get(FieldNames.FIELD_URN));
		List<BaseRecord> params = pdt.get(FieldNames.FIELD_PARAMETERS);
		List<BaseRecord> facts = prt.get(FieldNames.FIELD_FACTS);
		params.forEach(parm -> {
			try {
				BaseRecord fact = RecordFactory.model(ModelNames.MODEL_FACT).newInstance();
				parm.getFields().forEach(f -> {
					try {
						fact.set(f.getName(), parm.get(f.getName()));
					} catch (ModelNotFoundException | FieldException | ValueException e) {
						logger.error(e);
					}
				});
				facts.add(fact);
			} catch (ModelNotFoundException | FieldException e) {
				logger.error(e);
			}
		});
		return prt;
	}

	public BaseRecord generatePolicyDefinition(BaseRecord pol) throws FieldException, ValueException, ModelNotFoundException {
		BaseRecord pdt = RecordFactory.model(ModelNames.MODEL_POLICY_DEFINITION).newInstance();

		// logger.info("Blank Policy Definition");
		pdt.set(FieldNames.FIELD_CREATED_DATE, pol.get(FieldNames.FIELD_CREATED_DATE));
		if (pol.hasField(FieldNames.FIELD_DECISION_AGE)) {
			pdt.set(FieldNames.FIELD_DECISION_AGE, pol.get(FieldNames.FIELD_DECISION_AGE));
		}
		pdt.set(FieldNames.FIELD_ENABLED, pol.get(FieldNames.FIELD_ENABLED));
		pdt.set(FieldNames.FIELD_MODIFIED_DATE, pol.get(FieldNames.FIELD_MODIFIED_DATE));
		pdt.set(FieldNames.FIELD_MODIFIED_DATE, pol.get(FieldNames.FIELD_MODIFIED_DATE));
		pdt.set(FieldNames.FIELD_URN, pol.get(FieldNames.FIELD_URN));
		pdt.set(FieldNames.FIELD_ORGANIZATION_PATH, pol.get(FieldNames.FIELD_ORGANIZATION_PATH));
		copyParameters(pdt, pol);
		return pdt;
	}

	private void copyParameters(BaseRecord pdt, BaseRecord pol) {
		List<BaseRecord> rules = pol.get(FieldNames.FIELD_RULES);
		rules.forEach(r -> {
			if (reader != null) {
				reader.populate(r);
			}
			copyRuleParameters(pdt, r);
		});

	}

	private void copyRuleParameters(BaseRecord pdt, BaseRecord rule) {

		if (rule.hasField(FieldNames.FIELD_PATTERNS)) {
			List<BaseRecord> patterns = rule.get(FieldNames.FIELD_PATTERNS);
			patterns.forEach(p -> {
				if (reader != null) {
					reader.populate(p);
				}
				try {
					copyPatternParameters(pdt, p);
				} catch (FieldException | ValueException | ModelNotFoundException | IndexException
						| ReaderException e) {
					logger.error(e);
				}
			});
		}
		if (rule.hasField(FieldNames.FIELD_RULES)) {
			List<BaseRecord> rules = rule.get(FieldNames.FIELD_RULES);
			rules.forEach(r -> {
				copyRuleParameters(pdt, r);
			});
		}

	}

	private boolean haveParameter(BaseRecord pdt, BaseRecord fact) {
		boolean outBool = false;
		List<BaseRecord> parms = pdt.get(FieldNames.FIELD_PARAMETERS);
		// for(int i = 0; i < parms.size();i++){
		String furn = fact.get(FieldNames.FIELD_URN);
		if (furn == null) {
			logger.error("Null URN for fact");
			return outBool;
		}
		for (BaseRecord parm : parms) {
			// BaseRecord parm = parms.get(i);
			if (furn.equals(parm.get(FieldNames.FIELD_URN))) {
				outBool = true;
				break;
			}
		}
		return outBool;
	}

	private void copyPatternParameters(BaseRecord pdt, BaseRecord pattern)
			throws FieldException, ValueException, ModelNotFoundException, IndexException, ReaderException {

		BaseRecord fact = pattern.get(FieldNames.FIELD_FACT);
		String ftype = null;
		if (fact != null) {
			reader.populate(fact);
			ftype = fact.get(FieldNames.FIELD_TYPE);
		}
		
		if (fact != null && ftype != null && ftype.equals(FactEnumType.PARAMETER.toString())) {
			if (haveParameter(pdt, fact)) {
				return;
			}
			/// TODO: Is there some reason this is all by one-by-one vs. just copying the record?
			BaseRecord parmFact = RecordFactory.model(ModelNames.MODEL_FACT).newInstance();
			parmFact.set(FieldNames.FIELD_NAME, fact.get(FieldNames.FIELD_NAME));
			parmFact.set(FieldNames.FIELD_URN, fact.get(FieldNames.FIELD_URN));
			parmFact.set(FieldNames.FIELD_MODEL_TYPE, fact.get(FieldNames.FIELD_MODEL_TYPE));
			parmFact.set(FieldNames.FIELD_TYPE, fact.get(FieldNames.FIELD_TYPE));
			parmFact.set(FieldNames.FIELD_FACT_DATA, fact.get(FieldNames.FIELD_FACT_DATA));
			parmFact.set(FieldNames.FIELD_FACT_DATA_TYPE, fact.get(FieldNames.FIELD_FACT_DATA_TYPE));
			parmFact.set(FieldNames.FIELD_SOURCE_DATA, fact.get(FieldNames.FIELD_SOURCE_DATA));
			parmFact.set(FieldNames.FIELD_SOURCE_DATA_TYPE, fact.get(FieldNames.FIELD_SOURCE_DATA_TYPE));
			parmFact.set(FieldNames.FIELD_SOURCE_URN, fact.get(FieldNames.FIELD_SOURCE_URN));
			parmFact.set(FieldNames.FIELD_SOURCE_URL, fact.get(FieldNames.FIELD_SOURCE_URL));
			parmFact.set("valueType", fact.get("valueType"));
			parmFact.set("propertyName", fact.get("propertyName"));
			parmFact.set("parameters", fact.get("parameters"));
			/// TODO: Fix needing a null check before trying to copy a flex field via set
			///
			if(fact.hasField("value")) {
				parmFact.set("value", fact.get("value"));
			}
			PatternEnumType ptype = PatternEnumType.valueOf(pattern.get(FieldNames.FIELD_TYPE));
			String pfmtype = pattern.get(FieldNames.FIELD_FACT_FIELD_MODEL_TYPE);
			if (ptype == PatternEnumType.VERIFICATION && ModelNames.MODEL_DATA.equals(pfmtype)) {
				BaseRecord data = factUtil.getFactSource(parmFact);
				if (data != null) {
					parmFact.set(FieldNames.FIELD_FACT_DATA, ByteModelUtil.getValueString(data));
				} else {
					logger.error("Null fact data reference");
				}
			}

			List<BaseRecord> parms = pdt.get(FieldNames.FIELD_PARAMETERS);
			parms.add(parmFact);
		} else {
			logger.info("SKIP because " + ftype);
		}

	}

}