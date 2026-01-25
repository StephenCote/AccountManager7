package org.cote.rest.services;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.CharacterUtil;
import org.cote.accountmanager.olio.EthnicityEnumType;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileComparison;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.StatisticsUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@DeclareRoles({"admin","user"})
@Path("/olio")
public class OlioService {

	private static final Logger logger = LogManager.getLogger(OlioService.class);
	
	@Context
	ServletContext context;
	
	@RolesAllowed({"user"})
	@GET
	@Path("/{type:[A-Za-z\\.]+}/{objectId:[0-9A-Za-z\\-]+}/narrate")
	@Produces(MediaType.APPLICATION_JSON)
	public Response narrateCharacter(@PathParam("type") String type, @PathParam("objectId") String objectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		/// Need to clear all the caches because the narrative will get loaded in the population lists
		///
		CacheService.clearCaches();
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
		//q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID, "narrative"});
		q.planMost(true);
		BaseRecord a1 = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		BaseRecord n1 = null;
		if(a1 != null) {
			List<BaseRecord> nl = NarrativeUtil.getCreateNarrative(octx, Arrays.asList(new BaseRecord[] {a1}), "random");
			if(nl.size() > 0) {
				n1 = nl.get(0);
			}
			else {
				logger.warn("Narrative not found for " + objectId);
			}
		
		}
		else {
			logger.warn("Not found: " + objectId);
		}
		return Response.status((n1 != null ? 200 : 404)).entity((n1 != null ? n1.toFullString() : null)).build();
	}
	
	@RolesAllowed({"user"})
	@GET
	@Path("/randomImageConfig")
	@Produces(MediaType.APPLICATION_JSON)
	public Response randomImageConfig(@Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord sdConfig = SDUtil.randomSDConfig();
		sdConfig.setValue("imageSetting", NarrativeUtil.getRandomSetting());
		sdConfig.setValue("imageAction", NarrativeUtil.randomVerb());
		return Response.status(200).entity(sdConfig.toFullString()).build();
	}

	@RolesAllowed({"user"})
	@POST
	@Path("/{type:[A-Za-z\\.]+}/{objectId:[0-9A-Za-z\\-]+}/reimage")
	@Produces(MediaType.APPLICATION_JSON)
	public Response reimageWithConfig(String json, @PathParam("type") String type, @PathParam("objectId") String objectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord imp = JSONUtil.importObject(json,  LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		if(imp == null) {
			logger.error("Invalid config");
			return Response.status(200).entity(null).build();
		}
		if(imp.get("model") == null) {
			imp.setValue("model", context.getInitParameter("sd.model"));
		}
		if(imp.get("refinerModel") == null) {
			imp.setValue("refinerModel", context.getInitParameter("sd.refinerModel"));
		}

		SDUtil sdu = new SDUtil(SDAPIEnumType.valueOf(context.getInitParameter("sd.server.apiType")), context.getInitParameter("sd.server"));
		sdu.setDeferRemote(Boolean.parseBoolean(context.getInitParameter("task.defer.remote")));

		if(type.equals(ModelNames.MODEL_DATA)) {
			Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, objectId);
			q.planMost(true);
			BaseRecord sourceData = IOSystem.getActiveContext().getAccessPoint().find(user, q);
			if(sourceData == null) {
				logger.error("Data record not found: " + objectId);
				return Response.status(404).entity(null).build();
			}
			String groupPath = sourceData.get(FieldNames.FIELD_GROUP_PATH);
			if(groupPath == null) {
				long groupId = sourceData.get(FieldNames.FIELD_GROUP_ID);
				if(groupId > 0L) {
					try {
						BaseRecord grp = IOSystem.getActiveContext().getReader().read(ModelNames.MODEL_GROUP, groupId);
						if(grp != null) {
							groupPath = grp.get(FieldNames.FIELD_PATH);
						}
					} catch(ReaderException e) {
						logger.error(e);
					}
				}
			}
			String name = sourceData.get(FieldNames.FIELD_NAME);
			List<BaseRecord> images = sdu.createImage(user, groupPath, imp, name, 1, imp.get("hires"), imp.get("seed"));
			if(images.size() > 0) {
				return Response.status(200).entity(images.get(0).toFullString()).build();
			}
			return Response.status(200).entity(null).build();
		}

		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
		q.planMost(true);
		BaseRecord a1 = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if(a1 != null) {
			String verb = imp.get("imageAction");
			String bodyStyle = imp.get("bodyStyle");
			String setting = imp.get("imageSetting");
			sdu.generateSDImages(octx, Arrays.asList(a1), imp, setting, "((DEPRECATED))", bodyStyle, (verb != null && verb.length() > 0 ? verb : null), 1, false, imp.get("hires"), imp.get("seed"));
			octx.scanNestedGroups(octx.getWorld(), OlioFieldNames.FIELD_GALLERY, true);
		}
		BaseRecord oi = a1.get("profile.portrait");

		return Response.status(200).entity(oi.toFullString()).build();
	}
	
	
	@RolesAllowed({"user"})
	@GET
	@Path("/{type:[A-Za-z\\.]+}/{objectId:[0-9A-Za-z\\-]+}/reimage/{hires:[A-Za-z\\\\.]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response reimageCharacter(@PathParam("type") String type, @PathParam("objectId") String objectId, @PathParam("hires") boolean hires, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		// sdu.generateSDImages(octx, Arrays.asList(char1, char2), cmd.getOptionValue("setting"), cmd.getOptionValue("style"), cmd.getOptionValue("bodyStyle"), Integer.parseInt(cmd.getOptionValue("reimage")), cmd.hasOption("export"), cmd.hasOption("hires"), seed);

		BaseRecord a1 = null;
		SDUtil sdu = new SDUtil(SDAPIEnumType.valueOf(context.getInitParameter("sd.server.apiType")), context.getInitParameter("sd.server"));

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
		q.planMost(true);
		a1 = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if(a1 != null) {
			sdu.generateSDImages(octx, Arrays.asList(a1), "random", "professional photograph", "full body", null, 1, false, hires, -1);
			octx.scanNestedGroups(octx.getWorld(), OlioFieldNames.FIELD_GALLERY, true);
		}
		BaseRecord oi = a1.get("profile.portrait");

		return Response.status(200).entity(oi.toFullString()).build();
	}

	@RolesAllowed({"user"})
	@POST
	@Path("/apparel/{objectId:[0-9A-Za-z\\-]+}/reimage")
	@Produces(MediaType.APPLICATION_JSON)
	public Response reimageApparel(String json, @PathParam("objectId") String objectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord imp = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		if(imp == null) {
			logger.error("Invalid config");
			return Response.status(400).entity(null).build();
		}
		if(imp.get("model") == null) {
			imp.setValue("model", context.getInitParameter("sd.model"));
		}
		if(imp.get("refinerModel") == null) {
			imp.setValue("refinerModel", context.getInitParameter("sd.refinerModel"));
		}

		SDUtil sdu = new SDUtil(SDAPIEnumType.valueOf(context.getInitParameter("sd.server.apiType")), context.getInitParameter("sd.server"));
		sdu.setDeferRemote(Boolean.parseBoolean(context.getInitParameter("task.defer.remote")));

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_APPAREL, FieldNames.FIELD_OBJECT_ID, objectId);
		q.planMost(true);
		BaseRecord apparel = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if(apparel == null) {
			logger.error("Apparel record not found: " + objectId);
			return Response.status(404).entity(null).build();
		}
		List<BaseRecord> wearables = apparel.get(OlioFieldNames.FIELD_WEARABLES);
		if(wearables == null || wearables.isEmpty()) {
			logger.error("Apparel has no wearables: " + objectId);
			return Response.status(400).entity("{\"error\":\"Apparel has no wearables\"}").build();
		}

		String groupPath = apparel.get(FieldNames.FIELD_GROUP_PATH);
		if(groupPath == null) {
			groupPath = "~/Gallery/Apparel";
		}

		boolean hires = imp.get("hires") != null ? (Boolean)imp.get("hires") : false;
		long seed = imp.get("seed") != null ? ((Number)imp.get("seed")).longValue() : -1;

		List<BaseRecord> images = sdu.generateMannequinImages(user, groupPath, apparel, imp, hires, seed);

		if(images.isEmpty()) {
			return Response.status(200).entity("[]").build();
		}

		// Update apparel gallery with new images
		List<BaseRecord> gallery = apparel.get("gallery");
		if(gallery == null) {
			gallery = new java.util.ArrayList<>();
		}
		gallery.addAll(images);
		apparel.setValue("gallery", gallery);
		IOSystem.getActiveContext().getAccessPoint().update(user, apparel);

		// Return all generated images as JSON array
		StringBuilder sb = new StringBuilder("[");
		for(int i = 0; i < images.size(); i++) {
			if(i > 0) sb.append(",");
			sb.append(images.get(i).toFullString());
		}
		sb.append("]");

		return Response.status(200).entity(sb.toString()).build();
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/roll")
	@Produces(MediaType.APPLICATION_JSON)
	public Response rollCharacter(@Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord a1 = rollCharacter(user, (Math.random() <= 0.5 ? "male" : "female"));
		return Response.status((a1 != null ? 200 : 404)).entity((a1 != null ? a1.toFullString() : null)).build();
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/roll/{gender:[a-z]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response rollCharacterGender(@PathParam("gender") String gen, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		String gender = gen;
		if(!gender.equals("male") && !gender.equals("female")) {
			gender = "male";
		}
		BaseRecord a1 = rollCharacter(user, gender);
		return Response.status((a1 != null ? 200 : 404)).entity((a1 != null ? a1.toFullString() : null)).build();
	}
	
	private BaseRecord rollCharacter(BaseRecord user, String gender) {
		Factory f = IOSystem.getActiveContext().getFactory();
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		BaseRecord world = octx.getWorld();
		BaseRecord parWorld = world.get(OlioFieldNames.FIELD_BASIS);
		if(parWorld == null) {
			logger.error("A basis world is required");
			return null;
		}

		BaseRecord namesDir = parWorld.get(OlioFieldNames.FIELD_NAMES);
		BaseRecord surDir = parWorld.get(OlioFieldNames.FIELD_SURNAMES);
		
		Query fnq = QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, namesDir.get(FieldNames.FIELD_ID));
		fnq.field(FieldNames.FIELD_GENDER, gender.substring(0, 1).toUpperCase());
		String firstName = OlioUtil.randomSelectionName(user, fnq);
		String middleName = OlioUtil.randomSelectionName(user, fnq);
		String lastName = OlioUtil.randomSelectionName(user, QueryUtil.createQuery(ModelNames.MODEL_CENSUS_WORD, FieldNames.FIELD_GROUP_ID, surDir.get(FieldNames.FIELD_ID)));	

		BaseRecord a1 = null;
		try{
			a1 = f.newInstance(OlioModelNames.MODEL_CHAR_PERSON, user, null, null);
		
			a1.set(FieldNames.FIELD_FIRST_NAME, firstName);
			a1.set(FieldNames.FIELD_MIDDLE_NAME, middleName);
			a1.set(FieldNames.FIELD_LAST_NAME, lastName);
			a1.set(FieldNames.FIELD_NAME, firstName + " " + middleName + " " + lastName);

			a1.set(FieldNames.FIELD_GENDER, gender);
			a1.set("age", (new Random()).nextInt(7, 70));
			a1.set("alignment", OlioUtil.getRandomAlignment());
			
			StatisticsUtil.rollStatistics(a1.get(OlioFieldNames.FIELD_STATISTICS), (int)a1.get("age"));
			ProfileUtil.rollPersonality(a1.get(FieldNames.FIELD_PERSONALITY));
			a1.set(OlioFieldNames.FIELD_RACE, CharacterUtil.randomRaceType().stream().map(k -> k.toString()).collect(Collectors.toList()));
			a1.set("ethnicity", Arrays.asList(new String[] {EthnicityEnumType.ZERO.toString()}));
			CharacterUtil.setStyleByRace(null, a1);
			List<BaseRecord> apps = a1.get("store.apparel");
			BaseRecord app = ApparelUtil.randomApparel(null, a1);
			app.set(FieldNames.FIELD_NAME, "Primary Apparel");
			app.set(OlioFieldNames.FIELD_IN_USE, true);
			List<BaseRecord> wears = app.get(OlioFieldNames.FIELD_WEARABLES);
			wears.forEach(w -> {
				w.setValue(OlioFieldNames.FIELD_IN_USE, true);
			});
			apps.add(app);
		}
		catch(ModelNotFoundException | FieldException | ValueException | FactoryException e) {
			logger.error(e);
		}
		return a1;
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/profile/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response personalityProfile(@PathParam("objectId") String objectId, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
		q.planMost(true);
		BaseRecord person = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if(person == null){
			return Response.status(404).entity(null).build();
		}
		PersonalityProfile prof = ProfileUtil.getProfile(octx, person);
		return Response.status(200).entity(JSONUtil.exportObject(prof)).build();
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/compare/{objectId1:[0-9A-Za-z\\-]+}/{objectId2:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response profileComparison(@PathParam("objectId1") String objectId1, @PathParam("objectId2") String objectId2, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		Query q1 = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId1);
		q1.planMost(true);
		BaseRecord person1 = IOSystem.getActiveContext().getAccessPoint().find(user, q1);
		Query q2 = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId2);
		q2.planMost(true);
		BaseRecord person2 = IOSystem.getActiveContext().getAccessPoint().find(user, q2);
		if(person1 == null || person2 == null){
			return Response.status(404).entity(null).build();
		}
		PersonalityProfile prof1 = ProfileUtil.getProfile(octx, person1);
		PersonalityProfile prof2 = ProfileUtil.getProfile(octx, person2);
		ProfileComparison comp = new ProfileComparison(octx, prof1, prof2);
		return Response.status(200).entity(JSONUtil.exportObject(comp)).build();
	}

}
