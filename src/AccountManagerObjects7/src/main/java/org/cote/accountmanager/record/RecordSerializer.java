package org.cote.accountmanager.record;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.FieldTypes;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.RecordUtil;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

public class RecordSerializer extends JsonSerializer<BaseRecord> {
	public static final Logger logger = LogManager.getLogger(RecordSerializer.class);
	private static final String FK_SUFFIX = "_FK";
	private boolean filterVirtual = false;
	private boolean filterForeign = true;
	private boolean filterEphemeral = false;
	private boolean condenseModelDeclarations = true;
	private boolean condenseDeclarations = false;
	private boolean stopCondensing = false;
	
    public RecordSerializer() {

    }

    public boolean isFilterVirtual() {
		return filterVirtual;
	}

	public boolean isFilterEphemeral() {
		return filterEphemeral;
	}

	public void setFilterEphemeral(boolean filterEphemeral) {
		this.filterEphemeral = filterEphemeral;
	}

	public boolean isFilterForeign() {
		return filterForeign;
	}

	public void setFilterForeign(boolean filterForeign) {
		this.filterForeign = filterForeign;
	}

	public void setFilterVirtual(boolean filterVirtual) {
		this.filterVirtual = filterVirtual;
	}
	
	

	public boolean isCondenseModelDeclarations() {
		return condenseModelDeclarations;
	}

	public void setCondenseModelDeclarations(boolean condenseModelDeclarations) {
		this.condenseModelDeclarations = condenseModelDeclarations;
	}

	@Override
    public void serialize(BaseRecord value, JsonGenerator jgen, SerializerProvider provider) throws IOException, JsonProcessingException {
    	ModelSchema ltype = RecordFactory.getSchema(value.getModel());
    	RecordUtil.sortFields(value);
    	
        jgen.writeStartObject();

        if(ltype.isEmitModel() || stopCondensing || !condenseModelDeclarations || !condenseDeclarations) {
        	/// allow the emit specification to cascade down to the next object level
        	///
        	if(ltype.isEmitModel()) {
        		stopCondensing = true;
        	}
       		condenseDeclarations = true;

       		jgen.writeStringField(RecordFactory.JSON_MODEL_KEY, value.getModel());
        }
        // logger.info("*** SERIALIZE " + value.getModel());
        for(FieldType f: value.getFields()) {
        	// logger.info("*** SERIALIZE FIELD " + f.getName() + " " + f.getValueType().toString());
        	FieldSchema lft = ltype.getFieldSchema(f.getName());
        	if(
        		(filterVirtual && lft.isVirtual())
        		||
        		(filterEphemeral && lft.isEphemeral())
        	) {
        		continue;
        	}
        	
        	/*
    		if(f.getValue() == null) {
    			logger.warn("Null value for " + value.getModel() + " " + f.getName());
    			continue;
    		}
			*/
        	if(f.getValueType().equals(FieldEnumType.FLEX)) {
        		logger.warn("Abstract FieldType value for " + f.getName() + " cannot be exported.");
        		continue;
        		
        	}
        	switch(f.getValueType()) {
        		case ENUM:
	        		if(f.getValue() != null) {
	        			String lowVal = f.getValue();
	        			if(!lowVal.equals("UNKNOWN")) {
	        				jgen.writeStringField(f.getName(), lowVal.toLowerCase());
	        			}
	        		}
	        		break;
        		case STRING:
	        		if(f.getValue() != null) {
	        			jgen.writeStringField(f.getName(), f.getValue());
	        		}
	        		break;
	        	case LONG:
	        		if(f.getValue() == null) {
	        			logger.error(f.getName() + " value is null");
	        			continue;
	        		}
	        		
	        		long lval = f.getValue();
	        		if(lval != 0L) {
	        			jgen.writeNumberField(f.getName(), lval);
	        		}
	        		break;
	        	case BOOLEAN:
	        		if(f.getValue() != null) {
	        			jgen.writeBooleanField(f.getName(), f.getValue());
	        		}
	        		break;
	        	case ZONETIME:
	        		ZonedDateTime zone = f.getValue();
	        		if(zone != null) {
	        			jgen.writeStringField(f.getName(), zone.format(DateTimeFormatter.ISO_ZONED_DATE_TIME));
	        		}
	        		break;
	        	case TIMESTAMP:
	        		long ldval = ((Date)f.getValue()).getTime();
	        		if(ldval != 0L) {
	        			jgen.writeNumberField(f.getName(), ldval);
	        		}
	        		break;
	        	case BLOB:
	        		byte[] data = f.getValue();
	        		if(data != null && data.length > 0) {
	        			jgen.writeBinaryField(f.getName(), data);
	        		}
	        		break;
	        	case INT:
	        		if(f.getValue() != null) {
		        		int ival = f.getValue();
		        		if(ival != 0) {
		        			jgen.writeNumberField(f.getName(), ival);
		        		}
	        		}
	        		break;
	        	case DOUBLE:
	        		if(f.getValue() != null) {
		        		double dval = f.getValue();
		        		if(dval != 0.0) {
		        			jgen.writeNumberField(f.getName(), dval);
		        		}
	        		}
	        		break;
	        	case LIST:
	        		List<?> list = f.getValue();
	        		if(list != null && list.size() > 0) {
	        			boolean directWrite = true;
		        		if(filterForeign && lft.isForeign() && lft.getBaseType().equals(FieldTypes.TYPE_MODEL) && lft.getBaseModel() != null && lft.getForeignField() != null) {
	        				String fprop = lft.getForeignField();
	        				String fkey = f.getName() + FK_SUFFIX;
	        				BaseRecord rec1 = (BaseRecord) list.get(0);
	        				FieldType ftype = rec1.getField(fprop);
	        				if(ftype == null) {
	        					logger.debug("Required field for exporting foreign key is null: " + fprop);
	        				}
	        				else {
		        				if(!RecordUtil.isIdentityRecord(rec1)) {
		        					logger.error("Record is missing identity in foreign list");
		        					throw new IOException("Attempted to serialize a foreign keyed value missing an identity");
		        				}

	        					jgen.writeArrayFieldStart(fkey);
		        				for(Object reco: list) {
		        					BaseRecord rec = (BaseRecord) reco;
			        				switch(ftype.getValueType()) {
			        					case LONG:
			        						long flval = rec.get(fprop);
			        						if(flval != 0L) {
			        							jgen.writeNumber(flval);
			        						}
			        						break;
			        					case STRING:
			        						if(rec.get(fprop) != null) {
			        							jgen.writeString((String)rec.get(fprop));
			        						}
			        						break;
			        					default:
			        		        		logger.error("Unhandled foreign type: " + ftype.getValueType().toString());
			        		        		break;
			        				}
			        			}
		        				jgen.writeEndArray();
		        				directWrite = false;
	        				}
	        			}
		        		
		        		if(directWrite) {
		        			if(lft.getBaseModel() != null && lft.getBaseModel().equals(ModelNames.MODEL_FLEX)) {
		        				// logger.warn("Don't condense on flex");
		        				condenseDeclarations = false;
		        				stopCondensing = true;
		        			}
		        			jgen.writeArrayFieldStart(f.getName());
		        			for(Object o: list) {
		        				/// TODO: Add smarter limits on preventing recursion
		        				/// PLAN: Add an instance level tracker to only allow an object to appear once with all foreign references, and subsequently without
		        				///
		        				if(lft.getBaseModel() != null && (lft.getBaseModel().equals(value.getModel()) || value.inherits(lft.getBaseModel()))) {
		        					BaseRecord o2 = (BaseRecord)o;
		        					Set<String> fl = o2.getFields().stream().map(fx -> fx.getName()).collect(Collectors.toSet());
		        					List<String> ol = ltype.getFields().stream().filter(lx -> fl.contains(lx.getName()) && !lx.isForeign()).map(fx -> fx.getName()).collect(Collectors.toList());
		        					jgen.writeObject(o2.copyRecord(ol.toArray(new String[0])));
		        				}
		        				else {
		        					jgen.writeObject(o);
		        				}
		        			}
		        			jgen.writeEndArray();
		        		}
	        		}
	        		break;
	        	case MODEL:
	        		BaseRecord mval = f.getValue();
	        		
	        		if(mval != null) {
	        			boolean directWrite = true;
	        			if(filterForeign && lft.isForeign() && lft.getForeignField() != null) {
	        				String fprop = lft.getForeignField();
	        				FieldType ftype = mval.getField(fprop);
	        				if(ftype == null) {
	        					logger.debug("Required field for exporting foreign key is null: " + fprop);
	        				}
	        				else if(!RecordUtil.isIdentityRecord(mval)) {
	        					logger.debug("Record " + mval.getModel() + " is missing an identity to be used as a foreign property.  Saving field " + lft.getName() + " directly");
	        				}

	        				else {
		        				String fkey = f.getName() + FK_SUFFIX;
		        				switch(ftype.getValueType()) {
		        					case LONG:
		        						long flval = ftype.getValue();
		        						if(flval != 0L) {
		        							jgen.writeNumberField(fkey, flval);
		        						}
		        						break;
		        					case STRING:
		        						if(ftype.getValue() != null) {
		        							jgen.writeStringField(fkey, ftype.getValue());
		        						}
		        						break;
		        					default:
		        		        		logger.error("Unhandled foreign type: " + ftype.getValueType().toString());
		        		        		break;
		        				}
		        				directWrite = false;
	        				}
	        			}
	        			if(directWrite && mval != null) {
		        			if(lft.getBaseModel() != null && lft.getBaseModel().equals(ModelNames.MODEL_FLEX)) {
		        				condenseDeclarations = false;
		        				stopCondensing = true;
		        			}
	        				/// TODO: Add smarter limits on preventing recursion
	        				/// PLAN: Add an instance level tracker to only allow an object to appear once with all foreign references, and subsequently without
	        				///
	        				if(lft.getBaseModel() != null && lft.getBaseModel().equals(value.getModel()) || value.inherits(lft.getBaseModel())) {
	        					BaseRecord o2 = (BaseRecord)mval;
	        					Set<String> fl = o2.getFields().stream().map(fx -> fx.getName()).collect(Collectors.toSet());
	        					List<String> ol = ltype.getFields().stream().filter(lx -> fl.contains(lx.getName()) && !lx.isForeign()).map(fx -> fx.getName()).collect(Collectors.toList());
	        					jgen.writeObjectField(f.getName(), o2.copyRecord(ol.toArray(new String[0])));
	        				}
	        				else {
	        					jgen.writeObjectField(f.getName(), mval);
	        				}
	        			}
	        		}
	        		break;
	        	default:
	        		logger.error("Unhandled type: " + f.getValueType().toString());
	        		break;
        	}
        	
        }
        jgen.writeEndObject();
    }
	
}
