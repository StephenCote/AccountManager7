package org.cote.accountmanager.olio;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;


/*
 		/// < 1000
		/// Homestead, Neighborhood (1 - 5 families)
		/// Hamlet or Band ( < 100 people)
		/// Village, Tribe < 150
		/// Church, grocery, post, ...
		"Miniscule",
		/// < 100,000
		/// Town, Satellite Town
		/// Clinic, Pharmacy/Alchemist, Bank, Supermarket, Police, Fire, School, Neighborhoods, Restaurants.
		"Low",
		/// < 250,000
		/// < 100,000
		"Lower Medium",
		/// < 1,000,000
		/// City
		"Upper Medium",
		/// < 3,000,000
		"Metropolis",
		/// < 10,000,000
		"Global City",
		/// < 100,000,000
		"Megalapolis",
		/// >= 100,000,000
		"Gigalopolis"
 */

public enum DensityEnumType {
	UNKNOWN(0),
	ROADHOUSE(5),
	HOMESTEAD(10),
	BAND(50),
	HAMLET(100),
	TRIBE(150),
	VILLAGE(1000),
	RESIDENTIAL_TOWN(100000),
	TOWN(250000),
	CITY(1000000),
	METROPOLIS(1000000000),
	ECUMENOPOLIS(1000000001)
	;
	  private int val;

	    private static Map<Integer, DensityEnumType> alignMap = new HashMap<Integer, DensityEnumType>();

	    static {
	        for (DensityEnumType align : DensityEnumType.values()) {
	            alignMap.put(align.val, align);
	        }
	    }

	    private DensityEnumType(final int val) {
	    	this.val = val;
	    }
	    public static int getValue(DensityEnumType aet) {
	    	return aet.val;
	    }
	    public static int getAlignmentScore(BaseRecord rec) {
	    	int out = 0;
	    	if(rec.inherits(ModelNames.MODEL_ALIGNMENT)) {
	    		DensityEnumType aet = DensityEnumType.valueOf((String)rec.get(FieldNames.FIELD_ALIGNMENT));
	    		out = aet.val;
	    	}
	    	return out;
	    }

	    public static DensityEnumType valueOf(int val) {
			if(val < 1) {
				return UNKNOWN;
			}
			
			if(val > 1000000001) {
				return UNKNOWN;
			}
			int cval = alignMap.keySet().stream().min(Comparator.comparingInt(i -> Math.abs(i - val))).orElseThrow(NoSuchElementException::new);
			

			return alignMap.get(cval);
	    }
}
