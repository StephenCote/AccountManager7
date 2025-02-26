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
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.PatternEnumType;
import org.cote.accountmanager.util.TypeUtil;

public class PatternType extends LooseRecord {
	public static final Logger logger = LogManager.getLogger(PatternType.class);
	public PatternType(){
		try {
			RecordFactory.newInstance("policy.pattern", this, null);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	public PatternType(BaseRecord rec){
		this.setAMModel(rec.getAMModel());
		setFieldList(rec.getFields());
		setFieldMap(rec.getFieldMap());
	}
	public FactType getFact() {
		BaseRecord rec = get("fact");
		if(rec != null) return rec.toConcrete();
		return null;
	}
	public void setFact(BaseRecord fact) {
		try {
			set("fact", fact);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public FactType getMatch() {
		BaseRecord rec = get("match");
		if(rec != null) return rec.toConcrete();
		return null;
	}
	public void setMatch(BaseRecord match) {
		try {
			set("match", match);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public OperationType getOperation() {
		BaseRecord rec = get("operation");
		if(rec != null) return rec.toConcrete();
		return null;
	}
	public void setOperation(BaseRecord operation) {
		try {
			set("operation", operation);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public String getOperationClass() {
		return get("operationClass");
	}
	public void setOperationClass(String operationClass) {
		try {
			set("operationClass", operationClass);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public String getFactUrn() {
		return get("factUrn");
	}
	public void setFactUrn(String factUrn) {
		try {
			set("factUrn", factUrn);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public ComparatorEnumType getComparator() {
		return ComparatorEnumType.valueOf(get("comparator"));
	}
	public void setComparator(ComparatorEnumType comparator) {
		try {
			set("comparator", comparator.toString());
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public PatternEnumType getType() {
		return PatternEnumType.valueOf(get(FieldNames.FIELD_TYPE));
	}
	public void setType(PatternEnumType type) {
		try {
			set(FieldNames.FIELD_TYPE, type.toString());
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public String getMatchUrn() {
		return get("matchUrn");
	}
	public void setMatchUrn(String matchUrn) {
		try {
			set("matchUrn", matchUrn);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public String getOperationUrn() {
		return get("operationUrn");
	}
	public void setOperationUrn(String operationUrn) {
		try {
			set("operationUrn", operationUrn);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public String getEntitlementType() {
		return get("entitlementType");
	}
	public void setEntitlementType(String entitlementType) {
		try {
			set("entitlementType", entitlementType);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public String getEntitlementPath() {
		return get("entitlementPath");
	}
	public void setEntitlementPath(String entitlementPath) {
		try {
			set("entitlementPath", entitlementPath);
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
