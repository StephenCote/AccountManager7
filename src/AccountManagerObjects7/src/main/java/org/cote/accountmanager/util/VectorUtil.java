package org.cote.accountmanager.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.MemoryReader;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ConnectionEnumType;
import org.cote.accountmanager.tools.EmbeddingUtil;

import com.ibm.icu.util.StringTokenizer;
import com.pgvector.PGvector;

public class VectorUtil {
	public static final Logger logger = LogManager.getLogger(VectorUtil.class);
	
	public enum ChunkEnumType {
		UNKNOWN,
		SENTENCE,
		WORD,
		CHAPTER
	};
	
	private static Pattern tablePat = Pattern.compile("\\$\\{tableName\\}");
	private static Pattern tagFilterPat = Pattern.compile("\\$\\{tagFilter\\}");
	
	/// DISTINCT_WRAPPER
	private static final String DISTINCT_PREFACE = "SELECT distinct on (vectorReference, vectorReferenceType) id, keyId, vaultId, vaulted, organizationId, vectorReference, vectorReferenceType, score, content, chunk FROM(";
	private static final String DISTINCT_SUFFIX = """
)
WHERE score >= ?
ORDER BY vectorReference, vectorReferenceType
LIMIT ?
""";

	/// Derived from PG Vector example hybrid search query
	///
	private static final String HYBRID_SQL = """
WITH semantic_search AS (
    SELECT T1.id, keyId, vaultId, vaulted, T1.organizationId, vectorReference, vectorReferenceType, RANK () OVER (ORDER BY embedding <=> ?) AS rank, content, chunk
    FROM ${tableName} T1
    ${tagFilter}
    WHERE (vectorReference = ? or ? = 0) and (vectorReferenceType = ? or ? IS NULL)
    ORDER BY embedding <=> ?
    LIMIT ?
),
keyword_search AS (
    SELECT id, keyId, vaultId, vaulted, organizationId, vectorReference, vectorReferenceType, RANK () OVER (ORDER BY ts_rank_cd(to_tsvector('english', content), query) DESC), content, chunk
    FROM (SELECT DISTINCT T2.id, keyId, vaultId, vaulted, T2.organizationId, vectorReference, vectorReferenceType, content, chunk FROM ${tableName} T2 ${tagFilter}), plainto_tsquery('english', ?) query
    WHERE to_tsvector('english', content) @@ query
    AND (vectorReference = ? or ? = 0) and (vectorReferenceType = ? or ? IS NULL)
    ORDER BY ts_rank_cd(to_tsvector('english', content), query) DESC
    LIMIT ?
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
	
	private EmbeddingUtil embedUtil = null;
	
	public VectorUtil(LLMServiceEnumType type, String url, String token) {
		embedUtil = new EmbeddingUtil(type, url, token);
	}
	
	public List<BaseRecord> findByTag(String modelName, BaseRecord[] tags){
		List<BaseRecord> chunks = new ArrayList<>();
		if(modelName == null || tags.length == 0) {
			return chunks;
		}
		
		String tableName = IOSystem.getActiveContext().getDbUtil().getTableName(modelName);
		String sql = "SELECT DISTINCT T.id, keyId, vaultId, vaulted, T.organizationId, vectorReference, vectorReferenceType, content, chunk FROM " + tableName + " T "
		+ "INNER JOIN "
			+ IOSystem.getActiveContext().getDbUtil().getTableName(RecordFactory.getSchema(ModelNames.MODEL_TAG), ModelNames.MODEL_PARTICIPATION)
			/// AND TP.participantModel = vectorReferenceType
			+ " TP on TP.participantId = vectorReference  AND TP.participationId IN ("
			+ Arrays.asList(tags).stream().map(t -> Long.toString((long)t.get(FieldNames.FIELD_ID))).collect(Collectors.joining(","))
			+ ")"
		;

		try (Connection con = IOSystem.getActiveContext().getDbUtil().getDataSource().getConnection(); Statement stat = con.createStatement()){
	        ResultSet rs = stat.executeQuery(sql);
	        MemoryReader mem = new MemoryReader();
	        while (rs.next()) {
	        	BaseRecord vs = newVectorStore(rs.getLong("id"),  rs.getString("keyId"), rs.getString("vaultId"), rs.getBoolean("vaulted"), rs.getLong("organizationId"), rs.getLong("vectorReference"), rs.getString("vectorReferenceType"), 0.0, rs.getInt("chunk"), rs.getString("content"));
	        	mem.read(vs);
	        	chunks.add(vs);
	        }
	        rs.close();
		}
		catch(SQLException | FieldException | ModelNotFoundException | ReaderException e) {
			logger.error(e);
			e.printStackTrace();
		}
		
		return chunks;
    }
	
	public List<BaseRecord> find(BaseRecord model, String query){
		return find(model, new String[] {ModelNames.MODEL_VECTOR_MODEL_STORE}, query);
	}
	public List<BaseRecord> find(BaseRecord model, String[] vectorModels, String query){
		return findByEmbedding(model, (model != null ? model.getSchema() : null), new BaseRecord[0], vectorModels, query, 10, 60, false);
	}
	public List<BaseRecord> find(BaseRecord model, String query, int limit, double k){
		return find(model, null, query, limit, k, false);
	}
	public List<BaseRecord> find(BaseRecord model, String modelName, String query, int limit, double k, boolean distinct){
		return find(model, modelName, new BaseRecord[0], new String[] {ModelNames.MODEL_VECTOR_MODEL_STORE}, query, limit, k, distinct);
	}
	public List<BaseRecord> find(BaseRecord model, String modelName, BaseRecord[] tags, String[] vectorModels, String query, int limit, double k, boolean distinct){
		return findByEmbedding(model, modelName, tags, vectorModels, query, limit, k, distinct);
	}	
	private List<BaseRecord> findByEmbedding(BaseRecord model, String modelName, BaseRecord[] tags, String[] vectorModels, String query, int limit, double k, boolean distinct){
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
		
		Set<String> tables = new LinkedHashSet<>(Arrays.asList(vectorModels).stream().map(t -> IOSystem.getActiveContext().getDbUtil().getTableName(t)).collect(Collectors.toList()));
		String tagFilter = "";
		if(tags.length > 0) {
			tagFilter = "INNER JOIN "
				+ IOSystem.getActiveContext().getDbUtil().getTableName(RecordFactory.getSchema(ModelNames.MODEL_TAG), ModelNames.MODEL_PARTICIPATION)
				// AND TP.participantModel = vectorReferenceType
				+ " TP on TP.participantId = vectorReference AND TP.participationId IN ("
				+ Arrays.asList(tags).stream().map(t -> Long.toString((long)t.get(FieldNames.FIELD_ID))).collect(Collectors.joining(","))
				+ ")"
			;
		}
		for(String tableName : tables) {
			String selectTemplate = (distinct ? DISTINCT_PREFACE : "") + HYBRID_SQL + (distinct ? DISTINCT_SUFFIX : "");
			String sql = tablePat.matcher(selectTemplate).replaceAll(tableName);
			sql = tagFilterPat.matcher(sql).replaceAll(tagFilter);
	
			try (Connection con = IOSystem.getActiveContext().getDbUtil().getDataSource().getConnection(); PreparedStatement stat = con.prepareStatement(sql)){
		        float[] queryEmbedding = generateEmbeddings(new String[] {query}).get(0);

		        stat.setObject(1, new PGvector(queryEmbedding));
		        stat.setLong(2, id);
		        stat.setLong(3, id);
		        stat.setString(4, refType);
		        stat.setString(5, refType);
		        stat.setObject(6, new PGvector(queryEmbedding));
		        stat.setInt(7, limit);
		        stat.setString(8, query);
		        stat.setLong(9, id);
		        stat.setLong(10, id);
		        stat.setString(11, refType);
		        stat.setString(12, refType);
		        stat.setInt(13, limit);
		        stat.setDouble(14, k);
		        stat.setDouble(15, k);
		        stat.setInt(16, limit);
		        
		        if(distinct) {
		        	stat.setDouble(17, k);
		        	stat.setInt(18, limit);	
		        }
		        
		        logger.info(stat);
		        ResultSet rs = stat.executeQuery();
		        MemoryReader mem = new MemoryReader();
		        while (rs.next()) {
		        	BaseRecord vs = newVectorStore(rs.getLong("id"),  rs.getString("keyId"), rs.getString("vaultId"), rs.getBoolean("vaulted"), rs.getLong("organizationId"), rs.getLong("vectorReference"), rs.getString("vectorReferenceType"), rs.getDouble("score"), rs.getInt("chunk"), rs.getString("content"));
		        	mem.read(vs);
		        	content.add(vs);
		        }
		        rs.close();
			}
			catch(SQLException | FieldException | ModelNotFoundException | ReaderException e) {
				logger.error(e);
				e.printStackTrace();
			}
		}
		
		return content;
	}
	
	public List<BaseRecord> sortAndLimit(List<BaseRecord> lst, int limit){
		lst.sort((c1, c2) -> Double.compare(c2.get("score"), c1.get("score")));
		return lst.subList(0, Math.min(lst.size(), limit));
	}
	
	private BaseRecord newVectorStore(long id, String keyId, String vaultId, boolean vaulted, long orgId, long ref, String refType, double score, int chunk, String content) throws FieldException, ModelNotFoundException {
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

	public int countVectorStore(BaseRecord model){
		Query q = QueryUtil.createQuery(ModelNames.MODEL_VECTOR_MODEL_STORE, FieldNames.FIELD_VECTOR_REFERENCE, model.copyRecord(new String[] {FieldNames.FIELD_ID}));
		q.field(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, model.getSchema());
		q.field(FieldNames.FIELD_ORGANIZATION_ID, model.get(FieldNames.FIELD_ORGANIZATION_ID));
		return IOSystem.getActiveContext().getSearch().count(q);
	}
	public int deleteVectorStore(BaseRecord model){
		return deleteVectorStore(model, ModelNames.MODEL_VECTOR_MODEL_STORE);
	}
	public int deleteVectorStore(BaseRecord model, String vectorModel){
		Query q = QueryUtil.createQuery(vectorModel, FieldNames.FIELD_VECTOR_REFERENCE, model.copyRecord(new String[] {FieldNames.FIELD_ID}));
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
	
	public List<BaseRecord> getVectorStore(BaseRecord model){
		return getVectorStore(model, new String[0]);
	}
	
	public List<BaseRecord> getVectorStore(BaseRecord model, String[] fields){
		Query q = QueryUtil.createQuery(ModelNames.MODEL_VECTOR_MODEL_STORE, FieldNames.FIELD_VECTOR_REFERENCE, model.copyRecord(new String[] {FieldNames.FIELD_ID}));
		q.field(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, model.getSchema());
		q.setRequest(fields);
		List<BaseRecord> recs = new ArrayList<>();
		try {
			recs = Arrays.asList(IOSystem.getActiveContext().getSearch().find(q).getResults());
		} catch (ReaderException e) {
			logger.error(e);
		}
		return recs;
	}
	
	public List<BaseRecord> createVectorStore(BaseRecord model, ChunkEnumType chunkType, int chunkSize) throws FieldException {

		
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

	public List<BaseRecord> createVectorStore(BaseRecord model, String content, ChunkEnumType chunkType, int chunkSize) throws FieldException {

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
	        List<float[]> embeddings = generateEmbeddings(chunks.toArray(new String[0]));
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
		catch(ModelNotFoundException e) {
			logger.error(e);
		}
		
		return chunkRecs;
	}
	

    private List<String> chunkByWord(String block, int chunkSize) {
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
    
    public List<String> chunkByChapter(String name, String path, String block, int chunkSize) {
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
    
    private List<BaseRecord> chunkChapter(String content, String chapterTitle, int chapter, int chunkSize) {
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
    
    private List<String> chunkBySentence(String block, int chunkSize) {
    	
    	
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
    
	
    private List<float[]> generateEmbeddings(String[] input)  {
        List<float[]> embeddings = new ArrayList<>(input.length);
        for (String text : input) {
        	float[] embs = embedUtil.getEmbedding(text);
        	embeddings.add(embs);
        }
        return embeddings;
    }

	public EmbeddingUtil getEmbedUtil() {
		return embedUtil;
	}
    
    
}
