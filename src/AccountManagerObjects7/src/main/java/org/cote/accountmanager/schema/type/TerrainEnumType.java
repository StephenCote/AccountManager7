package org.cote.accountmanager.schema.type;

import java.awt.Color;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public enum TerrainEnumType {
		UNKNOWN(-1),
		GLACIER(0),
		TUNDRA(1),
		OCEAN(2),
		SHORELINE(3),
		MARSH(4),
		SWAMP(5),
		CLEAR(6),
		DESERT(7),
		DUNES(8),
		OASIS(9),
		POND(10),
		PLAINS(11),
		GRASS(12),
		LAKE(13),
		VALLEY(14),
		RIVER(15),
		FOREST(16),
		STREAM(17),
		HILL(18),
		PLATEAU(19),
		MOUNTAIN(20),
		CAVE(21),
		SHELTER(22),
		INDOORS(23),
		VOID(24)
	;
	
	 private static Map<TerrainEnumType, Color> colorMap = new HashMap<>();
		static {	    
			colorMap.put(TerrainEnumType.UNKNOWN, Color.decode("#000000"));
			colorMap.put(TerrainEnumType.OCEAN, Color.decode("#131E3A"));
			colorMap.put(TerrainEnumType.GLACIER, Color.decode("#89CFEF"));
			colorMap.put(TerrainEnumType.TUNDRA, Color.decode("#D9DDDC"));
			colorMap.put(TerrainEnumType.SHORELINE, Color.decode("#D6CFC7"));
			colorMap.put(TerrainEnumType.MARSH, Color.decode("#8A9A5B"));
			colorMap.put(TerrainEnumType.SWAMP, Color.decode("#4F7942"));
			colorMap.put(TerrainEnumType.CLEAR, Color.decode("#FFFDD0"));
			colorMap.put(TerrainEnumType.DESERT, Color.decode("#D2B55B"));
			colorMap.put(TerrainEnumType.DUNES, Color.decode("#FFD300"));
			colorMap.put(TerrainEnumType.OASIS, Color.decode("#9DC103"));
			colorMap.put(TerrainEnumType.POND, Color.decode("#7285A5"));
			colorMap.put(TerrainEnumType.PLAINS, Color.decode("#A9BA90"));
			colorMap.put(TerrainEnumType.GRASS, Color.decode("#3F7040"));
			colorMap.put(TerrainEnumType.LAKE, Color.decode("#6693F5"));
			colorMap.put(TerrainEnumType.VALLEY, Color.decode("#98FB98"));
			colorMap.put(TerrainEnumType.RIVER, Color.decode("#4D516D"));
			colorMap.put(TerrainEnumType.FOREST, Color.decode("#0B6623"));
			colorMap.put(TerrainEnumType.STREAM, Color.decode("#0F52BA"));
			colorMap.put(TerrainEnumType.HILL, Color.decode("#81560F"));
			colorMap.put(TerrainEnumType.PLATEAU, Color.decode("#EEDC82"));
			colorMap.put(TerrainEnumType.MOUNTAIN, Color.decode("#877B73"));
			colorMap.put(TerrainEnumType.CAVE, Color.decode("#4849B0"));
			colorMap.put(TerrainEnumType.SHELTER, Color.decode("#8b4000"));
			colorMap.put(TerrainEnumType.INDOORS, Color.decode("#81560F"));
			colorMap.put(TerrainEnumType.VOID, Color.decode("#000000"));
		}
	   	private int val;
	   	private static List<TerrainEnumType> inlandWater = Arrays.asList(new TerrainEnumType[] {
	   		STREAM,
	   		RIVER,
	   		SWAMP,
	   		MARSH,
	   		LAKE,
	   		POND,
	   		GLACIER
		});
	   	
	   	private static List<TerrainEnumType> arable = Arrays.asList(new TerrainEnumType[] {
	   		CLEAR,
	   		OASIS,
	   		PLAINS,
	   		GRASS,
	   		OASIS,
	   		VALLEY,
	   		FOREST
	   	});
	   	
	   	private static List<TerrainEnumType> rockyTerrain = Arrays.asList(new TerrainEnumType[] {
	   		MOUNTAIN,
	   		HILL,
	   		PLATEAU,
	   		DUNES,
	   		DESERT
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
	    
	    public static List<TerrainEnumType> getRockyTerrain(){
	    	return rockyTerrain;
	    }
	    
	    public static List<TerrainEnumType> getInlandWater(){
	    	return inlandWater;
	    }
	    
	    public static TerrainEnumType valueOf(int val) {
			if(val < 0) {
				return UNKNOWN;
			}
			else if (val > 24) {
				return VOID;
			}
	        return terrainMap.get(val);
	    }
	    public static int valueOf(TerrainEnumType ter) {
	    	return terrainMap.entrySet().stream().filter(entry -> ter == entry.getValue()).map(Map.Entry::getKey).findFirst().get();
	    }
	    
	   
	    public static Color getColor(TerrainEnumType tet) {
	    	return colorMap.get(tet);
	    }
}