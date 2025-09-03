
package org.cote.rest.services;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.analysis.FaceAnalysis;
import org.cote.accountmanager.analysis.FaceRequest;
import org.cote.accountmanager.analysis.FaceResponse;
import org.cote.accountmanager.analysis.FaceResult;
import org.cote.accountmanager.io.SystemTaskAgent;
import org.cote.accountmanager.olio.OlioTaskAgent;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.JSONUtil;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

@DeclareRoles({"admin","user"})
@Path("/word")
public class WordService {

	@Context
	ServletContext context;
	
	@Context
	SecurityContext securityCtx;
	
	private static final Logger logger = LogManager.getLogger(WordService.class);

	@RolesAllowed({"admin","user"})
	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response analyzeWord(@Context HttpServletRequest request){

		return Response.status(200).entity("Ping").build();
	}	

	@RolesAllowed({"admin","user"})
	@POST
	@Path("/analyze")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response analyzeFace(String json, @Context HttpServletRequest request){
		// logger.info("Received face request");
		WordRequest req = JSONUtil.importObject(json, WordRequest.class);
		WordResponse fr = null;
		boolean defer = Boolean.parseBoolean(context.getInitParameter("task.defer.remote"));
		/*
		if (defer) {
			BaseRecord task = FaceAnalysis.createTaskRequest(req);
			BaseRecord rtask = SystemTaskAgent.executeTask(task);
			if (rtask != null) {
				String resp = rtask.get("taskModelData");
				if (resp != null) {
					fr = JSONUtil.importObject(resp, FaceResponse.class);
				} else {
					logger.error("Task response was null");
				}
			}
		}
		else {
			fr = FaceAnalysis.postFaceRequest(req, "http://localhost:8003", "analyze");
		}
		*/
		fr = postWordRequest(req, "http://localhost:8004", "score");
		return Response.status((fr != null ? 200 : 404)).entity(fr != null ? JSONUtil.exportObject(fr) : null).build();
	}

	private static synchronized WordResponse postWordRequest(WordRequest req, String server, String apiName) {

		WordResponse frep = null;

		try {
			long start = System.currentTimeMillis();
			frep = ClientUtil.post(WordResponse.class, ClientUtil.getResource(server + "/" + apiName), null, req, MediaType.APPLICATION_JSON_TYPE);
		}
		catch(ProcessingException e) {
			logger.error(e);
		}
		return frep;
	}

	
	
}

class WordRequest{
	private List<String> phrases = new ArrayList<>();
	public WordRequest() {
		
	}
	public List<String> getPhrases() {
		return phrases;
	}
	public void setPhrases(List<String> phrases) {
		this.phrases = phrases;
	}
	
}

class WordResponse{
	private List<WordResponseScore> scores = new ArrayList<>();
	public WordResponse() {
		
	}
	public List<WordResponseScore> getScores() {
		return scores;
	}
	public void setScores(List<WordResponseScore> scores) {
		this.scores = scores;
	}

	
}

class WordResponseScore{
	private String phrase = null;
	private double score = 0.0;
	public WordResponseScore() {
		
	}

	public String getPhrase() {
		return phrase;
	}

	public void setPhrase(String phrase) {
		this.phrase = phrase;
	}

	public double getScore() {
		return score;
	}
	public void setScore(double score) {
		this.score = score;
	}
	
}



