package org.cote.accountmanager.olio.rules;

import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class GridSquareLocationInitializationRule implements IOlioContextRule {
	public static final Logger logger = LogManager.getLogger(GridSquareLocationInitializationRule.class);
	
	private SecureRandom rand = new SecureRandom();
	private String GZD = "30K";
	private int minOpenSpace = 5;
	private int maxOpenSpace = 15;
	private int mapWidth1km = 50;
	private int mapHeight1km = 50;
	private int mapCellWidthM = 10;
	private int mapCellHeightM = 10;
	private DecimalFormat df2 = new DecimalFormat("00");
	private DecimalFormat df3 = new DecimalFormat("000");

	private int maxConnectedRegions = 5;
    
    BaseRecord zone = null;
    List<BaseRecord> k100s = new ArrayList<>();
    BaseRecord k100 = null;
    

	public GridSquareLocationInitializationRule() {

	}
	
	public int getMapWidth1km() {
		return mapWidth1km;
	}

	public int getMapHeight1km() {
		return mapHeight1km;
	}

	public int getMapCellWidthM() {
		return mapCellWidthM;
	}

	public int getMapCellHeightM() {
		return mapCellHeightM;
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
	
	protected void prepCells(OlioContext ctx, BaseRecord location) {
		// ParameterList plist = ParameterList.newParameterList("path", ctx.getUniverse().get("locations.path"));
		IOSystem.getActiveContext().getReader().populate(location);
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, location.get(FieldNames.FIELD_ID));
		
		cq.field("geoType", "cell");
		int count = IOSystem.getActiveContext().getSearch().count(cq);
		if(count > 0) {
			logger.info("Location " + location.get(FieldNames.FIELD_NAME) + " is already prepared with cells");
			return;
		}
		logger.info("Preparing " + location.get(FieldNames.FIELD_NAME) + " cells");
		// logger.info(location.toFullString());
		int iter = 1 + IOSystem.getActiveContext().getSearch().count(QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, location.get(FieldNames.FIELD_GROUP_ID)));
		List<BaseRecord> cells = new ArrayList<>();
		try {
			for(int y = 0; y < mapCellHeightM; y++) {
				for(int x = 0; x < mapCellWidthM; x++) {
					int ie = x * 10;
					int in = y * 10;
	    			BaseRecord cell = GeoLocationUtil.newLocation(ctx, location, GZD + " " + location.get("kident") + " " + df2.format((int)location.get("eastings")) + "" + df2.format((int)location.get("northings")) + " " + df3.format(ie) + "" + df3.format(in), iter++);
	    			/// Location util defaults to putting new locations into the universe.  change to the world group
	    			///
	    			cell.set(FieldNames.FIELD_GROUP_ID, ctx.getWorld().get("locations.id"));
	    			cell.set("area", (double)10);
	    			cell.set("gridZone", GZD);
	    			cell.set("kident", location.get("kident"));
	    			cell.set("eastings", ie);
	    			cell.set("northings", in);
	    			cell.set("geoType", "cell");
	    			cells.add(cell);
				}
			}
			IOSystem.getActiveContext().getRecordUtil().createRecords(cells.toArray(new BaseRecord[0]));
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}
	
    private void prepGrid(OlioContext ctx) {
    	int iter = 100;
    	zone = GeoLocationUtil.newLocation(ctx, null, GZD, iter++);
    	IOSystem.getActiveContext().getRecordUtil().createRecord(zone);
    	BaseRecord[][] grid = new BaseRecord[mapWidth1km][mapHeight1km];
    	try {
	    	zone.set("gridZone", GZD);
	    	String identH = "ABCDEFGHJKLMNPQRSTUVWXYZ";
	    	String identV = "ABCDEFGHJKLMNPQRSTUV";
	    	List<BaseRecord> blocks = new ArrayList<>();
    		for(int y = 0; y < identV.length(); y++) {
    			for(int x = 0; x < identH.length(); x++) {
    				String sx = identH.substring(x,x+1);
    				String sy = identV.substring(y,y+1);
    				BaseRecord block = GeoLocationUtil.newLocation(ctx, zone, GZD + " " + sx + sy, iter++);
    				block.set("area", (double)mapWidth1km * mapHeight1km);
    				block.set("gridZone", GZD);
    				block.set("kident", sx + sy);
    				blocks.add(block);
    				block.set("geoType", "admin1");
    				// block.set("eastings", x * 100);
    				// block.set("northings", y * 100);
	    		}
	    	}
    		logger.info("Creating " + blocks.size() + " 100ks");
    		IOSystem.getActiveContext().getRecordUtil().createRecords(blocks.toArray(new BaseRecord[0]));
    		k100 = blocks.get((new SecureRandom()).nextInt(blocks.size()));
    		blocks.clear();
	    	for(int x = 0; x < mapWidth1km; x++) {
	    		for(int y = 0; y < mapHeight1km; y++) {
	    			grid[x][y] = GeoLocationUtil.newLocation(ctx, k100, GZD + " " + k100.get("kident") + " " + df2.format(x) + "" + df2.format(y), iter++);
	    			grid[x][y].set("area", (double)1000);
	    			grid[x][y].set("gridZone", GZD);
	    			grid[x][y].set("kident", k100.get("kident"));
	    			grid[x][y].set("eastings", x);
	    			grid[x][y].set("northings", y);
	    			/// Null out the type in order to be reset in the connection routine
	    			///
	    			grid[x][y].set("geoType", null);
	    			blocks.add(grid[x][y]);
	    		}
	    	}
	    	// logger.info("Staged " + blocks.size());
	    	logger.info("Connecting regions");
	    	connectRegions(grid);
	    	logger.info("Creating " + blocks.size() + " square kilometers");
	    	long start = System.currentTimeMillis();
	    	IOSystem.getActiveContext().getRecordUtil().createRecords(blocks.toArray(new BaseRecord[0]));
	    	long stop = System.currentTimeMillis();
	    	logger.info("Created " + blocks.size() + " in " + (stop - start) + "ms");
    	}
    	catch(ModelNotFoundException | FieldException | ValueException e) {
    		logger.error(e);
    	}
    }
    
    private void connectRegions(BaseRecord[][] grid){
        for(int i = 0; i < maxConnectedRegions; i++){
        	int rw = rand.nextInt(minOpenSpace, maxOpenSpace);
        	int rh = rand.nextInt(minOpenSpace, maxOpenSpace);
            placeRegion(grid, rw, rh);
        }
    }
    
    class Point{
    	private int x = 0;
    	private int y = 0;
    	public Point(int x, int y) {
    		this.x = x;
    		this.y = y;
    	}
		public int getX() {
			return x;
		}
		public int getY() {
			return y;
		}
    }
    
    private Point findSpaceLocation(BaseRecord[][] grid, int w, int h){
        Point pos = null;
        int iters = 0;
        int maxiters = 500;
        while(pos == null){
            iters++;
            if(iters > maxiters){
                logger.error("Exceeded location attempts");
                break;
            }
            int t = rand.nextInt(0, mapWidth1km);
            int l = rand.nextInt(0, mapHeight1km);
            if(l == 0) l = 1;
            if(t == 0) t = 1;
            if(l + w >= mapWidth1km) l = mapWidth1km - w - 2;
            if(t + h >= mapWidth1km) t = mapHeight1km - h - 2;

            boolean collision = false;
            // logger.info("Scan " + (l - 1) + " to " + (l + w + 1) + " and " + (t - 1) + " to " + (t + h + 1));
            for(int x = (l - 1); x < (l + w + 1); x++){
                for(int y = (t - 1); y < (t + h + 1); y++){
                    if(grid[x] == null || grid[x][y] == null){
                        logger.error("Cell error at " + x + ", " + y);
                        collision = true;
                        break;
                    }
                    BaseRecord cell = grid[x][y];
                    String type = cell.get("geoType");
                    if(type != null && type.equals("feature")){
                    	// logger.error("Collided at " + x + ", " + y + " - " + type + " " + cell.get(FieldNames.FIELD_NAME));
                        collision = true;
                        break;
                    }
                }
                if(collision) break;
            }
            if(!collision){
                pos = new Point(l, t);
                break;
            }
            else{
                // logger.warn("Collision detected");
            }
        }
        return pos;
    }
    
    private void placeRegion(BaseRecord[][] grid, int w, int h){
        Point pos = findSpaceLocation(grid, w, h);
         if(pos != null){
            logger.info("Connected regions at " + pos.getX() + ", " + pos.getY());
            for(int i = pos.getX(); i < pos.getX() + w; i++){
                for(int b = pos.getY(); b < pos.getY() + h; b++){
                    try {
						grid[i][b].set("geoType", "feature");
					} catch (FieldException | ValueException | ModelNotFoundException e) {
						logger.error(e);
					}
                }
            }
        }
        else{
            logger.warn("Failed to place room");
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
		//lq.field(FieldNames.FIELD_PARENT_ID, world.get(FieldNames.FIELD_ID));
		lq.field("geoType", "feature");
		lq.setRequestRange(0L, context.getConfig().getBaseLocationCount());
		try {
			lq.set(FieldNames.FIELD_SORT_FIELD, "random()");
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		recs.addAll(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(lq)));
		logger.info("Returning " + recs.size() + " locs");
		return recs.toArray(new BaseRecord[0]);
	}

	
}
