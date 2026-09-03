package org.cote.accountmanager.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;

import com.fasterxml.jackson.core.util.JacksonFeature;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation.Builder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;


public class ClientUtil {
	
	private static ArrayList<NewCookie> cookies = new ArrayList<NewCookie>();
	
	private static Client client = null;
	private static String cachePath = "./cache";
	private static String authToken = null;
	public static final Logger logger = LogManager.getLogger(ClientUtil.class);
	private static boolean disableSSLVerification = false;
	
	public static void clearCookies(){
		cookies.clear();
	}
	public static void setCookies(Map<String,NewCookie> in_cookies){
		clearCookies();
		for(String ck : in_cookies.keySet()){
			cookies.add(in_cookies.get(ck));
		}
	}
	
	public static boolean isDisableSSLVerification() {
		return disableSSLVerification;
	}
	public static void setDisableSSLVerification(boolean ds) {
		disableSSLVerification = ds;
	}

	/// Shared HTTP read timeout, in seconds. Deployment-configurable because the value that matters
	/// is set by the slowest legitimate backend call, which on this project is Stable Diffusion image
	/// generation - and that varies enormously by GPU (the same FLUX.2 request is ~3.3 min on a DGX
	/// Spark and ~10.6 min on a Strix Halo iGPU).
	///
	/// Default 1200s (20 min): roughly 2x the slowest generation actually measured, so a slower model
	/// or a hires pass has headroom, while still bounding a wedged backend rather than hanging forever.
	/// Raise it if generations legitimately run longer; do NOT lower it below your slowest real SD
	/// call, because the failure mode is a SocketTimeoutException on work the GPU is still doing.
	public static final String READ_TIMEOUT_CONFIG_KEY = "http.read.timeout";
	private static final int DEFAULT_READ_TIMEOUT_SECONDS = 1200;
	private static volatile int readTimeoutSeconds = DEFAULT_READ_TIMEOUT_SECONDS;

	/// Boot-pinned: the shared Client is built once, lazily, and caches the timeout. Calling this
	/// after the first HTTP request has no effect, so it must run during startup - this logs loudly
	/// rather than silently pretending to apply, since a stale timeout is exactly the kind of thing
	/// that looks configured and isn't.
	/// Opt-in tracing for this util's configuration decisions, mirroring
	/// PolicyUtil.setTrace/isTrace. OFF by default and deliberately so: these lines are diagnostic,
	/// not events. Bracket a specific call with setTrace(true)/setTrace(false) when investigating,
	/// the same way the PBAC trace is used - do not leave it on for a whole run.
	private static volatile boolean trace = false;

	public static void setTrace(boolean t) {
		trace = t;
	}

	public static boolean isTrace() {
		return trace;
	}

	public static void setReadTimeoutSeconds(int seconds) {
		if(seconds <= 0) {
			logger.warn("Ignoring non-positive " + READ_TIMEOUT_CONFIG_KEY + "=" + seconds
				+ "; keeping " + readTimeoutSeconds + "s");
			return;
		}
		if(seconds == readTimeoutSeconds) {
			return;
		}
		/// Trace-gated. This fired as a WARN on every value change, which meant every test that
		/// exercises the setter emitted a warning about a condition that is only interesting while
		/// investigating a timeout that did not apply. A warning nobody can act on is noise, and noise
		/// at WARN level is worse than none because it devalues real warnings.
		if(client != null && trace) {
			logger.info(READ_TIMEOUT_CONFIG_KEY + " changed from " + readTimeoutSeconds + "s to " + seconds
				+ "s AFTER the shared HTTP client was built - the new value will NOT take effect for this "
				+ "process. Set it during startup.");
		}
		readTimeoutSeconds = seconds;
	}

	public static int getReadTimeoutSeconds() {
		return readTimeoutSeconds;
	}
	public static void setCachePath(String s) {
		cachePath = s;
	}
	public static String getCachePath(){
		return cachePath;
	}

	public static Client getClient(){
		if(client != null) return client;
		ClientBuilder cb = ClientBuilder.newBuilder();
		if(disableSSLVerification) {
			TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager(){
			    public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
			    public void checkClientTrusted(X509Certificate[] certs, String authType){}
			    public void checkServerTrusted(X509Certificate[] certs, String authType){}
			}};
			try {
				SSLContext sc = SSLContext.getInstance("TLS");
			    sc.init(null, trustAllCerts, new SecureRandom());
			    SSLContext.setDefault(sc);
			    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
			    cb.sslContext(sc).hostnameVerifier((s1, s2) -> true);
			} catch (Exception e) {
			    
			}
		}
		
		
		/// Phase 5.3 (ConversationQualityPlan): finite timeouts to prevent
		/// infinite hangs when Ollama or another backend wedges. Without
		/// these, a stuck embedding call holds an HTTP connection from the
		/// shared pool and any subsequent chat request waits behind it.
		/// 30s connect is generous for local services.
		///
		/// The read timeout was 360s, on the stated premise that it "matches the worst-case batch
		/// image generation upper bound". That premise is false on current hardware: a FLUX.2 Klein 9B
		/// multi-reference composite measured 10.64 min (638s) of GPU time on the Beelink GTR9's
		/// Strix Halo iGPU, so every such generation died with
		///   PictureBookException: java.net.SocketTimeoutException: Read timed out
		/// after ~361s, with the GPU still working and the image eventually landing on the server -
		/// the client had simply stopped listening. Confirmed twice: a standalone FLUX.2 run failed at
		/// 361.9s, and TestPictureBookCustomPipeline at 667s (2026-08-07).
		///
		/// Now configurable, because the right value is a property of the GPU, not of the code.
		cb.connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS);
		cb.readTimeout(readTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
		logger.info("HTTP client read timeout = " + readTimeoutSeconds + "s ("
			+ READ_TIMEOUT_CONFIG_KEY + "), connect timeout = 30s");

		client = cb
			.build()
			.register(JacksonFeature.class)
		;

		LLMConnectionManager.registerClient("clientUtil.jakarta", client);
		return client;
	}
	
	public static <T> T postJSON(Class<T> cls, WebTarget resource, String jsonText, MediaType responseType){
		return postJSON(cls, resource, null, jsonText, responseType);
	}

	/// Raw-string POST variant that also sets the Azure/OpenAI "api-key" header when an
	/// authorization token is supplied (mirrors post(..., authorizationToken, ...)). Unlike
	/// post(cls,...), this reads the entity directly via readEntity(cls) and does NOT run
	/// JSONUtil.importObject — so requesting String.class returns the raw JSON body untouched
	/// (post(String.class,...) throws on a JSON-object body). Used by EmbeddingUtil's OPENAI
	/// branch so the raw response reaches its own openaiResponse parse code.
	public static <T> T postJSON(Class<T> cls, WebTarget resource, String authorizationToken, String jsonText, MediaType responseType){
		Builder bld = getRequestBuilder(resource).accept(responseType);
		if(authorizationToken != null) {
			bld.header("api-key", authorizationToken);
		}
		Response response = bld.post(Entity.json(jsonText));

		T outObj = null;
		if(response != null) {
			if(response.getStatus() == 200){
				outObj = response.readEntity(cls);
			}
			else {
				logger.warn("Received response: " + response.getStatus() + " for " + resource.getUri());
				logger.warn(response.readEntity(String.class));
			}
		}
		else {
			logger.warn("Null response");
		}
		return outObj;
	}
	
	public static <T> T post(Class<T> cls, WebTarget resource, Object object, MediaType responseType){
		return post(cls, resource, null, object, responseType);
	}
	
	public static <T> T post(Class<T> cls, WebTarget resource, String authorizationToken, Object object, MediaType responseType){
		Builder bld = getRequestBuilder(resource).accept(responseType);
		//logger.info(resource.getUri() + " -- " + authorizationToken);
		if(authorizationToken != null) {
			bld.header("api-key", authorizationToken);
		}
		Response response = bld.post(Entity.entity(object, MediaType.APPLICATION_JSON_TYPE));

		T outObj = null;
		if(response != null) {
			if(response.getStatus() == 200){
				String json = response.readEntity(String.class);
				outObj = JSONUtil.importObject(json, cls);
				//outObj = response.readEntity(cls);
			}
			else {
				logger.warn("Received response: " + response.getStatus() + " for " + resource.getUri());
				logger.warn(response.readEntity(String.class));
			}
		}
		else {
			logger.warn("Null response");
		}
		return outObj;
	}
	
	public static <T> T get(Class<T> cls, WebTarget resource, String authorizationToken, MediaType responseType){
		Builder bld = getRequestBuilder(resource).accept(responseType);
		if(authToken == null && authorizationToken != null) {
			bld.header("Authorization", "Bearer " + new String(authToken));
		}
		Response response = bld.get();

		T outObj = null;
		if(response != null) {
			if(response.getStatus() == 200){
				outObj = response.readEntity(cls);
			}
			else {
				logger.warn("Received response: " + response.getStatus() + " for " + resource.getUri());
				logger.warn(response.readEntity(String.class));
			}
		}
		else {
			logger.warn("Null response");
		}
		return outObj;
	}
	
	public static BaseRecord postToRecord(String modelName, WebTarget resource, String authZ, String json, MediaType responseType) {
		BaseRecord outObj = null;
		try {
			Builder bld = getRequestBuilder(resource).accept(responseType);
	
			if (authZ != null) {
				bld.header("api-key", authZ);
			}
			Response response = bld.post(Entity.entity(json, MediaType.APPLICATION_JSON_TYPE));
	
			
			if (response != null) {
				if (response.getStatus() == 200) {
	
					String ser = response.readEntity(String.class);
					outObj = RecordFactory.importRecord(modelName, ser);
				} else {
					logger.warn("Received response: " + response.getStatus() + " for " + resource.getUri());
					logger.warn(response.readEntity(String.class));
				}
			} else {
				logger.warn("Null response");
			}
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		return outObj;
	}
	
	public static CompletableFuture<HttpResponse<Stream<String>>> postToRecordAndStream(String url, String authorizationToken, String json) {
		return postToRecordAndStream(url, authorizationToken, json, null);
	}

	/// Tier B (LiteLLM/Langfuse) header-injection overload. extraHeaders is a PER-CALL method
	/// parameter — deliberately NOT a field (instance or static) on ClientUtil — so no request can
	/// mutate header state that a concurrent request on another thread observes. The url and its
	/// headers travel together as arguments of this single call, so there is no torn url/header pair.
	/// The 3-arg overload delegates here with null, preserving byte-identical behavior for every
	/// non-OPENAI_COMPAT caller. extraHeaders is applied AFTER the fixed headers (Accept,
	/// Content-Type, Authorization).
	public static CompletableFuture<HttpResponse<Stream<String>>> postToRecordAndStream(String url, String authorizationToken, String json, Map<String,String> extraHeaders) {

	    HttpClient streamClient = HttpClient.newBuilder()
	            .version(HttpClient.Version.HTTP_1_1)  // Important for SSE
	            .connectTimeout(Duration.ofSeconds(10))
	            .build();

	    /// Register for centralized shutdown
	    String clientKey = "clientUtil.stream-" + System.nanoTime();
	    LLMConnectionManager.registerClient(clientKey, streamClient);

		HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("Accept", "text/event-stream")
				.version(HttpClient.Version.HTTP_1_1)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json));
		if (authorizationToken != null && !authorizationToken.isEmpty()) {
			reqBuilder.header("Authorization", "Bearer " + authorizationToken);
		}
		if (extraHeaders != null) {
			for (Map.Entry<String,String> h : extraHeaders.entrySet()) {
				if (h.getKey() != null && h.getValue() != null) {
					reqBuilder.header(h.getKey(), h.getValue());
				}
			}
		}
		HttpRequest request = reqBuilder.build();

		return streamClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
			.whenComplete((result, error) -> {
				LLMConnectionManager.unregisterClient(clientKey);
			});
	}
	
	
	public static WebTarget getResource(String path){
		return getClient().target(path);
	}
	public static Builder getRequestBuilder(WebTarget resource){

		Builder b = resource.request();
		for(NewCookie ck : cookies){
			b.cookie(ck.getName(),ck.getValue());
		}
		if(authToken != null){
			b.header("Authorization", "Bearer " + new String(authToken));
		}
		/*
		b.property(ClientProperties.CONNECT_TIMEOUT, 10000);
		b.property(ClientProperties.READ_TIMEOUT, 360000);
		*/
		return b;
	}
	public static Response getResponse(String appUrl){
		return getResponse(appUrl,MediaType.APPLICATION_JSON_TYPE);
	}
	public static Response getResponse(String appUrl,MediaType type){
		WebTarget webResource = getResource(appUrl);
		
		
		return webResource.request().accept(type).get(Response.class);
	}
	
}
