package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GeographyEnumType;

public class GeoLocationUtil {
	public static final Logger logger = LogManager.getLogger(GeoLocationUtil.class);
	private static Map<String, String[]> altNamesCache = new HashMap<>();

	private static Map<Long, List<BaseRecord>> cellMap = new ConcurrentHashMap<>();

	/// 30K is in the middle of the ocean
	public static final String GZD = "30K";
	public static final DecimalFormat df2 = new DecimalFormat("00");
	public static final DecimalFormat df3 = new DecimalFormat("000");

	private static SecureRandom rand = new SecureRandom();
	
	private static int minOpenSpace = 5;
	private static int maxOpenSpace = 15;
	/// Not obvious: The 'feature' mapWidth/Height is set to 100x100 square kilometers
	private static int mapWidth1km = Rules.MAP_EXTERIOR_FEATURE_WIDTH;
	private static int mapHeight1km = Rules.MAP_EXTERIOR_FEATURE_HEIGHT;

	/// Not obvious: The cellWidth is set to 10x10 in a 1km square means each cell is really 100m x 100m (the multiplier is defined in the rules)
	///
	private static int mapCellWidthM = Rules.MAP_EXTERIOR_CELL_WIDTH;
	private static int mapCellHeightM = Rules.MAP_EXTERIOR_CELL_HEIGHT;
	
	private static int maxConnectedRegions = 5;
	
    public static int getMapWidth1km() {
		return mapWidth1km;
	}

	public static int getMapHeight1km() {
		return mapHeight1km;
	}

	public static int getMapCellWidthM() {
		return mapCellWidthM;
	}

	public static int getMapCellHeightM() {
		return mapCellHeightM;
	}
	
	
	public static List<BaseRecord> sortByDistanceToState(List<BaseRecord> list, BaseRecord state)
	{
		return list.stream().sorted((p1, p2) -> {
			int sort = 0;
			double d1 = getDistanceToState(p1.get(FieldNames.FIELD_STATE), state);
			double d2 = getDistanceToState(p2.get(FieldNames.FIELD_STATE), state);
			if(d1 < d2) sort = -1;
			else if(d1 > d2) sort = 1;
			return sort;
		}).collect(Collectors.toList());
	}

	public static List<BaseRecord> sortByDistance(List<BaseRecord> list, String fieldX, String fieldY, int origX, int origY)
	{
		return list.stream().sorted((p1, p2) -> {
			int sort = 0;
			int px1 = p1.get(fieldX);
			int py1 = p1.get(fieldY);
			int px2 = p2.get(fieldX);
			int py2 = p2.get(fieldY);
			double d1 = GeoLocationUtil.distance(origX, origY, px1, py1);
			double d2 = GeoLocationUtil.distance(origX, origY, px2, py2);
			if(d1 < d2) sort = -1;
			else if(d1 > d2) sort = 1;
			return sort;
		}).collect(Collectors.toList());
	}
	
	public static DirectionEnumType randomDirection() {
		DirectionEnumType dir = OlioUtil.randomEnum(DirectionEnumType.class);
		while(dir == DirectionEnumType.UNKNOWN) {
			dir = OlioUtil.randomEnum(DirectionEnumType.class);
		}
		return dir;
	}
	
	public static int prepareMapGrid(OlioContext ctx) {
    	long id = ctx.getUniverse().get(OlioFieldNames.FIELD_LOCATIONS_ID);
    	int count = IOSystem.getActiveContext().getSearch().count(QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, id));
		if(count > 0) {
			return count;
		}
    	
    	int iter = 100;
    	BaseRecord zone = newLocation(ctx, null, GZD, iter++);
    	IOSystem.getActiveContext().getRecordUtil().createRecord(zone);

    	try {
	    	zone.set(FieldNames.FIELD_GRID_ZONE, GZD);
	    	String identH = "ABCDEFGHJKLMNPQRSTUVWXYZ";
	    	String identV = "ABCDEFGHJKLMNPQRSTUV";
	    	List<BaseRecord> blocks = new ArrayList<>();
    		for(int y = 0; y < identV.length(); y++) {
    			for(int x = 0; x < identH.length(); x++) {
    				String sx = identH.substring(x,x+1);
    				String sy = identV.substring(y,y+1);
    				BaseRecord block = newLocation(ctx, zone, GZD + " " + sx + sy, iter++);
    				block.set(FieldNames.FIELD_AREA, (double)mapWidth1km * mapHeight1km);
    				block.set(FieldNames.FIELD_GRID_ZONE, GZD);
    				block.set(FieldNames.FIELD_KIDENT, sx + sy);
    				block.set(FieldNames.FIELD_GEOTYPE, "admin2");
    				
    				blocks.add(block);
    				
    				// block.set(FieldNames.FIELD_EASTINGS, x * 100);
    				// block.set(FieldNames.FIELD_NORTHINGS, y * 100);
	    		}
	    	}
    		logger.info("Creating " + blocks.size() + " 100ks");
    		count = IOSystem.getActiveContext().getRecordUtil().createRecords(blocks.toArray(new BaseRecord[0]));
    		// BaseRecord k100 = blocks.get((new SecureRandom()).nextInt(blocks.size()));
    		// prepK100(ctx, k100);
    	}
    	catch(ModelNotFoundException | FieldException | ValueException e) {
    		logger.error(e);
    	}
    	return count;
    }
    
	public static void checkK100(OlioContext context) {
		long udir = context.getUniverse().get(OlioFieldNames.FIELD_LOCATIONS_ID);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, udir);
		q.field(FieldNames.FIELD_GEOTYPE, FieldNames.FIELD_FEATURE);
		if(IOSystem.getActiveContext().getSearch().count(q) == 0) {
			Query q2 = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, udir);
			q2.field(FieldNames.FIELD_GEOTYPE, "admin2");
			OlioUtil.planMost(q2);
			BaseRecord k100 = OlioUtil.randomSelection(context.getOlioUser(), q2);
			if(k100 == null) {
				logger.error("Failed to find a random location!");
			}
			else {
				prepareK100(context, k100);
			}
		}
	}
	
    public static int prepareK100(OlioContext ctx, BaseRecord k100) {
    	long id = k100.get(FieldNames.FIELD_ID);
    	int count = IOSystem.getActiveContext().getSearch().count(QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, id));
    	
		if(count > 0) {
			return count;
		}
    	int iter = 100;
    	int mapWidth1km = Rules.MAP_EXTERIOR_FEATURE_WIDTH;
    	int mapHeight1km = Rules.MAP_EXTERIOR_FEATURE_HEIGHT;
    	List<BaseRecord> blocks = new ArrayList<>();
    	BaseRecord[][] grid = new BaseRecord[mapWidth1km][mapHeight1km];
    	try {
	    	for(int x = 0; x < mapWidth1km; x++) {
	    		for(int y = 0; y < mapHeight1km; y++) {
	    			grid[x][y] = newLocation(ctx, k100, GZD + " " + k100.get(FieldNames.FIELD_KIDENT) + " " + df2.format(x) + "" + df2.format(y), iter++);
	    			grid[x][y].set(FieldNames.FIELD_AREA, (double)1000);
	    			grid[x][y].set(FieldNames.FIELD_GRID_ZONE, GZD);
	    			grid[x][y].set(FieldNames.FIELD_KIDENT, k100.get(FieldNames.FIELD_KIDENT));
	    			grid[x][y].set(FieldNames.FIELD_EASTINGS, x);
	    			grid[x][y].set(FieldNames.FIELD_NORTHINGS, y);
	    			/// Null out the type in order to be reset in the connection routine
	    			///
	    			grid[x][y].set(FieldNames.FIELD_GEOTYPE, "featureless");
	    			blocks.add(grid[x][y]);
	    		}
	    	}
	    	// logger.info("Staged " + blocks.size());
	    	logger.info("Connecting regions");
	    	connectRegions(grid);
	    	logger.info("Blasting regions");
	    	TerrainUtil.blastAndWalk(null, blocks, mapWidth1km, mapHeight1km);
	    	logger.info("Creating " + blocks.size() + " grid squares for " + k100.get(FieldNames.FIELD_NAME));
	    	long start = System.currentTimeMillis();
	    	count = IOSystem.getActiveContext().getRecordUtil().createRecords(blocks.toArray(new BaseRecord[0]));
	    	long stop = System.currentTimeMillis();
	    	logger.info("Created " + count + " in " + (stop - start) + "ms");
    	}
    	catch(ModelNotFoundException | FieldException | ValueException e) {
    		logger.error(e);
    	}
    	return count;
    }
    /*
	public static void prepareGridCells(OlioContext ctx, BaseRecord location) {
		// ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getUniverse().get("locations.path"));
		IOSystem.getActiveContext().getReader().populate(location);
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, location.get(FieldNames.FIELD_ID));
		
		cq.field(FieldNames.FIELD_GEOTYPE, "cell");
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
			for(int y = 0; y < Rules.MAP_EXTERIOR_CELL_HEIGHT; y++) {
				for(int x = 0; x < Rules.MAP_EXTERIOR_CELL_WIDTH; x++) {
					int ie = x; //  * 10
					int in = y; //  * 10
	    			BaseRecord cell = GeoLocationUtil.newLocation(ctx, location, GZD + " " + location.get(FieldNames.FIELD_KIDENT) + " " + df2.format((int)location.get(FieldNames.FIELD_EASTINGS)) + "" + df2.format((int)location.get(FieldNames.FIELD_NORTHINGS)) + " " + df3.format(ie) + "" + df3.format(in), iter++);
	    			/// Location util defaults to putting new locations into the universe.  change to the world group
	    			///
	    			cell.set(FieldNames.FIELD_GROUP_ID, ctx.getWorld().get(OlioFieldNames.FIELD_LOCATIONS_ID));
	    			cell.set(FieldNames.FIELD_AREA, (double)10);
	    			cell.set(FieldNames.FIELD_GRID_ZONE, GZD);
	    			cell.set(FieldNames.FIELD_KIDENT, location.get(FieldNames.FIELD_KIDENT));
	    			cell.set(FieldNames.FIELD_EASTINGS, x);
	    			cell.set(FieldNames.FIELD_NORTHINGS, y);
	    			cell.set(FieldNames.FIELD_GEOTYPE, "cell");
	    			cells.add(cell);
				}
			}
			TerrainUtil.blastCells(ctx, location, cells, Rules.MAP_EXTERIOR_CELL_WIDTH, Rules.MAP_EXTERIOR_CELL_HEIGHT);
			IOSystem.getActiveContext().getRecordUtil().createRecords(cells.toArray(new BaseRecord[0]));
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}    
    */

    public static int prepareCells(OlioContext ctx, BaseRecord feature) {
		IOSystem.getActiveContext().getReader().populate(feature);

		Query cq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, feature.get(FieldNames.FIELD_ID));
		cq.field(FieldNames.FIELD_GEOTYPE, "cell");
		int count = IOSystem.getActiveContext().getSearch().count(cq);
		if(count > 0) {
			return count;
		}
		//logger.info("Preparing " + feature.get(FieldNames.FIELD_NAME) + " cells in group " + feature.get("groupId"));
		int iter = 1 + IOSystem.getActiveContext().getSearch().count(QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, feature.get(FieldNames.FIELD_GROUP_ID)));
		List<BaseRecord> cells = new ArrayList<>();
		try {
			for(int y = 0; y < mapCellHeightM; y++) {
				for(int x = 0; x < mapCellWidthM; x++) {
					int ie = x; //  * 10
					int in = y; //  * 10
	    			BaseRecord cell = newLocation(ctx, feature, GZD + " " + feature.get(FieldNames.FIELD_KIDENT) + " " + df2.format((int)feature.get(FieldNames.FIELD_EASTINGS)) + "" + df2.format((int)feature.get(FieldNames.FIELD_NORTHINGS)) + " " + df3.format(ie) + "" + df3.format(in), iter++);
	    			/// Location util defaults to putting new locations into the universe.  change to the world group
	    			///
	    			cell.set(FieldNames.FIELD_GROUP_ID, ctx.getWorld().get(OlioFieldNames.FIELD_LOCATIONS_ID));
	    			cell.set(FieldNames.FIELD_AREA, (double)10);
	    			cell.set(FieldNames.FIELD_GRID_ZONE, GZD);
	    			cell.set(FieldNames.FIELD_KIDENT, feature.get(FieldNames.FIELD_KIDENT));
	    			cell.set(FieldNames.FIELD_EASTINGS, x);
	    			cell.set(FieldNames.FIELD_NORTHINGS, y);
	    			cell.set(FieldNames.FIELD_GEOTYPE, "cell");
	    			cells.add(cell);
				}
			}
			TerrainUtil.blastCells(ctx, feature, cells, mapCellWidthM, mapCellHeightM);
			count = IOSystem.getActiveContext().getRecordUtil().createRecords(cells.toArray(new BaseRecord[0]));
			PointOfInterestUtil.paintPointsOfInterest(ctx, cells);
			
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		return count;
	}
    
    public static void connectRegions(BaseRecord[][] grid){
        for(int i = 0; i < maxConnectedRegions; i++){
        	int rw = rand.nextInt(minOpenSpace, maxOpenSpace);
        	int rh = rand.nextInt(minOpenSpace, maxOpenSpace);
            placeRegion(grid, rw, rh, "Region " + (i + 1));
        }
    }
    

    
    private static Point findSpaceLocation(BaseRecord[][] grid, int w, int h){
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
                    String type = cell.get(FieldNames.FIELD_GEOTYPE);
                    if(type != null && type.equals(FieldNames.FIELD_FEATURE)){
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
    
    private static void placeRegion(BaseRecord[][] grid, int w, int h, String regionName){
        Point pos = findSpaceLocation(grid, w, h);
         if(pos != null){
            logger.info("Connected regions at " + pos.getX() + ", " + pos.getY());
            for(int i = pos.getX(); i < pos.getX() + w; i++){
                for(int b = pos.getY(); b < pos.getY() + h; b++){
                    try {
						grid[i][b].set(FieldNames.FIELD_GEOTYPE, FieldNames.FIELD_FEATURE);
						grid[i][b].set(FieldNames.FIELD_FEATURE, regionName);
						grid[i][b].set(FieldNames.FIELD_CLASSIFICATION, pos.getX() + "," + pos.getY() + "," + w + "," + h);
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
    
    /// findAdjacentCells will spiral out from the specified cell - this is done because a) grid cells are created on demand, and b) findCellToEdgePlusOne will only look as far as one cell into an adjacent region

    public static BaseRecord[][] findAdjacentCells(OlioContext ctx, BaseRecord cell, int radius) {
    	int diam = (radius * 2) + 1;
    	BaseRecord[][] gcells = new BaseRecord[diam][diam];

    	BaseRecord lcell = cell;

    	int cx = radius;
    	int cy = radius;
    	gcells[cx][cy] = cell;
    	int max = (diam * diam) - 1;
    	Map<Long, List<BaseRecord>> cellMap = new HashMap<>();
    	DirectionEnumType dir = DirectionEnumType.WEST;
    	for(int i = 0; i < max; i++) {
    		cx += DirectionEnumType.getX(dir);
    		cy += DirectionEnumType.getY(dir);
    		// logger.info(dir.toString() + " " + cx + ", " + cy);
    		if(cx < 0 || cy < 0 || cx >= diam || cy >= diam) {
    			logger.error("Index out of bounds: " + cx + ", " + cy);
    			break;
    		}
    		/*
        	int east = (DirectionEnumType.getX(dir) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER) + (int)lcell.get(FieldNames.FIELD_EASTINGS);
        	int north = (DirectionEnumType.getY(dir) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER) + (int)lcell.get(FieldNames.FIELD_NORTHINGS);
        	*/
        	int east = DirectionEnumType.getX(dir) + (int)lcell.get(FieldNames.FIELD_EASTINGS);
        	int north = DirectionEnumType.getY(dir) + (int)lcell.get(FieldNames.FIELD_NORTHINGS);

    		long pid = cell.get(FieldNames.FIELD_PARENT_ID);
        	if(!cellMap.containsKey(pid)) {
        		cellMap.put(pid, getCells(ctx, getParentLocation(ctx, lcell)));
        	}
        	BaseRecord ocell = findCellToEdgePlusOne(ctx, lcell, cellMap.get(pid), east, north, true);
            /// Currently, this will not correct itself for being on a map border
        	if(ocell == null) {
        		logger.warn("Null cell at idx " + cx + ", " + cy + " - coord " + east + ", " + north);
        		break;
        	}
        	lcell = ocell;
        	gcells[cx][cy] = ocell;
        	
        	if(dir == DirectionEnumType.WEST) {
        		if(cy < (diam -1) && gcells[cx][cy + 1] == null) {
        			dir = DirectionEnumType.SOUTH;
        		}
        	}
        	else if(dir == DirectionEnumType.SOUTH) {
        		if(cx < (diam -1) && gcells[cx + 1][cy] == null) {
        			dir = DirectionEnumType.EAST;
        		}
        	}
        	else if(dir == DirectionEnumType.EAST) {
        		if(cy > 0 && gcells[cx][cy - 1] == null) {
        			dir = DirectionEnumType.NORTH;
        		}
        	}
        	else if(dir == DirectionEnumType.NORTH) {
        		if(cx > 0 && gcells[cx - 1][cy] == null) {
        			dir = DirectionEnumType.WEST;
        		}
        	}

    	}
  
    	return gcells;
    }
    
    /// findCell currently only looks 1 more past the edge
    public static BaseRecord findCellToEdgePlusOne(OlioContext ctx, BaseRecord loc, List<BaseRecord> cells, int stateX, int stateY, boolean crossFeature) {

		if(loc == null) {
			logger.warn("Null current location!");
		}
		BaseRecord cell = loc;
		if(cells.size() == 0) {
			logger.error("Empty cell list");
			return null;
		}
		BaseRecord par = getParentLocation(ctx, loc);
		/*
		int xedge = (Rules.MAP_EXTERIOR_CELL_WIDTH - 1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
		int yedge = (Rules.MAP_EXTERIOR_CELL_HEIGHT - 1) * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
		*/
		/*
		int xedge = (Rules.MAP_EXTERIOR_CELL_WIDTH - 1);
		int yedge = (Rules.MAP_EXTERIOR_CELL_HEIGHT - 1);
		*/

		int xedge = Rules.MAP_EXTERIOR_CELL_WIDTH * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
		int yedge = Rules.MAP_EXTERIOR_CELL_HEIGHT * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;

		int eastings = loc.get(FieldNames.FIELD_EASTINGS);
		int northings = loc.get(FieldNames.FIELD_NORTHINGS);

		// If stateX or stateY move outside of the current cell, then find the adjacent cell
		if(stateX < 0 || stateX >= xedge || stateY < 0 || stateY >= yedge){
			/// cross cell
			if(stateX < 0) eastings -= 1;
			else if(stateX >= xedge) eastings += 1;
			if(stateY < 0) northings -= 1;
			else if(stateY >= yedge) northings += 1;
			
			/// Check for cross-feature
			if(
				crossFeature
				&&
				(eastings < 0 || eastings > (Rules.MAP_EXTERIOR_CELL_WIDTH - 1) || northings < 0 || northings > (Rules.MAP_EXTERIOR_CELL_HEIGHT - 1))
			) {
				//logger.info("Cross feature - " + loc.get(FieldNames.FIELD_NAME) + " to " + eastings + ", " + northings);
				if(par != null) {
					int peastings = par.get(FieldNames.FIELD_EASTINGS);
					int pnorthings = par.get(FieldNames.FIELD_NORTHINGS);
					long ppar = par.get(FieldNames.FIELD_PARENT_ID);
					if(ppar == 0L) {
						logger.warn("Cannot move beyond current region - parentId = 0");
						return null;
					}
					if(eastings < 0 && peastings > 0) {
						peastings = peastings - 1;
						eastings = (Rules.MAP_EXTERIOR_CELL_WIDTH - 1);
					}
					else if(eastings > (Rules.MAP_EXTERIOR_CELL_WIDTH - 1) && peastings < (Rules.MAP_EXTERIOR_FEATURE_WIDTH - 1)) {
						peastings = peastings + 1;
						eastings = 0;
					}
					if(northings < 0 && pnorthings > 0) {
						pnorthings = pnorthings - 1;
						northings = (Rules.MAP_EXTERIOR_CELL_HEIGHT - 1);
					}
					else if(northings > (Rules.MAP_EXTERIOR_CELL_HEIGHT - 1) && pnorthings <  (Rules.MAP_EXTERIOR_FEATURE_HEIGHT - 1)) {
						pnorthings = pnorthings + 1;
						northings = 0;
					}
					
					if(peastings == (int)par.get(FieldNames.FIELD_EASTINGS) && pnorthings == (int)par.get(FieldNames.FIELD_NORTHINGS)) {
						/// Didn't move, hitting the border or otherwise impassable
						// logger.warn("Didn't actually move: " + eastings + ", " + northings + "::" + peastings + ", " + pnorthings);
						cell = null;
					}
					else {
						//logger.info("Find adjacent: " + par.get(FieldNames.FIELD_PARENT_ID) + " " + peastings + ", " + pnorthings);
						BaseRecord padj = findAdjacentLocationByGrid(ctx, par.get(FieldNames.FIELD_PARENT_ID), peastings, pnorthings);
						if(padj == null) {
							logger.error("Failed to find parent " + par.get(FieldNames.FIELD_PARENT_ID) + " " + peastings + ", " + pnorthings);
						}
						else {
							if(!FieldNames.FIELD_FEATURE.equals(padj.get(FieldNames.FIELD_GEOTYPE))) {
								//logger.warn("Navigating outside of established realm.");
								/*
								if("featureless".equals(padj.get(FieldNames.FIELD_GEOTYPE))) {
									long ulid = ctx.getUniverse().get(OlioFieldNames.FIELD_LOCATIONS_ID);
									long wlid = ctx.getWorld().get(OlioFieldNames.FIELD_LOCATIONS_ID);
									long gid = padj.get("groupId");
									if(gid == ulid) {
										logger.warn("Cloning location to world from " + gid + "/" + ulid + " to " + wlid);
										padj = OlioUtil.cloneIntoGroup(padj, ctx.getWorld().get(FieldNames.FIELD_LOCATIONS));
										IOSystem.getActiveContext().getRecordUtil().createRecord(padj);
									}
								}
								*/
								
							}

							prepareCells(ctx, padj);
							List<BaseRecord> pcells = getCells(ctx, padj);
							if(pcells.size() == 0) {
								logger.warn("Failed to find adjacent cells");
							}
							else {
								// logger.info("Find adjacent in parent " + padj.get(FieldNames.FIELD_ID) + " with " + cells.size() + " cells at " + eastings + ", " + northings);
								cell = findCell(pcells, eastings, northings);
								if(cell == null) {
									// logger.error("Failed to find adjacent cell");
								}
							}
						}
					}
				}
				else {
					logger.warn("Parent location is null");
				}
			}
			else {
				cell = findCell(cells, eastings, northings);
			}
		}
		else {
			/// return current cell
			return loc;
		}
		return cell;
		
	}
	public static BaseRecord findCell(List<BaseRecord> cells, final int east, final int north) {
		BaseRecord cell = null;
		Optional<BaseRecord> ocell = cells.stream().filter(c -> {
			int ceast = c.get(FieldNames.FIELD_EASTINGS);
			int cnorth = c.get(FieldNames.FIELD_NORTHINGS);
			return (ceast == east && cnorth == north);
		}).findFirst();
		if(ocell.isPresent()) {
			cell = ocell.get();
		}
		else {
			// logger.warn("Couldn't find cell at " + east + ", " + north);
		}
		return cell;
	}
    
	public static int getMaximumHeight(List<BaseRecord> locations) {
		return getMaximumInt(locations, FieldNames.FIELD_NORTHINGS);
	}
	public static int getMaximumWidth(List<BaseRecord> locations) {
		return getMaximumInt(locations, FieldNames.FIELD_EASTINGS);
	}
	public static int getMinimumHeight(List<BaseRecord> locations) {
		return getMinimumInt(locations, FieldNames.FIELD_NORTHINGS);
	}
	public static int getMinimumWidth(List<BaseRecord> locations) {
		return getMinimumInt(locations, FieldNames.FIELD_EASTINGS);
	}
	public static int getMaximumInt(List<BaseRecord> locations, String field) {
		int val = 0;
		Optional<Integer> oval = locations.stream().map(l -> (int)l.get(field)).max(Comparator.naturalOrder());
		if(oval.isPresent()) {
			val = oval.get();
		}
		return val;
	}

	public static int getMinimumInt(List<BaseRecord> locations, String field) {
		int val = 0;
		Optional<Integer> oval = locations.stream().map(l -> (int)l.get(field)).min(Comparator.naturalOrder());
		if(oval.isPresent()) {
			val = oval.get();
		}
		return val;
	}
	
	public static List<BaseRecord> limitToAdjacent(OlioContext ctx, List<BaseRecord> pop, BaseRecord location){
		if(pop.size() == 0) {
			return new ArrayList<>();
		}
		long locId = location.get(FieldNames.FIELD_ID);
		List<BaseRecord> acells = getAdjacentCells(ctx, location, Rules.MAXIMUM_OBSERVATION_DISTANCE);
		List<Long> aids = acells.stream().map(c -> ((long)c.get(FieldNames.FIELD_ID))).collect(Collectors.toList());
		return pop.stream().filter(zp ->{
			BaseRecord zloc = zp.get(OlioFieldNames.FIELD_STATE_CURRENT_LOCATION);
			long zlid = (zloc != null ? zloc.get(FieldNames.FIELD_ID) : 0L);
			return (zlid > 0 && (zlid == locId || aids.contains(zlid)));
		}).collect(Collectors.toList());
	}

	
	public static List<BaseRecord> getAdjacentCells(OlioContext ctx, BaseRecord origin, int distance){
		BaseRecord parentLoc = getParentLocation(ctx, origin);
		return getAdjacentCells(getCells(ctx, parentLoc), origin, distance);
	}
	public static List<BaseRecord> getAdjacentCells(List<BaseRecord> cells, BaseRecord origin, int distance){
		IOSystem.getActiveContext().getReader().populate(origin, new String[] {FieldNames.FIELD_EASTINGS, FieldNames.FIELD_NORTHINGS});
		int east = origin.get(FieldNames.FIELD_EASTINGS);
		int north = origin.get(FieldNames.FIELD_NORTHINGS);
		
		int peast = east + distance;
		int neast = east - distance;
		int pnorth = north + distance;
		int nnorth = north - distance;
		long id = origin.get(FieldNames.FIELD_ID);
		List<BaseRecord> outCells = cells.stream().filter(c -> {
			long cid = c.get(FieldNames.FIELD_ID);
			int ceast = c.get(FieldNames.FIELD_EASTINGS);
			int cnorth = c.get(FieldNames.FIELD_NORTHINGS);
			return (
				cid != id
				&&
				ceast >= neast && ceast <= peast
				&&
				cnorth >= nnorth && cnorth <= pnorth
			);
		}).collect(Collectors.toList());
		return outCells;
	}
	public static BaseRecord getParentLocation(OlioContext ctx, BaseRecord location) {
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_ID, location.get(FieldNames.FIELD_PARENT_ID));
		OlioUtil.planMost(cq);
		return IOSystem.getActiveContext().getSearch().findRecord(cq);
	}

	public static List<BaseRecord> getCells(OlioContext ctx, BaseRecord location){
		long id = location.get(FieldNames.FIELD_ID);
		if(cellMap.containsKey(id)) {
			return cellMap.get(id);
		}
		IOSystem.getActiveContext().getReader().populate(location);
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, id);
		cq.field(FieldNames.FIELD_GEOTYPE, "cell");
		cq.field(FieldNames.FIELD_GROUP_ID, ctx.getWorld().get(OlioFieldNames.FIELD_LOCATIONS_ID));
		//cq.setCache(false);
		OlioUtil.planMost(cq);
		List<BaseRecord> cells = Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(cq));
		if(cells.size() > 0) {
			cellMap.put(id,  cells);
		}
		return cells;
	}
	public static BaseRecord[] getLocationsByFeature(BaseRecord location, String feature, long groupId){
		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, location.get(FieldNames.FIELD_PARENT_ID));
		/// DON'T search by group id because feature level grid squares may still be in the universe group
		if(groupId > 0L) {
			pq.field(FieldNames.FIELD_GROUP_ID, groupId);
		}
		pq.field(FieldNames.FIELD_FEATURE, feature);
		OlioUtil.planMost(pq);

		return IOSystem.getActiveContext().getSearch().findRecords(pq);
	}

	public static List<BaseRecord> findLocationsRegionByGrid(List<BaseRecord> locs, int x, int y, int r) {
		return locs.stream().filter(l -> {
			int e = (int)l.get(FieldNames.FIELD_EASTINGS);
			int n = (int)l.get(FieldNames.FIELD_NORTHINGS);
			return(
				e >= (x-r) && e <= (x+r)
				&&
				n >= (y-r) && n <= (y+r)
			);
		}).collect(Collectors.toList());
	}
	
	public static BaseRecord findAdjacentLocationByGrid(OlioContext ctx, long parentId, int x, int y) {
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, parentId);
		cq.field(FieldNames.FIELD_EASTINGS, x);
		cq.field(FieldNames.FIELD_NORTHINGS, y);
		// cq.setLimitFields(false);
		return IOSystem.getActiveContext().getSearch().findRecord(cq);
	}
	
	public static BaseRecord findLocationByGrid(List<BaseRecord> locs, int x, int y) {
		BaseRecord rec = null;
		Optional<BaseRecord> oloc = locs.stream().filter(r -> 
			x == ((int)r.get(FieldNames.FIELD_EASTINGS))
			&&
			y == ((int)r.get(FieldNames.FIELD_NORTHINGS))
		).findFirst();
		if(oloc.isPresent()) {
			rec = oloc.get();
		}
		return rec;
	}
	
	public static BaseRecord newLocation(OlioContext ctx, BaseRecord parent, String name, int id) {
		return newLocation(ctx, parent, name, id, false);
	}
	public static BaseRecord newLocation(OlioContext ctx, BaseRecord parent, String name, int id, boolean world) {
		BaseRecord rec = null;
		if(ctx.getWorld() == null) {
			return rec;
		}
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, (world ? ctx.getWorld() : ctx.getUniverse()).get("locations.path"));
		plist.parameter(FieldNames.FIELD_NAME, name);
		try {
			rec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_GEO_LOCATION, ctx.getOlioUser(), null, plist);
			rec.set(FieldNames.FIELD_GEOGRAPHY_TYPE, GeographyEnumType.PHYSICAL);
			if(parent != null) {
				rec.set(FieldNames.FIELD_PARENT_ID, parent.get(FieldNames.FIELD_ID));
				rec.set(FieldNames.FIELD_GEOTYPE, FieldNames.FIELD_FEATURE);
				rec.set(FieldNames.FIELD_GEONAMEID, id);
			}
			else {
				rec.set(FieldNames.FIELD_GEOTYPE, "country");
			}
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}

		return rec;
		
	}
	
	public static BaseRecord createLocation(OlioContext ctx, BaseRecord parent, String name, int id) {
		BaseRecord rec = newLocation(ctx, parent, name, id);
		if(rec == null) {
			return rec;
		}
		IOSystem.getActiveContext().getRecordUtil().createRecord(rec);
		return rec;
	}
	public static double calculateGridDistance(BaseRecord location1, BaseRecord location2) {
		double dist = 0.0;
		int x1 = location1.get(FieldNames.FIELD_EASTINGS);
		int y1 = location1.get(FieldNames.FIELD_NORTHINGS);
		int x2 = location2.get(FieldNames.FIELD_EASTINGS);
		int y2 = location2.get(FieldNames.FIELD_NORTHINGS);
		return distance(x1, y1, x2, y2);
	}
	
	/// Grid square coordinates are in meters, not lat/long, so the calculation is a slope
	///
	public static double distance(int x1, int y1, int x2, int y2) {
		return Math.sqrt(Math.pow((double)x2 - x1,2) + Math.pow((double)y2 - y1, 2));
	}

	public static float calculateDistance(BaseRecord location1, BaseRecord location2) {
		StringBuilder buff = new StringBuilder();
		buff.append("SELECT SQRT(POW(69.1 * (G1.latitude -  G2.latitude), 2) + POW(69.1 * (G2.longitude - G1.longitude) * COS(G1.latitude / 57.3), 2))");
		String tableName = IOSystem.getActiveContext().getDbUtil().getTableName(ModelNames.MODEL_GEO_LOCATION);
		buff.append(" FROM " + tableName + " G1 CROSS JOIN " + tableName + " G2");
		buff.append(" WHERE G1.id = ? AND G2.id = ?");
		float distance = 0F;
	    try (Connection con = IOSystem.getActiveContext().getDbUtil().getDataSource().getConnection(); PreparedStatement st = con.prepareStatement(buff.toString())){
	    	st.setLong(1, location1.get(FieldNames.FIELD_ID));
	    	st.setLong(2, location2.get(FieldNames.FIELD_ID));
	    	ResultSet rset = st.executeQuery();
	    	if(rset.next()) {
	    		distance = rset.getFloat(1);
	    	}
	    	rset.close();
			st.close();
			
		} catch (SQLException e) {
			logger.error(e);
	    }
	    return distance;
	}
	public static BaseRecord getRootLocation(OlioContext ctx) {
		return getRootLocation(ctx.getOlioUser(), ctx.getWorld());
	}
	public static BaseRecord getRootLocation(BaseRecord user, BaseRecord world) {
		BaseRecord loc = null;
		BaseRecord evt = EventUtil.getRootEvent(user, world);
		if(evt != null) {
			loc = evt.get(FieldNames.FIELD_LOCATION);
		}
		return loc;
	}
	public static List<BaseRecord> getRegionLocations(OlioContext ctx) {
		return getRegionLocations(ctx.getOlioUser(), ctx.getWorld());
	}
	public static List<BaseRecord> getRegionLocations(BaseRecord user, BaseRecord world) {
		List<BaseRecord> locs = new ArrayList<>();
		BaseRecord[] evts = EventUtil.getBaseRegionEvents(user, world);
		if(evts.length == 0) {
			logger.error("Zero region events were found");
		}
		for(BaseRecord evt : evts) {
			locs.add(evt.get(FieldNames.FIELD_LOCATION));
		}
		return locs;
	}
	
	public static BaseRecord randomLocation(BaseRecord user, BaseRecord world) {
		BaseRecord dir = world.get(FieldNames.FIELD_LOCATIONS);
		logger.info("Random location in " + dir.get(FieldNames.FIELD_ID));
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_GEOTYPE, FieldNames.FIELD_FEATURE);
		return OlioUtil.randomSelection(user, q);
	}
	
	
	public static String[] getAlternateNames(BaseRecord user, BaseRecord location, String altType) {
		return getAlternateNames(user, null, location, altType);
	}
	public static String[] getAlternateNames(BaseRecord user, BaseRecord world, BaseRecord location, String altType) {
		String key = location.get(FieldNames.FIELD_GROUP_ID) + "-" + altType;
		if(altNamesCache.containsKey(key)) {
			return altNamesCache.get(key);
		}
		
		IOSystem.getActiveContext().getReader().conditionalPopulate(location, new String[] {FieldNames.FIELD_GEONAMEID, FieldNames.FIELD_GROUP_ID});
		long groupId = location.get(FieldNames.FIELD_GROUP_ID);
		if(world != null) {
			groupId = world.get(OlioFieldNames.FIELD_LOCATIONS_ID);
		}
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, "altgeonameid", location.get(FieldNames.FIELD_GEONAMEID));
		q.field(FieldNames.FIELD_GEOTYPE, "alternateName");
		q.field(FieldNames.FIELD_GROUP_ID, groupId);
		logger.info("Alt Type: " + altType);
		if(altType != null) {
			q.field(FieldNames.FIELD_ALT_TYPE, altType);
		}

		q.setRequestRange(0, 10);
		QueryResult qr = null;
		List<String> names = new ArrayList<>();
		try {
			qr = IOSystem.getActiveContext().getSearch().find(q);
			names = Arrays.asList(qr.getResults()).stream().map(r -> (String)r.get(FieldNames.FIELD_NAME)).collect(Collectors.toList());
		} catch (ReaderException e) {
			logger.error(e);
		}
		// if(names.size() > 0) {
		altNamesCache.put(key, names.toArray(new String[0]));
		//}

		return names.toArray(new String[0]);
	}
	
	public static double getDistanceToState(BaseRecord state1, BaseRecord state2) {
		Coordinates c1 = getCoordinatesToState(state1);
		Coordinates c2 = getCoordinatesToState(state2);
		return distance(c1.getX(), c1.getY(), c2.getX(), c2.getY());
	}
	
	public static double getDistanceToCell(BaseRecord state1, BaseRecord loc1) {
		Coordinates c1 = getCoordinatesToState(state1);
		Coordinates c2 = getCoordinatesFromLocation(loc1);
		return distance(c1.getX(), c1.getY(), c2.getX(), c2.getY());
	}
	
	public static Coordinates getCoordinatesToState(BaseRecord state) {
		return new Coordinates(getXCoordinateToState(state), getYCoordinateToState(state));
	}
	
	public static Coordinates getCoordinatesFromLocation(BaseRecord loc) {
		return new Coordinates(getXCoordinate(loc), getYCoordinate(loc));
	}
	
	
	
	public static int getXCoordinateToState(BaseRecord state) {
		return getXCoordinate(state.get(OlioFieldNames.FIELD_CURRENT_LOCATION)) + (int)state.get(FieldNames.FIELD_CURRENT_EAST);
	}

	public static int getXCoordinate(BaseRecord loc) {
		if(loc == null) {
			logger.error("Location is null in distance calculation");
			return 0;
		}
		
		BaseRecord floc = getParentLocation(null, loc);

		int eastings = ((int)loc.get(FieldNames.FIELD_EASTINGS)) * (Rules.MAP_EXTERIOR_CELL_WIDTH * Rules.MAP_EXTERIOR_CELL_MULTIPLIER);
		int feastings = ((int)floc.get(FieldNames.FIELD_EASTINGS)) * (Rules.MAP_EXTERIOR_FEATURE_WIDTH * Rules.MAP_EXTERIOR_CELL_MULTIPLIER);
		return (eastings + feastings);

	}
	
	public static int getYCoordinateToState(BaseRecord state) {
		return getYCoordinate(state.get(OlioFieldNames.FIELD_CURRENT_LOCATION)) + (int)state.get(FieldNames.FIELD_CURRENT_NORTH);
	}
	
	public static int getYCoordinate(BaseRecord loc) {
		if(loc == null) {
			logger.error("Location is null in distance calculation");
			return 0;
		}
		
		BaseRecord floc = getParentLocation(null, loc);

		int northings = ((int)loc.get(FieldNames.FIELD_NORTHINGS)) * (Rules.MAP_EXTERIOR_CELL_HEIGHT * Rules.MAP_EXTERIOR_CELL_MULTIPLIER);
		int fnorthings = ((int)floc.get(FieldNames.FIELD_NORTHINGS)) * (Rules.MAP_EXTERIOR_FEATURE_HEIGHT * Rules.MAP_EXTERIOR_CELL_MULTIPLIER);

		return (northings + fnorthings);
	}
	
	/// Based on a 12' (North) 0 vs. Trigometric x+ 0
	///
	public static double getAngleBetweenStatesInDegrees(BaseRecord state1, BaseRecord state2) {
		
		Coordinates c1 = getCoordinatesToState(state1);
		Coordinates c2 = getCoordinatesToState(state2);
		return getAngleBetweenInDegrees(c1, c2);
	}
	public static double getAngleBetweenInDegrees(Coordinates c1, Coordinates c2) {
		double y = (c2.getY() - c1.getY());

		double x = (c2.getX() - c1.getX());
		double at2 = Math.atan2(y, x);
		
		/// rotate 90 degrees
		///
		at2 += 1.5708;

		if (at2 < 0) {
	    	at2 += (Math.PI * 2);
	    }
		
		// logger.info(c1.getX() + ", " + c1.getY() + " :: " + c2.getX() + ", " + c2.getY() + " " + at2);
		//return at2 * (180 / Math.PI);

	    return Math.toDegrees(at2);
	}
	/*
	 * 		double at2 = Math.atan2(y2 - y1, x2 - x1);
	     if (at2 < 0) {
	          at2 += Math.PI * 2;
	     }
	     if(meander && rand.nextDouble() > .5) {
	    	 at2 += (rand.nextDouble() > .5 ? 1 : -1) * rand.nextDouble(1);
	     }
	     double deg = Math.toDegrees(at2);
	 */
	public static double contactRelativity(BaseRecord rec1, BaseRecord rec2) {
		int maxDist = Rules.MAXIMUM_CONTACT_DISTANCE;
		double dist = getDistanceToState(rec1.get(FieldNames.FIELD_STATE), rec2.get(FieldNames.FIELD_STATE));
		if(dist <= 0) {
			// logger.warn("Zero or negative distance detected");
		}
		double perc = 1.0 - (dist / maxDist);
		if(perc < 0) perc = 0;
		return perc;
	}	
	public static double distanceRelativityToState(BaseRecord rec1, BaseRecord rec2) {
		return distanceRelativity(getDistanceToState(rec1.get(FieldNames.FIELD_STATE), rec2.get(FieldNames.FIELD_STATE)));
	}
	public static double distanceRelativityToCell(BaseRecord rec1, BaseRecord loc1) {
		return distanceRelativity(getDistanceToCell(rec1.get(FieldNames.FIELD_STATE), loc1));
	}
	public static double distanceRelativity(double dist) {
		int maxDist = Rules.MAXIMUM_OBSERVATION_DISTANCE * Rules.MAP_EXTERIOR_CELL_WIDTH * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;

		if(dist <= 0) {
			// logger.warn("Zero or negative distance detected");
			
		}
		double perc = 1.0 - (dist / maxDist);
		if(perc < 0) perc = 0;
		return perc;
	}
	

}
