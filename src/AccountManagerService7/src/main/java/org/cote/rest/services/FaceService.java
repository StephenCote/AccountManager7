
package org.cote.rest.services;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.tools.VoiceRequest;
import org.cote.accountmanager.tools.VoiceResponse;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

@DeclareRoles({"admin","user"})
@Path("/face")
public class FaceService {

	@Context
	ServletContext context;
	
	@Context
	SecurityContext securityCtx;
	
	private static final Logger logger = LogManager.getLogger(FaceService.class);

	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response analyzeFace(@Context HttpServletRequest request){

		return Response.status(200).entity("Ping").build();
	}	

	@POST
	@Path("/analyze")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response analyzeFace(String json, @Context HttpServletRequest request){
		logger.info("Received face request");
		FaceRequest req = JSONUtil.importObject(json, FaceRequest.class);
		FaceResponse fr = postFaceRequest(req, "http://localhost:8003", "analyze");
		logger.info("Received face response: " + JSONUtil.exportObject(fr));
		return Response.status((fr != null ? 200 : 404)).entity(fr != null ? JSONUtil.exportObject(fr) : null).build();
	}

	private synchronized FaceResponse postFaceRequest(FaceRequest req, String server, String apiName) {

		FaceResponse voice = null;

		logger.info("Posting voice request to " + server + "/" + apiName + "/");
		try {
			voice = ClientUtil.post(FaceResponse.class, ClientUtil.getResource(server + "/" + apiName + "/"), null, req, MediaType.APPLICATION_JSON_TYPE);


		}
		catch(ProcessingException e) {
			logger.error(e);
		}
		return voice;
	}
	
	
}
class FaceLocation{
	private int top = 0;
	private int right = 0;
	private int bottom = 0;
	private int left = 0;

	public FaceLocation(int top, int right, int bottom, int left) {
		this.top = top;
		this.right = right;
		this.bottom = bottom;
		this.left = left;
	}

	public int getTop() {
		return top;
	}

	public void setTop(int top) {
		this.top = top;
	}

	public int getRight() {
		return right;
	}

	public void setRight(int right) {
		this.right = right;
	}

	public int getBottom() {
		return bottom;
	}

	public void setBottom(int bottom) {
		this.bottom = bottom;
	}

	public int getLeft() {
		return left;
	}

	public void setLeft(int left) {
		this.left = left;
	}
	
}
class FaceResponse{
	private FaceLocation face_location = null;
	private int age = 0;
	private String gender = null;
	private String dominant_emotion = null;
	public FaceResponse() {
		
	}
	public FaceLocation getFace_location() {
		return face_location;
	}
	public void setFace_location(FaceLocation face_location) {
		this.face_location = face_location;
	}
	public int getAge() {
		return age;
	}
	public void setAge(int age) {
		this.age = age;
	}
	public String getGender() {
		return gender;
	}
	public void setGender(String gender) {
		this.gender = gender;
	}
	public String getDominant_emotion() {
		return dominant_emotion;
	}
	public void setDominant_emotion(String dominant_emotion) {
		this.dominant_emotion = dominant_emotion;
	}
	
}
class FaceRequest{
	private String image_data = null;
	public FaceRequest() {
		
	}
	public String getImage_data() {
		return image_data;
	}
	public void setImage_data(String image_data) {
		this.image_data = image_data;
	}
	
	
}
