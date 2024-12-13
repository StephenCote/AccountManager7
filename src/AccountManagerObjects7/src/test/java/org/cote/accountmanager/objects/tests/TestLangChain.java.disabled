package org.cote.accountmanager.objects.tests;

import java.io.File;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OllamaMessage;
import org.cote.accountmanager.olio.llm.OllamaRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ZipUtil;
import org.junit.Test;
import org.xml.sax.SAXException;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import static dev.langchain4j.data.document.loader.FileSystemDocumentLoader.*;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
public class TestLangChain extends BaseTest {
	
	private String url = "http://localhost:11434";
	
	private EmbeddingStore<TextSegment> getEmbeddingStore(){
		return PgVectorEmbeddingStore.builder()
	        .host("localhost")
	        .port(15430)
	        .database("am7db")
	        .user("am7user")
	        .password("password")
	        .table("veclang")
	        .dimension(384)
	        .build();
	}
	/*
	@Test
	public void TestPGVector() {
		logger.info("Testing PG Vector Setup");
		EmbeddingStore<TextSegment> embeddingStore = getEmbeddingStore();
		
		 EmbeddingModel embeddingModel = new AllMiniLmL6V2EmbeddingModel();

         TextSegment segment1 = TextSegment.from("I like football.");
         Embedding embedding1 = embeddingModel.embed(segment1).content();
         embeddingStore.add(embedding1, segment1);

         TextSegment segment2 = TextSegment.from("The weather is good today.");
         Embedding embedding2 = embeddingModel.embed(segment2).content();
         embeddingStore.add(embedding2, segment2);

         Embedding queryEmbedding = embeddingModel.embed("What is your favourite sport?").content();
         List<EmbeddingMatch<TextSegment>> relevant = embeddingStore.findRelevant(queryEmbedding, 1);
         EmbeddingMatch<TextSegment> embeddingMatch = relevant.get(0);

         System.out.println(embeddingMatch.score()); // 0.8144288608390052
         System.out.println(embeddingMatch.embedded().text()); // I like football.
         
         
	}
	*/
    private ContentRetriever createContentRetriever(List<Document> documents) {
        //InMemoryEmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
    	EmbeddingStore<TextSegment> embeddingStore = getEmbeddingStore();
        EmbeddingStoreIngestor.ingest(documents,embeddingStore);
        return EmbeddingStoreContentRetriever.from(embeddingStore);
    }
	
    /*
	@Test
	public void TestLCSetup() {
		logger.info("Testing LC Setup");
		
		ChatLanguageModel model = OllamaChatModel.builder().baseUrl(url).modelName("hyp-local").timeout(Duration.ofSeconds(300)).build();

		Document document = Document.document(getPDF("./media/CardFox.pdf"));
		//Document document = loadDocument("./media/CardFox.pdf");
		 Assistant assistant = AiServices.builder(Assistant.class)
	                .chatLanguageModel(model) // it should use OpenAI LLM
	                .chatMemory(MessageWindowChatMemory.withMaxMessages(20)) // it should remember 10 latest messages
	                .contentRetriever(createContentRetriever(Arrays.asList(new Document[] {document}))) // it should have access to our documents
	                .build();
		
		 String query = "What is this story about?";
		 String agentAnswer = assistant.answer(query);
		 logger.info(agentAnswer);
		 
	}
	*/
    
    @Test
    public void TestAM7Chat() {
    	logger.info("Test AM7 Chat");

    	Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");;
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
    	Chat chat = null;
    	try {
    		/*
			BaseRecord pcfg = RecordFactory.newInstance(OlioModelNames.MODEL_PROMPT_CONFIG);
			((List<String>)pcfg.get("system")).add(sysPrompt);
			((List<String>)pcfg.get("assistant")).add("In this conversation, I will demonstrate anything you request.");
			((List<String>)pcfg.get("user")).add("I have all sorts of things I'd like to see demonstrated.");
			String xcfg = BinaryUtil.toBase64Str(pcfg.toFullString());
			FileUtil.emitFile("./media/demoprompt.txt", xcfg);
			*/

    		BaseRecord pcfg = RecordFactory.importRecord(FileUtil.getFileAsString("./media/analyze.prompt.json"));
			chat = new Chat(testUser1, null, pcfg);
			String sessName = "Session - " + UUID.randomUUID().toString();
			chat.setSessionName(sessName);
			String sessDataName = ChatUtil.getSessionName(testUser1, null, pcfg, sessName);
			OllamaRequest req = ChatUtil.getSession(testUser1, sessDataName);
			String userAn = ((List<String>)pcfg.get("userAnalyze")).stream().collect(Collectors.joining(System.lineSeparator()));
	    	if(req == null) {
		    	
	    		req = chat.getChatPrompt("uc-local");
	    		req.getMessages().clear();
	    		String sysPrompt2 = ((List<String>)pcfg.get("systemAnalyze")).stream().collect(Collectors.joining(System.lineSeparator()));
	    		logger.info(sysPrompt2);
	    		req.setSystem(sysPrompt2);
	    		//chat.setLlmSystemPrompt(((List<String>)pcfg.get("systemAnalyze")).stream().collect(Collectors.joining(System.lineSeparator())));
	    		//chat.newMessage(req, userAn);
	    		chat.newMessage(req, ((List<String>)pcfg.get("assistantAnalyze")).stream().collect(Collectors.joining(System.lineSeparator())), "assistant");
	    	}
			req.getOptions().setRepeatPenalty(1.01);
			req.getOptions().setTemperature(0.5);
			req.getOptions().setTopP(0.25);
			req.getOptions().setTopK(75);
			req.getOptions().setMinP(0.00);

	    	//logger.info(JSONUtil.exportObject(req));
	    	//String doc = getDocument("./media/HarlotsEight_Vol1_SM.docx");
			String doc = getDocument("./media/Vore.docx");
	    	assertNotNull("Document is null", doc);
	    	
	    	logger.info("Doc length: " + doc.length());
	    	int index = doc.indexOf("Chapter");

	    	String lastCR = null;
	    	int part = 1;
	    	boolean debug = true;
	    	
	    	List<String> crs = new ArrayList<>();
	    	boolean single = (index == -1);
	    	while(index > -1 || single) {
	    		if(single) {
	    			index = 0;
	    		}
	    		int idx2 = (single ? -1 : doc.indexOf("Chapter", index + 8));
	    		int edx = (idx2 > -1 ? idx2 : doc.length());
	    		String tmp = doc.substring(index, edx);
	    		index = idx2;
	    		logger.info("Part " + part + " " + tmp.split(" ").length + " words");

	    		if(req.getMessages().size() > 3) {
	    			req.getMessages().subList(4, req.getMessages().size()).clear();
	    		}
	    		

	    		
	    		if(tmp.length() > 2500) {
    				String stmp = tmp.substring(0, 2500);
    				stmp = stmp.substring(0, stmp.lastIndexOf(".") + 1);
    				tmp = stmp;
	    			while(tmp.length() > 2500) {
	    				stmp = tmp.substring(0, 2500);
	    				stmp = stmp.substring(0, stmp.lastIndexOf(".") + 1);
	    				chat.newMessage(req, stmp);
	    				tmp = tmp.substring(stmp.length());
	    			}
	    		}
	    		chat.newMessage(req, tmp);
	    		/*
	    		if(lastCR != null) {
	    			chat.newMessage(req, "PREVIOUS CR:" + System.lineSeparator() + lastCR);
	    		}
	    		*/
	    		chat.newMessage(req, userAn);
	    		chat.continueChat(req, null);
	    		// logger.info(JSONUtil.exportObject(req));	    		
		    	OllamaMessage msg = req.getMessages().get(req.getMessages().size() - 1);
		    	lastCR = msg.getContent();
		    	crs.add(lastCR);
		    	logger.info(lastCR);
		    	if(single) {
		    		break;
		    	}
	    		part++;
	    		/*
	    		if(debug && part > 12) {
	    			logger.warn("Debug Break at part " + part);
	    			break;
	    		}
	    		*/
	    		
	    	}
	    	
	    	FileUtil.emitFile("./crs.json", JSONUtil.exportObject(crs));
	    	
	    	/*
	    	chat.continueChat(req, "");

	    	OllamaMessage msg = req.getMessages().get(req.getMessages().size() - 1);
	    	logger.info(msg.getContent());
			*/
			// NullPointerException | FieldException | ModelNotFound
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}


    }
    
    private String getDocument(String file) {
    	String out = null;
    	try {
	    	InputStream fileStream = new FileInputStream(file);
	    	Parser parser = new AutoDetectParser();
	    	Metadata metadata = new Metadata();
	    	BodyContentHandler handler = new BodyContentHandler(Integer.MAX_VALUE);
	    	PDFParserConfig pdfConfig = new PDFParserConfig();
	    	pdfConfig.setExtractInlineImages(true);
	    	ParseContext parseContext = new ParseContext();
	    	parseContext.set(PDFParserConfig.class, pdfConfig);
	    	parseContext.set(Parser.class, parser);
	    	parser.parse(fileStream, handler, metadata, parseContext);
			out = handler.toString().replaceAll("[“”]", "\"").replaceAll("’", "'");
			
    	}
    	catch(IOException | SAXException | TikaException e) {
    		logger.error(e);
    	}
    	return out;
    }
    
	private String getPDF(String path) {
		String output = null;
		  PDDocument doc;
		try {
			doc = Loader.loadPDF(new File(path));
			output = new PDFTextStripper().getText(doc);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		  
		return output;
	}
	
	interface Assistant{
	    String answer(String query);
	}
	
	private String sysPrompt = "You are a master demonstrator";

}
