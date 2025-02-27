/*
	Generated
	Note: Inheritence isn't used here because the schema supports multiple inheritence
*/
package org.cote.accountmanager.objects.generated;

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
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.schema.type.PolicyRequestEnumType;
import org.cote.accountmanager.util.TypeUtil;

public class PolicyRequestType extends LooseRecord {
	public static final Logger logger = LogManager.getLogger(PolicyRequestType.class);
	public PolicyRequestType(){
		try {
			RecordFactory.newInstance("policy.policyRequest", this, null);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	public PolicyRequestType(BaseRecord rec){
		this.setSchema(rec.getSchema());
		setFieldList(rec.getFields());
		setFieldMap(rec.getFieldMap());
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
	public List<FactType> getFacts() {
		return TypeUtil.convertRecordList(get("facts"));
	}
	public void setFacts(List<FactType> facts) {
		try {
			set("facts", facts);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}

	public void setContextUser(BaseRecord contextUser) {
		try {
			set("contextUser", contextUser);
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
	public String getOrganizationPath() {
		return get("organizationPath");
	}
	public void setOrganizationPath(String organizationPath) {
		try {
			set("organizationPath", organizationPath);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public PolicyRequestEnumType getType() {
		return PolicyRequestEnumType.valueOf(get(FieldNames.FIELD_TYPE));
	}
	public void setType(PolicyRequestEnumType type) {
		try {
			set(FieldNames.FIELD_TYPE, type.toString());
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public String getSubject() {
		return get("subject");
	}
	public void setSubject(String subject) {
		try {
			set("subject", subject);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public String getSubjectType() {
		return get("subjectType");
	}
	public void setSubjectType(String subjectType) {
		try {
			set("subjectType", subjectType);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public CredentialEnumType getCredentialType() {
		return CredentialEnumType.valueOf(get("credentialType"));
	}
	public void setCredentialType(CredentialEnumType credentialType) {
		try {
			set("credentialType", credentialType.toString());
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
}
