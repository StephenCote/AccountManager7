/*******************************************************************************
 * Copyright (C) 2002, 2020 Stephen Cote Enterprises, LLC. All rights reserved.
 * Redistribution without modification is permitted provided the following conditions are met:
 *
 *    1. Redistribution may not deviate from the original distribution,
 *        and must reproduce the above copyright notice, this list of conditions
 *        and the following disclaimer in the documentation and/or other materials
 *        provided with the distribution.
 *    2. Products may be derived from this software.
 *    3. Redistributions of any form whatsoever must retain the following acknowledgment:
 *        "This product includes software developed by Stephen Cote Enterprises, LLC"
 *
 * THIS SOFTWARE IS PROVIDED BY STEPHEN COTE ENTERPRISES, LLC ``AS IS''
 * AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THIS PROJECT OR ITS CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, 
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY 
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *******************************************************************************/
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
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.tools.VoiceRequest;
import org.cote.accountmanager.tools.VoiceResponse;
import org.cote.accountmanager.util.ByteModelUtil;
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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

@DeclareRoles({"admin","user"})
@Path("/voice")
public class VoiceService {

	@Context
	ServletContext context;
	
	@Context
	SecurityContext securityCtx;
	
	private static final Logger logger = LogManager.getLogger(ListService.class);

	private static Set<String> synthesized = ConcurrentHashMap.newKeySet();
	private static Set<String> textToSpeech = ConcurrentHashMap.newKeySet();
	public static void clearCache() {
		synthesized.clear();
		textToSpeech.clear();
	}
	
	private static String getNormalizedVoice(String voice) {
		if(voice == null || voice.length() == 0 || voice.toLowerCase().equals("unknown")) {
			return voice;
		}
		
		ModelSchema ms = RecordFactory.getSchema(ModelNames.MODEL_VOICE);
		FieldSchema fs = ms.getFieldSchema("speaker");
		List<String> vcl = fs.getLimit().stream().map(v -> (String)v).filter(v -> v != null && v.toLowerCase().equals(voice.toLowerCase())).collect(Collectors.toList());
		if(vcl.size() > 0) {
			return vcl.get(0);
		}
		return voice;
	}
	
	private VoiceResponse getVoice(BaseRecord user, VoiceRequest req) {
		
		boolean appliedProfile = false;
		req.setSpeaker(getNormalizedVoice(req.getSpeaker()));
		if(req.getVoiceProfileId() != null) {
			Query q = QueryUtil.createQuery(ModelNames.MODEL_PROFILE, FieldNames.FIELD_OBJECT_ID, req.getVoiceProfileId());
			q.setRequest(new String[] {FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_VOICE});
			BaseRecord prof = IOSystem.getActiveContext().getAccessPoint().find(user, q);
			if(prof != null && prof.get(FieldNames.FIELD_VOICE) != null) {
				BaseRecord voice = prof.get(FieldNames.FIELD_VOICE);
				IOSystem.getActiveContext().getReader().populate(voice);
				String engine = voice.get("engine");
				String speaker = getNormalizedVoice(voice.get("speaker"));
				double speed = voice.get("speed");
				int speakerId = voice.get("speakerId");
				BaseRecord sample = voice.get("voiceSample");
				if(sample != null) {
					IOSystem.getActiveContext().getReader().populate(sample);
					try {
						req.setVoice_sample(ByteModelUtil.getValue(sample));
					} catch (ValueException | FieldException e) {
						logger.error(e);
					}
				}
				req.setSpeaker_id(speakerId);
				req.setSpeed(speed);
				req.setSpeaker(speaker);
				req.setEngine(engine);
				logger.info("Using Voice Profile ... " + voice.get(FieldNames.FIELD_NAME));
				// logger.info(JSONUtil.exportObject(req));
				appliedProfile = true;
			}
		}
		if(!appliedProfile && req.getVoiceSampleId() != null && req.getEngine().equals("xtts")) {
			logger.info("Using Voice Sample ID Reference ... " + req.getVoiceSampleId());
			Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, req.getVoiceSampleId());
			q.planMost(false);
			BaseRecord data = IOSystem.getActiveContext().getAccessPoint().find(user, q);
			if(data != null) {
				try {
					req.setVoice_sample(ByteModelUtil.getValue(data));
				} catch (ValueException | FieldException e) {
					logger.error(e);
				}
			}
			appliedProfile = true;
		}
		if(!appliedProfile && (req.getEngine() == null || req.getEngine().isEmpty())) {
			req.setEngine("piper");
			req.setSpeaker("en_GB-alba-medium");
			logger.info("Defaulting voice engine to piper and speaker to en_GB-alba-medium");
		}
		return IOSystem.getActiveContext().getVoiceUtil().getVoice(req);
	}
	
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/tts")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response textToSpeach(String json, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		
		
		VoiceRequest voiceReq = JSONUtil.importObject(json, VoiceRequest.class);
			
		if(voiceReq == null) {
			logger.error("Failed to parse chat request");
			return Response.status(400).entity("Failed to parse chat request").build();
		}
		
		String uid = voiceReq.getUid();
		if(uid == null || uid.length() == 0) {
			logger.error("Voice request uid is null or empty");
			return Response.status(400).entity("Failed to parse voice request").build();
		}

		if(textToSpeech.contains(uid)) {
			logger.info("Request already handled: " + uid);
			return Response.status(400).entity(null).build();
		}
		textToSpeech.add(uid);

		
		if(voiceReq.getAudio_sample() == null || voiceReq.getAudio_sample().length == 0) {
			logger.error("Audio sample is null or empty");
			return Response.status(400).entity("Failed to parse chat request").build();
		}

		VoiceResponse vr = IOSystem.getActiveContext().getVoiceUtil().getText(voiceReq);
		
		return Response.status((vr != null ? 200 : 404)).entity(vr != null ? JSONUtil.exportObject(vr) : null).build();
	}

	
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/{referenceId:[\\(\\)@%\\sa-zA-Z_0-9\\-\\.]+}")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response synthesizeVoice(String json, @PathParam("referenceId") String referenceId, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		
		String voiceName = "Voice - " + referenceId + ".mp3";
		BaseRecord dir = IOSystem.getActiveContext().getAccessPoint().make(user, ModelNames.MODEL_GROUP,  "~/Data/Synthesis", "DATA");
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_NAME, voiceName);
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.planMost(false);
		BaseRecord data = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if(data == null) {
			if(synthesized.contains(referenceId)) {
				logger.info("Voice already synthesized: " + voiceName);
				return Response.status(400).entity(null).build();
			}
			VoiceRequest voiceReq = JSONUtil.importObject(json, VoiceRequest.class);
			
			if(voiceReq == null) {
				logger.error("Failed to parse chat request");
				return Response.status(400).entity("Failed to parse chat request").build();
			}

			synthesized.add(referenceId);
			VoiceResponse vr = getVoice(user, voiceReq);
			if(vr != null && vr.getAudio().length > 0) {
				try {
					logger.info("Storing synthesized voice with " + vr.getAudio().length + " bytes as " + voiceName);
					data = RecordFactory.newInstance(ModelNames.MODEL_DATA);
					data.set(FieldNames.FIELD_NAME, voiceName);
					data.set(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
					data.set(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
					data.set(FieldNames.FIELD_CONTENT_TYPE, "audio/mpeg3");
					ByteModelUtil.setValue(data,  vr.getAudio());
					data = IOSystem.getActiveContext().getAccessPoint().create(user, data);
				}
				catch(ModelNotFoundException | FieldException | ValueException e) {
					logger.error("Failed to create data record for voice", e);
					return Response.status(500).entity("Failed to create data record for voice").build();
				}
			}
			else {
				logger.error("Failed to synthesize voice for " + referenceId);
				synthesized.remove(referenceId);
				return Response.status(500).entity("Failed to synthesize voice for " + referenceId).build();
			}
			
		}
		return Response.status((data != null ? 200 : 404)).entity(data != null ? data.toFullString() : null).build();
	}


	@RolesAllowed({"user"})
	@GET
	@Path("/{objectId:[0-9A-Za-z\\-]+}/count")
	@Produces(MediaType.APPLICATION_JSON)
	public Response countObjects(@PathParam("type") String type, @PathParam("objectId") String objectId, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		Query q = QueryUtil.buildQuery(user, type, objectId, null, 0L, 0);
		if(q == null) {
			logger.error("Invalid query object for " + type + " " + objectId);
			return Response.status(404).entity(null).build();	
		}
		int count = IOSystem.getActiveContext().getAccessPoint().count(user, q);
		return Response.status(200).entity(count).build();
	}
	
	
}
