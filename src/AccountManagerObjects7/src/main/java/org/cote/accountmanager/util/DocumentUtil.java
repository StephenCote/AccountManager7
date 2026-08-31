package org.cote.accountmanager.util;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.swing.text.BadLocationException;

import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
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
import org.cote.accountmanager.provider.IProvider;
import org.cote.accountmanager.provider.ProviderUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.GroupEnumType;

public class DocumentUtil {
	public static final Logger logger = LogManager.getLogger(DocumentUtil.class);

	/** MIME types that require binary extraction (Tika or POI) rather than raw UTF-8 read. */
	public static final Set<String> OFFICE_CONTENT_TYPES = Set.of(
		"application/msword",                                                         // .doc  (POI HWPF)
		"application/vnd.openxmlformats-officedocument.wordprocessingml.document",    // .docx (Tika OOXML)
		"application/rtf",                                                            // .rtf  (Tika)
		"text/rtf",                                                                   // .rtf  (alt MIME)
		"application/wordperfect",                                                    // .wpd  WP6+ (Tika WPDParser)
		"application/x-wordperfect",                                                  // .wpd  WP5 and earlier
		"application/vnd.wordperfect"                                                 // .wpd  IANA-registered
	);

	public static BaseRecord getRecord(BaseRecord owner, String modelName, String name, String path) {
		return getRecord(owner, modelName, name, path, true);
	}
	public static BaseRecord getRecord(BaseRecord owner, String modelName, String name, String path, boolean full) {
		return getRecord(owner, modelName, name, path, full, true);
	}

	public static BaseRecord getRecord(BaseRecord owner, String modelName, String name, String path, boolean full, boolean useCache) {
		BaseRecord dat = null;
		BaseRecord group = IOSystem.getActiveContext().getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, path,
				GroupEnumType.DATA.toString(), owner.get(FieldNames.FIELD_ORGANIZATION_ID)
		);
		if (group != null) {
			Query q = QueryUtil.createQuery(modelName, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_NAME, name);
			if(full) {
				q.planMost(true);
			}
			if(!useCache) {
				q.setCache(false);
			}

			dat = IOSystem.getActiveContext().getAccessPoint().find(owner, q);
		} else {
			logger.warn("Group is null: " + path);
		}
		return dat;
	}
	
	public static BaseRecord getData(BaseRecord owner, String name, String path) {
		return getRecord(owner, ModelNames.MODEL_DATA, name, path);
	}
	
	public static BaseRecord getNote(BaseRecord owner, String name, String path) {
		return getRecord(owner, ModelNames.MODEL_NOTE, name, path);
	}

	public static BaseRecord getNote(BaseRecord owner, String name, String path, boolean useCache) {
		return getRecord(owner, ModelNames.MODEL_NOTE, name, path, true, useCache);
	}
	
	public static BaseRecord getCreateNote(BaseRecord owner, String name, String path, String textContents) {
		BaseRecord dat = getRecord(owner, ModelNames.MODEL_NOTE, name, path);
		BaseRecord datT = null;

		if (dat == null) {
			if(textContents == null || textContents.length() == 0) {
				logger.error("Invalid text contents");
				return null;
			}
			try {
				datT = RecordFactory.newInstance(ModelNames.MODEL_NOTE);
				datT.set(FieldNames.FIELD_NAME, name);
				datT.set(FieldNames.FIELD_GROUP_PATH, path);
				datT.set(FieldNames.FIELD_TEXT, textContents);
				dat = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, owner, datT, null);
				dat = IOSystem.getActiveContext().getAccessPoint().create(owner, dat);

			} catch (FieldException | ModelNotFoundException | ValueException | FactoryException e) {
				logger.error(e);
			}
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
	
	public static boolean applyTag(BaseRecord user, String tagName, String tagType, BaseRecord targetObj, boolean enable) {
		BaseRecord tag = getCreateTag(user, tagName, tagType);
		boolean outBool = false;
		if(tag != null) {
			outBool = IOSystem.getActiveContext().getMemberUtil().member(user, tag, targetObj, null, true);
		}
		return outBool;
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
			if(!model.hasField(FieldNames.FIELD_BYTE_STORE)) {
				IOSystem.getActiveContext().getReader().populate(model, new String[] { FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_BYTE_STORE });
			}
			String contentType = model.get(FieldNames.FIELD_CONTENT_TYPE);
			if(contentType != null) {
				 try {

					if(contentType.startsWith("text/") || contentType.equals("application/x-javascript") || contentType.equals("text/xml") || contentType.equals("application/json")) {
						content = ByteModelUtil.getValueString(model);
					}
					else if(contentType.equals("application/pdf")) {
						content = readPDF(ByteModelUtil.getValue(model));
					}
					else if(OFFICE_CONTENT_TYPES.contains(contentType)) {
						content = readDocument(ByteModelUtil.getValue(model), Integer.MAX_VALUE, contentType);
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

    /**
     * Extract text from a legacy Word 97-2003 .doc binary using Apache POI HWPF.
     * <p>
     * Tika's {@link AutoDetectParser} without a content-type hint may fall back to
     * a generic OLE2 stream dump for these files, producing upper-ASCII binary garbage
     * instead of readable prose. Direct POI extraction is unambiguous.
     *
     * @param data raw bytes of the .doc (OLE2/HWPF) file
     * @return extracted plain text, or {@code null} on failure
     */
    static String readDocFile(byte[] data) {
    	try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(data));
    	     WordExtractor extractor = new WordExtractor(doc)) {
    		return replaceSmartQuotes(extractor.getText());
    	} catch (Exception e) {
    		logger.error("POI HWPF .doc extraction failed: {}", e.getMessage(), e);
    		return null;
    	}
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

    /**
     * Bounded byte[] document extraction. {@code maxChars} caps the number of characters Tika will
     * accumulate, bounding heap against a crafted/oversized upload — the unbounded
     * {@link #readDocument(byte[])} uses {@code BodyContentHandler(Integer.MAX_VALUE)}, which is
     * effectively unbounded. Returns null if extraction fails or the content exceeds {@code maxChars}.
     */
    public static String readDocument(byte[] data, int maxChars) {
    	String out = null;
    	try {
	    	ByteArrayInputStream bais = new ByteArrayInputStream(data);
	    	AutoDetectParser parser = new AutoDetectParser();
	    	Metadata metadata = new Metadata();
	    	BodyContentHandler handler = new BodyContentHandler(maxChars);
	    	parser.parse(bais, handler, metadata);
			out = replaceSmartQuotes(handler.toString());

    	}
    	catch(Exception e) {
    		logger.error(e);
    		e.printStackTrace();
    	}
    	return out;
    }

    /**
     * Bounded extraction with an explicit content-type hint.
     * <p>
     * Routes {@code application/msword} (.doc) to {@link #readDocFile(byte[])} (Apache POI
     * HWPF) to avoid the OLE2-stream-dump fallback that Tika's {@code AutoDetectParser}
     * may produce when the HWPF-specific parser is not preferred by detection alone.
     * WordPerfect MIME types ({@code application/wordperfect},
     * {@code application/x-wordperfect}, {@code application/vnd.wordperfect}) and all other
     * content types go through {@code AutoDetectParser} with the hint set in
     * {@link Metadata} so Tika can select the correct parser without relying solely on
     * magic-byte detection.
     *
     * @param data        raw bytes of the document
     * @param maxChars    maximum characters to extract; bounds heap against oversized uploads
     * @param contentType MIME type of the document, or {@code null} for pure auto-detection
     * @return extracted plain text, or {@code null} on failure
     */
    public static String readDocument(byte[] data, int maxChars, String contentType) {
    	if ("application/msword".equalsIgnoreCase(contentType)) {
    		String text = readDocFile(data);
    		if (text != null && maxChars > 0 && text.length() > maxChars) {
    			text = text.substring(0, maxChars);
    		}
    		return text;
    	}
    	String out = null;
    	try {
	    	ByteArrayInputStream bais = new ByteArrayInputStream(data);
	    	AutoDetectParser parser = new AutoDetectParser();
	    	Metadata metadata = new Metadata();
	    	if (contentType != null && !contentType.isEmpty()) {
	    		metadata.set("Content-Type", contentType);
	    	}
	    	BodyContentHandler handler = new BodyContentHandler(maxChars);
	    	parser.parse(bais, handler, metadata);
			out = replaceSmartQuotes(handler.toString());

    	}
    	catch(Exception e) {
    		logger.error(e);
    		e.printStackTrace();
    	}
    	return out;
    }
	
	/// Normalizes Word/Tika-introduced "smart" typography to plain ASCII so downstream consumers that
	/// treat extracted text as a literal source-of-truth (PageIndexUtil's LLM-TOC verbatim startMarker
	/// location via String.indexOf, in particular) aren't defeated by punctuation an LLM naturally
	/// "cleans up" when it echoes text back. Previously only right-double/right-single quotes were
	/// normalized; left-single quotes (U+2018 - Word sometimes emits these for possessive apostrophes,
	/// e.g. "Duña‘s"), en/em dashes, and ellipsis are common in prose docx/pdf content and were
	/// passed through verbatim, silently breaking exact-substring marker lookups (see PageIndexUtil's
	/// buildLlmTocTree/locateMarker). All substitutions here except the ellipsis are 1:1 character
	/// replacements, so callers that rely on stable character offsets into the returned string are unaffected.
	public static String replaceSmartQuotes(String txt) {
		if(txt == null) {
			return txt;
		}
		return txt
			.replaceAll("[“”]", "\"")
			.replaceAll("[‘’]", "'")
			// Whole Unicode dash/hyphen family, not just en/em. In source order the class holds
			// U+2010 hyphen, U+2011 non-breaking hyphen, U+2012 figure dash, U+2013 en dash,
			// U+2014 em dash, U+2015 horizontal bar, U+2212 minus sign — the first three are
			// visually indistinguishable from an ASCII hyphen, so trust this list, not the glyphs.
			// U+2011 in particular is what LLMs emit in compounds ("gull-wing", "neon-lit"); it
			// reaches Stable Diffusion as a distinct CLIP token from the ASCII hyphen.
			.replaceAll("[‐‑‒–—―−]", "-")
			.replaceAll("…", "...")
			.replaceAll(" ", " ");
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
