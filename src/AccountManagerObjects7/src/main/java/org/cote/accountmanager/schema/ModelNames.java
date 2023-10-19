package org.cote.accountmanager.schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.RecordFactory;

public class ModelNames {
	
	public static final Logger logger = LogManager.getLogger(ModelNames.class);
	
	public static final String MODEL_MODEL = "model";
	public static final String MODEL_MODEL_SCHEMA = "modelSchema";
	public static final String MODEL_PRIMARY_KEY = "primaryKey";
	
	public static final String MODEL_ACCOUNT = "account";
	public static final String MODEL_ADDRESS = "address";
	public static final String MODEL_ATTRIBUTE = "common.attribute";
	public static final String MODEL_ATTRIBUTE_LIST = "common.attributeList";
	public static final String MODEL_BASE = "common.base";
	public static final String MODEL_CIPHER_KEY = "crypto.cipherKey";
	public static final String MODEL_CONTACT = "contact";
	public static final String MODEL_CONTACT_INFORMATION = "contactInformation";
	public static final String MODEL_CONTROL = "control";
	public static final String MODEL_CREDENTIAL = "credential";
	public static final String MODEL_CRYPTOBYTESTORE = "crypto.cryptoByteStore";
	public static final String MODEL_DATA = "data";
	public static final String MODEL_PATH = "path";
	public static final String MODEL_DIRECTORY = "directory";
	public static final String MODEL_FACT = "policy.fact";
	public static final String MODEL_GROUP = "group";
	public static final String MODEL_HASH = "crypto.hash";
	public static final String MODEL_JOURNAL = "journal";
	public static final String MODEL_JOURNAL_ENTRY = "journalEntry";
	public static final String MODEL_JOURNAL_EXT = "journalExt";
	public static final String MODEL_KEY = "crypto.key";
	public static final String MODEL_KEY_SET = "crypto.keySet";
	public static final String MODEL_KEY_STORE = "crypto.keyStore";
	// public static final String MODEL_STORE = "store";
	public static final String MODEL_OPERATION = "operation";
	public static final String MODEL_ORGANIZATION = "organization";
	public static final String MODEL_ORGANIZATION_EXT = "organizationExt";
	public static final String MODEL_PARENT = "parent";
	public static final String MODEL_PARTICIPATION = "participation";
	public static final String MODEL_PARTICIPATION_LIST = "participationList";
	public static final String MODEL_PARTICIPATION_ENTRY = "participationEntry";
	public static final String MODEL_PATTERN = "policy.pattern";
	public static final String MODEL_PERMISSION = "permission";
	public static final String MODEL_AUDIT = "audit";
	
	public static final String MODEL_PERSON = "person";
	public static final String MODEL_POLICY = "policy.policy";
	public static final String MODEL_POLICY_DEFINITION = "policy.policyDefinition";
	public static final String MODEL_POLICY_REQUEST = "policy.policyRequest";
	public static final String MODEL_POLICY_RESPONSE = "policy.policyResponse";
	
	public static final String MODEL_POPULATE = "populate";
	public static final String MODEL_ROLE = "role";
	public static final String MODEL_RSA_KEY = "rsaKey";
	public static final String MODEL_RULE = "policy.rule";
	public static final String MODEL_SELF = "$self";
	public static final String MODEL_FLEX = "$flex";
	public static final String MODEL_SIMPLE_BYTE_STORE = "simpleByteStore";
	public static final String MODEL_UNKNOWN = "unknown";
	public static final String MODEL_SPOOL = "spool";
	public static final String MODEL_USER = "user";
	
	public static final String MODEL_QUERY = "query";
	public static final String MODEL_QUERY_FIELD = "queryField";
	public static final String MODEL_QUERY_RESULT = "queryResult";
	
	public static final String MODEL_TOKEN = "token";
	
	public static final String MODEL_PARAMETER_LIST = "parameterList";
	public static final String MODEL_PARAMETER = "parameter";
	
	public static final String MODEL_VAULT = "crypto.vault";
	public static final String MODEL_VAULT_EXT = "crypto.vaultExt";
	
	public static final String MODEL_INDEX_STORE = "indexStore";
	public static final String MODEL_INDEX2 = "index2";
	public static final String MODEL_INDEX_ENTRY2 = "indexEntry2";
	public static final String MODEL_INDEX_ENTRY_VALUE2 = "indexEntryValue2";
	
	public static final String MODEL_AUTHENTICATION_REQUEST = "authenticationRequest";
	public static final String MODEL_AUTHENTICATION_RESPONSE = "authenticationResponse";
	
	public static final String MODEL_REFERENCE = "reference";
	public static final String MODEL_REQUEST = "request";
	public static final String MODEL_ENTITLEMENT = "entitlement";
	public static final String MODEL_ACCESS_REQUEST = "accessRequest";
	
	public static final String MODEL_STREAM = "stream";
	public static final String MODEL_STREAM_SEGMENT = "streamSegment";
	
	public static final String MODEL_CONTENT_TYPE = "contentType";
	public static final String MODEL_THUMBNAIL = "thumbnail";
	public static final String MODEL_VALIDATION_RULE = "validationRule";
	public static final String MODEL_APPLICATION_PROFILE = "applicationProfile";
	public static final String MODEL_FUNCTION = "function";
	public static final String MODEL_TAG = "tag";
	public static final String MODEL_SOCKET_MESSAGE = "socketMessage";
	public static final String MODEL_NOTE = "note";
	
	public static final String MODEL_FIELD_LOCK = "fieldLock";
	public static final String MODEL_SUBJECT = "subject";
	
	public static final String MODEL_MODEL_CATEGORY = "modelCategory";
	
	public static final String MODEL_DEBUG = "debug";
	
	public static List<String> MODELS = Arrays.asList(
		MODEL_ORGANIZATION, MODEL_SIMPLE_BYTE_STORE, MODEL_USER, MODEL_ACCOUNT, MODEL_PERSON, MODEL_GROUP, MODEL_ATTRIBUTE, MODEL_ROLE,
		MODEL_PERMISSION, MODEL_DATA, MODEL_CONTROL, MODEL_CREDENTIAL, MODEL_OPERATION, MODEL_CONTACT, MODEL_ADDRESS,  MODEL_HASH, MODEL_KEY, MODEL_CIPHER_KEY,
		MODEL_KEY_SET, MODEL_RSA_KEY,  MODEL_POLICY, MODEL_RULE, MODEL_PATTERN, MODEL_FACT, MODEL_POLICY_REQUEST, MODEL_POLICY_RESPONSE, MODEL_POLICY_DEFINITION, MODEL_PARTICIPATION,
		MODEL_QUERY, MODEL_QUERY_FIELD, MODEL_QUERY_RESULT, MODEL_JOURNAL, MODEL_JOURNAL_ENTRY, MODEL_PARTICIPATION_LIST, MODEL_PARTICIPATION_ENTRY, MODEL_SPOOL, MODEL_TOKEN, MODEL_PARAMETER, MODEL_PARAMETER_LIST,
		MODEL_INDEX2, MODEL_INDEX_ENTRY2, MODEL_INDEX_ENTRY_VALUE2, MODEL_INDEX_STORE, MODEL_KEY_STORE, MODEL_VAULT, MODEL_AUTHENTICATION_REQUEST, MODEL_AUTHENTICATION_RESPONSE,
		MODEL_AUDIT, MODEL_CONTACT_INFORMATION, MODEL_ACCESS_REQUEST, MODEL_MODEL_SCHEMA, MODEL_STREAM, MODEL_STREAM_SEGMENT, MODEL_THUMBNAIL, MODEL_VALIDATION_RULE, MODEL_APPLICATION_PROFILE,
		MODEL_FUNCTION, MODEL_TAG, MODEL_MODEL_CATEGORY, MODEL_NOTE, MODEL_SOCKET_MESSAGE, MODEL_FIELD_LOCK
	);
	
	private static List<String> customModelNames = null;
	public static void releaseCustomModelNames() {
		customModelNames = null;
	}
	public static List<String> getCustomModelNames(){
		if(customModelNames == null) {
			customModelNames = listCustomModels();
		}
		return customModelNames;
	}
	
	public static void loadCustomModels() {
		releaseCustomModelNames();
		List<String> names = getCustomModelNames();
		for(String s : names) {
			RecordFactory.model(s);
		}
	}
	
	public static List<String> listCustomModels() {
		List<String> names = new ArrayList<>();
		if(IOSystem.getActiveContext() == null || !IOSystem.getActiveContext().isInitialized()) {
			return names;
		}
		OrganizationContext org = IOSystem.getActiveContext().getOrganizationContext("/System", null);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_MODEL_SCHEMA, FieldNames.FIELD_ORGANIZATION_ID, org.getOrganizationId());
		q.setRequest(new String[] {FieldNames.FIELD_NAME});
		QueryResult qr = null;
		try {
			qr = IOSystem.getActiveContext().getSearch().find(q);
		} catch (IndexException | ReaderException e) {
			logger.error(e);
		}
		
		if(qr != null) {
			names = Arrays.asList(qr.getResults()).stream().map(b -> (String)b.get(FieldNames.FIELD_NAME)).collect(Collectors.toList());	
		}
		return names;
	}

	public static void loadModels() {
		for(String model : MODELS) {
			RecordFactory.model(model);
		}
	}
	
	
}
