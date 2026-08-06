package org.cote.accountmanager.tools;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.LLMConnectionManager;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.MediaType;

public class EmbeddingUtil {
	public static final Logger logger = LogManager.getLogger(EmbeddingUtil.class);

	/// Dimension requested from OpenAI/Azure embedding models. Kept in lockstep with the
	/// common.vectorExt.embedding column (maxLength in vectorExtModel.json) and the local
	/// all-mpnet-base-v2 model so stored vectors match the column width. Only applied to the
	/// OPENAI branch (and only when > 0); the LOCAL branch is unaffected. Configurable via
	/// setEmbeddingDimensions(...) from test/service config; defaults to 768.
	public static final int DEFAULT_EMBEDDING_DIMENSIONS = 768;
	private volatile int embeddingDimensions = DEFAULT_EMBEDDING_DIMENSIONS;

	/// Immutable (serverUrl, authorizationToken) pair.
	///
	/// These two values are now mutable at runtime (ServerConfigUtil re-applies the DB-backed
	/// deployment config onto this process-wide singleton on every TTL refresh, from whatever
	/// request thread happens to trip the refresh). They are read by other threads with no
	/// synchronization, and getEmbedding() reads BOTH in a single expression — as two separate
	/// field reads, "new URL with old token" and "old URL with new token" are both observable.
	/// Holding the pair in one immutable holder swapped through a single volatile reference makes
	/// the pair un-tearable: a reader takes one snapshot and sees a consistent pair.
	/// (volatile on each field individually would fix visibility but NOT tearing.)
	public static final class Endpoint {
		private final String serverUrl;
		private final String authorizationToken;
		Endpoint(String serverUrl, String authorizationToken) {
			this.serverUrl = serverUrl;
			this.authorizationToken = authorizationToken;
		}
		public String getServerUrl() {
			return serverUrl;
		}
		public String getAuthorizationToken() {
			return authorizationToken;
		}
	}

	private volatile Endpoint endpoint = new Endpoint(null, null);
	private volatile LLMServiceEnumType serviceType = LLMServiceEnumType.UNKNOWN;

	public EmbeddingUtil(LLMServiceEnumType type, String url, String token) {
		this.endpoint = new Endpoint(url, token);
		this.serviceType = type;
	}

	public LLMServiceEnumType getServiceType() {
		return serviceType;
	}

	/// Single-read accessor for the (url, token) pair. Callers that need both MUST use this.
	public Endpoint getEndpoint() {
		return endpoint;
	}

	public String getServerUrl() {
		return endpoint.getServerUrl();
	}

	public String getAuthorizationToken() {
		return endpoint.getAuthorizationToken();
	}

	/// Atomically swap both halves of the endpoint. Preferred over the single-value setters.
	public void setEndpoint(String serverUrl, String authorizationToken) {
		this.endpoint = new Endpoint(serverUrl, authorizationToken);
	}

	public void setServerUrl(String serverUrl) {
		Endpoint cur = this.endpoint;
		this.endpoint = new Endpoint(serverUrl, cur.getAuthorizationToken());
	}

	public void setAuthorizationToken(String authorizationToken) {
		Endpoint cur = this.endpoint;
		this.endpoint = new Endpoint(cur.getServerUrl(), authorizationToken);
	}

	public int getEmbeddingDimensions() {
		return embeddingDimensions;
	}

	public void setEmbeddingDimensions(int embeddingDimensions) {
		enforceModelDimensionSync(embeddingDimensions);
		this.embeddingDimensions = embeddingDimensions;
	}

	/// Model-sync enforcement (fail fast): the configured embedding dimension MUST match the
	/// width of the common.vectorExt.embedding VECTOR column (maxLength). A mismatch guarantees
	/// the stored vector overflows or is silently truncated, corrupting every cosine comparison.
	/// Log an ERROR naming both values and throw. Skips the check when the configured value is
	/// <= 0 (means "let the model decide", no dimension sent) or when the schema/field/column
	/// width cannot be resolved (nothing to compare against).
	public static void enforceModelDimensionSync(int dimensions) {
		if(dimensions <= 0) {
			return;
		}
		ModelSchema ms = RecordFactory.getSchema(ModelNames.MODEL_VECTOR_EXT);
		if(ms == null) {
			return;
		}
		FieldSchema fs = ms.getFieldSchema(FieldNames.FIELD_EMBEDDING);
		if(fs == null) {
			return;
		}
		int columnWidth = fs.getMaxLength();
		if(columnWidth > 0 && columnWidth != dimensions) {
			logger.error("Embedding dimension mismatch: configured embedding.dimensions=" + dimensions
				+ " does not match " + ModelNames.MODEL_VECTOR_EXT + "." + FieldNames.FIELD_EMBEDDING
				+ " column width (maxLength=" + columnWidth + "). This guarantees vector overflow/truncation.");
			throw new IllegalStateException("Embedding dimension mismatch: configured=" + dimensions
				+ " vs " + ModelNames.MODEL_VECTOR_EXT + "." + FieldNames.FIELD_EMBEDDING + " maxLength=" + columnWidth);
		}
	}

	/// Phase 5.3 (ConversationQualityPlan): track this sync HTTP call with
	/// LLMConnectionManager so it shows up in getActiveLLMCallCount() and
	/// pressure-based deferral can see it. Caller passes a `label` like
	/// "embed:keywords" for diagnostics. Returns the ToolResponse, or null
	/// on transport error.
	private ToolResponse trackedToolPost(String label, String path, ToolRequest body) {
		String id = LLMConnectionManager.registerSyncCall(label);
		/// Single volatile read; the URL cannot change mid-call from this thread's point of view.
		Endpoint ep = this.endpoint;
		try {
			return ClientUtil.post(ToolResponse.class,
				ClientUtil.getResource(ep.getServerUrl() + path), null, body,
				MediaType.APPLICATION_JSON_TYPE);
		} catch (ProcessingException e) {
			logger.error(e);
			return null;
		} finally {
			LLMConnectionManager.unregisterSyncCall(id);
		}
	}

	public String[] getKeywords(String content){
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Keywords not supported");
			return new String[0];
		}
		ToolResponse resp = trackedToolPost("embed:keywords", "/extract_keywords", new ToolRequest(content));
		return resp != null ? resp.getKeywords() : new String[0];
	}

	public String[] getTopics(String content){
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Topics not supported");
			return new String[0];
		}
		ToolResponse resp = trackedToolPost("embed:topics", "/topic_modeling", new ToolRequest(content));
		return resp != null ? resp.getTopics() : new String[0];
	}

	public String[] getNames(String content){
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Names not supported");
			return new String[0];
		}
		ToolResponse resp = trackedToolPost("embed:names", "/named_entity_recognition", new ToolRequest(content));
		return resp != null ? resp.getEntities() : new String[0];
	}

	public String[] getTags(String content){
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Tags not supported");
			return new String[0];
		}
		ToolResponse resp = trackedToolPost("embed:tags", "/generate_tags", new ToolRequest(content));
		return resp != null ? resp.getTags() : new String[0];
	}

	public String getSentiment(String content){
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Sentiment not supported");
			return null;
		}
		ToolResponse resp = trackedToolPost("embed:sentiment", "/analyze_sentiment", new ToolRequest(content));
		return resp != null ? resp.getSentiment() : null;
	}

	public String getSummary(String content){
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Summary not supported");
			return null;
		}
		ToolResponse resp = trackedToolPost("embed:summary", "/generate_summary", new ToolRequest(content));
		return resp != null ? resp.getSummary() : null;
	}
	
	public float[] getEmbedding(String content){
		float[] emb = new float[0];
		/// ONE volatile read of the (url, token) pair for the whole call. Previously these were two
		/// separate reads of two separate mutable fields in a single expression, which could tear
		/// into new-URL-with-old-token (or the reverse) once the URL became runtime-configurable.
		Endpoint ep = this.endpoint;
		try {
			if(serviceType == LLMServiceEnumType.LOCAL) {
				ToolResponse resp = ClientUtil.post(ToolResponse.class, ClientUtil.getResource(ep.getServerUrl() + "/generate_embedding"), ep.getAuthorizationToken(), new ToolRequest(content), MediaType.APPLICATION_JSON_TYPE);
				if(resp != null) {
					emb = resp.getEmbedding();
				}
			}
			else if(serviceType == LLMServiceEnumType.OPENAI) {
				BaseRecord inp = RecordFactory.newInstance(OlioModelNames.MODEL_OPENAI_INPUT);
				inp.set("input", content);
				/// Bug 2: text-embedding-3-small returns 1536 dims by default, but the
				/// common.vectorExt.embedding column is a fixed width. Request the configured
				/// dimension count from Azure (text-embedding-3-small supports the "dimensions"
				/// parameter) so the stored vector matches the column and does not overflow/truncate.
				/// Only send it when > 0 (0/negative means "let the model decide").
				if(embeddingDimensions > 0) {
					inp.set("dimensions", embeddingDimensions);
				}
				/// Bug 1: read the raw JSON response string. post(String.class,...) runs
				/// JSONUtil.importObject(json, String.class) which throws on a JSON-object body,
				/// so it returned null and this branch never parsed the response. postJSON reads
				/// the entity directly and forwards the raw body to the parse code below.
				String respStr = ClientUtil.postJSON(String.class, ClientUtil.getResource(ep.getServerUrl()), ep.getAuthorizationToken(), inp.toFullString(), MediaType.APPLICATION_JSON_TYPE);
				if(respStr != null) {
					BaseRecord resp = RecordFactory.importRecord(OlioModelNames.MODEL_OPENAI_RESPONSE, respStr);
					if(resp != null) {
						List<BaseRecord> data = resp.get(FieldNames.FIELD_DATA);
						if(data != null && data.size() > 0) {
							// logger.info(data.get(0).toFullString());
							List<Float> embs = data.get(0).get("embedding");
	
							if(embs != null) {
								// emb = ArrayUtils.toPrimitive(embs.toArray(new Float[0]), 0.0F);
								int len = embs.size();
								emb = new float[len];
								logger.info("Len: " + len);
								for(int i = 0; i < len; i++) {
									Object obj = embs.get(i);
						            if (obj instanceof Double) {
						            	// logger.warn("Stupid Azure - " + obj);
						                emb[i] = ((Double) obj).floatValue();
						            } else if (obj instanceof Float) {
						                emb[i] = (Float) obj;
						            } else {
						                throw new IllegalArgumentException("List contains non-float and non-double elements - " + obj);
						            }
								}
							}
							else {
								logger.error("Float list was null");
							}
						}
					}
					else {
						logger.error("Failed to deserialize: " + resp);
					}
				}
				else {
					logger.error("Response was null");
				}

			}
			else {
				logger.error("Unhandled service type: " + serviceType.toString());
			}
		}
		catch(ProcessingException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}

		/// RUNTIME WIDTH GUARD: the embedding server URL is now runtime-configurable, so the
		/// configured server can be swapped for one running a DIFFERENT model with a DIFFERENT
		/// output dimension. enforceModelDimensionSync() does NOT cover that: it is only reached
		/// from setEmbeddingDimensions() at boot and compares a configured int against the schema
		/// width — it never touches the server or its response. This is the only guard covering the
		/// failure this feature makes reachable, and it is the one failure mode that silently
		/// corrupts persisted data.
		///
		/// The comparison is against the width THIS PROCESS HAS ALREADY SEEN, not the schema value —
		/// see noteAndResolveExpectedWidth for why an absolute comparison would break deployments
		/// that work today.
		return validateEmbeddingWidth(emb, noteAndResolveExpectedWidth(emb.length), ep.getServerUrl());
	}

	/// The width this process has accepted so far. 0 = nothing observed yet.
	private final java.util.concurrent.atomic.AtomicInteger observedWidth = new java.util.concurrent.atomic.AtomicInteger(0);

	public int getObservedEmbeddingWidth() {
		return observedWidth.get();
	}

	/// CONSISTENCY GUARD (not an absolute one).
	///
	/// Establishes the first non-empty width this process sees as the baseline, accepts it, and
	/// thereafter requires every embedding to match it. Returns the width to compare against, for
	/// validateEmbeddingWidth.
	///
	/// WHY NOT COMPARE AGAINST THE SCHEMA WIDTH: resolveExpectedDimensions() reads `maxLength` from
	/// models/common/vectorExtModel.json, which is the SCHEMA-DECLARED width, NOT the width of the
	/// column that actually exists in the database. DBUtil only ever emits ADD COLUMN / DROP COLUMN
	/// IF EXISTS (DBUtil.java:920,979) — it never alters an existing column's type. That maxLength
	/// changed 1024 -> 768 in commit 77f0fe8a (2025-03-26), so a database created before that still
	/// has vector(1024) columns. Such a deployment WORKS TODAY: the LOCAL branch never sends
	/// `dimensions`, and enforceModelDimensionSync is only reachable from setEmbeddingDimensions().
	/// An absolute 768 comparison would start rejecting its 1024-wide vectors — including QUERY
	/// embeddings, so similarity search would silently return nothing.
	///
	/// A consistency check targets the actual threat (the embedding URL being repointed at a
	/// different-dimension model mid-process) and cannot break a self-consistent legacy deployment,
	/// because 1024 simply becomes its baseline. The anti-corruption property is preserved: two
	/// different widths can never both be written into the same column by this process.
	///
	/// A baseline that disagrees with the schema is WARNED about once, never rejected.
	public int noteAndResolveExpectedWidth(int returnedLength) {
		if(returnedLength <= 0) {
			/// Nothing returned — not a width problem, and must not establish a baseline.
			return 0;
		}
		int baseline = observedWidth.get();
		if(baseline == 0 && observedWidth.compareAndSet(0, returnedLength)) {
			baseline = returnedLength;
			int schemaWidth = resolveExpectedDimensions();
			if(schemaWidth > 0 && schemaWidth != baseline) {
				logger.warn("Embedding width baseline for this process is " + baseline
					+ ", which does NOT match the schema-declared " + ModelNames.MODEL_VECTOR_EXT + "."
					+ FieldNames.FIELD_EMBEDDING + " maxLength of " + schemaWidth + ". This is EXPECTED on a"
					+ " database created before the schema width changed (the column is never altered in"
					+ " place), and is NOT being rejected. Vectors will be accepted at " + baseline
					+ " for the life of this process; a width change from that point on WILL be rejected.");
			}
			else {
				logger.info("Embedding width baseline for this process established at " + baseline);
			}
			return baseline;
		}
		return observedWidth.get();
	}

	/// The SCHEMA-DECLARED expected width: common.vectorExt.embedding `maxLength` when present,
	/// else the configured dimension count.
	///
	/// NOTE: this is the value declared in the model JSON, NOT the width of the column that exists
	/// in the database. Those diverge on any database created before the schema value last changed,
	/// because DBUtil never alters an existing column's type. Used for the boot-time
	/// enforceModelDimensionSync check and as the advisory comparison in
	/// noteAndResolveExpectedWidth; it is deliberately NOT the runtime rejection threshold.
	public int resolveExpectedDimensions() {
		ModelSchema ms = RecordFactory.getSchema(ModelNames.MODEL_VECTOR_EXT);
		if(ms != null) {
			FieldSchema fs = ms.getFieldSchema(FieldNames.FIELD_EMBEDDING);
			if(fs != null && fs.getMaxLength() > 0) {
				return fs.getMaxLength();
			}
		}
		return embeddingDimensions;
	}

	/// Validate a returned embedding against the expected width and REJECT it on mismatch —
	/// returning an empty float[] so it is never stored. VectorUtil.createVectorStore() throws
	/// FieldException on a null/empty embedding (VectorUtil.java:357-359), so an empty return
	/// aborts the store rather than persisting a wrong-width vector.
	///
	/// Static and public on purpose: the guard is reachable (and therefore testable) without a
	/// live embedding server.
	public static float[] validateEmbeddingWidth(float[] emb, int expectedDimensions, String serverUrl) {
		if(emb == null) {
			return new float[0];
		}
		/// Nothing returned (transport error / unsupported service type) — not a width problem.
		if(emb.length == 0) {
			return emb;
		}
		/// <= 0 means "let the model decide" and no column width was resolvable: nothing to compare.
		if(expectedDimensions <= 0) {
			return emb;
		}
		if(emb.length != expectedDimensions) {
			logger.error("Embedding width mismatch: returned vector length (float[].length) = " + emb.length
				+ ", expected embedding dimension (" + ModelNames.MODEL_VECTOR_EXT + "."
				+ FieldNames.FIELD_EMBEDDING + " column width / configured embedding.dimensions) = "
				+ expectedDimensions
				+ ". Embedding server = " + serverUrl
				+ ". REJECTING this vector — it will NOT be stored. The configured embedding server is"
				+ " almost certainly running a different model than the vector column was sized for.");
			return new float[0];
		}
		return emb;
	}

	public boolean heartbeat() {
		boolean outBool = false;
		if(serviceType != LLMServiceEnumType.LOCAL) {
			return true;
		}

		try {
			Status stat = ClientUtil.get(Status.class, ClientUtil.getResource(this.endpoint.getServerUrl() + "/heartbeat"), null, MediaType.APPLICATION_JSON_TYPE);
			if(stat != null) {
				outBool = stat.isStatus();
			}
		}
		catch(ProcessingException e) {
			logger.error(e);
		}
		return outBool;

	}

	public ToolResponse getMeta(String statement) {
		ToolResponse tr = new ToolResponse();
		tr.setKeywords(getKeywords(statement));
		tr.setEntities(getNames(statement));
		tr.setSentiment(getSentiment(statement));
		tr.setSummary(getSummary(statement));
		tr.setTags(getTags(statement));
		tr.setTopics(getTopics(statement));
		return tr;
	}
	
}
