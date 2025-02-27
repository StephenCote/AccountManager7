package org.cote.accountmanager.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Optional;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;

public class NameIdImporter {
	public static final Logger logger = LogManager.getLogger(NameIdImporter.class);
	
	public static List<Method> getMethods(Class<?> cls){
		List<Method> meths = new ArrayList<>();
		meths.addAll(Arrays.asList(cls.getDeclaredMethods()));
		Class parent = cls.getSuperclass();
		while(parent != null && !parent.getSimpleName().equals("Object")) {
			meths.addAll(Arrays.asList(parent.getDeclaredMethods()));	
			parent = parent.getSuperclass();
		}
		return meths;
	}
	
	/// Import a loose baseModel into the first class NameId POJO
	public static <T> T importModel(BaseRecord model) {
		Object outObj = null;
		
		String name = model.getSchema();
		String tname = name + FieldNames.FIELD_TYPE;
		Optional<Class<?>> ocls = NameIdExporter.nameIdTypes.stream().filter(o -> o.getSimpleName().toLowerCase().equals(tname)).findFirst();
		Class<?> cls = null;
		if(ocls.isPresent()) {
			cls = ocls.get();
		}
		if(cls == null) {
			logger.error("Class not found for " + name);
			return null;
		}
		ModelSchema ltype = RecordFactory.getSchema(name);
		if(ltype == null) {
			logger.error("Failed to load loose model for " + name);
			return null;
		}
		logger.info("Importing as " + tname);

		try {
			outObj = cls.getDeclaredConstructor().newInstance();
			//Method[] ms = cls.getDeclaredMethods();
			List<Method> ms = getMethods(cls);
			for(Method m : ms) {
				if(m.getName().startsWith("get") && m.getReturnType() != null && m.getReturnType().getSimpleName().toLowerCase().equals("list")) {
					String pname = m.getName().substring(3, 4).toLowerCase() + m.getName().substring(4, m.getName().length());
					//logger.warn("Handle import list: " + pname);
					if(model.hasField(pname) == false) {
						logger.warn("Model does not define field " + pname);
						continue;
					}
					List<Object> lst = (List)m.invoke(outObj);
					List<?> src = model.get(pname);
					
					FieldSchema lft = ltype.getFieldSchema(pname);
					src.forEach(o ->{
						if(lft.getBaseModel() != null) {
							Object io = importModel((BaseRecord)o);
							lst.add(io);
						}
						else {
							lst.add(o);
						}
					});
				}
				if(m.getName().startsWith("set")) {
					String pname = m.getName().substring(3, 4).toLowerCase() + m.getName().substring(4, m.getName().length());
					if(model.hasField(pname) == false) {
						logger.warn("Model does not define field " + pname);
						continue;
					}
					//logger.info(pname);
					Class[] ps = m.getParameterTypes();
					for(Class p : ps) {

						String cname = p.getSimpleName().toLowerCase();
						//logger.info("Param: " + pname + " -> " + cname);						
						//FieldType field = model.get(pname);
						//if(cname.equals("boolean") || cname.equals("long") || cname.equals("string") || cname.equals("integer")) {
						if(cname.equals("integer")) {
							m.invoke(outObj, (Integer)model.get(pname));
						}
						else if(cname.equals("boolean")) {
							m.invoke(outObj, (Boolean)model.get(pname));
						}
						else if(cname.equals("string")) {
							m.invoke(outObj, (String)model.get(pname));
						}
						else if(cname.equals("long")) {
							m.invoke(outObj, (Long)model.get(pname));
						}
						else if(cname.equals("byte[]")) {
							m.invoke(outObj, (byte[])model.getField(pname).getValue());
						}
						else if(cname.equals("double")) {
							m.invoke(outObj, (double)model.get(pname));
						}
						else if(cname.equals("xmlgregoriancalendar")) {
							Date date = (Date)model.get(pname);
							XMLGregorianCalendar xdate = null;
					        GregorianCalendar gcal = new GregorianCalendar();
					        gcal.setTime(date);
					        xdate = DatatypeFactory.newInstance().newXMLGregorianCalendar(gcal);
					        m.invoke(outObj, xdate);
						}
						else if(p.isEnum()) {
							Object entry = RecordFactory.getEnumValue(p.getName(), model.get(pname));
							m.invoke(outObj,  entry);
						}
						else if(p.getName().startsWith("org.cote")) {
							BaseRecord cmmod = model.get(pname);
							if(cmmod != null) {
								Object cmod = importModel(cmmod);
								if(cmod != null) {
									m.invoke(outObj,  cmod);
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
						
					}
				}
			}
		} catch (ClassCastException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException | DatatypeConfigurationException e) {
			logger.error(e);
		}
		/*
		NameIdExporter.nameIdTypes.(c -> {
			
		});
		*/
		return (T)outObj;
	}
}
