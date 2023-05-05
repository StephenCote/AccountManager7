package org.cote.accountmanager.schema;

import java.util.Arrays;
import java.util.List;

import org.cote.accountmanager.record.RecordFactory;

public class ModelNames {
	
	public static final String MODEL_MODEL = "model";
	public static final String MODEL_PRIMARY_KEY = "primaryKey";
	
	public static final String MODEL_ACCOUNT = "account";
	public static final String MODEL_ADDRESS = "address";
	public static final String MODEL_ATTRIBUTE = "attribute";
	public static final String MODEL_ATTRIBUTE_LIST = "attributeList";
	public static final String MODEL_BASE = "base";
	public static final String MODEL_CIPHER_KEY = "cipherKey";
	public static final String MODEL_CONTACT = "contact";
	public static final String MODEL_CONTACT_INFORMATION = "contactInformation";
	public static final String MODEL_CONTROL = "control";
	public static final String MODEL_CREDENTIAL = "credential";
	public static final String MODEL_CRYPTOBYTESTORE = "cryptoByteStore";
	public static final String MODEL_DATA = "data";
	public static final String MODEL_PATH = "path";
	public static final String MODEL_DIRECTORY = "directory";
	public static final String MODEL_FACT = "fact";
	public static final String MODEL_GROUP = "group";
	public static final String MODEL_HASH = "hash";
	public static final String MODEL_JOURNAL = "journal";
	public static final String MODEL_JOURNAL_ENTRY = "journalEntry";
	public static final String MODEL_JOURNAL_EXT = "journalExt";
	public static final String MODEL_KEY = "key";
	public static final String MODEL_KEY_SET = "keySet";
	public static final String MODEL_KEY_STORE = "keyStore";
	// public static final String MODEL_STORE = "store";
	public static final String MODEL_OPERATION = "operation";
	public static final String MODEL_ORGANIZATION = "organization";
	public static final String MODEL_ORGANIZATION_EXT = "organizationExt";
	public static final String MODEL_PARENT = "parent";
	public static final String MODEL_PARTICIPATION = "participation";
	public static final String MODEL_PARTICIPATION_LIST = "participationList";
	public static final String MODEL_PARTICIPATION_ENTRY = "participationEntry";
	public static final String MODEL_PATTERN = "pattern";
	public static final String MODEL_PERMISSION = "permission";
	public static final String MODEL_AUDIT = "audit";
	
	public static final String MODEL_PERSON = "person";
	public static final String MODEL_POLICY = "policy";
	public static final String MODEL_POLICY_DEFINITION = "policyDefinition";
	public static final String MODEL_POLICY_REQUEST = "policyRequest";
	public static final String MODEL_POLICY_RESPONSE = "policyResponse";
	
	public static final String MODEL_POPULATE = "populate";
	public static final String MODEL_ROLE = "role";
	public static final String MODEL_RSA_KEY = "rsaKey";
	public static final String MODEL_RULE = "rule";
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
	
	public static final String MODEL_VAULT = "vault";
	public static final String MODEL_VAULT_EXT = "vaultExt";
	
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
	
	//public static String[] MODELS = new String[] {
	public static List<String> MODELS = Arrays.asList(
		MODEL_ORGANIZATION, MODEL_SIMPLE_BYTE_STORE, MODEL_DIRECTORY, MODEL_USER, MODEL_ACCOUNT, MODEL_PERSON, MODEL_GROUP, MODEL_ATTRIBUTE, MODEL_ROLE,
		MODEL_PERMISSION, MODEL_DATA, MODEL_CONTROL, MODEL_CREDENTIAL, MODEL_OPERATION, MODEL_CONTACT, MODEL_ADDRESS,  MODEL_HASH, MODEL_KEY, MODEL_CIPHER_KEY,
		MODEL_KEY_SET, MODEL_RSA_KEY,  MODEL_POLICY, MODEL_RULE, MODEL_PATTERN, MODEL_FACT, MODEL_POLICY_REQUEST, MODEL_POLICY_RESPONSE, MODEL_POLICY_DEFINITION, MODEL_PARTICIPATION,
		MODEL_QUERY, MODEL_QUERY_FIELD, MODEL_QUERY_RESULT, MODEL_JOURNAL, MODEL_JOURNAL_ENTRY, MODEL_PARTICIPATION_LIST, MODEL_PARTICIPATION_ENTRY, MODEL_SPOOL, MODEL_TOKEN, MODEL_PARAMETER, MODEL_PARAMETER_LIST,
		MODEL_INDEX2, MODEL_INDEX_ENTRY2, MODEL_INDEX_ENTRY_VALUE2, MODEL_INDEX_STORE, MODEL_KEY_STORE, MODEL_VAULT, MODEL_AUTHENTICATION_REQUEST, MODEL_AUTHENTICATION_RESPONSE,
		MODEL_AUDIT, MODEL_CONTACT_INFORMATION, MODEL_ACCESS_REQUEST
	);

	public static void loadModels() {
		for(String model : MODELS) {
			// System.out.println(model);
			RecordFactory.model(model);
		}
	}
	
	
}
