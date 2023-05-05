package org.cote.accountmanager.util;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.datatype.XMLGregorianCalendar;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;


public class NameIdExporter {
	public static final Logger logger = LogManager.getLogger(NameIdExporter.class);
	private Set<Class<?>> modelQueue = new HashSet<>();
	private Map<String, String> map = new HashMap<>();
	private static NameIdExporter _instance = null;
	
	public static final List<Class<?>> nameIdTypes = new ArrayList<>(Arrays.asList(
			/*
			AttributeType.class,
			AccountType.class, 
			AddressType.class, 
			SecurityType.class, 
			ContactType.class, 
			ContactInformationType.class, 
			BaseParticipantType.class, 
			ControlType.class, 
			CredentialType.class, 
			DataType.class, 
			FactType.class, 
			FunctionFactType.class, 
			FunctionType.class, 
			BaseGroupType.class, 
			MessageSpoolType.class, 
			OperationType.class, 
			OrganizationType.class, 
			PatternType.class, 
			BasePermissionType.class, 
			PersonType.class, 
			PolicyType.class, 
			BaseRoleType.class, 
			RuleType.class, 
			SecuritySpoolType.class, 
			StatisticsType.class, 
			BaseTagType.class, 
			UserType.class, 
			ApproverType.class,
			ApprovalType.class,
			AccessRequestType.class,
			ArtifactType.class, 
			BudgetType.class, 
			CaseType.class, 
			CostType.class, 
			EstimateType.class, 
			EventType.class, 
			FormElementType.class, 
			FormElementValueType.class, 
			FormType.class, 
			GoalType.class, 
			LifecycleType.class, 
			LocationType.class, 
			MethodologyType.class, 
			ModelType.class, 
			ModuleType.class, 
			NoteType.class, 
			ProcessType.class, 
			ProcessStepType.class, 
			ProjectType.class, 
			RequirementType.class, 
			ResourceType.class, 
			ScheduleType.class, 
			StageType.class, 
			TaskType.class, 
			TicketType.class, 
			TimeType.class, 
			TraitType.class, 
			ValidationRuleType.class, 
			WorkType.class 
			*/
	));
	
	public static NameIdExporter instance() {
		if(_instance == null) _instance = new NameIdExporter();
		return _instance;
	}
	public NameIdExporter() {
		
	}
	
	public Map<String, String> exportAll(){
		modelQueue.clear();
		map.clear();
		for(Class cls : nameIdTypes) {
			getAllFields(cls);
		}
		return map;
	}
	
	public static BaseRecord export(Object obj) {
		BaseRecord model = null;
		Class<?> cls = obj.getClass();
		//logger.info(cls.getName());
		String mname = nameIdToModelName(cls.getSimpleName());
		BaseRecord ibmod = RecordFactory.model(mname);
		if(ibmod == null) {
			logger.error("Failed to retrieve model for " + mname);
			return null;
		}
		ModelSchema ltype = RecordFactory.getSchema(mname);
		if(ltype == null) {
			logger.error("Failed to load loose model for " + mname);
			return null;
		}
		try {
			model = ibmod.newInstance();
			List<Method> meths = NameIdImporter.getMethods(cls);
			for(Method m : meths) {
				if(m.getName().startsWith("get")) {
					String pname = m.getName().substring(3, 4).toLowerCase() + m.getName().substring(4, m.getName().length());
					if(model.hasField(pname) == false) {
						logger.warn("Model does not define field " + pname);
						continue;
					}
					Class<?> p = m.getReturnType();
					//for(Class<?> p : ps) {

						String cname = p.getSimpleName().toLowerCase();
						if(cname.equals("integer") || cname.equals("boolean") || cname.equals("long") || cname.equals("string") || cname.equals("double")) {
							//logger.info("Set " + pname + " " + cname);
							model.set(pname,  m.invoke(obj));
						}
						else if(cname.equals("byte[]")) {
							model.getField(pname).setValue(m.invoke(obj));
						}
						else if(cname.equals("list")) {
							FieldSchema lft = ltype.getFieldSchema(pname);
							List<Object> src = (List<Object>)m.invoke(obj);
							List<Object> dest = model.get(pname);
							src.forEach(o -> {
								if(lft.getBaseModel() != null) {
									dest.add(export(o));
								}
								else {
									dest.add(o);
								}
							});
						}
						else if(p.isEnum()) {
							Object e = m.invoke(obj);
							model.set(pname, e.toString());
						}
						else if(cname.equals("xmlgregoriancalendar")) {
							XMLGregorianCalendar xdate = (XMLGregorianCalendar)m.invoke(obj);
							model.set(pname,  xdate.toGregorianCalendar().getTime());
						}
						else if(p.getName().startsWith("org.cote")) {
							Object cobj = m.invoke(obj);
							if(cobj != null) {
								BaseRecord cmmod = export(cobj);
								if(cmmod != null) {
									model.set(pname,  cmmod);
								}
								else {
									logger.error("Failed to import child model " + pname);
								}
							}
							else {
								logger.info("Child model " + pname + " value was null");
							}
						}
						else {
							logger.warn("Handle param: " + pname + " -> " + p.getName());
						}
						
						
					//}
				}
			}
		} catch (FieldException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return model;
	}
	
	/// Export a class reference to a model definition
	///
	public Map<String, String> export(Class<?> cls){
		modelQueue.clear();
		map.clear();
		getAllFields(cls);
		return map;
	}
	
	private static String nameIdToModelName(String name) {
	    String modelName = name.replaceFirst("Type$", "");
	    modelName = modelName.substring(0, 1).toLowerCase() + modelName.substring(1);
	    return modelName;
	}

	//private List<Class> modelQueue = new ArrayList<>();
	private void getAllFields(Class<?> cls) {
	    //fields.addAll(Arrays.asList(cls.getDeclaredFields()));
		String modelName = nameIdToModelName(cls.getSimpleName());
		if(map.containsKey(modelName)) return;
		String inherits = null;
	    if (cls.getSuperclass() != null && !cls.getSuperclass().getSimpleName().equals("Object")) {
	    	inherits = nameIdToModelName(cls.getSuperclass().getSimpleName());
        	getAllFields(cls.getSuperclass());
	    }
	    
	    
	    //logger.info("Model - " + modelName);
	    String model = emitModel(modelName, inherits, Arrays.asList(cls.getDeclaredFields()), 0);
	    map.put(modelName, model);
	    
	    //Iterator<Class> iter = modelQueue.iterator();
	    Class[] queue = modelQueue.toArray(new Class[0]);
	    modelQueue.clear();
	    for(Class qcls : queue) {
	    //while(iter.hasNext()) {
	    //modelQueue.forEach(q -> {
	    	//Class qcls = iter.next();
	    	String qname = nameIdToModelName(qcls.getSimpleName());
	    	//logger.info(modelName + ": Queue - " + qname + " / " + qcls.getSimpleName());
	    	getAllFields(qcls);
	    //});
	    }
	    //modelQueue.clear();
	    //logger.info(modelName);
	    //logger.info(model);
	    return;
	}
	
	
	private String emitModel(String name, String inherits, List<Field> fields, int depth) {
		StringBuilder buff = new StringBuilder();
		StringBuilder tabBuff = new StringBuilder();
		for(int i = 0; i < depth; i++) tabBuff.append("\t");
		String tabStr = tabBuff.toString();
		//Collections.reverse(fields);
		buff.append(tabStr + "{\n");
		buff.append(tabStr + "\"name\": \"" + name + "\",\n");
		if(inherits != null) {
			buff.append(tabStr + "\"inherits\": [\"" + inherits + "\"],\n");	
		}
		buff.append(tabStr + "\"fields\": [\n");
		int count = 0;
		for(Field field: fields) {
			String ftabStr = tabStr + "\t";
			buff.append(ftabStr + (count > 0 ? "," : "") + "{\n");
			buff.append(ftabStr + "\"name\": \"" + field.getName() + "\",\n");
			//buff.append(ftabStr + "\"baseClass\": \"" + field.getDeclaringClass().getName() + "\",\n");
			String btype = field.getType().getName();
			String type = null;
			if(btype.startsWith("java.lang.")) {
				type = btype.replace("java.lang.", "").toLowerCase();
				if(type.equals("integer")) type = "int";
			}
			else if(field.getType().isEnum()) {
				type = "enum";
				buff.append(ftabStr + "\"baseClass\": \"" + field.getType().getName() + "\",\n");
			}
			else if(field.getType() == byte[].class) {
				type = "blob";
			}
			else if(btype.equals("javax.xml.datatype.XMLGregorianCalendar")) {
				type = "timestamp";
			}
			else if(btype.equals("java.util.List")) {
				type = "list";
				ParameterizedType listType = (ParameterizedType) field.getGenericType();
		        Class<?> listClass = (Class<?>) listType.getActualTypeArguments()[0];
		        if(listClass.getName().startsWith("java.lang.")) {
		        	buff.append(ftabStr + "\"baseType\": \"" + listClass.getName().replace("java.lang.", "").toLowerCase() + "\",\n");
		        	buff.append(ftabStr + "\"baseClass\": \"" + listClass.getName() + "\",\n");
		        }
		        else {
		        	String mname = nameIdToModelName(listClass.getSimpleName());
		        	buff.append(ftabStr + "\"baseModel\": \"" + mname + "\",\n");
		        	if(!modelQueue.contains(listClass)) modelQueue.add(listClass);
		        	//logger.warn("List generic type: " + mname + " " + listClass.getName() + " / " + field.getGenericType().getTypeName());
		        }
				
			}
			else if(btype.startsWith("org.cote.accountmanager.objects") || btype.startsWith("org.cote.propellant.objects")) {
				type = "model";
				String mname = nameIdToModelName(field.getType().getSimpleName());
				buff.append(ftabStr + "\"baseModel\": \"" + mname + "\",\n");
				if(!modelQueue.contains(field.getType())) modelQueue.add(field.getType());
			}
			else {
				logger.warn("Problem in " + name + "/" + field.getName());
				logger.warn("Handle type: " + btype);
				logger.warn(field);
			}
			if(type != null) {
				buff.append(ftabStr + "\"type\": \"" + type + "\"\n");
			}
			buff.append(ftabStr + "}\n");
			count++;
		}
		buff.append("]");
		buff.append("}");
		return buff.toString();
	}
}
