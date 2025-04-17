package org.cote.accountmanager.util;

import java.net.ConnectException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;

import com.fasterxml.jackson.core.util.JacksonFeature;


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
		
		
		client = cb
			.build()
			.register(JacksonFeature.class)
			/*
			.connectTimeout(360, TimeUnit.SECONDS)
			.readTimeout(360, TimeUnit.SECONDS)
			.property(ClientProperties.CONNECT_TIMEOUT, 360000)
			.property(ClientProperties.READ_TIMEOUT, 360000)
			*/
		;

		return client;
	}
	
	public static <T> T postJSON(Class<T> cls, WebTarget resource, String jsonText, MediaType responseType){
		Response response = getRequestBuilder(resource).accept(responseType).post(Entity.json(jsonText));

		T outObj = null;
		if(response != null) {
			if(response.getStatus() == 200){
				outObj = response.readEntity(cls);
			}
			else {
				logger.warn("Received response: " + response.getStatus() + " for " + resource.getUri());
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
			Builder bld = ClientUtil.getRequestBuilder(resource).accept(responseType);
	
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
