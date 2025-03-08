package org.cote.accountmanager.olio.assist;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class ScrivenerDocumentMap extends DocumentMap {
	public static final Logger logger = LogManager.getLogger(ScrivenerDocumentMap.class);
	protected Map<Path, ScrivenerBinding> documentMeta = new HashMap<>();
	
	public void mapMetaData(Path path, Element el, int index, String uid) {
		String title = null;
		NodeList t1 = el.getElementsByTagName("Title");
		if(t1.getLength() > 0) {
			Element tle = (Element)t1.item(0);
			title = tle.getTextContent();
		}
		NodeList pt1 = ((Element)el.getParentNode().getParentNode()).getElementsByTagName("Title");
		if(pt1.getLength() > 0) {
			Element tle = (Element)pt1.item(0);
			title = tle.getTextContent();
		}
		documentMeta.put(path, new ScrivenerBinding(uid, index, title, path));
		
	}
	
	public List<ScrivenerBinding> parseProject(String path) throws ParserConfigurationException, SAXException, IOException, XPathExpressionException {
		List<ScrivenerBinding> bds = new ArrayList<>();
		String projectFile = path.substring(path.lastIndexOf("\\")) + "x";
	    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
	    factory.setValidating(false);
	    factory.setIgnoringElementContentWhitespace(true);
	    DocumentBuilder builder = factory.newDocumentBuilder();
	    logger.info("Parse " + path + projectFile);
	    File file = new File(path + projectFile);
	    Document doc = builder.parse(file);
	    doc.getDocumentElement().normalize();
	    
	    XPath xPath = XPathFactory.newInstance().newXPath();
	    String expression = "//BinderItem[@Type='Text']";
	    NodeList nodeList = (NodeList) xPath.compile(expression).evaluate(doc, XPathConstants.NODESET);
	    
	    int idx = 0;
	    for(int i = 0; i < nodeList.getLength(); i++) {
	    	Element el = (Element)nodeList.item(i);
	    	NodeList incl = (NodeList)xPath.compile("MetaData/IncludeInCompile").evaluate(el, XPathConstants.NODESET);
	    	if(incl.getLength() > 0 && "Yes".equals(incl.item(0).getTextContent())) {
		    	String uid = el.getAttribute("UUID");
		    	String cntPath = path + File.separator + "Files" + File.separator + "Data" + File.separator + uid + File.separator + "content.rtf";
		    	File f = new File(cntPath);
		    	if(f.exists()) {
		    		Path bpath = Paths.get(f.getPath());
		    		mapMetaData(bpath, el, idx, uid);
		    		idx++;
		    		mapRtfDocument(bpath);
		    	}
	    	}
	    }
	    
	    return bds;
	    
	}
}

class ScrivenerBinding{
	private String title = null;
	private int index = 0;
	private Path path = null;
	private String uid = null;
	public ScrivenerBinding() {
		
	}
	public ScrivenerBinding(String uid, int index, String title, Path path) {
		this.index = index;
		this.title = title;
		this.path = path;
		this.uid = uid;
	}
	public String getTitle() {
		return title;
	}
	public int getIndex() {
		return index;
	}
	public Path getPath() {
		return path;
	}
	public String getUid() {
		return uid;
	}
	
	
}