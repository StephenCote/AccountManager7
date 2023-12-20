package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.TerrainEnumType;

public class TerrainUtil {
	public static final Logger logger = LogManager.getLogger(TerrainUtil.class);
	private static final SecureRandom rand = new SecureRandom();
	
	public static TerrainEnumType getCommonTerrainType(Map<TerrainEnumType, Double> map){
		TerrainEnumType otet = TerrainEnumType.UNKNOWN;
		double max = 0.0;
		for(TerrainEnumType tet : map.keySet()) {
			if(map.get(tet) > max) {
				max = map.get(tet);
				otet = tet;
			}
		}
		return otet;
	}
	public static Map<TerrainEnumType, Double> getTerrainTypesPerc(List<BaseRecord> locs){
		Map<TerrainEnumType, Double> omap = new HashMap<>();
		Map<TerrainEnumType, Integer> map = getTerrainTypes(locs);
		int total = 0;
		for(Integer i : map.values()) {
			total += i;
		}
		for(TerrainEnumType tet : map.keySet()) {
			double perc = map.get(tet) / total;
			omap.put(tet, perc);
		}
		return omap;
	}
	public static Map<TerrainEnumType, Integer> getTerrainTypes(List<BaseRecord> locs){
		Map<TerrainEnumType, Integer> types = new HashMap<>();
		for(BaseRecord r : locs) {
			TerrainEnumType tet = TerrainEnumType.valueOf((String)r.get("terrainType"));
			int count = 0;
			if(types.containsKey(tet)) {
				count = types.get(tet);
			}
			types.put(tet, count + 1);
		}
		return types;
	}
	
	public static void blastCells(OlioContext ctx, BaseRecord location, List<BaseRecord> cells, int mapWidth, int mapHeight) {
		logger.info("Terrain blasting " + location.get(FieldNames.FIELD_NAME) + " cells");
		Map<TerrainEnumType, Double> map = getTerrainTypesPerc(cells);
		// fill in everything with the parent terrain type
		TerrainEnumType tet = TerrainEnumType.valueOf((String)location.get("terrainType"));
		for(BaseRecord l : cells) {
			TerrainEnumType ctet = TerrainEnumType.valueOf((String)l.get("terrainType"));
			if(ctet == TerrainEnumType.UNKNOWN) {
				try {
					l.set("terrainType", tet);
				} catch (FieldException | ValueException | ModelNotFoundException e) {
					logger.error(e);
				}
			}
		}
		/// Blast the cells with the most commonly found terrains based on the frequency of occurrence
		for(TerrainEnumType ltet : map.keySet()) {
			blastTerrain(cells, ltet, mapWidth, mapHeight, mapWidth / 5, map.get(ltet), 0, 0);
		}
	}
	
	public static void blastAndWalk(OlioContext ctx, List<BaseRecord> locs, int mapWidth, int mapHeight) {
		blastRegions(locs, mapWidth, mapHeight);
		
		
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
	
	protected static void longShortWalk(List<BaseRecord> locs, Set<BaseRecord> targSet, List<BaseRecord> srcs, List<BaseRecord> targs, TerrainEnumType walkType, boolean isShort) {
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
	protected static void walk(List<BaseRecord> locs, BaseRecord loc, BaseRecord targ, TerrainEnumType type, boolean meander) {

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
	
	public static void blastRegions(List<BaseRecord> locs, int mapWidth, int mapHeight) {
		blastRegions(locs, TerrainEnumType.getArable().get(rand.nextInt(TerrainEnumType.getArable().size())), mapWidth, mapHeight);
	}
	public static void blastRegions(List<BaseRecord> locs, TerrainEnumType defaultTerrain, int mapWidth, int mapHeight) {
		try {
			for(TerrainEnumType tet : TerrainEnumType.getArable()) {
				blastRegions(locs, tet, mapWidth, mapHeight, 25);
			}
			
			for(TerrainEnumType tet : TerrainEnumType.getRockyTerrain()) {
				blastRegions(locs, tet, mapWidth, mapHeight, 25);
			}
			
			/// plant some water for later connections
			blastRegions(locs, TerrainEnumType.LAKE, mapWidth, mapHeight, 2);
			//blastRegions(locs, TerrainEnumType, 2);
			/*
			for(TerrainEnumType tet : TerrainEnumType.getInlandWater()) {
				blastRegions(locs, tet, 2);
			}
			*/
			/// Blast oceans at the corners
			if(rand.nextDouble() < .25) blastTerrain(locs, TerrainEnumType.OCEAN, mapWidth, mapHeight, 10, .75, 0, 0);
			if(rand.nextDouble() < .25) blastTerrain(locs, TerrainEnumType.OCEAN, mapWidth, mapHeight, 10, .75, 0, mapHeight - 1);
			if(rand.nextDouble() < .25) blastTerrain(locs, TerrainEnumType.OCEAN, mapWidth, mapHeight, 10, .75, mapWidth - 1, 0);
			if(rand.nextDouble() < .25) blastTerrain(locs, TerrainEnumType.OCEAN, mapWidth, mapHeight, 10, .75, mapWidth - 1, mapHeight - 1);
			
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
	protected static void blastRegions(List<BaseRecord> locs, TerrainEnumType tet, int mapWidth, int mapHeight, int iterations) {
		for(int i = 0; i < iterations; i++) {
			blastTerrain(locs, tet, mapWidth, mapHeight, 0, 0.0, -1, -1);
		}
	}
	protected static void blastTerrain(List<BaseRecord> locs, TerrainEnumType tet, int gridWidth, int gridHeight, int radius, double impact, int x, int y) {
		if(radius <= 0) {
			radius = rand.nextInt(gridWidth/10);
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
}
