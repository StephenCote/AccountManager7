package org.cote.rest.services;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RolesAllowed;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
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
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.StatisticsUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.service.util.ServiceUtil;

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
	@Path("/{type:[A-Za-z\\.]+}/{objectId:[0-9A-Za-z\\-]+}/reimage/{hires:[A-Za-z\\\\.]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response reimageCharacter(@PathParam("type") String type, @PathParam("objectId") String objectId, @PathParam("hires") boolean hires, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		// sdu.generateSDImages(octx, Arrays.asList(char1, char2), cmd.getOptionValue("setting"), cmd.getOptionValue("style"), cmd.getOptionValue("bodyStyle"), Integer.parseInt(cmd.getOptionValue("reimage")), cmd.hasOption("export"), cmd.hasOption("hires"), seed);
		BaseRecord i1 = null;
		BaseRecord a1 = null;
		SDUtil sdu = new SDUtil();

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
		q.planMost(true);
		a1 = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if(a1 != null) {
			sdu.generateSDImages(octx, Arrays.asList(a1), "random", "professional photograph", "full body", null, 1, false, hires, -1);
		}
		BaseRecord oi = a1.get("profile.portrait");

		return Response.status(200).entity(oi.toFullString()).build();
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
		
		BaseRecord a1 = null;
		try{
			a1 = f.newInstance(OlioModelNames.MODEL_CHAR_PERSON, user, null, null);
		
			a1.set(FieldNames.FIELD_FIRST_NAME, "Jay");
			a1.set(FieldNames.FIELD_MIDDLE_NAME, "Kippy");
			a1.set(FieldNames.FIELD_LAST_NAME, "Smith");
			a1.set(FieldNames.FIELD_NAME, "Jay Kippy Smith");

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
	
}
