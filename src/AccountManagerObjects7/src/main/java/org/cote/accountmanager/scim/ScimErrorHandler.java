package org.cote.accountmanager.scim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ScimErrorHandler {

	private static final ObjectMapper mapper = new ObjectMapper();
	private static final String SCIM_ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";
	public static final String SCIM_CONTENT_TYPE = "application/scim+json";

	public static String buildErrorResponse(int status, String detail) {
		return buildErrorResponse(status, null, detail);
	}

	public static String buildErrorResponse(int status, String scimType, String detail) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("schemas", List.of(SCIM_ERROR_SCHEMA));
		error.put("status", String.valueOf(status));
		if (scimType != null) {
			error.put("scimType", scimType);
		}
		if (detail != null) {
			error.put("detail", detail);
		}
		return toJson(error);
	}

	public static String toJson(Object obj) {
		try {
			return mapper.writeValueAsString(obj);
		} catch (JsonProcessingException e) {
			return "{\"schemas\":[\"" + SCIM_ERROR_SCHEMA + "\"],\"status\":\"500\",\"detail\":\"Internal serialization error\"}";
		}
	}
}
