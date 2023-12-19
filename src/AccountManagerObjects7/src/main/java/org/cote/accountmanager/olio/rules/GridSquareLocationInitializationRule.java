package org.cote.accountmanager.olio.rules;

import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.TerrainEnumType;

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
	    			cell.set("eastings", x);
	    			cell.set("northings", y);
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

	public void blastAndWalk(OlioContext ctx, List<BaseRecord> locs) {
		blastRegions(locs);
		
		
		List<BaseRecord> glaciers = locs.stream().filter(l -> TerrainEnumType.valueOf((String)l.get("terrainType")) == TerrainEnumType.GLACIER).collect(Collectors.toList());
		List<BaseRecord> lakes = locs.stream().filter(l -> TerrainEnumType.valueOf((String)l.get("terrainType")) == TerrainEnumType.LAKE).collect(Collectors.toList());
		List<BaseRecord> rivers = locs.stream().filter(l -> TerrainEnumType.valueOf((String)l.get("terrainType")) == TerrainEnumType.RIVER).collect(Collectors.toList());
		List<BaseRecord> oceans = locs.stream().filter(l -> TerrainEnumType.valueOf((String)l.get("terrainType")) == TerrainEnumType.OCEAN).collect(Collectors.toList());
		List<BaseRecord> mountains = locs.stream().filter(l -> TerrainEnumType.valueOf((String)l.get("terrainType")) == TerrainEnumType.MOUNTAIN).collect(Collectors.toList());
		
		Set<BaseRecord> targSet = new HashSet<>();

		logger.info("Compute walks");
		longShortWalk(locs, targSet, mountains, rivers, TerrainEnumType.RIVER, true);
		longShortWalk(locs, targSet, mountains, rivers, TerrainEnumType.RIVER, false);
		longShortWalk(locs, targSet, rivers, oceans, TerrainEnumType.RIVER, true);
		longShortWalk(locs, targSet, rivers, lakes, TerrainEnumType.RIVER, false);
		longShortWalk(locs, targSet, lakes, oceans, TerrainEnumType.RIVER, true);
		longShortWalk(locs, targSet, lakes, oceans, TerrainEnumType.RIVER, false);
		longShortWalk(locs, targSet, glaciers, lakes, TerrainEnumType.RIVER, true);
		longShortWalk(locs, targSet, glaciers, lakes, TerrainEnumType.RIVER, false);		
	}
	
	public void longShortWalk(List<BaseRecord> locs, Set<BaseRecord> targSet, List<BaseRecord> srcs, List<BaseRecord> targs, TerrainEnumType walkType, boolean isShort) {
		double currentDist = -1;
		if(srcs.size() == 0 || targs.size() == 0) {
			logger.warn("Invalid pairs");
			return;
		}

		BaseRecord mark1 = null;
		BaseRecord mark2 = null;
		for(BaseRecord r1: srcs) {
			for(BaseRecord r2: targs) {
				if(targSet.contains(r2)) {
					continue;
				}
				double dist = GeoLocationUtil.calculateGridDistance(r1, r2);
				if(currentDist == -1 || dist > 0 && ((isShort && dist < currentDist) || (!isShort && dist > currentDist))){
					currentDist = dist;
					mark1 = r1;
					mark2 = r2;
				}
			}
		}
		if(mark1 != null && mark2 != null) {
			targSet.add(mark2);
			logger.info((isShort ? "Short" : "Long") + " walk between " + mark1.get("terrainType") + " " + mark1.get(FieldNames.FIELD_NAME) + " to " + mark2.get("terrainType") + " " + mark2.get(FieldNames.FIELD_NAME) + " - " + currentDist + " sqs");
			walk(locs, mark1, mark2, walkType, true);
		}
	}
	protected void walk(List<BaseRecord> locs, BaseRecord loc, BaseRecord targ, TerrainEnumType type, boolean meander) {
		SecureRandom rand = new SecureRandom();
		int x1 = loc.get("eastings");
		int y1 = loc.get("northings");
		int x2 = targ.get("eastings");
		int y2 = targ.get("northings");
		double at2 = Math.atan2(y2 - y1, x2 - x1);
	     if (at2 < 0) {
	          at2 += Math.PI * 2;
	     }
	     if(meander && rand.nextDouble() > .5) {
	    	 at2 += (rand.nextDouble() > .5 ? 1 : -1) * rand.nextDouble(1);
	     }
	     double deg = Math.toDegrees(at2);

	     int x3 = x1;
	     int y3 = y1;
	     if(deg == 0) {
	    	 y3--;
	     }
	     else if(deg == 180) {
	    	 y3++;
	     }
	     else if(deg == 90) {
	    	 x3++;
	     }
	     else if(deg == 270) {
	    	 x3--;
	     }
	     else if(deg > 270) {
	    	 x3--;
	    	 y3--;
	     }
	     else if(deg > 180) {
	    	 x3--;
	    	 y3++;
	     }
	     else if(deg > 90) {
	    	 x3++;
	    	 y3++;
	     }
	     else {
	    	 x3++;
	    	 y3--;
	     }
	     if(x3 < 0) x3 = 0;
	     if(y3 < 0) y3 = 0;

	     if(y2 == y3 && x2 == x3) {
	    	 logger.info("Calc end crash");
	    	 return;
	     }
	     BaseRecord nloc = GeoLocationUtil.findLocationByGrid(locs, x3, y3);
	     if(nloc == null) {
	    	 logger.warn("Walked off map at " + x3 + ", " + y3);
	    	 return;
	     }
	     else {
	    	 TerrainEnumType wet = TerrainEnumType.valueOf((String)nloc.get("terrainType"));
	    	 if(wet != type) {
	    		 try {
					nloc.set("terrainType", wet);
				} catch (FieldException | ValueException | ModelNotFoundException e) {
					logger.error(e);
				}
	    		walk(locs, nloc, targ, type, meander);
	    	 }
	     }
	}
	
	public void blastRegions(List<BaseRecord> locs) {
		blastRegions(locs, TerrainEnumType.getArable().get(rand.nextInt(TerrainEnumType.getArable().size())));
	}
	public void blastRegions(List<BaseRecord> locs, TerrainEnumType defaultTerrain) {
		try {
			for(TerrainEnumType tet : TerrainEnumType.getArable()) {
				blastRegions(locs, tet, 25);
			}
			
			for(TerrainEnumType tet : TerrainEnumType.getRockyTerrain()) {
				blastRegions(locs, tet, 25);
			}
			
			/// plant some water for later connections
			blastRegions(locs, TerrainEnumType.LAKE, 2);
			//blastRegions(locs, TerrainEnumType, 2);
			/*
			for(TerrainEnumType tet : TerrainEnumType.getInlandWater()) {
				blastRegions(locs, tet, 2);
			}
			*/
			/// Blast oceans at the corners
			if(rand.nextDouble() < .25) blastTerrain(locs, TerrainEnumType.OCEAN, mapWidth1km, mapHeight1km, 10, .75, 0, 0);
			if(rand.nextDouble() < .25) blastTerrain(locs, TerrainEnumType.OCEAN, mapWidth1km, mapHeight1km, 10, .75, 0, mapHeight1km - 1);
			if(rand.nextDouble() < .25) blastTerrain(locs, TerrainEnumType.OCEAN, mapWidth1km, mapHeight1km, 10, .75, mapWidth1km - 1, 0);
			if(rand.nextDouble() < .25) blastTerrain(locs, TerrainEnumType.OCEAN, mapWidth1km, mapHeight1km, 10, .75, mapWidth1km - 1, mapHeight1km - 1);
			
			// fill remainder with random arable
			for(BaseRecord l : locs) {
				TerrainEnumType tet = TerrainEnumType.valueOf((String)l.get("terrainType"));
				if(tet == TerrainEnumType.UNKNOWN) {
					l.set("terrainType", defaultTerrain);
				}
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}
	}
	public void blastRegions(List<BaseRecord> locs, TerrainEnumType tet, int iterations) {
		for(int i = 0; i < iterations; i++) {
			blastTerrain(locs, tet, mapWidth1km, mapHeight1km, 0, 0.0, -1, -1);
		}
	}
	public void blastTerrain(List<BaseRecord> locs, TerrainEnumType tet, int gridWidth, int gridHeight, int radius, double impact, int x, int y) {
		if(radius <= 0) {
			radius = rand.nextInt(5);
		}
		if(impact <= 0) {
			impact = rand.nextDouble();
		}
		if(x < 0 || x >= gridWidth) x = rand.nextInt(gridWidth);
		if(y < 0 || y >= gridHeight) y = rand.nextInt(gridHeight);
		
		while(tet == TerrainEnumType.UNKNOWN || tet == TerrainEnumType.VOID || tet == TerrainEnumType.SHELTER || tet == TerrainEnumType.INDOORS || tet == TerrainEnumType.CAVE) {
			tet = OlioUtil.randomEnum(TerrainEnumType.class);
		}
		
		BaseRecord irec = GeoLocationUtil.findLocationByGrid(locs, x, y);
		if(irec == null) {
			logger.error("Failed to find location at " + x + ", " + y);
			return;
		}
		
		List<BaseRecord> irecs = GeoLocationUtil.findLocationsRegionByGrid(locs, x, y, radius);
		if(irecs.size() == 0 || irecs.size() == locs.size()) {
			logger.error("Invalid splash zone!");
			return;
		}
		
		// logger.info("Compute " + tet.toString() + " blast at " + x + ", " + y + " for " + radius + " at " + impact + " effect");
		int tetVal = TerrainEnumType.valueOf(tet);

		for(BaseRecord r : irecs) {
			TerrainEnumType utet = tet;
			TerrainEnumType itet = TerrainEnumType.valueOf((String)r.get("terrainType"));
			int rx = r.get("eastings");
			int ry = r.get("northings");
			if(itet != TerrainEnumType.UNKNOWN && itet != tet) {
				int itetVal = TerrainEnumType.valueOf(itet);
				int diff = (int)Math.abs((tetVal - itetVal) * impact);
				if(diff == 0) {
					utet = itet;
				}
				else {
					utet = TerrainEnumType.valueOf(diff);
				}
				if(utet == TerrainEnumType.UNKNOWN){
					utet = TerrainEnumType.OCEAN;
				}
				else if(utet == TerrainEnumType.VOID || utet == TerrainEnumType.INDOORS || utet == TerrainEnumType.SHELTER) {
					utet = TerrainEnumType.MOUNTAIN;
				}
				if(utet == TerrainEnumType.OCEAN && (
					(rx > 10 && rx < (gridWidth - 10))
					||
					(ry > 10 && ry < (gridHeight - 10))
				)){
					// logger.info("Shore ocean at " + rx + ", " + ry + " because rx > 10 && rx < " + (gridWidth - 10) + " or ry > 10 && ry < " + (gridHeight - 10));
					utet = TerrainEnumType.SHORELINE;
				}
			}
			try {
				r.set("terrainType", utet);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
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
	    			grid[x][y].set("geoType", "featureless");
	    			blocks.add(grid[x][y]);
	    		}
	    	}
	    	// logger.info("Staged " + blocks.size());
	    	logger.info("Connecting regions");
	    	connectRegions(grid);
	    	logger.info("Blasting regions");
	    	blastAndWalk(ctx, blocks);
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
    
    private void placeRegion(BaseRecord[][] grid, int w, int h, String regionName){
        Point pos = findSpaceLocation(grid, w, h);
         if(pos != null){
            logger.info("Connected regions at " + pos.getX() + ", " + pos.getY());
            for(int i = pos.getX(); i < pos.getX() + w; i++){
                for(int b = pos.getY(); b < pos.getY() + h; b++){
                    try {
						grid[i][b].set("geoType", "feature");
						grid[i][b].set("feature", regionName);
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
