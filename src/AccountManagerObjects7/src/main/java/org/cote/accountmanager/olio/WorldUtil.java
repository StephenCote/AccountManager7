package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.ParticipationFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.parsers.data.WordParser;
import org.cote.accountmanager.parsers.geo.GeoParser;
import org.cote.accountmanager.parsers.wordnet.WordNetParser;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.AlignmentEnumType;
import org.cote.accountmanager.schema.type.ContactEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.LocationEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.util.AttributeUtil;

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
	private static String[] getAlternateNames(BaseRecord user, BaseRecord location, String altType) {
		return getAlternateNames(user, null, location, altType);
	}
	private static String[] getAlternateNames(BaseRecord user, BaseRecord world, BaseRecord location, String altType) {
			
		IOSystem.getActiveContext().getReader().populate(location, new String[] {"geonameid", FieldNames.FIELD_GROUP_ID});
		long groupId = location.get(FieldNames.FIELD_GROUP_ID);
		if(world != null) {
			groupId = world.get("locations.id");
		}
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, "altgeonameid", location.get("geonameid"));
		q.field("geotype", "alternateName");
		q.field(FieldNames.FIELD_GROUP_ID, groupId);
		if(altType != null) {
			q.field("alttype", altType);
		}
		q.setRequestRange(0, 10);
		//logger.info(q.toFullString());
		
		//QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
		QueryResult qr = null;
		List<String> names = new ArrayList<>();
		try {
			qr = IOSystem.getActiveContext().getSearch().find(q);
			names = Arrays.asList(qr.getResults()).stream().map(r -> (String)r.get(FieldNames.FIELD_NAME)).collect(Collectors.toList());
		} catch (IndexException | ReaderException e) {
			logger.error(e);
		}
		
		return names.toArray(new String[0]);
	}
	
	private static void applyStateAndCountry(BaseRecord user, BaseRecord location, BaseRecord addr) throws FieldException, ValueException, ModelNotFoundException, ReaderException {
		
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
					// logger.info("Lookup parent #: " + parentId);
					BaseRecord parent = IOSystem.getActiveContext().getReader().read(ModelNames.MODEL_GEO_LOCATION, parentId);
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
	public static BaseRecord randomAddress(BaseRecord user, BaseRecord world, BaseRecord location, String groupPath)  {
		ParameterList plist = ParameterList.newParameterList("path", groupPath);
		IOSystem.getActiveContext().getReader().populate(location);
		IOSystem.getActiveContext().getReader().populate(user);
		if(world != null) {
			IOSystem.getActiveContext().getReader().populate(world);
		}


		BaseRecord addr = null;
		try {
			addr = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ADDRESS, user, null, plist);
			
			String[] posts = getAlternateNames(user, location, "post");
			if(posts.length > 0) {
				addr.set("postalCode", posts[0]);
			}
			else {
				/// logger.warn("No postal code found");
			}
			addr.set(FieldNames.FIELD_CITY, location.get(FieldNames.FIELD_NAME));
			addr.set(FieldNames.FIELD_STREET, randomBuilding() + " " + randomStreetName());
			addr.set("location", location.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID}));
			applyStateAndCountry(user, location, addr);
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException | ReaderException e) {
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
	

	public static BaseRecord randomPerson(BaseRecord user, BaseRecord world) {
		return randomPerson(user, world, null, null, null, null, null);
	}
	
	
	
	public static BaseRecord randomPerson(BaseRecord user, BaseRecord world, String preferredLastName) {
		return randomPerson(user, world, preferredLastName, null, null, null, null);
	}
	public static BaseRecord randomPerson(BaseRecord user, BaseRecord world, String preferredLastName, String[] mnames, String[] fnames, String[] snames, String[] tnames) {

		BaseRecord parWorld = world.get("basis");
		if(parWorld == null) {
			logger.error("A basis world is required");
			return null;
		}

		BaseRecord namesDir = parWorld.get("names");
		BaseRecord surDir = parWorld.get("surnames");
		BaseRecord occDir = parWorld.get("occupations");
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
			String[] names = mnames;
			if(gen.equals("female")) {
				names = fnames;
			}
			String firstName = (names != null ? names[rand.nextInt(names.length)] : OlioUtil.randomSelectionName(user, fnq));
			String middleName = (names != null ? names[rand.nextInt(names.length)] : OlioUtil.randomSelectionName(user, fnq));
			String lastName = (preferredLastName != null ? preferredLastName : (snames != null ? snames[rand.nextInt(snames.length)] : OlioUtil.randomSelectionName(user, QueryUtil.createQuery(ModelNames.MODEL_CENSUS_WORD, FieldNames.FIELD_GROUP_ID, surDir.get(FieldNames.FIELD_ID)))));			
			String name = firstName + " " + middleName + " " + lastName;
	
			while(OlioUtil.recordExists(user, ModelNames.MODEL_CHAR_PERSON, name, popDir)){
				logger.info("Name " + name + " exists .... trying again");
				firstName = (names != null ? names[rand.nextInt(names.length)] : OlioUtil.randomSelectionName(user, fnq));
				middleName = (names != null ? names[rand.nextInt(names.length)] : OlioUtil.randomSelectionName(user, fnq));
				lastName = (preferredLastName != null ? preferredLastName : (snames != null ? snames[rand.nextInt(snames.length)] : OlioUtil.randomSelectionName(user, QueryUtil.createQuery(ModelNames.MODEL_CENSUS_WORD, FieldNames.FIELD_GROUP_ID, surDir.get(FieldNames.FIELD_ID)))));

				name = firstName + " " + middleName + " " + lastName;
			}
	
			person.set("firstName", firstName);
			person.set("middleName", middleName);
			person.set("lastName", lastName);
			person.set(FieldNames.FIELD_NAME, name);
	
			List<String> trades = person.get("trades");
			trades.add((
				tnames != null ? tnames[rand.nextInt(tnames.length)]
				:
					OlioUtil.randomSelectionName(user, QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, occDir.get(FieldNames.FIELD_ID)))
			));
			
			if(Math.random() < .15) {
				trades.add((
					tnames != null ? tnames[rand.nextInt(tnames.length)]
					:
						OlioUtil.randomSelectionName(user, QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, occDir.get(FieldNames.FIELD_ID)))
				));			
			}
	
			
			AlignmentEnumType alignment = OlioUtil.randomEnum(AlignmentEnumType.class);
			while(alignment == AlignmentEnumType.UNKNOWN || alignment == AlignmentEnumType.NEUTRAL) {
				alignment = OlioUtil.randomEnum(AlignmentEnumType.class);
			}
			person.set("alignment", alignment);
		}
		catch(FieldException | ValueException | ModelNotFoundException | FactoryException | IndexException | ReaderException e) {
			logger.error(e);
		}
		return person;
	}
	
	public static BaseRecord randomLocation(BaseRecord user, BaseRecord world) {
		BaseRecord dir = world.get("locations");
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field("geoType", "feature");
		return OlioUtil.randomSelection(user, q);
	}

	/// Returns an array of objects related to the address
	///
	public static BaseRecord[] addressPerson(BaseRecord user, BaseRecord world, BaseRecord person, BaseRecord location) {
		List<BaseRecord> objs = new ArrayList<>();
		if(location == null) {
			location = randomLocation(user, world);
		}
		BaseRecord cit = null;
		try {
			cit = RecordFactory.newInstance(ModelNames.MODEL_CONTACT_INFORMATION);
			IOSystem.getActiveContext().getRecordUtil().applyOwnership(user, cit, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			
			BaseRecord addrDir = world.get("addresses");
			BaseRecord contactDir = world.get("contacts");
			IOSystem.getActiveContext().getReader().populate(contactDir);
			IOSystem.getActiveContext().getReader().populate(addrDir);

			BaseRecord email = RecordFactory.newInstance(ModelNames.MODEL_CONTACT);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, email, person.get(FieldNames.FIELD_NAME) + " Work Email", contactDir.get(FieldNames.FIELD_PATH), user.get(FieldNames.FIELD_ORGANIZATION_ID));
			objs.add(email);
			
			String tradeName = "unknown";
			List<String> trades = person.get("trades");
			if(trades.size() > 0) {
				if(trades.get(0) == null) {
					logger.error("Null trade name: " + trades.stream().collect(Collectors.joining(",")));
					tradeName = "Wildcard";
				}
				else {
					tradeName = trades.get(0).replaceAll("[^A-Za-z0-9]", "");
				}
			}
			
			String firstName = person.get("firstName");
			String middleName = person.get("middleName");
			String lastName = person.get("lastName");
			
			 email.set("contactValue", (firstName + (middleName != null ? "." + middleName : "") + "." + lastName + "@" + tradeName + ".com").toLowerCase());
			 email.set("locationType", LocationEnumType.WORK);
			 email.set("contactType", ContactEnumType.EMAIL);

			 List<BaseRecord> contacts = cit.get("contacts");
			 contacts.add(email);
			 
			BaseRecord phone = RecordFactory.newInstance(ModelNames.MODEL_CONTACT);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, phone, person.get(FieldNames.FIELD_NAME) + " Work Phone", contactDir.get(FieldNames.FIELD_PATH), user.get(FieldNames.FIELD_ORGANIZATION_ID));
			objs.add(phone);
			
			phone.set("contactValue", "000-000-0000");
			 phone.set("locationType", LocationEnumType.WORK);
			 phone.set("contactType", ContactEnumType.PHONE);
			 contacts.add(phone);
			 
			 List<BaseRecord> addrs = cit.get("addresses");
			 
			 BaseRecord home = randomAddress(user, world, location, addrDir.get(FieldNames.FIELD_PATH));
			 home.set(FieldNames.FIELD_NAME, person.get(FieldNames.FIELD_NAME) + " Home Address");
			 home.set("locationType", LocationEnumType.HOME);
			 addrs.add(home);
			 objs.add(home);
			 
			 BaseRecord work = randomAddress(user, world, location, addrDir.get(FieldNames.FIELD_PATH));
			 work.set(FieldNames.FIELD_NAME, person.get(FieldNames.FIELD_NAME) + " Work Address");
			 work.set("locationType", LocationEnumType.WORK);
			 addrs.add(work);
			 objs.add(work);
			 objs.add(cit);
			 person.set(FieldNames.FIELD_CONTACT_INFORMATION, cit);

		
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
			cit = null;
		}
		return objs.toArray(new BaseRecord[0]);

	}

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
	
	public static String generateEpochTitle(BaseRecord user, BaseRecord world, AlignmentEnumType alignment) {
		BaseRecord dir = world.get("dictionary");
		return generateEpochTitle(user, dir.get(FieldNames.FIELD_ID), alignment);
	}
	public static String generateEpochTitle(BaseRecord user, long groupId, AlignmentEnumType alignment){
	
		Query bq = QueryUtil.createQuery(ModelNames.MODEL_WORD_NET, FieldNames.FIELD_GROUP_ID, groupId);
		Query advQ = new Query(bq.copyRecord());
		advQ.field("type", WordNetParser.adverbWordType);
		String advWord = OlioUtil.randomSelectionName(user, advQ);

		Query verQ = new Query(bq.copyRecord());
		verQ.field("type", WordNetParser.verbWordType);
		String verWord = OlioUtil.randomSelectionName(user, verQ);

		Query adjQ = new Query(bq.copyRecord());
		adjQ.field("type", WordNetParser.adjectiveWordType);
		String adjWord = OlioUtil.randomSelectionName(user, adjQ);

		Query nounQ = new Query(bq.copyRecord());
		nounQ.field("type", WordNetParser.nounWordType);
		String nouWord = OlioUtil.randomSelectionName(user, nounQ);
		
		// logger.info(nounQ.toFullString());
		
		String title = null;
		switch(alignment){
			case CHAOTICEVIL:
				title = "Vile period of " + adjWord + " " + nouWord;
				break;
			case CHAOTICGOOD:
				title = "The " + advWord + " " + verWord + " upheavel";
				break;
			case CHAOTICNEUTRAL:
				title = "All quiet on the " + adjWord + " " + nouWord;
				break;
			case LAWFULEVIL:
				title = "The " + verWord + " " + nouWord + " circumstance";
				break;
			case LAWFULGOOD:
				title = "Triumph of " + adjWord + " " + nouWord;
				break;
			case LAWFULNEUTRAL:
				title = "Quiet of " + adjWord + " " + nouWord;
				break;
			case UNKNOWN:
				title = "Stillness of " + nouWord;
				break;
			case NEUTRAL:
				title = "A " + adjWord + " " + nouWord + " mystery"; 
				break;
			case NEUTRALEVIL:
				title = "The " + adjWord + " " + nouWord + " confusion";
				break;
			case NEUTRALGOOD:
				title = "The " + verWord + " of the " + nouWord;
				break;
			default:
				break;
		}


		return title;
		
	}
	
	public static BaseRecord getWorld(BaseRecord user, String groupPath, String worldName) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		return IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(user, ModelNames.MODEL_WORLD, (long)dir.get(FieldNames.FIELD_ID), worldName);
		
	}
	public static BaseRecord getCreateWorld(BaseRecord user, String groupPath, String worldName, String[] features) {
		return getCreateWorld(user, null, groupPath, worldName, features);
	}
	public static BaseRecord getCreateWorld(BaseRecord user, BaseRecord basis, String groupPath, String worldName, String[] features) {
		BaseRecord rec = getWorld(user, groupPath, worldName);
		if(rec == null) {
			BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));

			ParameterList plist = ParameterList.newParameterList("path", groupPath);
			plist.parameter("name", worldName);
			try {
				BaseRecord world = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_WORLD, user, null, plist);
				world.set("features", Arrays.asList(features));
				world.set("basis", basis);
				IOSystem.getActiveContext().getAccessPoint().create(user, world);
				rec = IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(user, ModelNames.MODEL_WORLD, (long)dir.get(FieldNames.FIELD_ID), worldName);
			} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
		}
		
		return rec;
	}
	
	private static int loadOccupations(BaseRecord user, BaseRecord world, String basePath, boolean reset) {
		IOSystem.getActiveContext().getReader().populate(world);
		BaseRecord occDir = world.get("occupations");
		IOSystem.getActiveContext().getReader().populate(occDir);

		WordParser.loadOccupations(user, occDir.get(FieldNames.FIELD_PATH), basePath, reset);
		return IOSystem.getActiveContext().getAccessPoint().count(user, WordParser.getQuery(user, ModelNames.MODEL_WORD, occDir.get(FieldNames.FIELD_PATH)));

	}
	
	private static int loadLocations(BaseRecord user, BaseRecord world, String basePath, boolean reset) {
		List<String> feats = world.get("features");
		String[] features = feats.toArray(new String[0]);
		IOSystem.getActiveContext().getReader().populate(world);
		BaseRecord locDir = world.get("locations");
		IOSystem.getActiveContext().getReader().populate(locDir);

		GeoParser.loadInfo(user, locDir.get(FieldNames.FIELD_PATH), basePath, features, reset);
		
		return IOSystem.getActiveContext().getAccessPoint().count(user, GeoParser.getQuery(null, null, locDir.get(FieldNames.FIELD_ID), user.get(FieldNames.FIELD_ORGANIZATION_ID)));

	}
	private static int loadDictionary(BaseRecord user, BaseRecord world, String basePath, boolean reset) {

		IOSystem.getActiveContext().getReader().populate(world);
		BaseRecord dictDir = world.get("dictionary");
		IOSystem.getActiveContext().getReader().populate(dictDir);
		
		String groupPath = dictDir.get(FieldNames.FIELD_PATH);
		String wnetPath = basePath;
		
		WordNetParser.loadAdverbs(user, groupPath, wnetPath, 0, reset);
		WordNetParser.loadAdjectives(user, groupPath, wnetPath, 0, reset);
		WordNetParser.loadNouns(user, groupPath, wnetPath, 0, reset);
		WordNetParser.loadVerbs(user, groupPath, wnetPath, 0, reset);
		return IOSystem.getActiveContext().getAccessPoint().count(user, WordNetParser.getQuery(user, null, groupPath));
	}
	private static int loadNames(BaseRecord user, BaseRecord world, String basePath, boolean reset) {
		IOSystem.getActiveContext().getReader().populate(world);
		BaseRecord nameDir = world.get("names");
		IOSystem.getActiveContext().getReader().populate(nameDir);
		
		String groupPath = nameDir.get(FieldNames.FIELD_PATH);

		WordParser.loadNames(user, groupPath, basePath, reset);
		return IOSystem.getActiveContext().getAccessPoint().count(user, WordParser.getQuery(user, ModelNames.MODEL_WORD, groupPath));
	}
	private static int loadColors(BaseRecord user, BaseRecord world, String basePath, boolean reset) {
		IOSystem.getActiveContext().getReader().populate(world);
		BaseRecord colDir = world.get("colors");
		IOSystem.getActiveContext().getReader().populate(colDir);
		
		String groupPath = colDir.get(FieldNames.FIELD_PATH);

		WordParser.loadColors(user, groupPath, basePath, reset);
		return IOSystem.getActiveContext().getAccessPoint().count(user, WordParser.getQuery(user, ModelNames.MODEL_COLOR, groupPath));
	}
	private static int loadPatterns(BaseRecord user, BaseRecord world, String basePath, boolean reset) {
		IOSystem.getActiveContext().getReader().populate(world);
		BaseRecord colDir = world.get("patterns");
		IOSystem.getActiveContext().getReader().populate(colDir);
		
		String groupPath = colDir.get(FieldNames.FIELD_PATH);

		WordParser.loadPatterns(user, groupPath, basePath, reset);
		return IOSystem.getActiveContext().getAccessPoint().count(user, WordParser.getQuery(user, ModelNames.MODEL_DATA, groupPath));
	}
	private static int loadTraits(BaseRecord user, BaseRecord world, String basePath, boolean reset) {
		IOSystem.getActiveContext().getReader().populate(world);
		BaseRecord traitsDir = world.get("traits");
		IOSystem.getActiveContext().getReader().populate(traitsDir);
		
		String groupPath = traitsDir.get(FieldNames.FIELD_PATH);

		WordParser.loadTraits(user, groupPath, basePath, reset);
		return IOSystem.getActiveContext().getAccessPoint().count(user, WordParser.getQuery(user, ModelNames.MODEL_TRAIT, groupPath));
	}
	private static int loadSurnames(BaseRecord user, BaseRecord world, String basePath, boolean reset) {
		IOSystem.getActiveContext().getReader().populate(world);
		BaseRecord nameDir = world.get("surnames");
		IOSystem.getActiveContext().getReader().populate(nameDir);
		String groupPath = nameDir.get(FieldNames.FIELD_PATH);
		WordParser.loadSurnames(user, groupPath, basePath, reset);
		return IOSystem.getActiveContext().getAccessPoint().count(user, WordParser.getQuery(user, ModelNames.MODEL_CENSUS_WORD, groupPath));
	}

	public static void populateWorld(BaseRecord user, BaseRecord world, String basePath, boolean reset) {
		logger.info("Checking world data ...");
		int dict = loadDictionary(user, world, basePath + "/wn3.1.dict/dict", reset);
		logger.info("Dictionary words: " + dict);
		int locs = loadLocations(user, world, basePath + "/location", reset);
		logger.info("Locations: " + locs);
		int occs = loadOccupations(user, world, basePath + "/occupations/noc_2021_version_1.0_-_elements.csv", reset);
		logger.info("Occupations: " + occs);
		int names = loadNames(user, world, basePath + "/names/yob2022.txt", reset);
		logger.info("Names: " + names);
		int surnames = loadSurnames(user, world, basePath + "/surnames/Names_2010Census.csv", reset);
		logger.info("Surnames: " + surnames);
		int traits = loadTraits(user, world, basePath, reset);
		logger.info("Traits: " + traits);
		int colors = loadColors(user, world, basePath + "/colors.csv", reset);
		logger.info("Colors: " + colors);
		int patterns = loadPatterns(user, world, basePath + "/patterns/patterns.csv", reset);
		logger.info("Patterns: " + patterns);
	}
	public static BaseRecord cloneIntoGroup(BaseRecord src, BaseRecord dir) {
		IOSystem.getActiveContext().getReader().populate(src);
		BaseRecord targ = src.copyDeidentifiedRecord();
		try {
			targ.set(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		}
		catch(ValueException | FieldException | ModelNotFoundException e) {
			logger.error(e);
			targ = null;
		}
		return targ;
	}
	public static BaseRecord generateRegion(BaseRecord user, BaseRecord world, int locCount, int popSeed){
		
		logger.info("Generate region ...");
		IOSystem.getActiveContext().getReader().populate(world, 2);
		List<BaseRecord> events = new ArrayList<>(); 
		BaseRecord root = null;
		BaseRecord rootLoc = null;
		BaseRecord parWorld = world.get("basis");
		BaseRecord locDir = world.get("locations");
		BaseRecord eventsDir = world.get("events");
		if(parWorld == null) {
			logger.error("A basis world is required");
			return null;
		}
		int evtCnt = IOSystem.getActiveContext().getSearch().count(QueryUtil.createQuery(ModelNames.MODEL_EVENT, FieldNames.FIELD_GROUP_ID, (long)eventsDir.get(FieldNames.FIELD_ID)));
		if(evtCnt > 0) {
			logger.warn("Region is already generated");
			return null;
		}
		IOSystem.getActiveContext().getReader().populate(parWorld, 2);
		BaseRecord traitsDir = parWorld.get("traits");

		List<BaseRecord> locations = new ArrayList<>();
		Set<String> locSet = new HashSet<>();
		for(int i = 0; i < (locCount + 1); i++) {
			BaseRecord loc = randomLocation(user, parWorld);
			while(loc != null && locSet.contains(loc.get(FieldNames.FIELD_NAME))) {
				loc = randomLocation(user, parWorld);
			}
			locSet.add(loc.get(FieldNames.FIELD_NAME));
			
			locations.add(cloneIntoGroup(loc, locDir));
		}
		if(locations.isEmpty()){
			logger.error("Expected a positive number of locations");
			logger.info(locDir.toFullString());
			return null;
		}
		
		try{
			
			int cloc = IOSystem.getActiveContext().getRecordUtil().updateRecords(locations.toArray(new BaseRecord[0]));
			if(cloc != locations.size()) {
				logger.error("Failed to create locations");
				return null;
			}
			for(BaseRecord loc: locations) {

				String locName = loc.get(FieldNames.FIELD_NAME);
				loc.set(FieldNames.FIELD_DESCRIPTION, null);
				//logger.info(loc.toFullString());
				BaseRecord event = null;

				if(root == null) {
					logger.info("Construct region: " + locName);
					ParameterList plist = ParameterList.newParameterList("path", eventsDir.get(FieldNames.FIELD_PATH));
					root = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_EVENT, user, null, plist);
					/// TODO: Need a way to bulk-add hierarchies
					/// The previous version used a complex method of identifier assignment and rewrite with negative values
					root.set(FieldNames.FIELD_NAME, "Construct Region " + locName);
					root.set(FieldNames.FIELD_LOCATION, loc);
					root.set(FieldNames.FIELD_TYPE, EventEnumType.CONSTRUCT);
					if(!IOSystem.getActiveContext().getRecordUtil().updateRecord(root)) {
						logger.error("Failed to create root event");
						return null;
					}
					event = root;
				}
				else {
					logger.info("Populate region: " + locName);
					BaseRecord popEvent = populateRegion(user, world, loc, popSeed);
					popEvent.set(FieldNames.FIELD_PARENT_ID, root.get(FieldNames.FIELD_ID));
					events.add(popEvent);
					// logger.info(popEvent.toFullString());
					event = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_EVENT, user, null, ParameterList.newParameterList("path", eventsDir.get(FieldNames.FIELD_PATH)));
					event.set(FieldNames.FIELD_NAME, "Construct " + locName);
					event.set(FieldNames.FIELD_PARENT_ID, root.get(FieldNames.FIELD_ID));
					
					Set<Long> tset = new HashSet<>();
					for(int b = 0; b < 2; b++) {
						List<BaseRecord> traits = event.get((b == 0 ? "entryTraits" : "exitTraits"));
						BaseRecord[] trecs = OlioUtil.randomSelections(user, QueryUtil.createQuery(ModelNames.MODEL_TRAIT, FieldNames.FIELD_GROUP_ID, traitsDir.get(FieldNames.FIELD_ID)), 3);
						// for(int e = 0; e < 3; e++) {
							//BaseRecord trait = randomSelection(user, QueryUtil.createQuery(ModelNames.MODEL_TRAIT, FieldNames.FIELD_GROUP_ID, traitsDir.get(FieldNames.FIELD_ID)));
						for(BaseRecord trait : trecs) {
							
							/*
							if(trait == null) {
								logger.error("Null trait");
								continue;
							}
							*/
							String name = trait.get(FieldNames.FIELD_NAME);
							if(tset.contains(name)) {
								continue;
							}
							traits.add(trait);
						}
					}
					event.set(FieldNames.FIELD_LOCATION, loc);
					event.set(FieldNames.FIELD_TYPE, EventEnumType.CONSTRUCT);
					events.add(event);
				}

				// logger.info(event.toFullString());
			}
			//logger.info("Events: " + events.size());
			// IOSystem.getActiveContext().getAccessPoint().create(user, events.toArray(new BaseRecord[0]), true);

		} catch (ValueException | FieldException | ModelNotFoundException | FactoryException e) {
			
			logger.error(e);
		}
		return root;
	}
	
	private static String[] leaderPopulation = new String[]{"Political","Religious","Military","Business","Social","Trade"};
	
	private static BaseRecord newRegionGroup(BaseRecord user, BaseRecord parent, String groupName) throws FieldException, ValueException, ModelNotFoundException {
		BaseRecord grp = RecordFactory.model(ModelNames.MODEL_GROUP).newInstance();
		grp.set(FieldNames.FIELD_NAME, groupName);
		grp.set(FieldNames.FIELD_TYPE, GroupEnumType.PERSON);
		grp.set(FieldNames.FIELD_PARENT_ID, parent.get(FieldNames.FIELD_ID));
		IOSystem.getActiveContext().getRecordUtil().applyOwnership(user, grp, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		return grp;
	}
	public static BaseRecord populateRegion(BaseRecord user, BaseRecord world, BaseRecord location, int popCount){

		String locName = location.get(FieldNames.FIELD_NAME);
		logger.info("Populating " + locName + " with " + popCount + " people");

		long start = System.currentTimeMillis();
		BaseRecord event = null;
		BaseRecord parWorld = world.get("basis");
		if(parWorld == null) {
			logger.error("A basis world is required");
			return null;
		}
		IOSystem.getActiveContext().getReader().populate(parWorld, 2);
		try {
			BaseRecord popDir = world.get("population");
			BaseRecord evtDir = world.get("events");
			BaseRecord namesDir = parWorld.get("names");
			BaseRecord surDir = parWorld.get("surnames");
			BaseRecord occDir = parWorld.get("occupations");
			
			ParameterList plist = ParameterList.newParameterList("path", evtDir.get(FieldNames.FIELD_PATH));
			event = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_EVENT, user, null, plist);
			event.set(FieldNames.FIELD_LOCATION, location);
			event.set(FieldNames.FIELD_TYPE, EventEnumType.INCEPT);
			event.set(FieldNames.FIELD_NAME, "Populate " + locName);
			List<BaseRecord> grps = event.get(FieldNames.FIELD_GROUPS);
			BaseRecord popGrp = newRegionGroup(user, popDir, locName + " Population");
			grps.add(popGrp);
			grps.add(newRegionGroup(user, popDir, locName + " Cemetary"));
			
			/// IOSystem.getActiveContext().getQueue().enqueue
			for(String name : leaderPopulation){
				grps.add(newRegionGroup(user, popDir, locName + " " + name + " Leaders"));				
			}
			
			IOSystem.getActiveContext().getRecordUtil().updateRecords(grps.toArray(new BaseRecord[0]));
			// IOSystem.getActiveContext().getAccessPoint().create(user, grps.toArray(new BaseRecord[0]));
			
			event.set(FieldNames.FIELD_GROUPS, grps);
			List<BaseRecord> actors = event.get("actors");
			if(popCount == 0){
				logger.error("Empty population");
				event.set(FieldNames.FIELD_DESCRIPTION, "Decimated");
			}
			else {
				long totalAge = 0;
				int totalAbsoluteAlignment = 0;
				// logger.info("Populating '" + popCount + '"');
				Date now = new Date();
				Query mnq = QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, namesDir.get(FieldNames.FIELD_ID));
				mnq.field("gender", "M");
				mnq.set("cache", false);
				String[] mnames = OlioUtil.randomSelectionNames(user, mnq, popCount * 3);
				Query fnq = QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, namesDir.get(FieldNames.FIELD_ID));
				fnq.field("gender", "F");
				fnq.set("cache", false);
				String[] fnames = OlioUtil.randomSelectionNames(user, fnq, popCount * 3);

				Query snq = QueryUtil.createQuery(ModelNames.MODEL_CENSUS_WORD, FieldNames.FIELD_GROUP_ID, surDir.get(FieldNames.FIELD_ID));
				snq.set("cache", false);
				String[] snames = OlioUtil.randomSelectionNames(user, snq, popCount * 2);
				Query tnq = QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, occDir.get(FieldNames.FIELD_ID));
				tnq.set("cache", false);
				String[] tnames = OlioUtil.randomSelectionNames(user, tnq, popCount * 2);
				if(mnames.length == 0 || fnames.length == 0 || snames.length == 0 || tnames.length == 0) {
					logger.error("Empty names");
				}
				for(int i = 0; i < popCount; i++){
					BaseRecord person = randomPerson(user, world, null, mnames, fnames, snames, tnames);
					addressPerson(user, world, person, location);
					int alignment = getAlignmentScore(person);
					long years = Math.abs(now.getTime() - ((Date)person.get("birthDate")).getTime()) / YEAR;
					person.set("age", (int)years);
					totalAge += years;
					totalAbsoluteAlignment += (alignment + 4);
					
					List<BaseRecord> appl = person.get("apparel");
					appl.add(ApparelUtil.randomApparel(user, world, person));
					
					actors.add(person);
					
					// BaseParticipantType bpt = ((GroupParticipationFactory)Factories.getBulkFactory(FactoryEnumType.GROUPPARTICIPATION)).newPersonGroupParticipation(populationGroup, person);
					// BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.GROUPPARTICIPATION, bpt);
				}
				//int created = IOSystem.getActiveContext().getAccessPoint().create(user, actors.toArray(new BaseRecord[0]));
				int created = IOSystem.getActiveContext().getRecordUtil().updateRecords(actors.toArray(new BaseRecord[0]));
				if(created != actors.size()) {
					logger.error("Created " + created + " but expected " + actors.size() + " records");
				}
				List<BaseRecord> parts = new ArrayList<>();
				for(BaseRecord rec : actors) {
					parts.add(ParticipationFactory.newParticipation(user, popGrp, null, rec));
				}
				IOSystem.getActiveContext().getRecordUtil().updateRecords(parts.toArray(new BaseRecord[0]));
				int eventAlignment = (totalAbsoluteAlignment / popCount) - 4;
				event.set(FieldNames.FIELD_ALIGNMENT, getAlignmentFromScore(eventAlignment));				
				/*
				if(organizePersonManagement){
					generatePersonOrganization(event.getActors().toArray(new PersonType[0]));
				}
				*/
				/*
				long avgAge = (totalAge > 0 ? (totalAge / len) : 0);
				int eventAlignment = (totalAbsoluteAlignment / len) - 4;
				AlignmentEnumType aType = DataGeneratorData.getAlignmentFromScore(eventAlignment);
				AttributeType attr = new AttributeType();
				attr.setName("alignment");
				attr.setDataType(SqlDataEnumType.VARCHAR);
				attr.getValues().add(aType.toString());
				event.getAttributes().add(attr);
				
				AttributeType attr2 = new AttributeType();
				attr2.setName("averageAge");
				attr2.setDataType(SqlDataEnumType.VARCHAR);
				attr2.getValues().add(Long.toString(avgAge));
				event.getAttributes().add(attr2);
				*/
			}
			
			IOSystem.getActiveContext().getRecordUtil().updateRecord(event);

			/*
			
			int len = popCount;
			if(randomizeSeedPopulation){

				len = rand.nextInt(popCount);
			}
			if(len == 0){
				logger.error("Empty population");
				event.setDescription("Decimated");
			}
			else{
				long totalAge = 0;
				int totalAbsoluteAlignment = 0;
				logger.info("Populating '" + popCount + '"');
				for(int i = 0; i < len; i++){
					PersonType person = randomPerson(user, personsDir);
					/// person.setContactInformation(null);
					int alignment = DataGeneratorData.getAlignmentScore(person);
					long years = Math.abs(CalendarUtil.getTimeSpanFromNow(person.getBirthDate())) / YEAR;
					totalAge += years;
					totalAbsoluteAlignment += (alignment + 4);
					BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.PERSON, person);
					event.getActors().add(person);
					BaseParticipantType bpt = ((GroupParticipationFactory)Factories.getBulkFactory(FactoryEnumType.GROUPPARTICIPATION)).newPersonGroupParticipation(populationGroup, person);
					BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.GROUPPARTICIPATION, bpt);
					addressPerson(person,location, sessionId);
				}
				if(organizePersonManagement){
					generatePersonOrganization(event.getActors().toArray(new PersonType[0]));
				}
				long avgAge = (totalAge > 0 ? (totalAge / len) : 0);
				int eventAlignment = (totalAbsoluteAlignment / len) - 4;
				AlignmentEnumType aType = DataGeneratorData.getAlignmentFromScore(eventAlignment);
				AttributeType attr = new AttributeType();
				attr.setName("alignment");
				attr.setDataType(SqlDataEnumType.VARCHAR);
				attr.getValues().add(aType.toString());
				event.getAttributes().add(attr);
				
				AttributeType attr2 = new AttributeType();
				attr2.setName("averageAge");
				attr2.setDataType(SqlDataEnumType.VARCHAR);
				attr2.getValues().add(Long.toString(avgAge));
				event.getAttributes().add(attr2);
			}
			BulkFactories.getBulkFactory().createBulkEntry(sessionId, FactoryEnumType.EVENT, event);
			
			*/
		} catch (ValueException | FieldException | ModelNotFoundException | FactoryException e) {
			
			logger.error(e);
		}
		logger.info("Finished populating " + locName + " in " + (System.currentTimeMillis() - start) + "ms");
		return event;
	}
	
	public static int cleanupWorld(BaseRecord user, BaseRecord world) {
		int totalWrites = 0;
		IOSystem.getActiveContext().getReader().populate(world, 2);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_GEO_LOCATION, (long)world.get("locations.id"), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		totalWrites += cleanupLocation(user, ModelNames.MODEL_EVENT, (long)world.get("events.id"), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		totalWrites += cleanupLocation(user, ModelNames.MODEL_CHAR_PERSON, (long)world.get("population.id"), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		totalWrites += cleanupLocation(user, ModelNames.MODEL_GROUP, (long)world.get("population.id"), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		totalWrites += cleanupLocation(user, ModelNames.MODEL_ADDRESS, (long)world.get("addresses.id"), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		totalWrites += cleanupLocation(user, ModelNames.MODEL_CONTACT, (long)world.get("contacts.id"), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		totalWrites += cleanupLocation(user, ModelNames.MODEL_WORD_NET, (long)world.get("words.id"), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		totalWrites += cleanupLocation(user, ModelNames.MODEL_CENSUS_WORD, (long)world.get("names.id"), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		totalWrites += cleanupLocation(user, ModelNames.MODEL_WORD, (long)world.get("occupations.id"), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		totalWrites += cleanupLocation(user, ModelNames.MODEL_COLOR, (long)world.get("colors.id"), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		totalWrites += cleanupLocation(user, ModelNames.MODEL_QUALITY, (long)world.get("qualities.id"), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		totalWrites += cleanupLocation(user, ModelNames.MODEL_WEARABLE, (long)world.get("wearables.id"), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		totalWrites += cleanupLocation(user, ModelNames.MODEL_APPAREL, (long)world.get("apparel.id"), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		return totalWrites;
	}
	
	public static int cleanupLocation(BaseRecord user, String model, long groupId, long organizationId) {
		
		if(groupId <= 0L || organizationId <= 0L) {
			logger.error("Invalid group or organization");
			return 0;
		}
		Query lq = QueryUtil.getGroupQuery(model, null, groupId, organizationId);
		int deleted = 0;
		try {
			deleted = IOSystem.getActiveContext().getWriter().delete(lq);
		} catch (WriterException e) {
			logger.error(e);
			e.printStackTrace();
		}
		logger.info("Cleaned up " + deleted + " " + model + " in #" + groupId + " (#" + organizationId + ")");
		return deleted;
	}
     
}
