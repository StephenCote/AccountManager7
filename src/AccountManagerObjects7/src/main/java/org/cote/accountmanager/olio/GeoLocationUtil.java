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

	public static int prepareMapGrid(OlioContext ctx) {
    	long id = ctx.getUniverse().get("locations.id");
    	int count = IOSystem.getActiveContext().getSearch().count(QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, id));
		if(count > 0) {
			return count;
		}
    	
    	int iter = 100;
    	BaseRecord zone = GeoLocationUtil.newLocation(ctx, null, GeoLocationUtil.GZD, iter++);
    	IOSystem.getActiveContext().getRecordUtil().createRecord(zone);

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
    				block.set("geoType", "admin2");
    				
    				blocks.add(block);
    				
    				// block.set("eastings", x * 100);
    				// block.set("northings", y * 100);
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
		long udir = context.getUniverse().get("locations.id");
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, udir);
		q.field("geoType", "feature");
		if(IOSystem.getActiveContext().getSearch().count(q) == 0) {
			Query q2 = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, udir);
			q2.field("geoType", "admin2");
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
    
	public static int prepareCells(OlioContext ctx, BaseRecord feature) {
		// ParameterList plist = ParameterList.newParameterList("path", ctx.getUniverse().get("locations.path"));
		IOSystem.getActiveContext().getReader().populate(feature);
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, feature.get(FieldNames.FIELD_ID));
		cq.field("geoType", "cell");
		int count = IOSystem.getActiveContext().getSearch().count(cq);
		if(count > 0) {
			return count;
		}
		int iter = 1 + IOSystem.getActiveContext().getSearch().count(QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, feature.get(FieldNames.FIELD_GROUP_ID)));
		List<BaseRecord> cells = new ArrayList<>();
		try {
			for(int y = 0; y < mapCellHeightM; y++) {
				for(int x = 0; x < mapCellWidthM; x++) {
					int ie = x; //  * 10
					int in = y; //  * 10
	    			BaseRecord cell = GeoLocationUtil.newLocation(ctx, feature, GeoLocationUtil.GZD + " " + feature.get("kident") + " " + GeoLocationUtil.df2.format((int)feature.get("eastings")) + "" + GeoLocationUtil.df2.format((int)feature.get("northings")) + " " + GeoLocationUtil.df3.format(ie) + "" + GeoLocationUtil.df3.format(in), iter++);
	    			/// Location util defaults to putting new locations into the universe.  change to the world group
	    			///
	    			cell.set(FieldNames.FIELD_GROUP_ID, ctx.getWorld().get("locations.id"));
	    			cell.set("area", (double)10);
	    			cell.set("gridZone", GeoLocationUtil.GZD);
	    			cell.set("kident", feature.get("kident"));
	    			cell.set("eastings", x);
	    			cell.set("northings", y);
	    			cell.set("geoType", "cell");
	    			cells.add(cell);
				}
			}
			TerrainUtil.blastCells(ctx, feature, cells, mapCellWidthM, mapCellHeightM);
			count = IOSystem.getActiveContext().getRecordUtil().createRecords(cells.toArray(new BaseRecord[0]));
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
    
    private static void placeRegion(BaseRecord[][] grid, int w, int h, String regionName){
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
    
	public static int getMaximumHeight(List<BaseRecord> locations) {
		return getMaximumInt(locations, "northings");
	}
	public static int getMaximumWidth(List<BaseRecord> locations) {
		return getMaximumInt(locations, "eastings");
	}
	public static int getMinimumHeight(List<BaseRecord> locations) {
		return getMinimumInt(locations, "northings");
	}
	public static int getMinimumWidth(List<BaseRecord> locations) {
		return getMinimumInt(locations, "eastings");
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
		long locId = location.get(FieldNames.FIELD_ID);
		List<BaseRecord> acells = GeoLocationUtil.getAdjacentCells(ctx, location, Rules.MAXIMUM_OBSERVATION_DISTANCE);
		List<Long> aids = acells.stream().map(c -> ((long)c.get(FieldNames.FIELD_ID))).collect(Collectors.toList());
		return pop.stream().filter(zp ->{
			BaseRecord zloc = zp.get("state.currentLocation");
			long zlid = (zloc != null ? zloc.get("id") : 0L);
			return (zlid > 0 && (zlid == locId || aids.contains(zlid)));
		}).collect(Collectors.toList());
	}
	
	public static void prepareGridCells(OlioContext ctx, BaseRecord location) {
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
			for(int y = 0; y < Rules.MAP_EXTERIOR_CELL_HEIGHT; y++) {
				for(int x = 0; x < Rules.MAP_EXTERIOR_CELL_WIDTH; x++) {
					int ie = x; //  * 10
					int in = y; //  * 10
	    			BaseRecord cell = GeoLocationUtil.newLocation(ctx, location, GZD + " " + location.get("kident") + " " + df2.format((int)location.get("eastings")) + "" + df2.format((int)location.get("northings")) + " " + df3.format(ie) + "" + df3.format(in), iter++);
	    			/// Location util defaults to putting new locations into the universe.  change to the world group
	    			///
	    			cell.set(FieldNames.FIELD_GROUP_ID, ctx.getWorld().get("locations.id"));
	    			cell.set("area", (double)10);
	    			cell.set("gridZone", GZD);
	    			cell.set("kident", location.get("kident"));
	    			cell.set("eastings", x);
	    			cell.set("northings", y);
	    			cell.set("geoType", "cell");
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

	
	public static List<BaseRecord> getAdjacentCells(OlioContext ctx, BaseRecord origin, int distance){
		BaseRecord parentLoc = getParentLocation(ctx, origin);
		return getAdjacentCells(getCells(ctx, parentLoc), origin, distance);
	}
	public static List<BaseRecord> getAdjacentCells(List<BaseRecord> cells, BaseRecord origin, int distance){
		IOSystem.getActiveContext().getReader().populate(origin, new String[] {"eastings", "northings"});
		int east = origin.get("eastings");
		int north = origin.get("northings");
		
		int peast = east + distance;
		int neast = east - distance;
		int pnorth = north + distance;
		int nnorth = north - distance;
		long id = origin.get("id");
		List<BaseRecord> outCells = cells.stream().filter(c -> {
			long cid = c.get("id");
			int ceast = c.get("eastings");
			int cnorth = c.get("northings");
			return (cid != id && (ceast == peast || ceast == neast || ceast == east) && (cnorth == pnorth || cnorth == nnorth || cnorth == north));
		}).collect(Collectors.toList());
		return outCells;
	}
	public static BaseRecord getParentLocation(OlioContext ctx, BaseRecord location) {
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_ID, location.get(FieldNames.FIELD_PARENT_ID));
		cq.setLimitFields(false);
		return IOSystem.getActiveContext().getSearch().findRecord(cq);
	}

	public static List<BaseRecord> getCells(OlioContext ctx, BaseRecord location){
		long id = location.get(FieldNames.FIELD_ID);
		if(cellMap.containsKey(id)) {
			return cellMap.get(id);
		}
		IOSystem.getActiveContext().getReader().populate(location);
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, id);
		cq.field("geoType", "cell");
		cq.setCache(false);
		cq.setLimitFields(false);
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
		pq.field("feature", feature);
		try {
			pq.set(FieldNames.FIELD_LIMIT_FIELDS, false);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return IOSystem.getActiveContext().getSearch().findRecords(pq);
	}

	public static List<BaseRecord> findLocationsRegionByGrid(List<BaseRecord> locs, int x, int y, int r) {
		return locs.stream().filter(l -> {
			int e = (int)l.get("eastings");
			int n = (int)l.get("northings");
			return(
				e >= (x-r) && e <= (x+r)
				&&
				n >= (y-r) && n <= (y+r)
			);
		}).collect(Collectors.toList());
	}
	
	public static BaseRecord findAdjacentLocationByGrid(OlioContext ctx, long parentId, int x, int y) {
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, parentId);
		cq.field("eastings", x);
		cq.field("northings", y);
		// cq.setLimitFields(false);
		return IOSystem.getActiveContext().getSearch().findRecord(cq);
	}
	
	public static BaseRecord findLocationByGrid(List<BaseRecord> locs, int x, int y) {
		BaseRecord rec = null;
		Optional<BaseRecord> oloc = locs.stream().filter(r -> 
			x == ((int)r.get("eastings"))
			&&
			y == ((int)r.get("northings"))
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
		ParameterList plist = ParameterList.newParameterList("path", (world ? ctx.getWorld() : ctx.getUniverse()).get("locations.path"));
		plist.parameter("name", name);
		try {
			rec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_GEO_LOCATION, ctx.getOlioUser(), null, plist);
			rec.set("geographyType", GeographyEnumType.PHYSICAL);
			if(parent != null) {
				rec.set(FieldNames.FIELD_PARENT_ID, parent.get(FieldNames.FIELD_ID));
				rec.set("geoType", "feature");
				rec.set("geonameid", id);
			}
			else {
				rec.set("geoType", "country");
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
		int x1 = location1.get("eastings");
		int y1 = location1.get("northings");
		int x2 = location2.get("eastings");
		int y2 = location2.get("northings");
		return distance(x1, y1, x2, y2);
	}
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
			loc = evt.get("location");
		}
		return loc;
	}
	public static BaseRecord[] getRegionLocations(OlioContext ctx) {
		return getRegionLocations(ctx.getOlioUser(), ctx.getWorld());
	}
	public static BaseRecord[] getRegionLocations(BaseRecord user, BaseRecord world) {
		List<BaseRecord> locs = new ArrayList<>();
		BaseRecord[] evts = EventUtil.getBaseRegionEvents(user, world);
		if(evts.length == 0) {
			logger.error("Zero region events were found");
		}
		for(BaseRecord evt : evts) {
			locs.add(evt.get("location"));
		}
		return locs.toArray(new BaseRecord[0]);
	}
	
	public static BaseRecord randomLocation(BaseRecord user, BaseRecord world) {
		BaseRecord dir = world.get("locations");
		logger.info("Random location in " + dir.get(FieldNames.FIELD_ID));
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field("geoType", "feature");
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
		
		IOSystem.getActiveContext().getReader().conditionalPopulate(location, new String[] {"geonameid", FieldNames.FIELD_GROUP_ID});
		long groupId = location.get(FieldNames.FIELD_GROUP_ID);
		if(world != null) {
			groupId = world.get("locations.id");
		}
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, "altgeonameid", location.get("geonameid"));
		q.field("geoType", "alternateName");
		q.field(FieldNames.FIELD_GROUP_ID, groupId);
		logger.info("Alt Type: " + altType);
		if(altType != null) {
			q.field("altType", altType);
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

}
