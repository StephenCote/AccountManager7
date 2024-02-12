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
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.TerrainUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

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
public class GridSquareLocationInitializationRule implements IOlioContextRule {
	public static final Logger logger = LogManager.getLogger(GridSquareLocationInitializationRule.class);
	
	private SecureRandom rand = new SecureRandom();

	private int minOpenSpace = 5;
	private int maxOpenSpace = 15;
	/// Not obvious: The 'feature' mapWidth/Height is set to 100x100 square kilometers
	private int mapWidth1km = Rules.MAP_EXTERIOR_FEATURE_WIDTH;
	private int mapHeight1km = Rules.MAP_EXTERIOR_FEATURE_HEIGHT;

	/// Not obvious: The cellWidth is set to 10x10 in a 1km square means each cell is really 100m x 100m (the multiplier is defined in the rules)
	///
	private int mapCellWidthM = Rules.MAP_EXTERIOR_CELL_WIDTH;
	private int mapCellHeightM = Rules.MAP_EXTERIOR_CELL_HEIGHT;
	
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
	
	public void prepCells(OlioContext ctx, BaseRecord location) {
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
					int ie = x; //  * 10
					int in = y; //  * 10
	    			BaseRecord cell = GeoLocationUtil.newLocation(ctx, location, GeoLocationUtil.GZD + " " + location.get("kident") + " " + GeoLocationUtil.df2.format((int)location.get("eastings")) + "" + GeoLocationUtil.df2.format((int)location.get("northings")) + " " + GeoLocationUtil.df3.format(ie) + "" + GeoLocationUtil.df3.format(in), iter++);
	    			/// Location util defaults to putting new locations into the universe.  change to the world group
	    			///
	    			cell.set(FieldNames.FIELD_GROUP_ID, ctx.getWorld().get("locations.id"));
	    			cell.set("area", (double)10);
	    			cell.set("gridZone", GeoLocationUtil.GZD);
	    			cell.set("kident", location.get("kident"));
	    			cell.set("eastings", x);
	    			cell.set("northings", y);
	    			cell.set("geoType", "cell");
	    			cells.add(cell);
				}
			}
			TerrainUtil.blastCells(ctx, location, cells, mapCellWidthM, mapCellHeightM);
			IOSystem.getActiveContext().getRecordUtil().createRecords(cells.toArray(new BaseRecord[0]));
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}

	
	

	
    private void prepGrid(OlioContext ctx) {
    	int iter = 100;
    	zone = GeoLocationUtil.newLocation(ctx, null, GeoLocationUtil.GZD, iter++);
    	IOSystem.getActiveContext().getRecordUtil().createRecord(zone);
    	BaseRecord[][] grid = new BaseRecord[mapWidth1km][mapHeight1km];
    	try {
	    	zone.set("gridZone", GeoLocationUtil.GZD);
	    	String identH = "ABCDEFGHJKLMNPQRSTUVWXYZ";
	    	String identV = "ABCDEFGHJKLMNPQRSTUV";
	    	List<BaseRecord> blocks = new ArrayList<>();
    		for(int y = 0; y < identV.length(); y++) {
    			for(int x = 0; x < identH.length(); x++) {
    				String sx = identH.substring(x,x+1);
    				String sy = identV.substring(y,y+1);
    				BaseRecord block = GeoLocationUtil.newLocation(ctx, zone, GeoLocationUtil.GZD + " " + sx + sy, iter++);
    				block.set("area", (double)mapWidth1km * mapHeight1km);
    				block.set("gridZone", GeoLocationUtil.GZD);
    				block.set("kident", sx + sy);
    				blocks.add(block);
    				block.set("geoType", "admin2");
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
	    			grid[x][y] = GeoLocationUtil.newLocation(ctx, k100, GeoLocationUtil.GZD + " " + k100.get("kident") + " " + GeoLocationUtil.df2.format(x) + "" + GeoLocationUtil.df2.format(y), iter++);
	    			grid[x][y].set("area", (double)1000);
	    			grid[x][y].set("gridZone", GeoLocationUtil.GZD);
	    			grid[x][y].set("kident", k100.get("kident"));
	    			grid[x][y].set("eastings", x);
	    			grid[x][y].set("northings", y);
	    			/// Null out the type in order to be reset in the connection routine
	    			///
	    			grid[x][y].set("geoType", "featureless");
	    			blocks.add(grid[x][y]);
	    		}
	    	}
	    	// logger.info("Staged " + blocks.size());
	    	logger.info("Connecting regions");
	    	connectRegions(grid);
	    	logger.info("Blasting regions");
	    	TerrainUtil.blastAndWalk(ctx, blocks, mapWidth1km, mapHeight1km);
	    	logger.info("Creating " + blocks.size() + " grid squares for " + k100.get(FieldNames.FIELD_NAME));
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
            placeRegion(grid, rw, rh, "Region " + (i + 1));
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
            int t = rand.nextInt(1, mapWidth1km);
            int l = rand.nextInt(1, mapHeight1km);
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
    
    private void placeRegion(BaseRecord[][] grid, int w, int h, String regionName){
        Point pos = findSpaceLocation(grid, w, h);
         if(pos != null){
            logger.info("Connected regions at " + pos.getX() + ", " + pos.getY());
            for(int i = pos.getX(); i < pos.getX() + w; i++){
                for(int b = pos.getY(); b < pos.getY() + h; b++){
                    try {
						grid[i][b].set("geoType", "feature");
						grid[i][b].set("feature", regionName);
						grid[i][b].set("classification", pos.getX() + "," + pos.getY() + "," + w + "," + h);
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

	@Override
	public void postgenerate(OlioContext context) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void generateRegion(OlioContext context, BaseRecord rootEvent, BaseRecord event) {
		// TODO Auto-generated method stub
		
	}

	
}
