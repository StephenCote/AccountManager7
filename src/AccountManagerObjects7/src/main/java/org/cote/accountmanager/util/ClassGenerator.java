package org.cote.accountmanager.util;

import java.util.HashSet;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldTypes;
import org.cote.accountmanager.schema.ModelSchema;

public class ClassGenerator {
	public static final Logger logger = LogManager.getLogger(ClassGenerator.class);
	
	private static Set<String> generated = new HashSet<>();
	public static void generateClass(String packageName, String modelName) {
		//generated.clear();
		generate(packageName, modelName);
	}
	private static void generate(String packageName, String modelName) {
		//logger.info("Model: '" + modelName + "'");
		BaseRecord test = RecordFactory.model(modelName);
		if(test == null) {
			logger.warn("Invalid or abstract model: '" + modelName + "'");
		}
		if(generated.contains(modelName)) {
			return;
		}
		generated.add(modelName);
		StringBuilder buff = new StringBuilder();
		
		
		ModelSchema ltype = RecordFactory.getSchema(modelName);
		ltype.getInherits().forEach(i -> {
			generate(packageName, i);
		});

		String className = modelName.substring(0, 1).toUpperCase() + modelName.substring(1) + "Type";
		logger.info("Generating concrete " + className);
		
		
		Set<String> imports = new HashSet<>();
		
		//String packageName = "org.cote.accountmanager.objects";

		imports.add("org.cote.accountmanager.exceptions.FieldException");
		imports.add("org.cote.accountmanager.exceptions.ModelNotFoundException");
		imports.add("org.cote.accountmanager.exceptions.ValueException");
		imports.add("org.cote.accountmanager.record.BaseRecord");
		imports.add("org.cote.accountmanager.record.LooseRecord");
		imports.add("org.cote.accountmanager.record.RecordFactory");
		imports.add("org.apache.logging.log4j.LogManager");
		imports.add("org.apache.logging.log4j.Logger");
		
		buff.append("public " + (ltype.isAbs() ? "abstract " : "") + "class " + className + " extends LooseRecord {\n");
		buff.append("\tpublic static final Logger logger = LogManager.getLogger(" + className + ".class);\n");
		buff.append("\tpublic " + className + "(){\n");
		buff.append("\t\ttry {\n");
		buff.append("\t\t\tRecordFactory.newInstance(\"" + modelName + "\", this, null);\n");
		buff.append("\t\t} catch (FieldException | ModelNotFoundException e) {\n");
		buff.append("\t\t\tlogger.error(e);\n");
		buff.append("\t\t}\n");
		buff.append("\t}\n");
		
		buff.append("\tpublic " + className + "(BaseRecord rec){\n");
		buff.append("\t\tthis.setModel(rec.getModel());\n");
		//buff.append("\t\tthis.setFields(record.getFields());\n");
		buff.append("\t\tsetFieldList(rec.getFields());\n");
		buff.append("\t\tsetFieldMap(rec.getFieldMap());\n");
		buff.append("\t}\n");
		ltype.getFields().forEach(f -> {
			//if(f.isInherited()) {
				//logger.info("Skip inherited field: " + f.getName());
			//}
			//else {
				if(f.getBaseModel() != null && !f.getBaseModel().equals("$self")) {
					generate(packageName, f.getBaseModel());
				}
				//logger.info("Include " + f.getName());
				String ftype = f.getType();
				String type = ftype;
				String genType = "";
				boolean conc = false;
				if(type.equals(FieldTypes.TYPE_FLEX)) {
					ftype = "T";
					genType = "<T> ";
				}
				if(type.equals(FieldTypes.TYPE_STRING)) {
					ftype = "String";
				}
				else if(type.equals(FieldTypes.TYPE_BLOB)) {
					ftype = "byte[]";
				}
				else if(type.equals(FieldTypes.TYPE_MODEL)) {
					ftype = "BaseRecord";
					conc = true;
					
				}
				else if(type.equals(FieldTypes.TYPE_TIMESTAMP)) {
					imports.add("java.util.Date");
					ftype = "Date";
				}
				else if(type.equals(FieldTypes.TYPE_ENUM)) {
					imports.add(f.getBaseClass());
					ftype = f.getBaseClass().substring(f.getBaseClass().lastIndexOf(".") + 1);
				}
				else if(type.equals(FieldTypes.TYPE_LIST)) {
					imports.add("java.util.List");
					String lstype = f.getBaseType();
					if(lstype == null) {
						logger.error("Fix schema for list to include baseType: " + modelName + "." + f.getName());
						logger.error(JSONUtil.exportObject(f));
					}
					if(lstype.equals(FieldTypes.TYPE_MODEL)) {
						imports.add("org.cote.accountmanager.util.TypeUtil");
						lstype = f.getBaseModel().substring(0, 1).toUpperCase() + f.getBaseModel().substring(1) + "Type";
					}
					else {
						if(lstype.equals(FieldTypes.TYPE_STRING)) {
							lstype = "String";
						}
						else if(lstype.equals(FieldTypes.TYPE_FLEX)) {
							ftype = "T";
							genType = "<T>";
						}
						else if(lstype.equals(FieldTypes.TYPE_BLOB)) {
							ftype = "data[]";
						}
					}
					ftype = "List<" + lstype + ">";
				}
				String gtype = ftype;
				if(conc) {
					String tmodel = f.getBaseModel();
					if(tmodel == null || tmodel.equals("$self")) {
						tmodel = f.getName();
					}
					gtype = tmodel.substring(0, 1).toUpperCase() + tmodel.substring(1) + "Type";
				}
				String mname = f.getName().substring(0,1).toUpperCase() + f.getName().substring(1);
				buff.append("\tpublic " + genType + gtype + " get" + mname + "() {\n");
				if(type.equals(FieldTypes.TYPE_ENUM)) {
					buff.append("\t\treturn " + ftype + ".valueOf(get(\""+ f.getName() + "\"));\n");
				}
				else if(type.equals(FieldTypes.TYPE_LIST) && f.getBaseType() != null && f.getBaseType().equals(FieldTypes.TYPE_MODEL)) {
					buff.append("\t\treturn TypeUtil.convertRecordList(get(\""+ f.getName() + "\"));\n");
				}
				else if(conc) {
					buff.append("\t\tBaseRecord rec = get(\"" + f.getName() + "\");\n");
					buff.append("\t\tif(rec != null) return rec.toConcrete();\n");
					buff.append("\t\treturn null;\n");
				}
				else {
					buff.append("\t\treturn get(\"" + f.getName() + "\");\n");
				}
				buff.append("\t}\n");
				buff.append("\tpublic " + genType + "void set" + mname + "(" + ftype + " " + f.getName() + ") {\n");
				buff.append("\t\ttry {\n");
				if(type.equals(FieldTypes.TYPE_ENUM)) {
					buff.append("\t\t\tset(\"" + f.getName() + "\", " + f.getName() + ".toString());\n");
				}
				else {
					buff.append("\t\t\tset(\"" + f.getName() + "\", " + f.getName() + ");\n");
				}
				buff.append("\t\t} catch (FieldException | ModelNotFoundException | ValueException e) {\n");
				buff.append("\t\t\tlogger.error(e);\n");
				buff.append("\t\t}\n");

				buff.append("\t}\n");
				
			//}
		});
		
		
		buff.append("}\n");
		
		StringBuilder header = new StringBuilder();
		header.append("/*\n\tGenerated\n\tNote: Inheritence isn't used here because the schema supports multiple inheritence\n*/\n");
		header.append("package " + packageName + ";\n\n");
		imports.forEach(s->{
			header.append("import " + s + ";\n");
		});
		header.append("\n");
		String packagePath = packageName.replaceAll("\\.", "/");
		FileUtil.emitFile("./src/main/java/" + packagePath + "/" + className + ".java", header.toString() + buff.toString());
	}
}
