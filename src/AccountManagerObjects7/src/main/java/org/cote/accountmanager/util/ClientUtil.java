package org.cote.accountmanager.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

import com.fasterxml.jackson.core.util.JacksonFeature;


public class ClientUtil {
	
	private static ArrayList<NewCookie> cookies = new ArrayList<NewCookie>();
	
	private static Client client = null;
	private static String cachePath = "./cache";
	private static String authToken = null;
	public static final Logger logger = LogManager.getLogger(ClientUtil.class);

	public static void clearCookies(){
		cookies.clear();
	}
	public static void setCookies(Map<String,NewCookie> in_cookies){
		clearCookies();
		for(String ck : in_cookies.keySet()){
			cookies.add(in_cookies.get(ck));
		}
	}
	
	public static void setCachePath(String s) {
		cachePath = s;
	}
	public static String getCachePath(){
		return cachePath;
	}

	public static Client getClient(){
		if(client != null) return client;
		client = ClientBuilder
			.newClient()
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
	
	
	public static <T> T post(Class<T> cls, WebTarget resource, Object object, MediaType responseType){
		Response response = getRequestBuilder(resource).accept(responseType).post(Entity.entity(object, MediaType.APPLICATION_JSON_TYPE));

		T outObj = null;
		if(response != null) {
			if(response.getStatus() == 200){
				outObj = response.readEntity(cls);
			}
			else {
				logger.warn("Received response: " + response.getStatus());
			}
		}
		else {
			logger.warn("Null response");
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
