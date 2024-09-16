package org.cote.accountmanager.olio.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.CharacterUtil;
import org.cote.accountmanager.olio.Decks;
import org.cote.accountmanager.olio.EventUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.EventEnumType;

/// Construct maps in the data.geoLocation model using Military Grid Reference System (MGRS)
/// (1) Grid Zone (GZD) - 6d-wide universal transverse mercator (UTM) zones, 1 - 60, Identified by northermost latitude band and latitude band letter  E.G.: 30K (in the middle of the south Atlantic, BTW)
/// (2) 100Km square identification (kident).  Identified by column letter (A–Z, omitting I and O) and row letter (A–V, omitting I and O).  E.G.: AB
/// (3) Numerical location composed of Eastings and Northings, which represent the number of meters from the western-most boundary and northern-most boundary respectively
///
/// For Random Olio GridSquare Maps, the following currently apply:
///   - The GZD is hardcoded as 30K
///   - The kident is randomly chosen when constructing a region
///   - The kident area MAY NOT REPRESENT 100Km - it will be the product of mapWidth1km x mapHeight1km. Depending on the type of map being constructed, it may be desirable to cut this down, such as to 50.  However, note that the naming convention will still imply 100km square, while the area will correctly reflect the adjusted size.
///   - The numerical locations are constructed only with 100m square blocks at the moment, with the intent that they may be subdivided as needed
///
public class GridSquareLocationInitializationRule extends CommonContextRule implements IOlioContextRule {
	public static final Logger logger = LogManager.getLogger(GridSquareLocationInitializationRule.class);
	
	public GridSquareLocationInitializationRule() {

	}
	
	@Override
	public void pregenerate(OlioContext context) {
		GeoLocationUtil.prepareMapGrid(context);
		GeoLocationUtil.checkK100(context);
	}
	
	@Override
	public BaseRecord generate(OlioContext ctx) {
		
		List<BaseRecord> events = new ArrayList<>(); 
		BaseRecord world = ctx.getWorld();
		BaseRecord parWorld = world.get(OlioFieldNames.FIELD_BASIS);
		BaseRecord locDir = world.get(FieldNames.FIELD_LOCATIONS);
		BaseRecord eventsDir = world.get(OlioFieldNames.FIELD_EVENTS);
		if(parWorld == null) {
			logger.error("A basis world is required");
			return null;
		}
		BaseRecord root = EventUtil.getRootEvent(ctx);
		if(root != null) {
			// logger.info("Region is already generated");
			return root;
		}
		
		logger.info("Generate region ...");
		IOSystem.getActiveContext().getReader().populate(parWorld, 2);
		BaseRecord[] locs = new BaseRecord[0];
		for(IOlioContextRule rule : ctx.getConfig().getContextRules()) {
			locs = rule.selectLocations(ctx);
			if(locs != null && locs.length > 0) {
				break;
			}
		}
		if(locs == null || locs.length == 0) {
			locs = (new RandomLocationInitializationRule()).selectLocations(ctx);
		}
		List<BaseRecord> locations = new ArrayList<>();
		for(BaseRecord l : locs) {
			locations.add(OlioUtil.cloneIntoGroup(l, locDir));
		}
		if(locations.isEmpty()){
			logger.error("Expected a positive number of locations");
			logger.info(locDir.toFullString());
			return null;
		}
		
		try{
			
			int cloc = IOSystem.getActiveContext().getRecordUtil().updateRecords(locations.toArray(new BaseRecord[0]));
			if(cloc != locations.size()) {
				logger.error("Failed to create locations");
				return null;
			}
			for(BaseRecord loc: locations) {

				String locName = loc.get(FieldNames.FIELD_NAME);
				loc.set(FieldNames.FIELD_DESCRIPTION, null);
				BaseRecord event = null;

				if(root == null) {
					ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, eventsDir.get(FieldNames.FIELD_PATH));
					root = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_EVENT, ctx.getOlioUser(), null, plist);
					root.set(FieldNames.FIELD_NAME, "Construct Region " + locName);
					root.set(FieldNames.FIELD_LOCATION, loc);
					root.set(FieldNames.FIELD_TYPE, EventEnumType.CONSTRUCT);
					root.set(OlioFieldNames.FIELD_EVENT_START, ctx.getConfig().getBaseInceptionDate());
					root.set(OlioFieldNames.FIELD_EVENT_PROGRESS, ctx.getConfig().getBaseInceptionDate());
					root.set(OlioFieldNames.FIELD_EVENT_END, ctx.getConfig().getBaseInceptionDate());
					if(!IOSystem.getActiveContext().getRecordUtil().updateRecord(root)) {
						logger.error("Failed to create root event");
						return null;
					}
					event = root;
				}
				else {
					BaseRecord realm = ctx.getRealm(loc);
					BaseRecord popEvent = CharacterUtil.populateRegion(ctx, realm, loc, root, ctx.getConfig().getBasePopulationCount());
					popEvent.set(FieldNames.FIELD_PARENT_ID, root.get(FieldNames.FIELD_ID));
					events.add(popEvent);
					event = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_EVENT, ctx.getOlioUser(), null, ParameterList.newParameterList(FieldNames.FIELD_PATH, eventsDir.get(FieldNames.FIELD_PATH)));
					event.set(FieldNames.FIELD_NAME, "Construct " + locName);
					event.set(FieldNames.FIELD_PARENT_ID, root.get(FieldNames.FIELD_ID));
					
					for(int b = 0; b < 2; b++) {
						List<BaseRecord> traits = event.get((b == 0 ? "entryTraits" : "exitTraits"));
						traits.addAll(Arrays.asList(Decks.getRandomTraits(ctx.getOlioUser(), parWorld, 3)));
					}
					event.set(FieldNames.FIELD_LOCATION, loc);
					event.set(FieldNames.FIELD_TYPE, EventEnumType.CONSTRUCT);
					event.set(OlioFieldNames.FIELD_EVENT_START, ctx.getConfig().getBaseInceptionDate());
					event.set(OlioFieldNames.FIELD_EVENT_PROGRESS, ctx.getConfig().getBaseInceptionDate());
					event.set(OlioFieldNames.FIELD_EVENT_END, ctx.getConfig().getBaseInceptionDate());


					event.set("realm", realm);
					if(!IOSystem.getActiveContext().getRecordUtil().updateRecord(event)) {
						logger.error("Failed to create region event");
						return null;
					}
					events.add(event);
					
				}
			}

		}
		catch (ValueException | FieldException | ModelNotFoundException | FactoryException e) {
			logger.error(e);
		}
		if(!IOSystem.getActiveContext().getRecordUtil().updateRecord(root)) {
			logger.error("Failed to update root event");
			return null;
		}
		
		Queue.processQueue();
		return root;
	}
	
	@Override
	public BaseRecord[] selectLocations(OlioContext context) {
		List<BaseRecord> recs = new ArrayList<>();
		long id = context.getUniverse().get(OlioFieldNames.FIELD_LOCATIONS_ID);

		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, id);
		pq.field(FieldNames.FIELD_PARENT_ID, 0L);
		BaseRecord world = IOSystem.getActiveContext().getSearch().findRecord(pq);
		if(world == null){
			logger.error("No world locations found");
			return new BaseRecord[0];
		}
		recs.add(world);
		Query lq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, id);

		lq.field("geoType", FieldNames.FIELD_FEATURE);
		lq.setRequestRange(0L, context.getConfig().getBaseLocationCount());
		try {
			lq.set(FieldNames.FIELD_SORT_FIELD, "random()");
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		recs.addAll(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(lq)));

		return recs.toArray(new BaseRecord[0]);
	}
	
}
