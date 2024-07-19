package org.cote.accountmanager.olio;

import java.util.HashMap;
import java.util.Map;

public enum DirectionEnumType {
	NORTH(0, -1),
	NORTHEAST(1, -1),
	EAST(1, 0),
	SOUTHEAST(1, 1),
	SOUTH(0, 1),
	SOUTHWEST(-1, 1),
	WEST(-1, 0),
	NORTHWEST(-1, -1),
	UNKNOWN(0, 0);
	
	  private int x = 0;
	  private int y = 0;
	  
	  private static Map<Integer, DirectionEnumType> angleMap = new HashMap<>();
	  static {
		  angleMap.put(0, NORTH);
		  angleMap.put(1, NORTHEAST);
		  angleMap.put(2, EAST);
		  angleMap.put(3, SOUTHEAST);
		  angleMap.put(4, SOUTH);
		  angleMap.put(5, SOUTHWEST);
		  angleMap.put(6, WEST);
		  angleMap.put(7, NORTHWEST);
		  angleMap.put(8, NORTH);
		  
	  }

	    private DirectionEnumType(final int x, final int y) {
	    	this.x = x;
	    	this.y = y;
	    }
	    
	    public static int getX(DirectionEnumType dir) {
	    	return dir.x;
	    }
	    public static int getY(DirectionEnumType dir) {
	    	return dir.y;
	    }
	    public static DirectionEnumType getDirectionFromDegrees(double degrees) {
			long angleM = 0;
			if(degrees > 0) angleM = Math.round(degrees / 45);//11.25;
			if(angleM < 0 || angleM > 8) {
				return UNKNOWN;
			}
			// System.out.println("AngleM -- " + degrees + " -> " + angleM);
			return angleMap.get((int)angleM);
			
	    }

}
