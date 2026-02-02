package org.cote.accountmanager.scim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.ContactEnumType;
import org.cote.accountmanager.schema.type.LocationEnumType;
import org.cote.accountmanager.schema.type.UserStatusEnumType;

public class ScimUserAdapter {

	private static final Logger logger = LogManager.getLogger(ScimUserAdapter.class);
	public static final String SCIM_USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

	public static Map<String, Object> toScim(BaseRecord user, BaseRecord person, String baseUrl) {
		Map<String, Object> scim = new LinkedHashMap<>();
		scim.put("schemas", List.of(SCIM_USER_SCHEMA));

		String objectId = user.get(FieldNames.FIELD_OBJECT_ID);
		scim.put("id", objectId);

		String urn = user.get(FieldNames.FIELD_URN);
		if (urn != null) {
			scim.put("externalId", urn);
		}

		scim.put("userName", user.get(FieldNames.FIELD_NAME));

		String status = user.get(FieldNames.FIELD_STATUS);
		scim.put("active", mapStatusToActive(status));

		if (person != null) {
			Map<String, Object> name = new LinkedHashMap<>();
			String firstName = person.get(FieldNames.FIELD_FIRST_NAME);
			String middleName = person.get(FieldNames.FIELD_MIDDLE_NAME);
			String lastName = person.get(FieldNames.FIELD_LAST_NAME);
			String title = person.get("title");
			String suffix = person.get("suffix");
			String prefix = person.get("prefix");

			if (firstName != null) name.put("givenName", firstName);
			if (middleName != null) name.put("middleName", middleName);
			if (lastName != null) name.put("familyName", lastName);
			if (title != null) name.put("honorificPrefix", title);
			if (suffix != null) name.put("honorificSuffix", suffix);
			name.put("formatted", formatName(prefix, firstName, middleName, lastName, suffix));
			scim.put("name", name);
			scim.put("displayName", formatDisplayName(firstName, lastName));

			mapContacts(scim, person);
			mapAddresses(scim, person);
		}

		mapGroupMembership(scim, user, baseUrl);
		mapRoleMembership(scim, user, baseUrl);

		scim.put("meta", buildMeta(user, "User", baseUrl));

		return scim;
	}

	public static boolean mapStatusToActive(String status) {
		if (status == null) return true;
		try {
			UserStatusEnumType st = UserStatusEnumType.fromValue(status);
			return st == UserStatusEnumType.NORMAL || st == UserStatusEnumType.REGISTERED;
		} catch (Exception e) {
			return true;
		}
	}

	public static String mapActiveToStatus(boolean active) {
		return active ? UserStatusEnumType.NORMAL.toString() : UserStatusEnumType.DISABLED.toString();
	}

	private static String formatName(String prefix, String first, String middle, String last, String suffix) {
		StringBuilder sb = new StringBuilder();
		if (prefix != null && !prefix.isEmpty()) sb.append(prefix).append(" ");
		if (first != null && !first.isEmpty()) sb.append(first).append(" ");
		if (middle != null && !middle.isEmpty()) sb.append(middle).append(" ");
		if (last != null && !last.isEmpty()) sb.append(last);
		if (suffix != null && !suffix.isEmpty()) sb.append(", ").append(suffix);
		return sb.toString().trim();
	}

	private static String formatDisplayName(String first, String last) {
		StringBuilder sb = new StringBuilder();
		if (first != null && !first.isEmpty()) sb.append(first);
		if (last != null && !last.isEmpty()) {
			if (sb.length() > 0) sb.append(" ");
			sb.append(last);
		}
		return sb.toString();
	}

	private static void mapContacts(Map<String, Object> scim, BaseRecord person) {
		List<Map<String, Object>> emails = new ArrayList<>();
		List<Map<String, Object>> phones = new ArrayList<>();

		BaseRecord ci = person.get(FieldNames.FIELD_CONTACT_INFORMATION);
		if (ci == null) return;

		List<BaseRecord> contacts = ci.get("contacts");
		if (contacts == null) return;

		for (BaseRecord contact : contacts) {
			String contactType = contact.get(FieldNames.FIELD_CONTACT_TYPE);
			String contactValue = contact.get(FieldNames.FIELD_CONTACT_VALUE);
			String locationType = contact.get(FieldNames.FIELD_LOCATION_TYPE);
			boolean preferred = Boolean.TRUE.equals(contact.get(FieldNames.FIELD_PREFERRED));

			if (contactValue == null || contactValue.isEmpty()) continue;

			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("value", contactValue);
			entry.put("type", mapLocationType(locationType));
			entry.put("primary", preferred);

			if (ContactEnumType.EMAIL.toString().equals(contactType)) {
				emails.add(entry);
			} else if (ContactEnumType.PHONE.toString().equals(contactType)) {
				phones.add(entry);
			}
		}

		scim.put("emails", emails);
		scim.put("phoneNumbers", phones);
	}

	private static void mapAddresses(Map<String, Object> scim, BaseRecord person) {
		List<Map<String, Object>> addresses = new ArrayList<>();

		BaseRecord ci = person.get(FieldNames.FIELD_CONTACT_INFORMATION);
		if (ci == null) return;

		List<BaseRecord> addrs = ci.get("addresses");
		if (addrs == null) return;

		for (BaseRecord addr : addrs) {
			Map<String, Object> entry = new LinkedHashMap<>();
			String street = addr.get(FieldNames.FIELD_STREET);
			String street2 = addr.get(FieldNames.FIELD_STREET2);
			String city = addr.get(FieldNames.FIELD_CITY);
			String state = addr.get(FieldNames.FIELD_STATE);
			String region = addr.get(FieldNames.FIELD_REGION);
			String postalCode = addr.get(FieldNames.FIELD_POSTAL_CODE);
			String country = addr.get(FieldNames.FIELD_COUNTRY);
			String locationType = addr.get(FieldNames.FIELD_LOCATION_TYPE);
			boolean preferred = Boolean.TRUE.equals(addr.get(FieldNames.FIELD_PREFERRED));

			StringBuilder formatted = new StringBuilder();
			if (street != null) {
				entry.put("streetAddress", street2 != null ? street + "\n" + street2 : street);
				formatted.append(street);
				if (street2 != null) formatted.append("\n").append(street2);
			}
			if (city != null) {
				entry.put("locality", city);
				if (formatted.length() > 0) formatted.append(", ");
				formatted.append(city);
			}
			if (region != null || state != null) {
				String reg = region != null ? region : state;
				entry.put("region", reg);
				if (formatted.length() > 0) formatted.append(", ");
				formatted.append(reg);
			}
			if (postalCode != null) {
				entry.put("postalCode", postalCode);
				if (formatted.length() > 0) formatted.append(" ");
				formatted.append(postalCode);
			}
			if (country != null) {
				entry.put("country", country);
				if (formatted.length() > 0) formatted.append(", ");
				formatted.append(country);
			}
			entry.put("formatted", formatted.toString());
			entry.put("type", mapLocationType(locationType));
			entry.put("primary", preferred);
			addresses.add(entry);
		}

		scim.put("addresses", addresses);
	}

	private static void mapGroupMembership(Map<String, Object> scim, BaseRecord user, String baseUrl) {
		List<Map<String, Object>> groups = new ArrayList<>();
		try {
			Query q = QueryUtil.createQuery(ModelNames.MODEL_GROUP);
			q.filterParticipant(ModelNames.MODEL_GROUP, null, user, null);
			q.planCommon(false);
			QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
			if (qr != null && qr.getResults() != null) {
				for (BaseRecord group : qr.getResults()) {
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("value", group.get(FieldNames.FIELD_OBJECT_ID));
					entry.put("display", group.get(FieldNames.FIELD_NAME));
					entry.put("$ref", baseUrl + "/v2/Groups/" + group.get(FieldNames.FIELD_OBJECT_ID));
					entry.put("type", "direct");
					groups.add(entry);
				}
			}
		} catch (Exception e) {
			logger.warn("Error mapping group membership: " + e.getMessage());
		}
		scim.put("groups", groups);
	}

	private static void mapRoleMembership(Map<String, Object> scim, BaseRecord user, String baseUrl) {
		List<Map<String, Object>> roles = new ArrayList<>();
		try {
			Query q = QueryUtil.createQuery(ModelNames.MODEL_ROLE);
			q.filterParticipant(ModelNames.MODEL_ROLE, null, user, null);
			q.planCommon(false);
			QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
			if (qr != null && qr.getResults() != null) {
				for (BaseRecord role : qr.getResults()) {
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("value", role.get(FieldNames.FIELD_OBJECT_ID));
					entry.put("display", role.get(FieldNames.FIELD_NAME));
					roles.add(entry);
				}
			}
		} catch (Exception e) {
			logger.warn("Error mapping role membership: " + e.getMessage());
		}
		scim.put("roles", roles);
	}

	public static Map<String, Object> buildMeta(BaseRecord record, String resourceType, String baseUrl) {
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("resourceType", resourceType);

		if (record.hasField(FieldNames.FIELD_CREATED_DATE)) {
			Object created = record.get(FieldNames.FIELD_CREATED_DATE);
			if (created != null) meta.put("created", created.toString());
		}
		if (record.hasField(FieldNames.FIELD_MODIFIED_DATE)) {
			Object modified = record.get(FieldNames.FIELD_MODIFIED_DATE);
			if (modified != null) meta.put("lastModified", modified.toString());
		}

		String objectId = record.get(FieldNames.FIELD_OBJECT_ID);
		String endpoint = "User".equals(resourceType) ? "/v2/Users/" : "/v2/Groups/";
		meta.put("location", baseUrl + endpoint + objectId);

		return meta;
	}

	public static String mapLocationType(String locationType) {
		if (locationType == null) return "other";
		try {
			LocationEnumType lt = LocationEnumType.fromValue(locationType);
			return switch (lt) {
				case HOME -> "home";
				case WORK -> "work";
				case MOBILE -> "mobile";
				default -> "other";
			};
		} catch (Exception e) {
			return "other";
		}
	}

	public static String mapScimTypeToLocationType(String scimType) {
		if (scimType == null) return LocationEnumType.OTHER.toString();
		return switch (scimType.toLowerCase()) {
			case "home" -> LocationEnumType.HOME.toString();
			case "work" -> LocationEnumType.WORK.toString();
			case "mobile" -> LocationEnumType.MOBILE.toString();
			default -> LocationEnumType.OTHER.toString();
		};
	}
}
