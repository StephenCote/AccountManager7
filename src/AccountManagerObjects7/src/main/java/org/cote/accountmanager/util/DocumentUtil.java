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
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class DocumentUtil {
	public static final Logger logger = LogManager.getLogger(DocumentUtil.class);
	
	public static String getStringContent(BaseRecord model) {
		String content = null;
		if(model.inherits(ModelNames.MODEL_CRYPTOBYTESTORE)) {
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
					else if(contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
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
