package org.cote.accountmanager.util;

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

public class DocumentUtil {
	public static final Logger logger = LogManager.getLogger(DocumentUtil.class);
	
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
