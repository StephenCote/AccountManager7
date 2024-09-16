package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ContactEnumType;
import org.cote.accountmanager.schema.type.ContactInformationEnumType;
import org.cote.accountmanager.schema.type.LocationEnumType;

public class AddressUtil {
	public static final Logger logger = LogManager.getLogger(AddressUtil.class);
	private static SecureRandom rand = new SecureRandom();
    protected static final String[] STREET_NAME_BASE = new String[]{
			"SN","SN","SN","SN","SN","##","##","##","##","##","First","Second","Third","Fourth","Fifth","Sixth","Seventh","Eighth","Ninth","Tenth","Main","Church","High","Elm","Washington","Walnut","Park","Broad","Chestnut","Maple","Center","Pine","Water","Oak","River","Union","Market","Spring","Prospect","Central","School","Front","Cherry","Franklin","Highland","Mill","Bridge","Cedar","Jefferson","State","Spruce","Madison","Pearl","Pleasant","Academy","Jackson","Grove","Pennsylvania","Adams","Locust","Elizabeth","Green","Lincoln","Meadow","Dogwood","Liberty","Vine","Brookside","Delaware","Hickory","Hillside","Monroe","Virginia","Winding","Charles","Clinton","College","Railroad","Summit","Colonial","Division","Valley","Williams","Woodland","Lafayette","Lake","Oak","Penn","Poplar","Primrose","Sunset","Warren","Willow","Beech","Berkshire","Deerfield","Harrison","Laurel","Cambridge","Cherry","Dogwood","Heather","Hillcrest","Holly","King","Laurel","Mulberry","Riverside","Sherwood","Smith","Valley","York","Arch","Creek","Essex","Forest","Garden","George","Glenwood","Grant","Hamilton","James","John","Magnolia","Myrtle","Olive","Orange","Oxford","Aspen","Bank","Buckingham","Canal","Canterbury","Carriage","Clark","Devon","Durham","Lilac","Locust","Maple","Surrey","Wall","Windsor","Beechwood","Columbia","Cottage","Garfield","Henry","Linden","Mechanic","Rosewood","Skyline","Sycamore","William"
	};
	protected static final String[] BUILDING_UNIT = new String[]{"A","B","C","D"};

	protected static final String[] CARDINAL_DIRECTION = new String[]{"N","W","S","E",""};
	protected static final String[] QUADRANT_DIRECTION = new String[]{"NW","NE","SW","SE",""};
	protected static final String[] STREET_TYPE_BASE = new String[]{"","","","Avenue","Street","Drive","Circle","Court","Place","Terrace","Highway","Pike","Boulevard","Alley","Bend","Gardens","Gate","Grove","Heights","Lane","Trail","Vale","Way","Cove","Park","Plaza","Ridge","Hill","Canyon","Loop","Circle","Road","View"};
	private static int randomStreetSeed = 1000;
	
	protected static BaseRecord simpleAddressPerson(OlioContext ctx, BaseRecord location, BaseRecord person) {
		/// IOSystem.getActiveContext().getReader().populate(person, new String[] {FieldNames.FIELD_CONTACT_INFORMATION});
		/// person.get(FieldNames.FIELD_CONTACT_INFORMATION);
		BaseRecord cit = null;
		try {
			// if(cit == null) {
				cit = RecordFactory.newInstance(ModelNames.MODEL_CONTACT_INFORMATION);
				cit.set("contactInformationType", ContactInformationEnumType.PERSON);
				cit.set(FieldNames.FIELD_REFERENCE_ID, person.get(FieldNames.FIELD_ID));
				cit.set(FieldNames.FIELD_REFERENCE_TYPE, person.getModel());
				IOSystem.getActiveContext().getRecordUtil().applyOwnership(ctx.getOlioUser(), cit, ctx.getOlioUser().get(FieldNames.FIELD_ORGANIZATION_ID));
				List<BaseRecord> addrs = cit.get("addresses");
				BaseRecord addr = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ADDRESS, ctx.getOlioUser(), null, ParameterList.newParameterList("path", ctx.getWorld().get("addresses.path")));
				addr.set(FieldNames.FIELD_NAME, UUID.randomUUID().toString());
				addr.set("location", location.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID}));
				addr.set("locationType", LocationEnumType.OTHER);
				addr.set("preferred", true);
				addrs.add(addr);
				//IOSystem.getActiveContext().getRecordUtil().createRecord(cit);
				person.set(FieldNames.FIELD_CONTACT_INFORMATION, cit);
			// }
		}
		catch(ModelNotFoundException | FieldException | ValueException | FactoryException e) {
			logger.error(e);
		}
		return cit;
	}
	
	/// Returns an array of objects related to the address
	///
	protected static BaseRecord[] randomAddressPerson(BaseRecord user, BaseRecord world, BaseRecord person, BaseRecord location) {
		List<BaseRecord> objs = new ArrayList<>();
		if(location == null) {
			location = GeoLocationUtil.randomLocation(user, world);
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
			
			String firstName = person.get(FieldNames.FIELD_FIRST_NAME);
			String middleName = person.get(FieldNames.FIELD_MIDDLE_NAME);
			String lastName = person.get(FieldNames.FIELD_LAST_NAME);
			
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
			
			String[] posts = GeoLocationUtil.getAlternateNames(user, location, "post");
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
	
	private static void applyStateAndCountry(BaseRecord user, BaseRecord location, BaseRecord addr) throws FieldException, ValueException, ModelNotFoundException, ReaderException {
		
		boolean checkParent = false;
		IOSystem.getActiveContext().getReader().populate(location);
		//IOSystem.getActiveContext().getReader().conditionalPopulate(location, new String[] {"geoType", FieldNames.FIELD_PARENT_ID, FieldNames.FIELD_NAME});
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

	private static String randomBuilding(){
		Random r = new Random();
		return (r.nextInt(20000) + 1) + (Math.floor(r.nextDouble()*2)==1 ? BUILDING_UNIT[r.nextInt(BUILDING_UNIT.length)] : "");
		
	}

	private static String randomStreetName(){
		Random r = new Random();
		String streetName = STREET_NAME_BASE[r.nextInt(STREET_NAME_BASE.length)];
		if(streetName == "SN"){
			// streetName = dutil.getNames().get("common")[r.nextInt(dutil.getNames().get("common").length)];
			streetName = (Decks.surnameNamesDeck.length > 0 ? Decks.surnameNamesDeck[rand.nextInt(Decks.surnameNamesDeck.length)] : "Every Street");
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
