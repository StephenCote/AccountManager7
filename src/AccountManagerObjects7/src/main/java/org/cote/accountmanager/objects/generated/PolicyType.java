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
import org.cote.accountmanager.schema.type.ConditionEnumType;
import org.cote.accountmanager.util.TypeUtil;

public class PolicyType extends LooseRecord {
	public static final Logger logger = LogManager.getLogger(PolicyType.class);
	public PolicyType(){
		try {
			RecordFactory.newInstance("policy.policy", this, null);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	public PolicyType(BaseRecord rec){
		this.setSchema(rec.getSchema());
		setFieldList(rec.getFields());
		setFieldMap(rec.getFieldMap());
	}
	public List<RuleType> getRules() {
		return TypeUtil.convertRecordList(get("rules"));
	}
	public void setRules(List<RuleType> rules) {
		try {
			set("rules", rules);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public boolean getEnabled() {
		return get("enabled");
	}
	public void setEnabled(boolean enabled) {
		try {
			set("enabled", enabled);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public long getDecisionAge() {
		return get("decisionAge");
	}
	public void setDecisionAge(long decisionAge) {
		try {
			set("decisionAge", decisionAge);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public ConditionEnumType getCondition() {
		return ConditionEnumType.valueOf(get("condition"));
	}
	public void setCondition(ConditionEnumType condition) {
		try {
			set("condition", condition.toString());
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public long getGroupId() {
		return get("groupId");
	}
	public void setGroupId(long groupId) {
		try {
			set("groupId", groupId);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public String getGroupPath() {
		return get("groupPath");
	}
	public void setGroupPath(String groupPath) {
		try {
			set("groupPath", groupPath);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public String getName() {
		return get(FieldNames.FIELD_NAME);
	}
	public void setName(String name) {
		try {
			set(FieldNames.FIELD_NAME, name);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public long getOwnerId() {
		return get("ownerId");
	}
	public void setOwnerId(long ownerId) {
		try {
			set("ownerId", ownerId);
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
	public long getId() {
		return get(FieldNames.FIELD_ID);
	}
	public void setId(long id) {
		try {
			set(FieldNames.FIELD_ID, id);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public String getObjectId() {
		return get("objectId");
	}
	public void setObjectId(String objectId) {
		try {
			set("objectId", objectId);
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
	public long getOrganizationId() {
		return get("organizationId");
	}
	public void setOrganizationId(long organizationId) {
		try {
			set("organizationId", organizationId);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public boolean getPopulated() {
		return get("populated");
	}
	public void setPopulated(boolean populated) {
		try {
			set("populated", populated);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public String getDescription() {
		return get("description");
	}
	public void setDescription(String description) {
		try {
			set("description", description);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public Date getCreatedDate() {
		return get("createdDate");
	}
	public void setCreatedDate(Date createdDate) {
		try {
			set("createdDate", createdDate);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public Date getModifiedDate() {
		return get("modifiedDate");
	}
	public void setModifiedDate(Date modifiedDate) {
		try {
			set("modifiedDate", modifiedDate);
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
