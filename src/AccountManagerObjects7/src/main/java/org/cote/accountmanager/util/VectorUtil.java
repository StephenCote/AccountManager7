package org.cote.accountmanager.util;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConnectionEnumType;

import com.ibm.icu.util.StringTokenizer;
import com.pgvector.PGvector;

import ai.djl.ModelException;
import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;

public class VectorUtil {
	public static final Logger logger = LogManager.getLogger(VectorUtil.class);
	
	public enum ChunkEnumType {
		UNKNOWN,
		SENTENCE,
		WORD
	};
	
	private static Pattern tablePat = Pattern.compile("\\$\\{tableName\\}");
	private static final String HYBRID_SQL = """
WITH semantic_search AS (
    SELECT id, vectorReference, vectorReferenceType, RANK () OVER (ORDER BY embedding <=> ?) AS rank, content, chunk
    FROM ${tableName}
    WHERE vectorReference = ? and vectorReferenceType = ?
    ORDER BY embedding <=> ?
    LIMIT 20
),
keyword_search AS (
    SELECT id, vectorReference, vectorReferenceType, RANK () OVER (ORDER BY ts_rank_cd(to_tsvector('english', content), query) DESC), content, chunk
    FROM ${tableName}, plainto_tsquery('english', ?) query
    WHERE to_tsvector('english', content) @@ query
    ORDER BY ts_rank_cd(to_tsvector('english', content), query) DESC
    LIMIT 20
)
SELECT
    COALESCE(semantic_search.id, keyword_search.id) AS id,
    COALESCE(semantic_search.vectorReference, keyword_search.vectorReference) AS vectorReference,
    COALESCE(semantic_search.vectorReferenceType, keyword_search.vectorReferenceType) AS vectorReferenceType,
    COALESCE(1.0 / (? + semantic_search.rank), 0.0) +
    COALESCE(1.0 / (? + keyword_search.rank), 0.0) AS score,
    COALESCE(semantic_search.content, keyword_search.content) as content,
    COALESCE(semantic_search.chunk, keyword_search.chunk) as chunk
FROM semantic_search
FULL OUTER JOIN keyword_search ON semantic_search.id = keyword_search.id
ORDER BY score DESC
LIMIT ?
""";
	
	public static boolean isVectorSupported() {
		DBUtil du = IOSystem.getActiveContext().getDbUtil();
		return (du.isEnableVectorExtension() && du.getConnectionType() == ConnectionEnumType.POSTGRE);
	}
	
	

	
	public static List<BaseRecord> find(BaseRecord model, String query){
		return findByEmbedding(getZooModel(), model, query, 10, 60);
	}
	public static List<BaseRecord> find(BaseRecord model, String query, int limit, double k){
		return findByEmbedding(getZooModel(), model, query, limit, k);
	}	
	private static List<BaseRecord> findByEmbedding(ZooModel<String, float[]> zoo, BaseRecord model, String query, int limit, double k){
		List<BaseRecord> content = new ArrayList<>();
		long id = 0L;
		String refType = null;
		if(model != null) {
			id = model.get(FieldNames.FIELD_ID);
			refType = model.getModel();
		}
		String tableName = IOSystem.getActiveContext().getDbUtil().getTableName(ModelNames.MODEL_VECTOR_MODEL_STORE);
		String sql = tablePat.matcher(HYBRID_SQL).replaceAll(tableName);

		try (Connection con = IOSystem.getActiveContext().getDbUtil().getDataSource().getConnection()){
	        float[] queryEmbedding = generateEmbeddings(zoo, new String[] {query}).get(0);
	
	        PreparedStatement queryStmt = con.prepareStatement(sql);
	        queryStmt.setObject(1, new PGvector(queryEmbedding));
	        queryStmt.setLong(2, id);
	        queryStmt.setString(3, refType);
	        queryStmt.setObject(4, new PGvector(queryEmbedding));
	        queryStmt.setString(5, query);
	        queryStmt.setDouble(6, k);
	        queryStmt.setDouble(7, k);
	        queryStmt.setInt(8, limit);
	        ResultSet rs = queryStmt.executeQuery();
	        while (rs.next()) {
	        	content.add(newVectorStore(rs.getLong("id"), rs.getLong("vectorReference"), rs.getString("vectorReferenceType"), rs.getDouble("score"), rs.getInt("chunk"), rs.getString("content")));
	        }
	        rs.close();
		}
		catch(SQLException | TranslateException | FieldException | ModelNotFoundException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return content;
	}
	
	private static BaseRecord newVectorStore(long id, long ref, String refType, double score, int chunk, String content) throws FieldException, ModelNotFoundException {
		BaseRecord vs = RecordFactory.newInstance(ModelNames.MODEL_VECTOR_MODEL_STORE);
		vs.setValue(FieldNames.FIELD_ID, id);
		if(ref > 0L && refType != null) {
			BaseRecord vsr = RecordFactory.newInstance(refType);
			vsr.setValue(FieldNames.FIELD_ID, id);
			vs.setValue(FieldNames.FIELD_VECTOR_REFERENCE, vsr);
		}
		vs.setValue(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, refType);
		vs.setValue(FieldNames.FIELD_SCORE, score);
		vs.setValue(FieldNames.FIELD_CHUNK, chunk);
		vs.setValue(FieldNames.FIELD_CONTENT, content);
		return vs;
	}

	public static int countVectorStore(BaseRecord model){
		Query q = QueryUtil.createQuery(ModelNames.MODEL_VECTOR_MODEL_STORE, FieldNames.FIELD_VECTOR_REFERENCE, model.copyRecord(new String[] {FieldNames.FIELD_ID}));
		q.field(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, model.getModel());
		q.field(FieldNames.FIELD_ORGANIZATION_ID, model.get(FieldNames.FIELD_ORGANIZATION_ID));
		return IOSystem.getActiveContext().getSearch().count(q);
	}
	
	public static int deleteVectorStore(BaseRecord model){
		Query q = QueryUtil.createQuery(ModelNames.MODEL_VECTOR_MODEL_STORE, FieldNames.FIELD_VECTOR_REFERENCE, model.copyRecord(new String[] {FieldNames.FIELD_ID}));
		q.field(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, model.getModel());
		q.field(FieldNames.FIELD_ORGANIZATION_ID, model.get(FieldNames.FIELD_ORGANIZATION_ID));
		int del = 0;
		try {
			del = IOSystem.getActiveContext().getWriter().delete(q);
		} catch (WriterException e) {
			logger.error(e);
		}
		return del;
	}
	
	public static List<BaseRecord> getVectorStore(BaseRecord model){
		Query q = QueryUtil.createQuery(ModelNames.MODEL_VECTOR_MODEL_STORE, FieldNames.FIELD_VECTOR_REFERENCE, model.copyRecord(new String[] {FieldNames.FIELD_ID}));
		q.field(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, model.getModel());
		List<BaseRecord> recs = new ArrayList<>();
		try {
			recs = Arrays.asList(IOSystem.getActiveContext().getSearch().find(q));
		} catch (ReaderException e) {
			logger.error(e);
		}
		return recs;
	}
	
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
						content = DocumentUtil.readPDF(ByteModelUtil.getValue(model));
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
			logger.warn("Unhandled model: " + model.getModel());
		}
		return content;
	}
	

	public static List<BaseRecord> createVectorStore(BaseRecord model, ChunkEnumType chunkType, int chunkSize) throws FieldException {

		
		//BaseRecord vector = RecordFactory.newInstance(ModelNames.MODEL_VECTOR_MODEL_STORE);
		//BaseRecord vectorRef = model.get(FieldNames.FIELD_VECTOR_REFERENCE);
		if(model == null) {
			throw new FieldException("Model is null");
		}
		if(!RecordUtil.isIdentityRecord(model)) {
			throw new FieldException("Model is missing identity");
		}
		
		if(countVectorStore(model) > 0) {
			logger.info("Replacing vector store for " + model.getModel());
			if(deleteVectorStore(model) == 0) {
				throw new FieldException("Vector store already exists and was not deleted.  Alternately, specify the content directly to append.");
			}
		}

		return createVectorStore(model, getStringContent(model), chunkType, chunkSize);
	}

	public static List<BaseRecord> createVectorStore(BaseRecord model, String content, ChunkEnumType chunkType, int chunkSize) throws FieldException {

		List<BaseRecord> chunkRecs = new ArrayList<>();
		List<String> chunks = Arrays.asList(new String[] {content});

		if(content == null || content.length() == 0) {
			throw new FieldException("Content is null or empty");
		}
		
		if(chunkType == ChunkEnumType.SENTENCE) {
			chunks = chunkBySentence(content, chunkSize).stream().collect(Collectors.toList());
		}
		else if(chunkType == ChunkEnumType.WORD) {
			chunks = chunkByWord(content, chunkSize).stream().collect(Collectors.toList());
		}
		logger.info("Generating " + chunks.size() + " embeddings");
		long start = System.currentTimeMillis();
		try {
	        List<float[]> embeddings = generateEmbeddings(getZooModel(), chunks.toArray(new String[0]));
	        int size = chunks.size();
	        for (int i = 0; i < chunks.size(); i++) {
	        	BaseRecord chunkModel = RecordFactory.newInstance(ModelNames.MODEL_VECTOR_MODEL_STORE);
	        	chunkModel.setValue(FieldNames.FIELD_CHUNK, (i + 1));
	        	chunkModel.setValue(FieldNames.FIELD_CHUNK_COUNT, size);
	        	chunkModel.setValue(FieldNames.FIELD_CONTENT, chunks.get(i));
	        	chunkModel.setValue(FieldNames.FIELD_EMBEDDING, embeddings.get(i));
	        	chunkModel.setValue(FieldNames.FIELD_VECTOR_REFERENCE, model);
	        	chunkModel.setValue(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, model.getModel());
	        	chunkModel.setValue(FieldNames.FIELD_ORGANIZATION_ID, model.get(FieldNames.FIELD_ORGANIZATION_ID));
	        	chunkRecs.add(chunkModel);
	        }
		}
		catch(TranslateException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		return chunkRecs;
	}
	
	private static ZooModel<String, float[]> zooModel = null;
	private static ZooModel<String, float[]> getZooModel(){
		if(zooModel == null) {
			try {
				zooModel = loadModel();
			} catch (IOException | ModelException e) {
				logger.error(e);
				e.printStackTrace();
			}
		}
		return zooModel;
	}
	
    private static ZooModel<String, float[]> loadModel() throws IOException, ModelException {
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
            //.optArgument("normalize", "false")
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

    private static List<String> chunkByWord(String block, int chunkSize) {
    	StringTokenizer st = new StringTokenizer(block, " ");
    	final AtomicInteger counter = new AtomicInteger(0);
    	List<String> sx = new ArrayList<>();
    	StringBuilder buff = new StringBuilder();

    	while (st.hasMoreTokens()) {
    		String tk = st.nextToken();
    		if(tk == null || tk.trim().length() == 0) {
    			continue;
    		}
    		buff.append(tk);
    		if(st.hasMoreTokens()) {
    			buff.append(" ");
    		}
			if(counter.getAndIncrement() % chunkSize == 0) {
				sx.add(buff.toString());
				buff = new StringBuilder();
			}
    	}
		if(buff.length() > 0) {
			sx.add(buff.toString());
		}
		return sx;
	}
    
    
    private static List<String> chunkBySentence(String block, int chunkSize) {
		List<String> sents = Arrays.asList(block.split("((?<=\\.)|(?=\\.))"))
			.stream()
			.filter(s -> s.trim().length() > 0)
			.map(s ->  s.replaceAll("[“”]", "\"").replaceAll("’", "'"))
			.collect(Collectors.toList());
		final AtomicInteger counter = new AtomicInteger(0);
		List<String> sx = new ArrayList<>();
		StringBuilder buff = new StringBuilder();
		for(String s: sents) {
			buff.append(s);
			if(counter.getAndIncrement() % chunkSize == 0) {
				sx.add(buff.toString());
				buff = new StringBuilder();
			}
		}
		if(buff.length() > 0) {
			sx.add(buff.toString());
		}
		return sx;
	}
    
	
    private static List<float[]> generateEmbeddings(ZooModel<String, float[]> model, String[] input) throws TranslateException {
        Predictor<String, float[]> predictor = model.newPredictor();
        List<float[]> embeddings = new ArrayList<>(input.length);
        for (String text : input) {
        	float[] f = predictor.predict(text);
            embeddings.add(f);
        }
        return embeddings;
    }
}
