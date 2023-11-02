package org.cote.accountmanager.util;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.AlignmentEnumType;

public class WorldUtil {
	public static final Logger logger = LogManager.getLogger(WorldUtil.class);
	
	private static final long SECOND = 1000;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final long YEAR = 365 * DAY;
    private static SecureRandom rand = new SecureRandom();
    
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
	public static String[] getAlternateNames(BaseRecord user, BaseRecord location, String altType) {

		IOSystem.getActiveContext().getReader().populate(location, new String[] {"geonameid", FieldNames.FIELD_GROUP_ID});
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, "altgeonameid", location.get("geonameid"));
		q.field("geotype", "alternateName");
		q.field(FieldNames.FIELD_GROUP_ID, location.get(FieldNames.FIELD_GROUP_ID));
		if(altType != null) {
			q.field("alttype", altType);
		}
		q.setRequestRange(0, 10);
		//logger.info(q.toFullString());
		
		QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
		
		List<String> names = Arrays.asList(qr.getResults()).stream().map(r -> (String)r.get(FieldNames.FIELD_NAME)).collect(Collectors.toList());
		return names.toArray(new String[0]);
	}
	
	private static void applyStateAndCountry(BaseRecord user, BaseRecord location, BaseRecord addr) throws FieldException, ValueException, ModelNotFoundException {
		
		boolean checkParent = false;
		IOSystem.getActiveContext().getReader().populate(location);
		String geoType = location.get("geoType");
		
		if(geoType != null) {
			if(geoType.equals("feature")) {
				checkParent = true;
			}
			else if(geoType.equals("admin2")) {
				addr.set("region", location.get(FieldNames.FIELD_NAME));
				checkParent = true;
			}
			else if(geoType.equals("admin1")) {
				addr.set("state", location.get(FieldNames.FIELD_NAME));
				checkParent = true;
			}			
			else if(geoType.equals("country")) {
				addr.set("country", location.get(FieldNames.FIELD_NAME));
			}				
			if(checkParent) {
				long parentId = location.get(FieldNames.FIELD_PARENT_ID);
				if(parentId > 0L) {
					logger.info("Lookup parent #: " + parentId);
					BaseRecord parent = IOSystem.getActiveContext().getAccessPoint().findById(user, ModelNames.MODEL_GEO_LOCATION, parentId);
					if(parent != null) {
						applyStateAndCountry(user, parent, addr);
					}
					else {
						logger.error("Failed to find parent from:");
						logger.error(location.toFullString());
					}
				}
			}
		}
		else {
			logger.warn("Invalid location type: " + geoType);
			logger.warn(location.toFullString());
		}
	}
	public static BaseRecord randomAddress(BaseRecord user, BaseRecord location, String groupPath)  {
		ParameterList plist = ParameterList.newParameterList("path", groupPath);
		IOSystem.getActiveContext().getReader().populate(location);
		IOSystem.getActiveContext().getReader().populate(user);


		BaseRecord addr = null;
		try {
			addr = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ADDRESS, user, null, plist);
			
			String[] posts = getAlternateNames(user, location, "post");
			if(posts.length > 0) {
				addr.set("postalCode", posts[0]);
			}
			else {
				logger.warn("No postal code found");
			}
			addr.set(FieldNames.FIELD_CITY, location.get(FieldNames.FIELD_NAME));
			addr.set(FieldNames.FIELD_STREET, randomBuilding() + " " + randomStreetName());
			addr.set("location", location.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID}));
			applyStateAndCountry(user, location, addr);
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
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
	public static String randomSelectionName(BaseRecord user, Query query) {
		BaseRecord sel = randomSelection(user, query);
		if(sel != null) {
			return sel.get(FieldNames.FIELD_NAME);
		}
		return null;
	}
	public static BaseRecord randomSelection(BaseRecord user, Query query) {
		Query q2 = new Query(query.copyRecord());
		
		int regCount = IOSystem.getActiveContext().getAccessPoint().count(user, query);
		if(regCount == 0) {
			return null;
		}
		long randomIndex = (new Random()).nextLong(regCount);
		q2.setRequestRange(randomIndex, 1);
		QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q2);
		if(qr.getCount() > 0) {
			return qr.getResults()[0];
		}
		return null;
	}
	private static boolean recordExists(BaseRecord user, String modelName, String name, BaseRecord group) {
		Query q = QueryUtil.createQuery(modelName, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, (long)group.get(FieldNames.FIELD_ID));
		q.setRequest(new String[] {FieldNames.FIELD_ID});
		return (IOSystem.getActiveContext().getAccessPoint().find(user, q) != null);
	}
    private static <T extends Enum<?>> T randomEnum(Class<T> cls){
        int x = (new Random()).nextInt(cls.getEnumConstants().length);
        return cls.getEnumConstants()[x];
    }
	public static BaseRecord randomPerson(BaseRecord user, BaseRecord world) {
		return randomPerson(user, world, null);
	}
	public static BaseRecord randomPerson(BaseRecord user, BaseRecord world, String preferredLastName) {
		
		BaseRecord namesDir = world.get("names");
		BaseRecord surDir = world.get("surnames");
		BaseRecord occDir = world.get("occupations");
		BaseRecord popDir = world.get("population");
		IOSystem.getActiveContext().getReader().populate(popDir);
		
		ParameterList plist = ParameterList.newParameterList("path", popDir.get(FieldNames.FIELD_PATH));
		BaseRecord person = null; 		
		try {
			person = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CHAR_PERSON, user, null, plist);

			boolean isMale = (Math.random() < 0.5);
			
			
			long birthEpoch = System.currentTimeMillis() - (YEAR * rand.nextInt(75));
			Date birthDate = new Date(birthEpoch);
			person.set("birthDate", birthDate);
			String gen = isMale ? "male":"female";
			person.set("gender", gen);
			
			Query fnq = QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, namesDir.get(FieldNames.FIELD_ID));
			fnq.field("gender", gen.substring(0, 1).toUpperCase());
			String firstName = randomSelectionName(user, fnq);
			String middleName = randomSelectionName(user, fnq);
			String lastName = (preferredLastName != null ? preferredLastName : randomSelectionName(user, QueryUtil.createQuery(ModelNames.MODEL_CENSUS_WORD, FieldNames.FIELD_GROUP_ID, surDir.get(FieldNames.FIELD_ID))));
			
			String name = firstName + " " + middleName + " " + lastName;
	
			while(recordExists(user, ModelNames.MODEL_CHAR_PERSON, name, popDir)){
				logger.info("Name " + name + " exists .... trying again");
				firstName = randomSelectionName(user, fnq);
				middleName = randomSelectionName(user, fnq);
				lastName = (preferredLastName != null ? preferredLastName : randomSelectionName(user, QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, surDir.get(FieldNames.FIELD_ID))));
				name = firstName + " " + middleName + " " + lastName;
			}
	
			person.set("firstName", firstName);
			person.set("middleName", middleName);
			person.set("lastName", lastName);
			person.set(FieldNames.FIELD_NAME, name);
	
			List<String> trades = person.get("trades");
			trades.add(randomSelectionName(user, QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, occDir.get(FieldNames.FIELD_ID))));
			
			if(Math.random() < .15) {
				trades.add(randomSelectionName(user, QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, occDir.get(FieldNames.FIELD_ID))));			
			}
	
			
			AlignmentEnumType alignment = randomEnum(AlignmentEnumType.class);
			while(alignment == AlignmentEnumType.UNKNOWN || alignment == AlignmentEnumType.NEUTRAL) {
				alignment = randomEnum(AlignmentEnumType.class);
			}
			person.set("alignment", alignment);
		}
		catch(FieldException | ValueException | ModelNotFoundException | FactoryException e) {
			logger.error(e);
		}
		return person;
	}
	/*
	private void addressPerson(PersonType person, LocationType location, String sessionId) throws ArgumentException, FactoryException{
		 ContactInformationType cit = ((ContactInformationFactory)Factories.getFactory(FactoryEnumType.CONTACTINFORMATION)).newContactInformation(person);
		 BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.CONTACTINFORMATION, cit);
		 
		 person.setContactInformation(cit);
		 
		 ContactType email = ((ContactFactory)Factories.getFactory(FactoryEnumType.CONTACT)).newContact(user, contactsDir.getId());
		 String tradeName = Factories.getAttributeFactory().getAttributeValueByName(person, "trade").replaceAll("[^A-Za-z0-9]", "");
		 email.setContactValue((person.getFirstName() + (person.getMiddleName() != null ? "." + person.getMiddleName() : "") + "." + person.getLastName() + "@" + tradeName + ".com").toLowerCase());
		 email.setName(person.getName() + " Work Email");
		 email.setLocationType(LocationEnumType.WORK);
		 email.setContactType(ContactEnumType.EMAIL);;
		 BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.CONTACT, email);
		 cit.getContacts().add(email);
		 person.getAttributes().add(Factories.getAttributeFactory().newAttribute(person, "email", email.getContactValue()));
		 
		 
		 ContactType phone = ((ContactFactory)Factories.getFactory(FactoryEnumType.CONTACT)).newContact(user, contactsDir.getId());
		 phone.setContactValue("000-000-0000");
		 phone.setName(person.getName() + " Work Phone");
		 phone.setLocationType(LocationEnumType.WORK);
		 phone.setContactType(ContactEnumType.PHONE);
		 BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.CONTACT, phone);
		 cit.getContacts().add(phone);
		 person.getAttributes().add(Factories.getAttributeFactory().newAttribute(person, "phone", phone.getContactValue()));
		 
		 AddressType home = DataGeneratorData.randomAddress(this, location, addressesDir);
		 home.setName(person.getName() + " Home Address");
		 home.setLocationType(LocationEnumType.HOME);
		 BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.ADDRESS, home);
		 cit.getAddresses().add(home);
		 
		 AddressType work =  DataGeneratorData.randomAddress(this, location, addressesDir);
		 work.setGroupId(addressesDir.getId());
		 work.setName(person.getName() + " Work Address");
		 work.setLocationType(LocationEnumType.WORK);
		 BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.ADDRESS, work);
		 cit.getAddresses().add(work);

	}
	*/
	public static void generateOrganizationHierarchy(BaseRecord[] persons){

		/// limits the top n depth to these positional values
		///
		int[] iDepthLimits = new int[]{3, 5, 10, 20};
		int iDefaultLimit = 7;
		int iDepth = 0;
		int iRepC = 1;
		int iNewDepth = 0;
		int r = 0;
		try {
			for(int i = 0; i < persons.length && iRepC < persons.length; i++){
				int iWidth = (iDepth < iDepthLimits.length ? iDepthLimits[iDepth] : iDefaultLimit);
	
				int iRep = rand.nextInt(iWidth);
				/// take the next 'iRep' number people offset by previously reported people and make them report to person 'i'
				///
				for(r = 0; r < iRep; r++){
					if((iRepC + r) >= persons.length) break;
					AttributeUtil.addAttribute(persons[iRepC + r], "manager", (String)persons[i].get(FieldNames.FIELD_OBJECT_ID));
				}
				iRepC += r;
				if(i >= iNewDepth){
					iDepth++;
					iNewDepth += iWidth;
				}
			}
		}
		catch(ValueException | ModelException | FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
	}

     
}
