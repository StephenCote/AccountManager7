package org.cote.accountmanager.record;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldFactory;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.FieldTypes;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;

public class RecordDeserializer<T extends BaseRecord> extends StdDeserializer<T>
{
    private ObjectMapper mapper = new ObjectMapper();
    
    public static final Logger logger = LogManager.getLogger(RecordDeserializer.class);

    private String[] fkImportFields = null;
    private NodeLink currentNode = null;
	private boolean accessForeignKey = false;
	private boolean trace = false;
	private int level = 0;
    private Map<Integer, String> lastModel = new ConcurrentHashMap<>();
    private boolean condensedFields = false;
    private boolean detectCondensedFields = true;
    private boolean suppressInvalidFieldWarning = false;
    
    public RecordDeserializer() {
    		this(null);
    }
    
    public RecordDeserializer(Class<?> c) {
    		super(c);
    		mapper.enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature());
    }

    
    
    public boolean isSuppressInvalidFieldWarning() {
		return suppressInvalidFieldWarning;
	}

	public void setSuppressInvalidFieldWarning(boolean suppressInvalidFieldWarning) {
		this.suppressInvalidFieldWarning = suppressInvalidFieldWarning;
	}

	public boolean isTrace() {
		return trace;
	}
	public void setTrace(boolean trace) {
		this.trace = trace;
	}
	public boolean isCondensedFields() {
		return condensedFields;
	}
	public void setCondensedFields(boolean condensedFields) {
		this.condensedFields = condensedFields;
	}
	public boolean isDetectCondensedFields() {
		return detectCondensedFields;
	}
	public void setDetectCondensedFields(boolean detectCondensedFields) {
		this.detectCondensedFields = detectCondensedFields;
	}
	public void setFkImportFields(String[] fkImportFields) {
		this.fkImportFields = fkImportFields;
	}
	public void applyModule(SimpleModule mod) {
    	mapper.registerModule(mod);
	}
	public void setAccessForeignKey(boolean accessForeignKey) {
		this.accessForeignKey = accessForeignKey;
	}
	/*
	public void setReader(IReader reader) {
    	this.reader = reader;
    }
    */
	public String findModel(JsonParser jsonParser, JsonNode node, NodeLink link) {
		String model = null;
		if(link != null) {
			if(link.getListElementModel() != null && !link.getListElementModel().isEmpty()) {
				model = link.getListElementModel();
			}
			else if(link.getSchema() != null && link.getSchema().getBaseModel() != null) {
				model = link.getSchema().getBaseModel();
				if(model.equals(ModelNames.MODEL_SELF) && link.getModel() != null) {
					model = link.getModel();
				}
			}
			else if(link.getParent() != null) {
				// logger.info("Find in parent");
				logger.info(JSONUtil.exportObject(link.getParent()));
			}
			else if(link.getModel() != null) {
				model = link.getModel();
			}
		}

		return model;
	}
	
	private String getName(ModelSchema schema, String shortName) {
		String name = shortName;
		List<FieldSchema> fschemas = schema.getFields().stream().filter(s -> ((condensedFields && s.getShortName() != null && s.getShortName().equals(shortName)) || s.getName().equals(shortName))).collect(Collectors.toList());
		if(fschemas.size() > 0) {
			if(fschemas.size() > 1) {
				logger.error("Found multiple matches for name '" + shortName + "'");
			}
			name = fschemas.get(0).getName();
			
		}
		return name;
	}
	
	@SuppressWarnings("unchecked")
	@Override
    public T deserialize(JsonParser jsonParser, DeserializationContext context) throws IOException, JsonProcessingException
    {
		
        JsonNode node = jsonParser.getCodec().readTree(jsonParser);
        String modelName = null;
        
        if(node.has(RecordFactory.JSON_MODEL_KEY)) {
        	modelName = node.get(RecordFactory.JSON_MODEL_KEY).asText().intern();
        }
        else if((detectCondensedFields || condensedFields) && node.has(RecordFactory.JSON_MODEL_SHORT_KEY)) {
        	logger.warn("Deserializing by condensed fields");
        	modelName = node.get(RecordFactory.JSON_MODEL_SHORT_KEY).asText().intern();
        	condensedFields = true;
        }
        else {
        	modelName = findModel(jsonParser, node, currentNode);
        }

        
        
        if(modelName == null) {
        	if(lastModel.containsKey(level)) {
        		modelName = lastModel.get(level);
            	if(trace) {
            		logger.info("Adopting last model: " + level + " " + lastModel.get(level));
            	}

        	}
        	else {
	        	logger.error("Invalid model name");
	        	logger.error("Current node: " + node.toString());
	        	if(currentNode != null) {
	        		logger.error("Model name is null");
	        		logger.error("Current node: " + node.toString());
	        		logger.error(JSONUtil.exportObject(currentNode));
	        	}
	        	return null;
        	}
        }
        else {
        	lastModel.put(level, modelName);
        	
        	if(trace) {
        		logger.info("Assigning last model: " + level + " " + lastModel.get(level));
        	}
        }
        if(modelName.equals(ModelNames.MODEL_SELF) || modelName.equals(ModelNames.MODEL_FLEX)) {
        	logger.error("Unresolved " + modelName +" reference");
    		logger.error("Current node: " + node.toString());
    		logger.error(JSONUtil.exportObject(currentNode));
    		return null;
        }
        
        ModelSchema ltype = RecordFactory.getSchema(modelName);
        List<FieldType> fields = new CopyOnWriteArrayList<>();
        BaseRecord type = null;
        try{
        	type = RecordFactory.newInstance(modelName);
        }
        catch(FieldException | ModelNotFoundException e) {
        	logger.error(e);
        }
        
        if(ltype == null || type == null) {
        	logger.error("Failed to create model instance");
        	return null;
        }

        Iterator<Map.Entry<String, JsonNode>> pairs = node.fields();
        int errors = 0;
        // logger.warn("DESERIALIZE " + modelName);
        NodeLink lastNode = currentNode;
        
        Map<String, JsonNode> foreignFlex = new HashMap<>();
        Map<String, JsonNode> fieldFlex = new HashMap<>();
        
        level++;
        while(pairs.hasNext()) {
        	Map.Entry<String, JsonNode> entry = pairs.next();
        	String fname = entry.getKey().intern();
        	if(fname == RecordFactory.JSON_MODEL_KEY) {
        		continue;
        	}
        	if(condensedFields) {
        		if(fname == RecordFactory.JSON_MODEL_SHORT_KEY) {
        			continue;
        		}
        		fname = getName(ltype, fname);
        	}
         	JsonNode value = entry.getValue();
         	boolean possibleForeign = false;
         	if(fname.endsWith(FieldNames.FIELD_SUFFIX_FOREIGN_KEY)) {
         		fname = fname.substring(0, fname.lastIndexOf(FieldNames.FIELD_SUFFIX_FOREIGN_KEY));
         		possibleForeign = true;
         	}
        	FieldType ifld = type.getField(fname);
        	if(ifld == null) {
        		if(!suppressInvalidFieldWarning) {
        			logger.error("Invalid field: " + modelName + "." + fname + " " + (entry.getValue() != null ? entry.getValue().textValue() : "null"));
        		}
        	}
        	else {
        		FieldType fld = null;
        		if(!ifld.getValueType().equals(FieldEnumType.FLEX)) {
        			fld = FieldFactory.fieldByType(ifld.getValueType(), fname, ifld.getValue());
        		}
				
				FieldSchema lft = ltype.getFieldSchema(fname);
				if(lft == null) {
					logger.error("Loose field " + fname + " could not be found");
				}
				else if(value != null) {

            		try {
            			if(possibleForeign && lft.getType().equals(ModelNames.MODEL_MODEL) && lft.getBaseModel() != null && lft.getBaseModel().equals(ModelNames.MODEL_FLEX) && lft.getForeignType() != null) {
            				foreignFlex.put(fname, value);
            			}
            			else if(ifld.getValueType().equals(FieldEnumType.FLEX)) {
            				fieldFlex.put(fname,  value);
            			}
            			else {
        					currentNode = new NodeLink(null);
        					currentNode.setFieldName(fname);
        					currentNode.setModel(modelName);
        					currentNode.setSchema(lft);
            				fld = setFieldValue(jsonParser, ifld, fld, lft, possibleForeign, value);
            			}
            		}
            		catch(ValueException e) {
            			logger.error(modelName + " - " + fname);
            			logger.error(e);
            		}
            	}
				if(fld == null) {
					if(ifld.getValueType().equals(FieldEnumType.FLEX)) {
						fieldFlex.put(fname,  value);
					}
					else {
						logger.error("Null field for " + fname);
					}
				}
				else {
					fields.add(fld);
				}
        	}
        }
        for(String k : fieldFlex.keySet()) {
        	FieldType ifld = type.getField(k);
        	FieldSchema lft = ltype.getFieldSchema(k);
        	FieldEnumType valType = null;
			Optional<FieldType> ofld = fields.stream().filter(f -> f.getName().equals(lft.getValueType())).findFirst();
			
			if(ofld.isPresent()) {
				valType = FieldEnumType.valueOf(ofld.get().getValue());
				Optional<FieldType> ofld2 = fields.stream().filter(f -> f.getName().equals("valueModel")).findFirst();
				if(ofld2.isPresent()) {
					String valModel = ofld2.get().getValue();
					if(valModel != null && !valModel.isEmpty() && !valModel.equals(ModelNames.MODEL_FLEX)) {
						currentNode.setListElementModel(valModel);
					}
					
				}
			}
			else {
				valType = ifld.getValueType();
			}
			try {
				FieldType fld = FieldFactory.fieldByType(valType, k, ifld.getValue());
				if(fld == null) {
					logger.error("Null field for " + k + " as " + valType);
				}
				else {
				fld = setFieldValue(jsonParser, fld, fld, lft, null, false, fieldFlex.get(k));
					if(fld != null) {
						fields.add(fld);
					}
					else {
						logger.error("Null field for " + k + " as " + valType);
					}
				}
			}
			catch (ValueException | IOException e) {
				logger.error(e);
			}
        }
        for(String k : foreignFlex.keySet()) {
        	FieldType ifld = type.getField(k);
        	FieldSchema lft = ltype.getFieldSchema(k);
			currentNode = new NodeLink(null);
			currentNode.setFieldName(k);
			currentNode.setModel(modelName);
			currentNode.setSchema(lft);
			String foreignType = null;
			Optional<FieldType> ofld = fields.stream().filter(f -> f.getName().equals(lft.getForeignType())).findFirst();
			if(ofld.isPresent()) {
				foreignType = ofld.get().getValue();
			}
        	try {
        		FieldType fld = FieldFactory.fieldByType(ifld.getValueType(), k, ifld.getValue());
				fld = setFieldValue(jsonParser, ifld, fld, lft, foreignType, true, foreignFlex.get(k));
				if(fld == null) {
					logger.error("Null field for " + k);
				}
				else {
					fields.add(fld);
				}
			} catch (ValueException | IOException e) {
				logger.error(e);
			}
        }
        currentNode = lastNode;
        level--;
        if(errors > 0) {
        	return null;
        }
        type.setFields(fields);
        RecordUtil.sortFields(type);
        return (T)type;
        
      }
	
	private FieldType setFieldValue(JsonParser jsonParser, FieldType ifld, FieldType fld, FieldSchema lft, boolean possibleForeign, JsonNode value) throws ValueException, IOException {
		return setFieldValue(jsonParser, ifld, fld, lft, null, possibleForeign, value);
	}
	private FieldType setFieldValue(JsonParser jsonParser, FieldType ifld, FieldType fld, FieldSchema lft, String foreignType, boolean possibleForeign, JsonNode value) throws ValueException, IOException {
			FieldType outFld = fld;
        	switch(ifld.getValueType()) {
	        	case ZONETIME:
	        		String zdv = value.textValue();
	        		/// TODO: Verify the value isn't re-adjusted from GMT
	        		if(zdv.indexOf("Z") == -1) {
	        			zdv += "Z";
	        		}
	        		fld.setValue(ZonedDateTime.parse(zdv, DateTimeFormatter.ISO_ZONED_DATE_TIME));
	        		break;
    			case TIMESTAMP:
    				fld.setValue(new Date(value.longValue()));
    				break;
    			case LONG:
    				fld.setValue(value.longValue());
    				break;
    			case BOOLEAN:
    				/// .booleanValue is not reliable
    				fld.setValue(Boolean.parseBoolean(value.asText()));
    				break;

    			case FLEX:
					if(value.isBoolean()) {
						outFld = FieldFactory.fieldByType(FieldEnumType.BOOLEAN, ifld.getName(), value.booleanValue());	
					}
					else if(value.isTextual() || value.isNull()) {
						outFld = FieldFactory.fieldByType(FieldEnumType.STRING, ifld.getName(), value.textValue());
					}
					else if(value.isInt()) {
						outFld = FieldFactory.fieldByType(FieldEnumType.INT, ifld.getName(), value.intValue());	
					}
					else if(value.isLong()) {
						outFld = FieldFactory.fieldByType(FieldEnumType.LONG, ifld.getName(), value.longValue());
					}
					else if(value.isDouble()) {
						outFld = FieldFactory.fieldByType(FieldEnumType.DOUBLE, ifld.getName(), value.longValue());
					}
					else {
    					logger.error("Unhandled flex type: " + value.getNodeType());
    				}
    				break;
    			case LIST:
    				fld.getFieldValueType().setBaseModel(lft.getBaseModel());
    				fld.getFieldValueType().setBaseType(lft.getBaseType());
    				/// DEBUG: Try to cleanup flexible lists
    				if(lft.getBaseType() == null && foreignType != null) {
        				fld.getFieldValueType().setBaseModel(foreignType);
        				//fld.getFieldValueType().setBaseType("model");

    				}
    				if(possibleForeign && lft.isForeign()) {
    					TypeReference tr = new TypeReference<List<?>>() {};
    					ObjectReader reader = mapper.readerFor(tr);
	    				List<?> list = reader.readValue(value);
	    				List<BaseRecord> impList = new ArrayList<>();
	    				for(Object o : list) {
	    					BaseRecord rec = null;
	    					// try {
		    					if(o instanceof String) {
		    						rec = IOSystem.getActiveContext().getSearch().findRecord(QueryUtil.createQuery(lft.getBaseModel(), FieldNames.FIELD_OBJECT_ID, (String)o));
		    						// rec = this.reader.read(lft.getBaseModel(), (String)o);
		    					}
		    					else if(o instanceof Long) {
		    						rec = IOSystem.getActiveContext().getSearch().findRecord(QueryUtil.createQuery(lft.getBaseModel(), FieldNames.FIELD_ID, (long)o));
		    						// rec = this.reader.read(lft.getBaseModel(), (long)o);
		    					}
		    					else if(o instanceof Integer) {
		    						Integer i = (Integer)o;
		    						rec = IOSystem.getActiveContext().getSearch().findRecord(QueryUtil.createQuery(lft.getBaseModel(), FieldNames.FIELD_ID, i.longValue()));
		    						//rec = this.reader.read(lft.getBaseModel(), i.longValue());
		    					}

		    					else {
		    						logger.error("Unhandled foreign key type: " + o);
		    					}
		    					filterFields(rec);
		    				/*
	    					}
	    					catch(ReaderException e) {
	    						logger.error(e.getMessage());
	    						
	    					}
	    					*/
	    					if(rec != null) {
	    						impList.add(rec);
	    					}
	    				}
	    				fld.setValue(impList);
    				}
    				else {
	    				TypeReference tr = null;
	    				String dbg = value.toString();

	    				if(currentNode.getListElementModel() != null || (lft.getBaseType() != null && lft.getBaseType().equals(FieldTypes.TYPE_MODEL))) {
	    					tr = new TypeReference<List<LooseRecord>>() {};
	    				}
	    				else {
	    					tr = new TypeReference<List<?>>() {};
	    				}
	    				//mapper.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
	    				if(!value.isNull()) {
	    					ObjectReader reader = mapper.readerFor(tr);
    						List<?> list = reader.readValue(value);
    						fld.setValue(list);
	    				}
    				}
    				break;
    			case MODEL:
    				BaseRecord val = null;
    				fld.getFieldValueType().setBaseModel(lft.getBaseModel());
    				if(possibleForeign && lft.isForeign()) {
    					String fmodel = lft.getBaseModel();
    					if(foreignType != null) {
    						fmodel = foreignType;
    					}
						if(value.isNumber()) {
							val = IOSystem.getActiveContext().getSearch().findRecord(QueryUtil.createQuery(fmodel, FieldNames.FIELD_ID, value.longValue()));
						}
						else if(value.isTextual()) {
							String txtVal = value.textValue();
							if(txtVal.startsWith("{")) {
								logger.warn("Detected direct reference in foreign field " + lft.getName());
								val = mapper.treeToValue(value, LooseRecord.class);
							}
							else {
								val = IOSystem.getActiveContext().getSearch().findRecord(QueryUtil.createQuery(fmodel, FieldNames.FIELD_OBJECT_ID, txtVal));
								if(val == null) {
									logger.warn("Failed to find foreign key: " + txtVal);
								}
							}
						}
						else {
							logger.error("Unhandled foreign key value for " + fld.getName());
						}
						filterFields(val);

    				}
    				else if(!value.isNull()){
    					val = mapper.treeToValue(value, LooseRecord.class);
    					if(val == null) {
    						logger.error("Failed to deserialize model");
    						logger.error(JSONUtil.exportObject(fld));
    					}
    				}
					fld.setValue(val);
						
    				break;
    			case ENUM:
					fld.getFieldValueType().setBaseClass(lft.getBaseClass());
					if(value.textValue() != null) {
						fld.setValue(value.textValue().toUpperCase());
					}
					break;
    			case STRING:
    				fld.setValue(value.textValue());
    				break;
    			case DOUBLE:
    				fld.setValue(value.doubleValue());
    				break;
    			case INT:
    				fld.setValue(value.intValue());
    				break;
    			case BLOB:
    				if(value.textValue() != null) {
    					String tmp = value.textValue().replaceAll("[\\r\\n]", "");
    					fld.setValue(Base64.getDecoder().decode(tmp));
    				}
    				break;
    			default:
    				logger.error("Unhandled field type: " + ifld.getValueType().toString());
    				break;
        	}
	        return outFld;
		}
		
		private void filterFields(BaseRecord rec) {
			if(rec != null && fkImportFields != null && fkImportFields.length > 0) {
				rec.setFields(
					rec.getFields().stream().filter(o -> {
						return Stream.of(fkImportFields).anyMatch(str -> str.equals(o.getName()));
					}).collect(Collectors.toList())
				);
			}
		}

}

class NodeLink{
	private String model = null;
	private String fieldName = null;
	private NodeLink parent = null;
	private NodeLink previous = null;
	private FieldSchema schema = null;
	private String listElementModel = null;

	public NodeLink(NodeLink parent) {
		this.parent = parent;
	}
	
	public String getListElementModel() {
		return listElementModel;
	}
	
	public void setListElementModel(String listElementModel) {
		this.listElementModel = listElementModel;
	}
	
	public NodeLink getPrevious() {
		return previous;
	}

	public void setPrevious(NodeLink previous) {
		this.previous = previous;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getFieldName() {
		return fieldName;
	}

	public void setFieldName(String fieldName) {
		this.fieldName = fieldName;
	}

	public NodeLink getParent() {
		return parent;
	}

	public void setParent(NodeLink parent) {
		this.parent = parent;
	}

	public FieldSchema getSchema() {
		return schema;
	}

	public void setSchema(FieldSchema schema) {
		this.schema = schema;
	}
	
	
	
}

