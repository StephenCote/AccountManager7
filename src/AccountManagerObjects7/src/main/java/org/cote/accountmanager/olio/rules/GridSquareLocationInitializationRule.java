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
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class GridSquareLocationInitializationRule implements IOlioContextRule {
	public static final Logger logger = LogManager.getLogger(GridSquareLocationInitializationRule.class);
	private String GZD = "30K";
	private String kident = "MM";
	private int minOpenSpace = 5;
	private int maxOpenSpace = 30;
	private int mapWidth1km = 30;
	private int mapHeight1km = 30;
    BaseRecord[][] grid = new BaseRecord[mapWidth1km][mapHeight1km];
    BaseRecord zone = null;
    List<BaseRecord> k100s = new ArrayList<>();
    BaseRecord k100 = null;
    

	public GridSquareLocationInitializationRule() {

	}
	
	@Override
	public void pregenerate(OlioContext context) {
		long id = context.getUniverse().get("locations.id");
		if(IOSystem.getActiveContext().getSearch().count(QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, id)) > 0) {
			logger.info("Locations already setup");
			return;
		}
		String path = context.getUniverse().get("locations.path");
		if(path == null) {
			logger.error("Path is not specified");
			return;
		}
		
		prepGrid(context);
	}
	
    private void prepGrid(OlioContext ctx) {
    	int iter = 100;
    	zone = GeoLocationUtil.newLocation(ctx, null, GZD, iter++);
    	IOSystem.getActiveContext().getRecordUtil().createRecord(zone);
    	try {
	    	zone.set("gridZone", GZD);
	    	String identH = "ABCDEFGHJKLMNPQRSTUVWXYZ";
	    	String identV = "ABCDEFGHJKLMNPQRSTUV";
	    	List<BaseRecord> blocks = new ArrayList<>();
    		for(int y = 0; y < identV.length(); y++) {
    			for(int x = 0; x < identH.length(); x++) {
    				String sx = identH.substring(x,1);
    				String sy = identV.substring(y,1);
    				BaseRecord block = GeoLocationUtil.newLocation(ctx, zone, GZD + " " + sx + sy, iter++);
    				block.set("area", 10000);
    				block.set("gridZone", GZD);
    				block.set("kident", sx + sy);
    				blocks.add(block);
    				// block.set("eastings", x * 100);
    				// block.set("northings", y * 100);
	    		}
	    	}
    		
    		IOSystem.getActiveContext().getRecordUtil().createRecords(blocks.toArray(new BaseRecord[0]));
    		k100 = k100s.get((new SecureRandom()).nextInt(k100s.size()));
    		blocks.clear();
	    	for(int x = 0; x < mapWidth1km; x++) {
	    		for(int y = 0; y < mapHeight1km; y++) {
	    			grid[x][y] = GeoLocationUtil.newLocation(ctx, k100, GZD + " " + k100.get("kident"), iter++);
	    			grid[x][y].set("area", 1000);
	    			grid[x][y].set("gridZone", GZD);
	    			grid[x][y].set("kident", k100.get("kident"));
	    			grid[x][y].set("eastings", x);
	    			grid[x][y].set("northings", y);
	    			grid[x][y].set("geotype", "feature");
	    			blocks.add(grid[x][y]);
	    		}
	    	}
	    	IOSystem.getActiveContext().getRecordUtil().createRecords(blocks.toArray(new BaseRecord[0]));
    	}
    	catch(ModelNotFoundException | FieldException | ValueException e) {
    		logger.error(e);
    	}
    }

	@Override
	public BaseRecord[] selectLocations(OlioContext context) {
		List<BaseRecord> recs = new ArrayList<>();
		long id = context.getUniverse().get("locations.id");

		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, id);
		pq.field(FieldNames.FIELD_PARENT_ID, 0L);
		BaseRecord world = IOSystem.getActiveContext().getSearch().findRecord(pq);
		if(world == null){
			logger.error("No world locations found");
			return new BaseRecord[0];
		}
		recs.add(world);
		Query lq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, id);
		lq.field(FieldNames.FIELD_PARENT_ID, world.get(FieldNames.FIELD_ID));
		lq.setRequestRange(0L, context.getConfig().getBaseLocationCount());
		recs.addAll(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(lq)));
		logger.info("Returning " + recs.size() + " locs");
		return recs.toArray(new BaseRecord[0]);
	}

	
}
