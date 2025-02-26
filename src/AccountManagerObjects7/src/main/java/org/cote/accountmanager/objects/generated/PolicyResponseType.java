/*
	Generated
	Note: Inheritence isn't used here because the schema supports multiple inheritence
*/
package org.cote.accountmanager.objects.generated;

import java.util.Date;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.util.TypeUtil;

public class PolicyResponseType extends LooseRecord {
	public static final Logger logger = LogManager.getLogger(PolicyResponseType.class);
	public PolicyResponseType(){
		try {
			RecordFactory.newInstance("policy.policyResponse", this, null);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	public PolicyResponseType(BaseRecord rec){
		this.setAMModel(rec.getAMModel());
		setFieldList(rec.getFields());
		setFieldMap(rec.getFieldMap());
	}
	public List<String> getMessages() {
		return get("messages");
	}
	public void addMessage(String message) {
		getMessages().add(message);
	}
	public List<String> getPatternChain() {
		return get("patternChain");
	}
	public void setPatternChain(List<String> patternChain) {
		try {
			set("patternChain", patternChain);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}

	public PolicyResponseEnumType getType() {
		return PolicyResponseEnumType.valueOf(get(FieldNames.FIELD_TYPE));
	}
	public void setType(PolicyResponseEnumType type) {
		try {
			set(FieldNames.FIELD_TYPE, type.toString());
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public String getUrn() {
		return get("urn");
	}
	public void setUrn(String urn) {
		try {
			set("urn", urn);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public Date getExpiryDate() {
		return get("expiryDate");
	}
	public void setExpiryDate(Date expiryDate) {
		try {
			set("expiryDate", expiryDate);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public List<AttributeType> getAttributes() {
		return TypeUtil.convertRecordList(get("attributes"));
	}
	public void setAttributes(List<AttributeType> attributes) {
		try {
			set("attributes", attributes);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public int getScore() {
		return get("score");
	}
	public void setScore(int score) {
		try {
			set("score", score);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
}
