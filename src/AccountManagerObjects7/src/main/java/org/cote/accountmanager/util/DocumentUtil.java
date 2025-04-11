package org.cote.accountmanager.util;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.swing.text.BadLocationException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.provider.ProviderUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.provider.IProvider;

public class DocumentUtil {
	public static final Logger logger = LogManager.getLogger(DocumentUtil.class);
	
	public static BaseRecord getData(BaseRecord owner, String name, String path) {
		BaseRecord dat = null;
		BaseRecord group = IOSystem.getActiveContext().getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, path,
				GroupEnumType.DATA.toString(), owner.get(FieldNames.FIELD_ORGANIZATION_ID));
		if (group != null) {
			Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_NAME, name);
			q.planMost(true);
			dat = IOSystem.getActiveContext().getSearch().findRecord(q);
		} else {
			logger.warn("Group is null: " + path);
		}
		return dat;
	}
	
	public static BaseRecord getCreateData(BaseRecord owner, String name, String path, String textContents) {
		BaseRecord dat = getData(owner, name, path);
		BaseRecord datT = null;

		if (dat == null) {
			if(textContents == null || textContents.length() == 0) {
				logger.error("Invalid text contents");
				return null;
			}
			try {
				datT = RecordFactory.newInstance(ModelNames.MODEL_DATA);
				datT.set(FieldNames.FIELD_NAME, name);
				datT.set(FieldNames.FIELD_GROUP_PATH, path);
				datT.set(FieldNames.FIELD_BYTE_STORE, textContents.getBytes());
				datT.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
				dat = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, owner, datT, null);
				dat = IOSystem.getActiveContext().getAccessPoint().create(owner, dat);

			} catch (FieldException | ModelNotFoundException | ValueException | FactoryException e) {
				logger.error(e);
			}
		}
		return dat;
	}
	
	public static BaseRecord getCreateTag(BaseRecord user, String name, String type) {
		BaseRecord group = IOSystem.getActiveContext().getAccessPoint().make(user, ModelNames.MODEL_GROUP, "~/Tags", GroupEnumType.DATA.toString());
		if(group != null) {
			return getCreateTag(user, name, type, group);
		}
		return null;
	}
	
	public static BaseRecord getCreateTag(BaseRecord user, String name, String type, BaseRecord group) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_TAG, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_TYPE, type);
		BaseRecord rec = IOSystem.getActiveContext().getSearch().findRecord(q);
		if(rec == null) {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, group.get(FieldNames.FIELD_PATH));
			plist.parameter(FieldNames.FIELD_NAME, name);
			try {
				rec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_TAG, user, null, plist);
				rec.set(FieldNames.FIELD_TYPE, type);
				IOSystem.getActiveContext().getRecordUtil().createRecord(rec);
			}
			catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
		}
		return rec;
	}
	
	
	public static String getStringContent(BaseRecord model) {
		String content = null;
		ModelSchema ms = RecordFactory.getSchema(model.getSchema());
		if(ms.getVector() != null) {
			IProvider prov = ProviderUtil.getProviderInstance(ms.getVector());
			logger.info("Vector provider " + ms.getVector());
			if(prov != null) {
				content = prov.describe(ms, model);
			}
			else {
				logger.error("Vector provider could not be instantiated: " + ms.getVector());
			}
		}
		else if(model.inherits(ModelNames.MODEL_CRYPTOBYTESTORE)) {
			//IOSystem.getActiveContext().getReader().populate(vectorRef, new String[] { FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_BYTE_STORE });
			String contentType = model.get(FieldNames.FIELD_CONTENT_TYPE);
			if(contentType != null) {
				 try {

					if(contentType.startsWith("text/") || contentType.equals("application/x-javascript") || contentType.equals("text/xml") || contentType.equals("application/json")) {
						content = ByteModelUtil.getValueString(model);
					}
					else if(contentType.equals("application/pdf")) {
						content = readPDF(ByteModelUtil.getValue(model));
					}
					else if(
						contentType.equals("application/msword") ||
						contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
					) {
						content = readDocument(ByteModelUtil.getValue(model));
					}
					else {
						logger.warn("Unhandled content type: " + contentType);
					}
				} catch (ValueException | FieldException e) {
					logger.error(e);
				}

			}
		}
		else if(model.hasField(FieldNames.FIELD_TEXT)) {
			content = model.get(FieldNames.FIELD_TEXT);
		}
		else {
			logger.warn("Unhandled model: " + model.getSchema());
		}
		return content;
	}
	
	public static String readRtf(String file) {
	   var rtfEK = new javax.swing.text.rtf.RTFEditorKit();
	   var rtf = rtfEK.createDefaultDocument();
	   String outStr = null;
	   try {
		   rtfEK.read(new java.io.ByteArrayInputStream(FileUtil.getFile(file)), rtf, 0);
		   outStr = rtf.getText(0, rtf.getLength());
		} catch (IOException | BadLocationException e) {
			logger.error(e);
			e.printStackTrace();
		}
	   return outStr;
	}
	
    public static String readDocument(String file) {
    	String out = null;
    	try {
	    	InputStream fileStream = new FileInputStream(file);
	    	AutoDetectParser parser = new AutoDetectParser();
	    	Metadata metadata = new Metadata();
	    	BodyContentHandler handler = new BodyContentHandler(Integer.MAX_VALUE);
	    	//XXX PDFParserConfig pdfConfig = new PDFParserConfig();
	    	//XXX pdfConfig.setExtractInlineImages(true);
	    	//XXX parseContext.set(PDFParserConfig.class, pdfConfig);
	    	//ParseContext parseContext = new ParseContext();
	    	//parseContext.set(Parser.class, parser);
	    	// , parseContext
	    	parser.parse(fileStream, handler, metadata);
			out = replaceSmartQuotes(handler.toString());
			
    	}
    	catch(Exception e) {
    		logger.error(e);
    		e.printStackTrace();
    	}
    	return out;
    }
    
    public static String readDocument(byte[] data) {
    	String out = null;
    	try {
	    	ByteArrayInputStream bais = new ByteArrayInputStream(data);
	    	AutoDetectParser parser = new AutoDetectParser();
	    	Metadata metadata = new Metadata();
	    	BodyContentHandler handler = new BodyContentHandler(Integer.MAX_VALUE);
	    	parser.parse(bais, handler, metadata);
			out = replaceSmartQuotes(handler.toString());
			
    	}
    	catch(Exception e) {
    		logger.error(e);
    		e.printStackTrace();
    	}
    	return out;
    }
	
	public static String replaceSmartQuotes(String txt) {
		return txt.replaceAll("[“”]", "\"").replaceAll("’", "'");
	}

	public static String readPDF(byte[] pdfBytes) {
		String output = null;
		PDDocument doc;
		try {
			doc = Loader.loadPDF(pdfBytes);
			output = replaceSmartQuotes(new PDFTextStripper().getText(doc));
		} catch (IOException e) {
			logger.error(e);
		}
		return output;
	}
	
	public static String readPDF(String path) {
		String output = null;
		PDDocument doc;
		try {
			doc = Loader.loadPDF(new File(path));
			output = replaceSmartQuotes(new PDFTextStripper().getText(doc));
		} catch (IOException e) {
			logger.error(e);
		}
		  
		return output;
	}
}
