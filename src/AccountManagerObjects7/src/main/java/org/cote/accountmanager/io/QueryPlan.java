package org.cote.accountmanager.io;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.RecordUtil;

public class QueryPlan extends LooseRecord {
	
	private ModelSchema schema = null;
	public QueryPlan() {
		try {
			RecordFactory.newInstance(ModelNames.MODEL_QUERY_PLAN, this, null);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	public QueryPlan(String modelName) {
		this();
		this.schema = RecordFactory.getSchema(modelName);
	}
	
	public QueryPlan(BaseRecord plan) {
		this((String)plan.get(FieldNames.FIELD_MODEL_NAME));
		this.setFields(plan.getFields());
		linkParent(plan);
		//this.setValue(FieldNames.FIELD_MODEL_NAME, (String)plan.get(FieldNames.FIELD_MODEL_NAME));

	}
	
	public QueryPlan(String modelName, String fieldName, String[] fields) {
		this(modelName, fieldName, Arrays.asList(fields));
	}
	
	public QueryPlan(String modelName, String fieldName, List<String> fields) {
		this(modelName);
		this.setValue(FieldNames.FIELD_MODEL_NAME, modelName);
		this.setValue(FieldNames.FIELD_FIELD_NAME, fieldName);
		getPlanFields().addAll(fields);
	}
	
	private static void linkParent(BaseRecord plan) {
		List<BaseRecord> plans = plan.get("plans");
		for(BaseRecord p : plans) {
			p.setValue("parent", plan);
			linkParent(p);
		}
	}
	
	private QueryPlan getEmbeddedPlan(String fieldName) {
		
		String[] embedded = fieldName.split("\\.");
		QueryPlan qp = getSubPlan(embedded[0]);
		if(qp != null) {
			String[] newKey = new String[embedded.length - 1];
			System.arraycopy(embedded, 1, newKey, 0, newKey.length);
			String outKey = Arrays.stream(newKey).collect(Collectors.joining("."));
			List<BaseRecord> plans = qp.getPlans();
			return qp.getSubPlan(outKey);
		}

		logger.warn("No sub plan for " + fieldName);
		return null;
		
	}
	
	public void limitPlan(List<String> fieldNames) {
	}
	public static void limitPlan(BaseRecord plan, List<String> fieldNames) {
		List<String> lfields = new ArrayList<>(plan.get(FieldNames.FIELD_FIELDS));
		for(String f: lfields) {
			if(!fieldNames.contains(f)) {
				unplan(plan, f);
			}
		}
	}
	
	public void unplan(String fieldName) {
		unplan(this, fieldName);
	}
	
	public static void unplan(BaseRecord plan, String fieldName) {
		List<String> planFields = plan.get(FieldNames.FIELD_FIELDS);
		planFields.remove(fieldName);
		List<BaseRecord> plans = plan.get("plans");
		plan.setValue("plans", plans.stream().filter(p -> !fieldName.equals(p.get("fieldName"))).collect(Collectors.toList()));
	}
	
	protected List<BaseRecord> findPlans(String modelName, String fieldName){
		return findPlans(this, modelName, fieldName);
	}

	public static List<BaseRecord> findPlans(BaseRecord plan, String modelName, String fieldName){
		return findPlans(plan, modelName, fieldName, new ArrayList<>());
	}

	private static List<BaseRecord> findPlans(BaseRecord plan, String modelName, String fieldName, List<BaseRecord> planList){
		String pmodel = plan.get(FieldNames.FIELD_MODEL_NAME);
		String pfield = plan.get(FieldNames.FIELD_FIELD_NAME);
		List<BaseRecord> plans = plan.get("plans");
		if(pmodel.equals(modelName)) {
			if(pfield != null && pfield.equals(fieldName)) {
				planList.add(new QueryPlan(plan));
			}
			Optional<BaseRecord> oplan = plans.stream().filter(q -> fieldName.equals(q.get(FieldNames.FIELD_FIELD_NAME))).findFirst();
			if(oplan.isPresent()) {
				planList.add(oplan.get());
			}
		}
		plans.forEach(p -> {
			findPlans(p, modelName, fieldName, planList);
		});
		
		return planList;
	}

	
	protected QueryPlan getSubPlan(String fieldName) {
		if(fieldName.contains(".")) {
			return getEmbeddedPlan(fieldName);
		}
		Optional<BaseRecord> oplan = getPlans().stream().filter(q -> fieldName.equals(q.get("fieldName"))).findFirst();
		if(oplan.isPresent()) {
			return new QueryPlan(oplan.get());
		}
		else {
			// logger.warn("No plan for " + fieldName);
		}
		return null;
	}
	
	protected String planPath() {
		return getPlanPath(this);
	}
	private static String getPlanPath(BaseRecord rec) {
		BaseRecord parent = rec.get("parent");
		
		String modelName = rec.get(FieldNames.FIELD_MODEL_NAME);
		String fieldName = rec.get(FieldNames.FIELD_FIELD_NAME);
		
		String parentPath = "";
		if(parent != null) {
			parentPath = getPlanPath(parent);
		}
		
		return (
			(parentPath.length() > 0 ? parentPath + " -> " : "") + modelName + (fieldName != null ? "." + fieldName : "")
		);
				
	}
	
	private void clearPlan() {
		List<String> fields = getPlanFields();
		fields.clear();
		List<BaseRecord> plans = getPlans();
		plans.clear();
	}
	
	private enum PlanType{
		COMMON,
		MOST,
		ID
	}
	
	protected void filterPlan(String model, String fieldName) {
		filterPlan(this, model, fieldName);
	}
	
	protected static void filterPlan(BaseRecord plan, String model, String fieldName) {
		String pmodel = plan.get(FieldNames.FIELD_MODEL_NAME);
		List<String> planFields = plan.get(FieldNames.FIELD_FIELDS);
		List<BaseRecord> plans = plan.get("plans");
		if(pmodel.equals(model)) {
			planFields.remove(fieldName);
		}
		plans.forEach(p -> {
			filterPlan(p, model, fieldName);
		});

	}
	
	protected void planForCommonFields() {
		planForFields(false, PlanType.COMMON, new ArrayList<>(), new HashSet<>());
	}
	
	protected void planForCommonFields(boolean recurse) {
		planForFields(recurse, PlanType.COMMON, new ArrayList<>(), new HashSet<>());
	}
	
	protected void planForMostFields() {
		planForFields(false, PlanType.MOST, new ArrayList<>(), new HashSet<>());
	}
	
	protected void planForMostFields(boolean recurse, List<String> filter) {
		planForFields(recurse, PlanType.MOST, filter, new HashSet<>());
	}
	private int maximumDepth = 500;
	
	private void planForFields(boolean recurse, PlanType planType, List<String> filter, Set<String> pathSet) {
		List<String> flds = new ArrayList<>();
		BaseRecord parent = get("parent");
		if(pathSet.size() >= maximumDepth) {
			logger.error("Exceeded maximum depth");
		}
		if(!schema.isFollowReference() || planType == PlanType.COMMON) {
			flds = Arrays.asList(RecordUtil.getCommonFields(schema.getName()));
		}
		else if(planType == PlanType.MOST) {
			if(filter.size() == 0) {
				flds = RecordUtil.getMostRequestFields(schema.getName());
			}
			else {
				flds = RecordUtil.getRequestFields(schema, filter);
			}
		}
		
		final List<String> uflds = flds.stream().filter(s -> {
			boolean ob = true;
			if(parent != null) {
				FieldSchema f = schema.getFieldSchema(s);
				ob = (f.getBaseModel() == null || (
					!ModelNames.MODEL_SELF.equals(f.getBaseModel())
					&&
					!ModelNames.MODEL_FLEX.equals(f.getBaseModel())
				));
			}
			return ob;
		}).collect(Collectors.toList());
		if(uflds.size() == 0) {
			logger.info(flds.stream().collect(Collectors.joining(", ")));
		}
		clearPlan();
		getPlanFields().addAll(uflds);
		
		if(recurse) {
			schema.getFields().forEach(f -> {
				if(
				uflds.contains(f.getName())
				&& canPlan(f)
				&& f.isFollowReference()
				&& !schema.getName().equals(f.getBaseModel())
			) {
					QueryPlan qp = plan(f.getName(), new String[0]);
					String pp = qp.planPath();
					if(!pathSet.contains(pp)) {
						pathSet.add(pp);
						qp.planForFields(recurse, (checkRecursion(f) ? PlanType.COMMON : planType), filter, pathSet);	
					}
					else {
						logger.warn("Recursion warning: " + pp);
					}
					
				}
			});
		}
	}
	
	private boolean checkRecursion(FieldSchema field) {
		boolean outBool = false;
		BaseRecord parent = get("parent");
		if(parent != null) {
			String pmodel = parent.get(FieldNames.FIELD_MODEL_NAME);
			String pfield = parent.get(FieldNames.FIELD_FIELD_NAME);
			String fmodel = field.getBaseModel();
			String ffield = field.getName();
			// logger.info("Check " + pmodel + "." + pfield + " -> " + fmodel + "." + ffield);
			if(pfield != null && fmodel != null) {
				if(pmodel.equals(fmodel) && pfield.equals(ffield)) {
					// logger.warn("Recursion detected on " + fmodel + "." + ffield);
					outBool = true;
				}
			}
		}
		return outBool;
	}

	private boolean canPlan(FieldSchema field) {
		return (
			(field.getFieldType() == FieldEnumType.MODEL || (field.getFieldType() == FieldEnumType.LIST && ModelNames.MODEL_MODEL.equals(field.getBaseType())))
			&& field.getBaseModel() != null
			&& !field.getBaseModel().equals(ModelNames.MODEL_SELF)
			&& !field.getBaseModel().equals(ModelNames.MODEL_FLEX)
			
		);
	}
	
	protected QueryPlan plan(String fieldName, String[] fields) {
		
		QueryPlan subPlan = null;
		FieldSchema field = schema.getFieldSchema(fieldName);
		if(field == null) {
			logger.error(schema.getName() + "." + fieldName + " is not valid");
			return subPlan;
		}
		
		QueryPlan oplan = getSubPlan(fieldName);
		if(oplan != null) {
			logger.warn(schema.getName() + "." + fieldName + " is already planend");
			return oplan;
		}
		
		if(!canPlan(field)) {
			return null;
		}
		
		if(!getPlanFields().contains(fieldName)) {
			getPlanFields().add(fieldName);
		}
		
		subPlan = new QueryPlan(field.getBaseModel(), fieldName, fields);
		List<BaseRecord> plans = get("plans");
		plans.add(subPlan);
		subPlan.setValue("parent", this);
		return subPlan;
	}

	public String getModelName() {
		return get(FieldNames.FIELD_MODEL_NAME);
	}
	
	public String getFieldName() {
		return get(FieldNames.FIELD_FIELD_NAME);
	}
	
	public List<String> getPlanFields() {
		return get(FieldNames.FIELD_FIELDS);
	}
	
	public List<BaseRecord> getPlans(){
		return get("plans");
	}

}
