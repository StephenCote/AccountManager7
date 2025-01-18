package org.cote.accountmanager.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

public class DocumentUtil {
	public static final Logger logger = LogManager.getLogger(DocumentUtil.class);
	
    public static String readDocument(String file) {
    	String out = null;
    	try {
	    	InputStream fileStream = new FileInputStream(file);
	    	Parser parser = new AutoDetectParser();
	    	Metadata metadata = new Metadata();
	    	BodyContentHandler handler = new BodyContentHandler(Integer.MAX_VALUE);
	    	//PDFParserConfig pdfConfig = new PDFParserConfig();
	    	//pdfConfig.setExtractInlineImages(true);
	    	ParseContext parseContext = new ParseContext();
	    	//parseContext.set(PDFParserConfig.class, pdfConfig);
	    	parseContext.set(Parser.class, parser);
	    	parser.parse(fileStream, handler, metadata, parseContext);
			out = replaceSmartQuotes(handler.toString());
			
    	}
    	catch(IOException | SAXException | TikaException e) {
    		logger.error(e);
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
