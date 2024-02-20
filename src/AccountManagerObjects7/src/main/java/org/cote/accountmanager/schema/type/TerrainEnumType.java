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
		SAVANNA(13),
		LAKE(14),
		VALLEY(15),
		RIVER(16),
		JUNGLE(17),
		FOREST(18),
		STREAM(19),
		HILL(20),
		PLATEAU(21),
		MOUNTAIN(22),
		CAVE(23),
		SHELTER(24),
		INDOORS(25),
		UNDERWATER(26),
		AIR(27),
		VOID(28)
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
			colorMap.put(TerrainEnumType.SAVANNA, Color.decode("#C6F304"));
			colorMap.put(TerrainEnumType.LAKE, Color.decode("#6693F5"));
			colorMap.put(TerrainEnumType.VALLEY, Color.decode("#98FB98"));
			colorMap.put(TerrainEnumType.RIVER, Color.decode("#4D516D"));
			colorMap.put(TerrainEnumType.FOREST, Color.decode("#0B6623"));
			colorMap.put(TerrainEnumType.JUNGLE, Color.decode("#12AB3B"));
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
	   		VALLEY,
	   		FOREST,
	   		SAVANNA,
	   		JUNGLE
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
	    
	    public static boolean isTerra(TerrainEnumType tet) {
	    	return (
	    		tet != TerrainEnumType.UNKNOWN
	    		&& tet != TerrainEnumType.VOID
	    		&& tet != TerrainEnumType.SHELTER
	    		&& tet != TerrainEnumType.INDOORS
	    		&& tet != TerrainEnumType.CAVE
	    		&& tet != TerrainEnumType.AIR
	    		&& tet != TerrainEnumType.UNDERWATER
	    	);
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
			else if (val > 28) {
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
