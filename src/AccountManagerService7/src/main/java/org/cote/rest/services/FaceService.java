
package org.cote.rest.services;

import java.util.ArrayList;
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

import com.fasterxml.jackson.annotation.JsonProperty;

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
	public FaceLocation() {
		
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
	private List<FaceResult> results = null;
	private String message = null;
	public FaceResponse() {
		
	}
	
	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public List<FaceResult> getResults() {
		return results;
	}
	public void setResults(List<FaceResult> results) {
		this.results = results;
	}
	
}
class FaceRegion{
	private int x = 0;
	private int y = 0;
	private int w = 0;
	private int h = 0;
	private List<Integer> left_eye = new ArrayList<>();
	private List<Integer> right_eye = new ArrayList<>();
	public FaceRegion() {
		
	}
	public int getX() {
		return x;
	}
	public void setX(int x) {
		this.x = x;
	}
	public int getY() {
		return y;
	}
	public void setY(int y) {
		this.y = y;
	}
	public int getW() {
		return w;
	}
	public void setW(int w) {
		this.w = w;
	}
	public int getH() {
		return h;
	}
	public void setH(int h) {
		this.h = h;
	}
	public List<Integer> getLeft_eye() {
		return left_eye;
	}
	public void setLeft_eye(List<Integer> left_eye) {
		this.left_eye = left_eye;
	}
	public List<Integer> getRight_eye() {
		return right_eye;
	}
	public void setRight_eye(List<Integer> right_eye) {
		this.right_eye = right_eye;
	}
	
}
class FaceResult{
	private FaceLocation face_location = null;
	private int age = 0;
	private String dominant_gender = null;
	private String dominant_emotion = null;
	private String dominant_race = null;
	private FaceEmotionScores emotion_scores = null;
	private FaceRaceScores race_scores = null;
	private FaceGenderScores gender_scores = null;
	private FaceRegion region = null;
	private double face_confidence = 0.0;
	public FaceResult() {
		
	}
	
	public String getDominant_race() {
		return dominant_race;
	}

	public void setDominant_race(String dominant_race) {
		this.dominant_race = dominant_race;
	}

	public FaceEmotionScores getEmotion_scores() {
		return emotion_scores;
	}

	public void setEmotion_scores(FaceEmotionScores emotion_scores) {
		this.emotion_scores = emotion_scores;
	}

	public FaceRegion getRegion() {
		return region;
	}

	public void setRegion(FaceRegion region) {
		this.region = region;
	}

	public double getFace_confidence() {
		return face_confidence;
	}

	public void setFace_confidence(double face_confidence) {
		this.face_confidence = face_confidence;
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

	public String getDominant_gender() {
		return dominant_gender;
	}
	public void setDominant_gender(String dominant_gender) {
		this.dominant_gender = dominant_gender;
	}

	public FaceRaceScores getRace_scores() {
		return race_scores;
	}
	public void setRace_scores(FaceRaceScores race_scores) {
		this.race_scores = race_scores;
	}
	public FaceGenderScores getGender_scores() {
		return gender_scores;
	}
	public void setGender_scores(FaceGenderScores gender_scores) {
		this.gender_scores = gender_scores;
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

class FaceEmotionScores{
	private double angry = 0.0;
	private double disgust = 0.0;
	private double fear = 0.0;
	private double happy = 0.0;
	private double sad = 0.0;
	private double surprise = 0.0;
	private double neutral = 0.0;
	public FaceEmotionScores() {
		
	}
	public double getAngry() {
		return angry;
	}
	public void setAngry(double angry) {
		this.angry = angry;
	}
	public double getDisgust() {
		return disgust;
	}
	public void setDisgust(double disgust) {
		this.disgust = disgust;
	}
	public double getFear() {
		return fear;
	}
	public void setFear(double fear) {
		this.fear = fear;
	}
	public double getHappy() {
		return happy;
	}
	public void setHappy(double happy) {
		this.happy = happy;
	}
	public double getSad() {
		return sad;
	}
	public void setSad(double sad) {
		this.sad = sad;
	}
	public double getSurprise() {
		return surprise;
	}
	public void setSurprise(double surprise) {
		this.surprise = surprise;
	}
	public double getNeutral() {
		return neutral;
	}
	public void setNeutral(double neutral) {
		this.neutral = neutral;
	}
	
}
class FaceRaceScores{
	private double asian = 0.0;
	private double indian = 0.0;
	private double black = 0.0;
	private double white = 0.0;
	@JsonProperty("middle eastern")
	private double middle_eastern = 0.0;
	@JsonProperty("latino hispanic")
	private double latino_hispanic = 0.0;
	public FaceRaceScores() {
		
	}
	public double getAsian() {
		return asian;
	}
	public void setAsian(double asian) {
		this.asian = asian;
	}
	public double getIndian() {
		return indian;
	}
	public void setIndian(double indian) {
		this.indian = indian;
	}
	public double getBlack() {
		return black;
	}
	public void setBlack(double black) {
		this.black = black;
	}
	public double getWhite() {
		return white;
	}
	public void setWhite(double white) {
		this.white = white;
	}
	public double getMiddle_eastern() {
		return middle_eastern;
	}
	public void setMiddle_eastern(double middle_eastern) {
		this.middle_eastern = middle_eastern;
	}
	public double getLatino_hispanic() {
		return latino_hispanic;
	}
	public void setLatino_hispanic(double latino_hispanic) {
		this.latino_hispanic = latino_hispanic;
	}
	
}
class FaceGenderScores{
	@JsonProperty("Woman")
	private double woman = 0.0;
	@JsonProperty("Man")
	private double man = 0.0;
	public FaceGenderScores() {
		
	}
	public double getWoman() {
		return woman;
	}
	public void setWoman(double woman) {
		this.woman = woman;
	}
	public double getMan() {
		return man;
	}
	public void setMan(double man) {
		this.man = man;
	}
	
}