package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.objects.tests.olio.OlioTranslator;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.junit.Before;
import org.junit.Test;
import com.pgvector.PGvector;

import ai.djl.Device;
import ai.djl.MalformedModelException;
import ai.djl.ModelException;
import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.modality.nlp.DefaultVocabulary;
import ai.djl.modality.nlp.Vocabulary;
import ai.djl.modality.nlp.bert.BertToken;
import ai.djl.modality.nlp.bert.BertTokenizer;
import ai.djl.modality.nlp.qa.QAInput;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.DeferredTranslatorFactory;
import ai.djl.translate.TranslateException;
public class TestVectorIndex extends BaseTest {

	@Before
	public void load() {

		logger.info("Loading Torch ...");
		System.loadLibrary("torch_cuda");
		logger.info("... Loaded Torch");

	}
	
	/*
	@Test
	public void TestPDF() {
		logger.info("Testing PDF");
		String pdfText = getPDF("./media/CardFox.pdf");
		assertNotNull("Text was null", pdfText);
		logger.info("Text len: " + pdfText.length());

		//logger.info("Begin translating ...");
		if(documentExists("CardFox")) {
			deleteDocument("CardFox");
		}
		loadDocument("CardFox", pdfText);

	}
	*/
	/*
	@Test
	public void TestTokenizer() {
		logger.info("Testing tokenizer");
		String question = "When did BBC Japan start broadcasting?";
		String resourceDocument = 
		  "BBC Japan was a general entertainment Channel.\n" + 
		  "Which operated between December 2004 and April 2006.\n" + 
		  "It ceased operations after its Japanese distributor folded.";
		
		String output = translate(question, resourceDocument);
		logger.info("Output: " + output);
	}
	*/
	/*
	@Test
	public void TestVectorStore() {
		logger.info("Testing vector store");
		
		// ModelSchema ms = RecordFactory.getSchema(ModelNames.MODEL_DATA);
		// logger.info(IOSystem.getActiveContext().getDbUtil().generateSchema(ms));
		
		try (Connection con = ioContext.getDbUtil().getDataSource().getConnection()){
	        PGvector.addVectorType(con);
	        ZooModel<String, float[]> model = loadModel(testProperties.getProperty("test.datagen.path") + "/nlp/torchscript/bert-base-cased-squad2");
	        String[] input = {
	                "The dog is barking",
	                "The cat is purring",
	                "The bear is growling"
	            };
	            List<float[]> embeddings = generateEmbeddings(model, input);

	            for (int i = 0; i < input.length; i++) {
	                PreparedStatement insertStmt = con.prepareStatement("INSERT INTO vecdoc (content, embedding) VALUES (?, ?)");
	                insertStmt.setString(1, input[i]);
	                insertStmt.setObject(2, new PGvector(embeddings.get(i)));
	                insertStmt.executeUpdate();
	            }
	            
	            String query = "growling bear";
	            float[] queryEmbedding = generateEmbeddings(model, new String[] {query}).get(0);
	            double k = 60;

	            PreparedStatement queryStmt = con.prepareStatement(HYBRID_SQL);
	            queryStmt.setObject(1, new PGvector(queryEmbedding));
	            queryStmt.setObject(2, new PGvector(queryEmbedding));
	            queryStmt.setString(3, query);
	            queryStmt.setDouble(4, k);
	            queryStmt.setDouble(5, k);
	            ResultSet rs = queryStmt.executeQuery();
	            while (rs.next()) {
	                System.out.println(String.format("document: %d, RRF score: %f", rs.getLong("id"), rs.getDouble("score")));
	            }
		}
		catch(SQLException | IOException | ModelException | TranslateException e) {
			logger.error(e);
			e.printStackTrace();
		}

	}
	*/
	
	 public static final String HYBRID_SQL = """
			    WITH semantic_search AS (
			        SELECT id, RANK () OVER (ORDER BY embedding <=> ?) AS rank
			        FROM vecdoc
			        ORDER BY embedding <=> ?
			        LIMIT 20
			    ),
			    keyword_search AS (
			        SELECT id, RANK () OVER (ORDER BY ts_rank_cd(to_tsvector('english', content), query) DESC)
			        FROM vecdoc, plainto_tsquery('english', ?) query
			        WHERE to_tsvector('english', content) @@ query
			        ORDER BY ts_rank_cd(to_tsvector('english', content), query) DESC
			        LIMIT 20
			    )
			    SELECT
			        COALESCE(semantic_search.id, keyword_search.id) AS id,
			        COALESCE(1.0 / (? + semantic_search.rank), 0.0) +
			        COALESCE(1.0 / (? + keyword_search.rank), 0.0) AS score
			    FROM semantic_search
			    FULL OUTER JOIN keyword_search ON semantic_search.id = keyword_search.id
			    ORDER BY score DESC
			    LIMIT 5
			    """;
	
	 // https://pub.towardsai.net/deploy-huggingface-nlp-models-in-java-with-deep-java-library-e36c635b2053
	 // https://gist.github.com/KexinFeng/97e6344556f88822650d023acfbdf4f5
	
	private String predict(OlioTranslator ot, QAInput input) throws ModelNotFoundException, MalformedModelException, IOException, TranslateException {
		Criteria<QAInput, String> criteria = Criteria.builder()
				  .setTypes(QAInput.class, String.class)
		          //  .optModelUrls("djl://ai.djl.huggingface.pytorch/sentence-transformers/bert-large-nli-cls-token")
		            .optArgument("normalize", "false")
		            .optModelPath(Paths.get(testProperties.getProperty("test.datagen.path") + "/nlp/trace_cased_bertqa.pt"))
				  .optEngine("PyTorch")
				  //.optDevice(Device.gpu())
				 
				  .optTranslator(ot)
				  .build();
				ZooModel<QAInput, String> model = criteria.loadModel();
				Predictor<QAInput, String> predictor = model.newPredictor(ot);
				return predictor.predict(input);
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
	
	private List<String> chunkText(String block, int chunkSize) {
		/// .map(s -> s.trim())
		List<String> sents = Arrays.asList(block.split("((?<=\\.)|(?=\\.))")).stream().filter(s -> s.length() > 0).collect(Collectors.toList());
		final AtomicInteger counter = new AtomicInteger(0);
		return sents.stream().collect(Collectors.groupingBy(s -> counter.getAndIncrement()/chunkSize)).values().stream().flatMap(List::stream).collect(Collectors.toList());
	}
	
	private ZooModel<String, float[]> zooModel = null;
	private ZooModel<String, float[]> getZooModel(){
		if(zooModel == null) {
			try {
				zooModel = loadModel(testProperties.getProperty("test.datagen.path") + "/nlp/trace_cased_bertqa.gz");
			} catch (IOException | ModelException e) {
				logger.error(e);
				e.printStackTrace();
			}
		}
		return zooModel;
	}
	
	
	private void loadDocument(String documentId, String documentData) {
		List<String> chunks = chunkText(documentData, 20).stream().collect(Collectors.toList());
		loadDocument(documentId, chunks);
	}
	private void loadDocument(String documentId, List<String> chunks) {
		if(documentExists(documentId)) {
			logger.warn("Document exists");
			return;
		}
		try (Connection con = ioContext.getDbUtil().getDataSource().getConnection()){
			PGvector.addVectorType(con);
			logger.info("Generating " + chunks.size() + " embeddings");
            List<float[]> embeddings = generateEmbeddings(getZooModel(), chunks.toArray(new String[0]));
            int size = chunks.size();
            for (int i = 0; i < chunks.size(); i++) {
                PreparedStatement insertStmt = con.prepareStatement("INSERT INTO vecdoc (documentId, chunk, chunkCount, content, embedding) VALUES (?, ?, ?, ?, ?)");
                insertStmt.setString(1, documentId);
                insertStmt.setInt(2, (i + 1));
                insertStmt.setInt(3, size);
                insertStmt.setString(4, chunks.get(i));
                insertStmt.setObject(5, new PGvector(embeddings.get(i)));
                insertStmt.executeUpdate();
            }
            
		}
		catch(SQLException | TranslateException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}

	private String getDocument(String id, int start, int length, boolean bidi) {
		int upd = 0;
		StringBuilder sql = new StringBuilder();
		sql.append("SELECT content FROM vecdoc WHERE documentId = ?");
		if(length > 0) {
			sql.append(" AND chunk => ? ANd chunk < ?");
		}
		List<String> doc = new ArrayList<>();
		try (Connection con = ioContext.getDbUtil().getDataSource().getConnection()){
	            PreparedStatement queryStmt = con.prepareStatement(sql.toString());
	            queryStmt.setString(1, id);
	            if(length > 0) {
	            	queryStmt.setInt(2, start - (bidi ? length : 0));
	            	queryStmt.setInt(3, start + length);
	            }
	            ResultSet rs = queryStmt.executeQuery();
	            while(rs.next()) {
	            	doc.add(rs.getString(1));
	            }
	            rs.close();
	            
		}
		catch(SQLException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return doc.stream().collect(Collectors.joining());
	}
	
	private int deleteDocument(String id) {
		int upd = 0;
		try (Connection con = ioContext.getDbUtil().getDataSource().getConnection()){
	            PreparedStatement queryStmt = con.prepareStatement("DELETE FROM vecdoc WHERE documentId = ?");
	            queryStmt.setString(1, id);
	            upd = queryStmt.executeUpdate();
		}
		catch(SQLException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return upd;
	}
	
	private boolean documentExists(String id) {
		boolean exists = false;
		try (Connection con = ioContext.getDbUtil().getDataSource().getConnection()){
	            PreparedStatement queryStmt = con.prepareStatement("SELECT count(id) FROM vecdoc WHERE documentId = ?");
	            queryStmt.setString(1, id);
	            ResultSet rs = queryStmt.executeQuery();
	            if(rs.next()) {
	            	exists = (rs.getInt(1) > 0);
	            }
	            rs.close();
		}
		catch(SQLException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return exists;
	}

	private String translate(String question, String content) {
		// "/nlp/bert-base-cased-vocab.txt"
		// "/nlp/vocab.json"
		OlioTranslator ot = new OlioTranslator(testProperties.getProperty("test.datagen.path") + "/nlp/bert-base-cased-vocab.txt");
		
		QAInput input = new QAInput(question, content);
		String output = null;
		try {
			output = predict(ot, input);
		} catch (ModelNotFoundException | MalformedModelException | IOException | TranslateException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return output;
	}

	/// From https://github.com/pgvector/pgvector-java/blob/master/examples/djl/src/main/java/com/example/Example.java
	/// https://djl.ai/extensions/tokenizers/ for bert-base-cased-squad2
    private static ZooModel<String, float[]> loadModel(String id) throws IOException, ModelException {
    	//logger.info(id + " / " + Paths.get(id));
        return Criteria.builder()
            .setTypes(String.class, float[].class)
            //.optModelUrls("djl://ai.djl.huggingface.pytorch/" + id)
            // 384
           //.optModelUrls("djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2")

            //.optModelUrls("djl://ai.djl.huggingface.pytorch/sentence-transformers/all-mpnet-base-v2")
            // 768 - but produces very large dimensions 
            //.optModelUrls("djl://ai.djl.huggingface.pytorch/bert-base-uncased")
            
            // 1024
            .optModelUrls("djl://ai.djl.huggingface.pytorch/sentence-transformers/bert-large-nli-cls-token")
            .optArgument("normalize", "false")
            .optEngine("PyTorch")
            
            /*
            .optEngine("MXNet")
            .optModelUrls(id)
            */
            //.optDevice(Device.gpu()) 
            .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
            .build()
            .loadModel();
    }
    
    private static List<float[]> generateEmbeddings(ZooModel<String, float[]> model, String[] input) throws TranslateException {
        Predictor<String, float[]> predictor = model.newPredictor();
        List<float[]> embeddings = new ArrayList<>(input.length);
        logger.info(embeddings.size());
        for (String text : input) {
        	float[] f = predictor.predict(text);
            embeddings.add(f);
        }
        return embeddings;
    }
}
