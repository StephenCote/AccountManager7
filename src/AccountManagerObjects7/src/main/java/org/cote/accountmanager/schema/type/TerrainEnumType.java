package org.cote.accountmanager.schema.type;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public enum TerrainEnumType {
		UNKNOWN(-1),
		OCEAN(0),
		GLACIER(1),
		TUNDRA(2),
		SHORELINE(4),
		MARSH(5),
		SWAMP(6),
		CLEAR(7),
		DESERT(8),
		DUNES(9),
		OASIS(9),
		POND(11),
		PLAINS(10),
		GRASS(11),
		LAKE(14),
		VALLEY(12),
		RIVER(13),
		FOREST(15),
		HILL(16),
		PLATEAU(17),
		MOUNTAIN(18),
		CAVE(19),
		SHELTER(20),
		INDOORS(21),
		VOID(22)
		;
	
	   	private int val;
	   	private static List<TerrainEnumType> inlandWater = Arrays.asList(new TerrainEnumType[] {
	   		RIVER,
	   		SWAMP,
	   		MARSH,
	   		LAKE,
	   		VALLEY,
	   		FOREST
		});
	   	private static List<TerrainEnumType> arable = Arrays.asList(new TerrainEnumType[] {
	   		CLEAR,
	   		OASIS,
	   		PLAINS,
	   		GRASS,
	   		VALLEY,
	   		FOREST
	   	});
	   	private static SecureRandom rand = new SecureRandom();
	    private static Map<Integer, TerrainEnumType> terrainMap = new HashMap<Integer, TerrainEnumType>();

	    static {
	        for (TerrainEnumType ter : TerrainEnumType.values()) {
	            terrainMap.put(ter.val, ter);
	        }
	    }
	    
	    private TerrainEnumType(final int val) {
	    	this.val = val;
	    }

	    public static List<TerrainEnumType> getArable(){
	    	return arable;
	    }
	    
	    public static List<TerrainEnumType> getInlandWater(){
	    	return inlandWater;
	    }
	    
	    public static TerrainEnumType valueOf(int val) {
			if(val < 0) {
				return UNKNOWN;
			}
			else if (val > 22) {
				return VOID;
			}
	        return terrainMap.get(val);
	    }
	    public static int valueOf(TerrainEnumType ter) {
	    	return terrainMap.entrySet().stream().filter(entry -> ter == entry.getValue()).map(Map.Entry::getKey).findFirst().get();
	    }
}
