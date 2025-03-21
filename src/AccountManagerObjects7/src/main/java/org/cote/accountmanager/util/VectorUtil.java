package org.cote.accountmanager.util;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.cote.accountmanager.io.MemoryReader;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
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
		WORD,
		CHAPTER
	};
	
	private static Pattern tablePat = Pattern.compile("\\$\\{tableName\\}");
	
	/// Derived from PG Vector example hybrid search query
	///
	private static final String HYBRID_SQL = """
WITH semantic_search AS (
    SELECT id, keyId, vaultId, vaulted, organizationId, vectorReference, vectorReferenceType, RANK () OVER (ORDER BY embedding <=> ?) AS rank, content, chunk
    FROM ${tableName}
    WHERE (vectorReference = ? or ? = 0) and (vectorReferenceType = ? or ? IS NULL)
    ORDER BY embedding <=> ?
    LIMIT 20
),
keyword_search AS (
    SELECT id, keyId, vaultId, vaulted, organizationId, vectorReference, vectorReferenceType, RANK () OVER (ORDER BY ts_rank_cd(to_tsvector('english', content), query) DESC), content, chunk
    FROM ${tableName}, plainto_tsquery('english', ?) query
    WHERE to_tsvector('english', content) @@ query
    ORDER BY ts_rank_cd(to_tsvector('english', content), query) DESC
    LIMIT 20
)
SELECT
    COALESCE(semantic_search.id, keyword_search.id) AS id,
    COALESCE(semantic_search.keyId, keyword_search.keyId) AS keyId,
    COALESCE(semantic_search.vaultId, keyword_search.vaultId) AS vaultId,
    COALESCE(semantic_search.vaulted, keyword_search.vaulted) AS vaulted,
    COALESCE(semantic_search.organizationId, keyword_search.organizationId) AS organizationId,
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
	private static String[] vectorModels = new String[0];
	public static String[] getVectorModels(){
		if(vectorModels.length == 0) {
			vectorModels = ModelNames.MODELS.stream().filter(m -> {
				ModelSchema ms = RecordFactory.getSchema(m);
				// !m.equals(ModelNames.MODEL_VECTOR_MODEL_STORE) && 
				return ms.inherits(ModelNames.MODEL_VECTOR_EXT);
			}).collect(Collectors.toList()).toArray(new String[0]);
		}
		return vectorModels;
	}

	public static List<BaseRecord> find(BaseRecord model, String query){
		return find(model, new String[] {ModelNames.MODEL_VECTOR_MODEL_STORE}, query);
	}
	public static List<BaseRecord> find(BaseRecord model, String[] vectorModels, String query){
		return findByEmbedding(getZooModel(), model, (model != null ? model.getSchema() : null), vectorModels, query, 10, 60);
	}
	public static List<BaseRecord> find(BaseRecord model, String query, int limit, double k){
		return find(model, null, query, limit, k);
	}
	public static List<BaseRecord> find(BaseRecord model, String modelName, String query, int limit, double k){
		return find(model, null, new String[] {ModelNames.MODEL_VECTOR_MODEL_STORE}, query, limit, k);
	}
	public static List<BaseRecord> find(BaseRecord model, String modelName, String[] vectorModels, String query, int limit, double k){
		return findByEmbedding(getZooModel(), model, modelName, vectorModels, query, limit, k);
	}	
	private static List<BaseRecord> findByEmbedding(ZooModel<String, float[]> zoo, BaseRecord model, String modelName, String[] vectorModels, String query, int limit, double k){
		List<BaseRecord> content = new ArrayList<>();
		long id = 0L;
		String refType = null;
		if(model != null) {
			id = model.get(FieldNames.FIELD_ID);
			refType = model.getSchema();
		}
		else if(modelName != null) {
			refType = modelName;
		}
		
		//List<String> tablesX = new ArrayList<>();
		//tablesX.add(IOSystem.getActiveContext().getDbUtil().getTableName(ModelNames.MODEL_VECTOR_MODEL_STORE));
		Set<String> tables = new LinkedHashSet<>(Arrays.asList(vectorModels).stream().map(t -> IOSystem.getActiveContext().getDbUtil().getTableName(t)).collect(Collectors.toList()));
		// Set<String> tables = new LinkedHashSet<>(tablesX);
		
		for(String tableName : tables) {
			String sql = tablePat.matcher(HYBRID_SQL).replaceAll(tableName);
	
			try (Connection con = IOSystem.getActiveContext().getDbUtil().getDataSource().getConnection(); PreparedStatement stat = con.prepareStatement(sql)){
		        float[] queryEmbedding = generateEmbeddings(zoo, new String[] {query}).get(0);
		
		        stat.setObject(1, new PGvector(queryEmbedding));
		        stat.setLong(2, id);
		        stat.setLong(3, id);
		        stat.setString(4, refType);
		        stat.setString(5, refType);
		        stat.setObject(6, new PGvector(queryEmbedding));
		        stat.setString(7, query);
		        stat.setDouble(8, k);
		        stat.setDouble(9, k);
		        stat.setInt(10, limit);

		        ResultSet rs = stat.executeQuery();
		        MemoryReader mem = new MemoryReader();
		        while (rs.next()) {
		        	BaseRecord vs = newVectorStore(rs.getLong("id"),  rs.getString("keyId"), rs.getString("vaultId"), rs.getBoolean("vaulted"), rs.getLong("organizationId"), rs.getLong("vectorReference"), rs.getString("vectorReferenceType"), rs.getDouble("score"), rs.getInt("chunk"), rs.getString("content"));
		        	mem.read(vs);
		        	content.add(vs);
		        }
		        rs.close();
			}
			catch(SQLException | TranslateException | FieldException | ModelNotFoundException | ReaderException e) {
				logger.error(e);
				e.printStackTrace();
			}
		}
		
		return content;
	}
	
	public static List<BaseRecord> sortAndLimit(List<BaseRecord> lst, int limit){
		lst.sort((c1, c2) -> Double.compare(c2.get("score"), c1.get("score")));
		return lst.subList(0, Math.min(lst.size(), limit));
	}
	
	private static BaseRecord newVectorStore(long id, String keyId, String vaultId, boolean vaulted, long orgId, long ref, String refType, double score, int chunk, String content) throws FieldException, ModelNotFoundException {
		BaseRecord vs = RecordFactory.newInstance(ModelNames.MODEL_VECTOR_MODEL_STORE);
		vs.setValue(FieldNames.FIELD_ID, id);
		if(ref > 0L && refType != null) {
			BaseRecord vsr = RecordFactory.newInstance(refType);
			vsr.setValue(FieldNames.FIELD_ID, ref);
			vs.setValue(FieldNames.FIELD_VECTOR_REFERENCE, vsr);
		}
		vs.setValue(FieldNames.FIELD_KEY_ID, keyId);
		vs.setValue(FieldNames.FIELD_VAULT_ID, vaultId);
		vs.setValue(FieldNames.FIELD_VAULTED, vaulted);
		vs.setValue(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		vs.setValue(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, refType);
		vs.setValue(FieldNames.FIELD_SCORE, score);
		vs.setValue(FieldNames.FIELD_CHUNK, chunk);
		vs.setValue(FieldNames.FIELD_CONTENT, content);
		
		return vs;
	}

	public static int countVectorStore(BaseRecord model){
		Query q = QueryUtil.createQuery(ModelNames.MODEL_VECTOR_MODEL_STORE, FieldNames.FIELD_VECTOR_REFERENCE, model.copyRecord(new String[] {FieldNames.FIELD_ID}));
		q.field(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, model.getSchema());
		q.field(FieldNames.FIELD_ORGANIZATION_ID, model.get(FieldNames.FIELD_ORGANIZATION_ID));
		return IOSystem.getActiveContext().getSearch().count(q);
	}
	
	public static int deleteVectorStore(BaseRecord model){
		Query q = QueryUtil.createQuery(ModelNames.MODEL_VECTOR_MODEL_STORE, FieldNames.FIELD_VECTOR_REFERENCE, model.copyRecord(new String[] {FieldNames.FIELD_ID}));
		q.field(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, model.getSchema());
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
		q.field(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, model.getSchema());
		List<BaseRecord> recs = new ArrayList<>();
		try {
			recs = Arrays.asList(IOSystem.getActiveContext().getSearch().find(q));
		} catch (ReaderException e) {
			logger.error(e);
		}
		return recs;
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
			logger.info("Replacing vector store for " + model.getSchema());
			if(deleteVectorStore(model) == 0) {
				throw new FieldException("Vector store already exists and was not deleted.  Alternately, specify the content directly to append.");
			}
		}

		return createVectorStore(model, DocumentUtil.getStringContent(model), chunkType, chunkSize);
	}

	public static List<BaseRecord> createVectorStore(BaseRecord model, String content, ChunkEnumType chunkType, int chunkSize) throws FieldException {

		List<BaseRecord> chunkRecs = new ArrayList<>();
		List<String> chunks = Arrays.asList(new String[] {content});

		if(content == null || content.length() == 0) {
			throw new FieldException("Content is null or empty");
		}
		
		if(chunkType == ChunkEnumType.SENTENCE) {
			chunks = chunkBySentence(content, chunkSize);
		}
		else if(chunkType == ChunkEnumType.WORD) {
			chunks = chunkByWord(content, chunkSize);
		}
		else if(chunkType == ChunkEnumType.CHAPTER) {
			chunks = chunkByChapter(model.get(FieldNames.FIELD_NAME), null, content, chunkSize);
		}
		// logger.info("Generating " + chunks.size() + " embeddings");
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
	        	chunkModel.setValue(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, model.getSchema());
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
    	int iter = 0;
    	while (st.hasMoreTokens()) {
    		String tk = st.nextToken();
    		if(tk == null || tk.trim().length() == 0) {
    			continue;
    		}
    		buff.append(tk);
    		iter++;
    		if(st.hasMoreTokens()) {
    			buff.append(" ");
    		}
			if(counter.getAndIncrement() % chunkSize == 0 && iter > 1) {
				sx.add(buff.toString());
				buff = new StringBuilder();
				iter = 0;
			}
    	}
		if(buff.length() > 0) {
			sx.add(buff.toString());
		}
		return sx;
	}
    
    /*
    private static List<String> getSentences(String content){
		return Arrays.asList(content.split("((?<=\\.)|(?=\\.))"))
			.stream()
			.filter(s -> s.trim().length() > 0)
			.map(s ->  s.replaceAll("[“”]", "\"").replaceAll("’", "'"))
			.map(l -> l.replaceAll("[\\x93-\\x94]", "'")
			.collect(Collectors.toList());
    }
    */
    
    public static List<String> chunkByChapter(String name, String path, String block, int chunkSize) {
    	List<BaseRecord> vchunks = new ArrayList<>();
    	
    	List<String> chunks = new ArrayList<>();
    	
    	int chapter = 0;
    	int chunk = 0;
    	
    	String[] output = block.split("\\r?\\n");
    	StringBuilder buff = new StringBuilder();
    	String chapterTitle = null;
    	for(String line : output) {
    		String tmp = line.trim();
    		if(tmp.length() == 0) {
    			continue;
    		}
    		if(tmp.startsWith("Chapter ")) {
    			vchunks.addAll(
    				chunkChapter(buff.toString(), chapterTitle, chapter, chunkSize)
    			);
    			buff = new StringBuilder();
    			chapter++;
    			chapterTitle = tmp;
    		}
    		buff.append(tmp + System.lineSeparator());
    	}
    	if(buff.length() > 0) {
    		vchunks.addAll(chunkChapter(buff.toString(), chapterTitle, chapter, chunkSize));
    	}
    	for(BaseRecord v: vchunks) {
    		chunk++;
    		v.setValue(FieldNames.FIELD_NAME, name);
    		v.setValue("path", path);
    		v.setValue("chunk", chunk);
    		v.setValue("chunkCount", vchunks.size());
    		chunks.add(JSONUtil.exportObject(v, RecordSerializerConfig.getHiddenForeignUnfilteredModule()));
    	}
    	return chunks;
    }
    
    private static List<BaseRecord> chunkChapter(String content, String chapterTitle, int chapter, int chunkSize) {
    	List<BaseRecord> vchunks = new ArrayList<>();
    	List<String> strChunks = chunkBySentence(content, chunkSize);
    	for(String s: strChunks) {
    		BaseRecord vchunk = null;
    		try {
				vchunk = RecordFactory.newInstance(ModelNames.MODEL_VECTOR_CHUNK);
				vchunk.setValue("content", s);
				vchunk.setValue("chapter", chapter);
				vchunk.setValue("chapterTitle", chapterTitle);
				vchunks.add(vchunk);
			} catch (FieldException | ModelNotFoundException e) {
				logger.error(e);
			}
    	}
    	return vchunks;
    }
    
    private static List<String> chunkBySentence(String block, int chunkSize) {
    	
    	
    	BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.US);
    	iterator.setText(block);
    	int start = iterator.first();
    	List<String> bsents = new ArrayList<>();
    	
    	for (int end = iterator.next(); end != BreakIterator.DONE; end = iterator.next()) {
    	    bsents.add(block.substring(start,end));
    	    start = end;
    	}
    	/// Arrays.asList(block.split("((?<=\\.)|(?=\\.))"))
		List<String> sents = bsents
			.stream()
			.filter(s -> s.trim().length() > 0)
			.map(s ->  s.replaceAll("[“”]", "\"").replaceAll("’", "'"))
			.collect(Collectors.toList());
		final AtomicInteger counter = new AtomicInteger(0);
		List<String> sx = new ArrayList<>();
		StringBuilder buff = new StringBuilder();
		for(String s: sents) {
			buff.append(s);
			if(chunkSize != 0 && counter.getAndIncrement() % chunkSize == 0) {
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
