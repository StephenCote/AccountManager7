package org.cote.accountmanager.olio.rules;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.TerrainEnumType;

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
public class ArenaInitializationRule extends CommonContextRule implements IOlioContextRule {
	public static final Logger logger = LogManager.getLogger(ArenaInitializationRule.class);
	
	private SecureRandom rand = new SecureRandom();

	/// Not obvious: The cellWidth is set to 10x10 in a 1km square means each cell is really 100m x 100m (the multiplier is defined in the rules)
	///
	protected static int fieldWidthM = 5;
	protected static int fieldHeightM = 5;
	
	public ArenaInitializationRule() {

	}
		
	@Override
	public void pregenerate(OlioContext context) {
		long id = context.getUniverse().get(OlioFieldNames.FIELD_LOCATIONS_ID);
		if(IOSystem.getActiveContext().getSearch().count(QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, id)) > 0) {
			logger.info("Locations already setup");
			return;
		}
		String path = context.getUniverse().get("locations.path");
		if(path == null) {
			logger.error("Path is not specified");
			return;
		}
		
		prepArena(context);
	}
	

	

    private void prepArena(OlioContext ctx) {
    	logger.info("Prep arena");
    	try {
	    	int iter = 100;
	    	BaseRecord arena = GeoLocationUtil.newLocation(ctx, null, "Arena", iter++);
	    	IOSystem.getActiveContext().getRecordUtil().createRecord(arena);
	    	BaseRecord holding = GeoLocationUtil.newLocation(ctx, arena, "Holding", iter++);
	    	BaseRecord stands = GeoLocationUtil.newLocation(ctx, arena, "Stands", iter++);
	    	BaseRecord field = GeoLocationUtil.newLocation(ctx, arena, "Field", iter++);
	    	BaseRecord conc = GeoLocationUtil.newLocation(ctx, arena, "Concession", iter++);
	    	BaseRecord stage = GeoLocationUtil.newLocation(ctx, arena, "Staging", iter++);
	    	
	    	stands.set("geoType", "admin2");
	    	holding.set("geoType", "admin2");
	    	field.set("geoType", "admin2");
	    	conc.set("geoType", "admin2");
	    	stage.set("geoType", "admin2");
	    	IOSystem.getActiveContext().getRecordUtil().createRecords(new BaseRecord[] {holding, stands, field, conc, stage});
	    	BaseRecord goodSeats = GeoLocationUtil.newLocation(ctx, stands, "Ring Side", iter++);
	    	BaseRecord badSeats = GeoLocationUtil.newLocation(ctx, stands, "Cheap Seats", iter++);
	    	BaseRecord holding1 = GeoLocationUtil.newLocation(ctx, holding, "Holding 1", iter++);
	    	BaseRecord holding2 = GeoLocationUtil.newLocation(ctx, holding, "Holding 2", iter++);
	    	BaseRecord holding3 = GeoLocationUtil.newLocation(ctx, holding, "Holding 3", iter++);
	    	BaseRecord field1 = GeoLocationUtil.newLocation(ctx, field, "Field 1", iter++);
	    	BaseRecord field2 = GeoLocationUtil.newLocation(ctx, field, "Field 2", iter++);
	    	BaseRecord field3 = GeoLocationUtil.newLocation(ctx, field, "Field 3", iter++);
	    	BaseRecord staging1 = GeoLocationUtil.newLocation(ctx, stands, "Staging 1", iter++);
	
	    	goodSeats.set("geoType", FieldNames.FIELD_FEATURE);
	    	badSeats.set("geoType", FieldNames.FIELD_FEATURE);
	    	holding1.set("geoType", FieldNames.FIELD_FEATURE);
	    	holding2.set("geoType", FieldNames.FIELD_FEATURE);
	    	holding3.set("geoType", FieldNames.FIELD_FEATURE);
	    	field1.set("geoType", FieldNames.FIELD_FEATURE);
	    	field1.set("terrainType", TerrainEnumType.getArable().get(rand.nextInt(TerrainEnumType.getArable().size())));
	    	field2.set("geoType", FieldNames.FIELD_FEATURE);
	    	field2.set("terrainType", TerrainEnumType.getArable().get(rand.nextInt(TerrainEnumType.getArable().size())));
	    	field3.set("geoType", FieldNames.FIELD_FEATURE);
	    	field3.set("terrainType", TerrainEnumType.getArable().get(rand.nextInt(TerrainEnumType.getArable().size())));
	    	staging1.set("geoType", FieldNames.FIELD_FEATURE);
	    	IOSystem.getActiveContext().getRecordUtil().createRecords(new BaseRecord[] {goodSeats, badSeats, holding1, holding2, holding3, field1, field2, field3, staging1});
	    	
    	}
    	catch(ModelNotFoundException | FieldException | ValueException e) {
    		logger.error(e);
    	}

    }

	public void prepField(OlioContext ctx, BaseRecord field) {
		IOSystem.getActiveContext().getReader().populate(field);
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, field.get(FieldNames.FIELD_ID));
		
		cq.field("geoType", "cell");
		int count = IOSystem.getActiveContext().getSearch().count(cq);
		if(count > 0) {
			// logger.info("Location " + field.get(FieldNames.FIELD_NAME) + " is already prepared with cells");
			return;
		}
		int iter = 1 + IOSystem.getActiveContext().getSearch().count(QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, field.get(FieldNames.FIELD_GROUP_ID)));
		List<BaseRecord> cells = new ArrayList<>();
		try {
			for(int y = 0; y < fieldHeightM; y++) {
				for(int x = 0; x < fieldWidthM; x++) {
					int ie = x;
					
					int in = y;
	    			BaseRecord cell = GeoLocationUtil.newLocation(ctx, field, field.get(FieldNames.FIELD_NAME) + " " + GeoLocationUtil.df3.format(ie) + "" + GeoLocationUtil.df3.format(in), iter++);
	    			cell.set(FieldNames.FIELD_GROUP_ID, ctx.getWorld().get(OlioFieldNames.FIELD_LOCATIONS_ID));
	    			cell.set("area", (double)10);
	    			cell.set("eastings", x);
	    			cell.set("northings", y);
	    			cell.set("geoType", "cell");
	    			cell.set("terrainType", TerrainEnumType.getArable().get(rand.nextInt(TerrainEnumType.getArable().size())));
	    			cells.add(cell);
				}
			}
			IOSystem.getActiveContext().getRecordUtil().createRecords(cells.toArray(new BaseRecord[0]));
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
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
		lq.field(FieldNames.FIELD_NAME, "Staging 1");
		lq.setRequestRange(0L, context.getConfig().getBaseLocationCount());
		recs.addAll(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(lq)));
		return recs.toArray(new BaseRecord[0]);
	}

	public static BaseRecord findLocation(OlioContext context, String name) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, context.getUniverse().get(OlioFieldNames.FIELD_LOCATIONS_ID));
		q.field(FieldNames.FIELD_NAME, name);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}
	
	@Override
	public void generateRegion(OlioContext context, BaseRecord realm) {
		// TODO Auto-generated method stub
		logger.info("Prep fields");
		BaseRecord field1 = findLocation(context, "Field 1");
		BaseRecord field2 = findLocation(context, "Field 2");
		BaseRecord field3 = findLocation(context, "Field 3");
    	prepField(context, field1);
    	prepField(context, field2);
    	prepField(context, field3);
		
	}

	
}
