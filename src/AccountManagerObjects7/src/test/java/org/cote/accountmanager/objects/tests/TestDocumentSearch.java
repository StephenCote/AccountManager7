package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class TestDocumentSearch extends BaseTest {

	
	private String scrivenerProjectPath = "C:\\Users\\swcot\\OneDrive\\Documents\\Scrivner\\The Verse.scriv";
	
	@Test
	public void TestScrivener() {
		logger.info("Test scan scrivener...");
		try {
			parseProject(scrivenerProjectPath);
		} catch (ParserConfigurationException | SAXException | IOException | XPathExpressionException e) {
			logger.error(e);
			e.printStackTrace();
		}
		
		watch(scrivenerProjectPath);
	}
	
	private List<ScrivenerBinding> parseProject(String path) throws ParserConfigurationException, SAXException, IOException, XPathExpressionException {
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
	
	private List<Path> modifiedFiles = new ArrayList<>();
	private Map<Path, Long> processedFiles = new HashMap<>();
	private Map<Path,List<String>> documentMap = new HashMap<>();
	private Map<Path, ScrivenerBinding> documentMeta = new HashMap<>();
	private Map<Path, String> documentSummary = new HashMap<>();
	
	private long pause = 5000;
	private void mapMetaData(Path path, Element el, int index, String uid) {
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
	
	private void mapRtfDocument(Path path) {
		String txt = DocumentUtil.readRtf(path.toString());
		if(txt == null) {
			logger.error("Null content for " + path);
			return;
		}
		else if(txt.trim().length() == 0) {
			logger.info("Skip empty document " + path);
			return;
		}
		List<String> lines = Arrays.asList(txt.split("\n")).stream().map(l -> l.trim()).collect(Collectors.toList());
		// logger.info(path + " " + lines.size());
		documentMap.put(path, lines);
	}
	
	private void processModified(Path path) {
		long now = System.currentTimeMillis();
		if(processedFiles.containsKey(path) && (processedFiles.get(path) + pause) > now) {
			logger.info("Too soon for " + path + ": " + (processedFiles.get(path) + pause) + " > " + now);
			return;
		}

		modifiedFiles.add(path);
		if(!documentMap.containsKey(path)) {
			mapRtfDocument(path);
		}
		else {
			List<String> lines1 = documentMap.get(path);
			// remap
			mapRtfDocument(path);
			if(!documentSummary.containsKey(path)) {
				int cont = 0;
				try {
					cont = summarize(path, lines1.stream().collect(Collectors.joining(System.lineSeparator())));
				} catch (FieldException | ModelNotFoundException e) {
					logger.error(e);
					e.printStackTrace();
				}
				if(cont == 2) {
					return;
				}
			}
			List<String> lines2 = documentMap.get(path);
			String prevLine = null;
			String line = null;
			String nextLine = null;
			List<String> lines3 = lines2.stream().filter(l -> !lines1.contains(l)).collect(Collectors.toList());
			logger.info("Changed lines: " + lines3.size());
			
			for(int i = 0; i < lines2.size(); i++) {
				String tline = lines2.get(i);
				
				/// Found the first changed line
				if(lines3.contains(tline)) {
					line = tline;
					if(i < (lines2.size() - 1)) {
						nextLine = lines2.get(i+1);
					}
					break;
				}
				prevLine = tline;
			}
			if(line != null) {
				try {
					assist(path, prevLine, line, nextLine);
				} catch (FieldException | ModelNotFoundException e) {
					logger.error(e);
					e.printStackTrace();
				}
				processedFiles.put(path,  System.currentTimeMillis());
				/*
				logger.info("*PREVIOUS* " + prevLine);
				logger.info("*CURRENT* " + line);
				if(nextLine != null) {
					logger.info("*NEXT* " + nextLine);
				}
				*/
			}
			
			
		}

	}
	
	String analysisPrompt = """
You are are an editor assistant and expert in Critical Discourse Analysis and Critical Rhetorical Analysis content analyst.
Rules:
* Use Critical Discoures Analysis to provide a broad analysis of language use within its social and cultural context, and look for patterns of communication beyond just persuasion tactics.
* Use Critical Rhetorical Analysis to provide a focused analysis on the strategies and techniques of how power dynamics and ideology are employed to influence the characters and audience.
* Include effective and ineffective language styles and patterns.
* Include implicit or explicit biases.
* Include social, political, and gender dynamics, biases, and/or implications.
* Include key characters, relationships, and plot developments.
ALWAYS Format Response As Follows:
Characters: Narrator, Character1, Charactor2, ... etc.
Discourse: Brief objective constructive discourse analysis.
Rhetorical: Brief objective constructive rhetorical analysis.
ALWAYS LIMIT RESPONSE TO 200 WORDS OR FEWER
""";

	String summaryCommand = "Summarize the following content so I can quickly understand what's going on:";

	String summaryPrompt = """
You are are an editor assistant and expert in creating short summaries of provided content.
Rules:
* Create objective summaries of content
* Give the content a quality rating: Read to Publish, Still Needs Work, Draft
* Include character names, relationships, and plot developments.
ALWAYS LIMIT RESPONSE TO 200 WORDS OR FEWER
""";

	String analysisCommand = "Analyze the following content.";
	
	String completionPrompt = """
You are are an editor assistant and provide inline editing assistance.
Rules:
* Use the provided content summary and context samples to suggest any sentence completion.
* Limit response to 200 words or fewer.
* Include a description of the writing style so that you'd be able to replicate it later on.
* ONLY provide a suggested completion or revision.
* DO NOT provide commentary or any other content.
* ONLY PROVIDE ONE TYPE OF RESPONSE
* ALWAYS FORMAT RESPONSE AS FOLLOWS:
** <Completion> Put your response here
** <Revision> Revised sentance
** <Suggestion> Put suggestion here

Example:
USER Please make a suggestion for the following:
*SECTION SUMMARY*
This is a summary of the story that you created
*PREVIOUS LINE* The kitten jumped off the stool.
*CURRENT LINE* The kitten
*NEXT LINE* The kitten meowed in terror.
ASSISTANT <Completion> landed in a bowl of water. 

DO NOT RESPOND WITH *REVISED LINE*, *PREVIOUS LINE*, *CURRENT LINE*, or *NEXT LINE*
""";

	String completionCommand = "Complete or revise content, or suggest changes, for content marked as *CURRENT LINE*.";
	private BaseRecord getConfig() throws FieldException, ModelNotFoundException {
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_CONFIG);
		cfg.setValue("serviceType", LLMServiceEnumType.OPENAI);
		cfg.setValue("apiVersion", testProperties.getProperty("test.llm.openai.version"));
		cfg.setValue("serverUrl", testProperties.getProperty("test.llm.openai.server"));
		cfg.setValue("model", "gpt-4o");
		cfg.setValue("apiKey", testProperties.getProperty("test.llm.openai.authorizationToken"));
		
		return cfg;
	}
	private Chat getChat(BaseRecord user, String prompt) throws FieldException, ModelNotFoundException {
		Chat chat = new Chat(user, getConfig(), null);
		chat.setLlmSystemPrompt(prompt);
		return chat;
	}
	private OpenAIRequest getRequest(Chat chat) {
		OpenAIRequest req = chat.getChatPrompt();
		req.setValue("max_tokens", 2048);
		req.setValue("frequency_penalty",  1.5);
		req.setValue("presence_penalty",  1.5);
		return req;
	}
	private boolean resetSummary = false;
	private int summarize(Path path, String contents) throws FieldException, ModelNotFoundException {
		int outVal = 0;
		ScrivenerBinding sb = documentMeta.get(path);
		
		logger.info("Summarizing " + sb.getTitle());
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String cnt = null;
		BaseRecord sdata = getData(testUser1, sb.getUid(), "~/Data");
		if(sdata != null && resetSummary) {
			try {
				ioContext.getWriter().delete(sdata);
			} catch (WriterException e) {
				logger.error(e);
				e.printStackTrace();
			}
			sdata = null;
		}
		if(sdata == null) {
			Chat chat = getChat(testUser1, summaryPrompt);
			OpenAIRequest req = getRequest(chat);
			chat.newMessage(req, summaryCommand + System.lineSeparator() + contents, "user");
			OpenAIResponse resp = chat.chat(req);
			
			cnt = resp.getMessage().getContent();
			if(cnt != null) {
				sdata = getCreateData(testUser1, sb.getUid(), "~/Data", cnt);
			}
			else {
				logger.error("Failed to retrieve LLM response");
			}
			outVal = 2;
		}
		else {
			try {
				cnt = ByteModelUtil.getValueString(sdata);
				outVal = 1;
			} catch (ValueException | FieldException e) {
				logger.error(e);
				e.printStackTrace();
			}
		}
		if(cnt != null && cnt.length() > 0) {
			documentSummary.put(path, cnt);
		}
		logger.info(cnt);
		return outVal;
	}
	
	private void assist(Path path, String prevLine, String line, String nextLine) throws FieldException, ModelNotFoundException {

		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		Chat chat = getChat(testUser1, completionPrompt);
		
		StringBuilder lineBuff = new StringBuilder();
		String sum = documentSummary.get(path);
		lineBuff.append("*SECTION SUMMARY* " + System.lineSeparator() + lineBuff + System.lineSeparator());
		if(prevLine != null) {
			lineBuff.append("*PREVIOUS LINE* " + prevLine + System.lineSeparator());
		}
		lineBuff.append("*CURRENT LINE* " + line + System.lineSeparator());
		if(nextLine != null) {
			lineBuff.append("*NEXT LINE* " + nextLine + System.lineSeparator());
		}
		OpenAIRequest req = getRequest(chat);
		chat.newMessage(req, completionCommand + System.lineSeparator() + lineBuff.toString(), "user");
		OpenAIResponse resp = chat.chat(req);
		
		logger.info("SUGGESTION - " + resp.getMessage().getContent());


	}
	
	private void watch(String path) {
	       try {
	            // Specify the directory which supposed to be watched
	            Path directoryPath = Paths.get(path);

	            // Create a WatchService
	            WatchService watchService = FileSystems.getDefault().newWatchService();

	            // Register the directory for specific events
	            /*
	            directoryPath.register(watchService,
	                    StandardWatchEventKinds.ENTRY_CREATE,
	                    StandardWatchEventKinds.ENTRY_DELETE,
	                    StandardWatchEventKinds.ENTRY_MODIFY);
				*/
	            Files.walkFileTree(directoryPath, new SimpleFileVisitor<Path>() {
	                @Override
	                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
	                    dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
	                    return FileVisitResult.CONTINUE;
	                }
	            });
	            System.out.println("Watching directory: " + directoryPath);

	            // Infinite loop to continuously watch for events
	            while (true) {
	                WatchKey key = watchService.take();

	                for (WatchEvent<?> event : key.pollEvents()) 
	                {
	                    // Handle the specific event
	                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) 
	                    {
	                        System.out.println("File created: " + event.context());
	                    } 
	                    else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) 
	                    {
	                        System.out.println("File deleted: " + event.context());
	                    } 
	                    else if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) 
	                    {
	                    	String name = event.context().toString();
	                    	if(name.equals("content.rtf")) {
	                    		Path dir = (Path)key.watchable();
	                    	
	                    		Path fullPath = dir.resolve(name);
	                    		//System.out.println("File modified: " + fullPath);
	                    		processModified(fullPath);
	                    	}
	                    }
	                }

	                // To receive further events, reset the key
	                key.reset();
	            }

	        } 
	        catch (IOException | InterruptedException e) 
	        {
	            e.printStackTrace();
	        }
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
