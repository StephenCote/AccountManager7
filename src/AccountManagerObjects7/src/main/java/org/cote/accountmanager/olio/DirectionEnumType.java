package org.cote.accountmanager.olio;

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

}
