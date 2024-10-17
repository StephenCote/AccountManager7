package org.cote.accountmanager.objects.tests;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

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

    		BaseRecord pcfg = RecordFactory.importRecord(BinaryUtil.fromBase64Str(FileUtil.getFileAsString("./media/demoprompt.txt")));
			chat = new Chat(testUser1, null, pcfg);
			String sessName = "Session 1";
			chat.setSessionName(sessName);
			String sessDataName = ChatUtil.getSessionName(testUser1, null, pcfg, sessName);
			OllamaRequest req = ChatUtil.getSession(testUser1, sessDataName);
	    	if(req == null) {
	    		req = chat.getChatPrompt("hyp-local");
	    	}
	    	chat.continueChat(req, "Write a poem for me.");

	    	OllamaMessage msg = req.getMessages().get(req.getMessages().size() - 1);
	    	logger.info(msg.getContent());
			
			// NullPointerException | FieldException | ModelNotFound
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}


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
