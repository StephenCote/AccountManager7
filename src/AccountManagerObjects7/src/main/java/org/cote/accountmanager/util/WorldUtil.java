package org.cote.accountmanager.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.AlignmentEnumType;

public class WorldUtil {
	public static final Logger logger = LogManager.getLogger(WorldUtil.class);
	
	private static Map<AlignmentEnumType,Integer> alignmentScoreMap = new HashMap<>();
	static{
		alignmentScoreMap.put(AlignmentEnumType.CHAOTICEVIL, -4);
		alignmentScoreMap.put(AlignmentEnumType.NEUTRALEVIL, -3);
		alignmentScoreMap.put(AlignmentEnumType.LAWFULEVIL, -2);
		alignmentScoreMap.put(AlignmentEnumType.CHAOTICNEUTRAL, -1);
		alignmentScoreMap.put(AlignmentEnumType.NEUTRAL, 0);
		alignmentScoreMap.put(AlignmentEnumType.UNKNOWN, 0);
		alignmentScoreMap.put(AlignmentEnumType.LAWFULNEUTRAL, 1);
		alignmentScoreMap.put(AlignmentEnumType.CHAOTICGOOD, 2);
		alignmentScoreMap.put(AlignmentEnumType.NEUTRALGOOD, 3);
		alignmentScoreMap.put(AlignmentEnumType.LAWFULGOOD, 4);
	}
		

    
    public static AlignmentEnumType getAlignmentFromScore(int alignment){
		AlignmentEnumType aType = AlignmentEnumType.NEUTRAL;
		for(Map.Entry<AlignmentEnumType,Integer> aet : alignmentScoreMap.entrySet()){
			if(aet.getValue() == alignment){
				aType = aet.getKey();
				break;
			}
		}
		return aType;
    }
	public static int getAlignmentScore(String alignment){
		if(alignment != null && alignment.length() > 0){
			return getAlignmentScore(AlignmentEnumType.valueOf(alignment));
		}
		else{
			logger.error("Invalid alignmnet");
		}
		return 0;
	}    
	public static int getAlignmentScore(BaseRecord obj){
		int score = 0;
		if(obj.inherits(ModelNames.MODEL_ALIGNMENT)) {
			score = getAlignmentScore((String)obj.get(FieldNames.FIELD_ALIGNMENT));
		}
		else {
			logger.warn(obj.getModel() + " does not inherit from " + ModelNames.MODEL_ALIGNMENT);
		}
		return score;
	}

	public static int getAlignmentScore(AlignmentEnumType alignment){
		if(alignment != null){
			return alignmentScoreMap.get(alignment);
		}
		else{
			logger.error("Invalid alignment");
		}
		return 0;
	}
    
    protected static final String[] STREET_NAME_BASE = new String[]{
			"SN","SN","SN","SN","SN","##","##","##","##","##","First","Second","Third","Fourth","Fifth","Sixth","Seventh","Eighth","Ninth","Tenth","Main","Church","High","Elm","Washington","Walnut","Park","Broad","Chestnut","Maple","Center","Pine","Water","Oak","River","Union","Market","Spring","Prospect","Central","School","Front","Cherry","Franklin","Highland","Mill","Bridge","Cedar","Jefferson","State","Spruce","Madison","Pearl","Pleasant","Academy","Jackson","Grove","Pennsylvania","Adams","Locust","Elizabeth","Green","Lincoln","Meadow","Dogwood","Liberty","Vine","Brookside","Delaware","Hickory","Hillside","Monroe","Virginia","Winding","Charles","Clinton","College","Railroad","Summit","Colonial","Division","Valley","Williams","Woodland","Lafayette","Lake","Oak","Penn","Poplar","Primrose","Sunset","Warren","Willow","Beech","Berkshire","Deerfield","Harrison","Laurel","Cambridge","Cherry","Dogwood","Heather","Hillcrest","Holly","King","Laurel","Mulberry","Riverside","Sherwood","Smith","Valley","York","Arch","Creek","Essex","Forest","Garden","George","Glenwood","Grant","Hamilton","James","John","Magnolia","Myrtle","Olive","Orange","Oxford","Aspen","Bank","Buckingham","Canal","Canterbury","Carriage","Clark","Devon","Durham","Lilac","Locust","Maple","Surrey","Wall","Windsor","Beechwood","Columbia","Cottage","Garfield","Henry","Linden","Mechanic","Rosewood","Skyline","Sycamore","William"
	};
	protected static final String[] BUILDING_UNIT = new String[]{"A","B","C","D"};

	protected static final String[] CARDINAL_DIRECTION = new String[]{"N","W","S","E",""};
	protected static final String[] QUADRANT_DIRECTION = new String[]{"NW","NE","SW","SE",""};
	protected static final String[] STREET_TYPE_BASE = new String[]{"","","","Avenue","Street","Drive","Circle","Court","Place","Terrace","Highway","Pike","Boulevard","Alley","Bend","Gardens","Gate","Grove","Heights","Lane","Trail","Vale","Way","Cove","Park","Plaza","Ridge","Hill","Canyon","Loop","Circle","Road","View"};

	protected static BaseRecord randomAddress(BaseRecord user, BaseRecord location, String groupPath) throws FactoryException, FieldException, ValueException, ModelNotFoundException {
		ParameterList plist = ParameterList.newParameterList("path", groupPath);
		BaseRecord addr = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ADDRESS, user, null, plist);
		
		
		//addr.setPostalCode(Factories.getAttributeFactory().getAttributeValueByName(location, "post"));
		addr.set(FieldNames.FIELD_CITY, location.get(FieldNames.FIELD_NAME));
		addr.set(FieldNames.FIELD_STREET, randomBuilding() + " " + randomStreetName());
		return addr;
	}

	private static String randomBuilding(){
		Random r = new Random();
		return (r.nextInt(20000) + 1) + (Math.floor(r.nextDouble()*2)==1 ? BUILDING_UNIT[r.nextInt(BUILDING_UNIT.length)] : "");
		
	}
	protected static int randomStreetSeed = 1000;
	private static String randomStreetName(){
		Random r = new Random();
		String streetName = STREET_NAME_BASE[r.nextInt(STREET_NAME_BASE.length)];
		if(streetName == "SN"){
			// streetName = dutil.getNames().get("common")[r.nextInt(dutil.getNames().get("common").length)];
			streetName = "Random surname";
		}
		else if(streetName == "##"){
			streetName = "" + (r.nextInt(randomStreetSeed) + 1);
			if(streetName.matches("1$")) streetName += "st";
			else if(streetName.matches("2$")) streetName += "nd";
			else if(streetName.matches("3$")) streetName += "rd";
			else streetName += "th";
		}
		int tQoC = r.nextInt(3);
		String qoc = (tQoC == 1 ? CARDINAL_DIRECTION[r.nextInt(CARDINAL_DIRECTION.length)] : tQoC == 2 ? QUADRANT_DIRECTION[r.nextInt(QUADRANT_DIRECTION.length)]: "");
		String streetType = STREET_TYPE_BASE[r.nextInt(STREET_TYPE_BASE.length)];
		if(tQoC ==1 && qoc.length() > 0) {
			return qoc + " " + streetName + (streetType.length() > 0 ? " " + streetType : "");
		}
		else if(tQoC ==2 && qoc.length() > 0) {
			return streetName + (streetType.length() > 0 ? " " + streetType : "") + " " + qoc;
		}
		return streetName + (streetType.length() > 0 ? " " + streetType : "");
	}
	


     
}
