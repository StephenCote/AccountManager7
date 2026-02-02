package org.cote.accountmanager.schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
	public static final String MODEL_SELF = "$self";
	public static final String MODEL_FLEX = "$flex";

	public static final String MODEL_MODEL_SCHEMA = "system.modelSchema";
	public static final String MODEL_PRIMARY_KEY = "system.primaryKey";
	
	public static final String MODEL_ACCOUNT = "identity.account";
	public static final String MODEL_ADDRESS = "identity.address";
	public static final String MODEL_CONTACT = "identity.contact";
	public static final String MODEL_CONTACT_INFORMATION = "identity.contactInformation";
	public static final String MODEL_PERSON = "identity.person";
	public static final String MODEL_PROFILE = "identity.profile";
	public static final String MODEL_APPLICATION_PROFILE = "identity.applicationProfile";
	public static final String MODEL_SUBJECT = "identity.subject";
	public static final String MODEL_PERSONALITY = "identity.personality";
	public static final String MODEL_SOCIAL_BEHAVIOR = "identity.socialBehavior";
	public static final String MODEL_SOCIAL_GROUP = "identity.socialGroup";
	public static final String MODEL_BEHAVIOR = "identity.behavior";

	
	public static final String MODEL_ATTRIBUTE = "common.attribute";
	public static final String MODEL_NAME = "common.name";
	public static final String MODEL_ATTRIBUTE_LIST = "common.attributeList";
	public static final String MODEL_BASE = "common.base";
	public static final String MODEL_CIPHER_KEY = "crypto.cipherKey";

	
	public static final String MODEL_CONTROL = "policy.control";
	public static final String MODEL_CREDENTIAL = "auth.credential";
	public static final String MODEL_CRYPTOBYTESTORE = "crypto.cryptoByteStore";
	public static final String MODEL_DATA = "data.data";
	public static final String MODEL_PATH = "common.path";
	public static final String MODEL_DIRECTORY = "data.directory";
	public static final String MODEL_FACT = "policy.fact";
	public static final String MODEL_FACT_PARAMETER = "policy.factParameter";
	public static final String MODEL_GROUP = "auth.group";
	public static final String MODEL_HASH = "crypto.hash";
	public static final String MODEL_JOURNAL = "data.journal";
	public static final String MODEL_JOURNAL_ENTRY = "data.journalEntry";
	public static final String MODEL_JOURNAL_EXT = "data.journalExt";
	public static final String MODEL_KEY = "crypto.key";
	public static final String MODEL_KEY_SET = "crypto.keySet";
	public static final String MODEL_KEY_STORE = "crypto.keyStore";
	public static final String MODEL_OPERATION = "policy.operation";
	public static final String MODEL_ORGANIZATION = "system.organization";
	public static final String MODEL_ORGANIZATION_EXT = "system.organizationExt";
	public static final String MODEL_PARENT = "common.parent";
	public static final String MODEL_PARTICIPATION = "system.participation";
	public static final String MODEL_PARTICIPATION_LIST = "file.participationList";
	public static final String MODEL_PARTICIPATION_ENTRY = "file.participationEntry";
	public static final String MODEL_PATTERN = "policy.pattern";
	public static final String MODEL_PERMISSION = "auth.permission";
	public static final String MODEL_AUDIT = "system.audit";
	
	public static final String MODEL_POLICY = "policy.policy";
	public static final String MODEL_POLICY_DEFINITION = "policy.policyDefinition";
	public static final String MODEL_POLICY_REQUEST = "policy.policyRequest";
	public static final String MODEL_POLICY_RESPONSE = "policy.policyResponse";
	
	public static final String MODEL_POPULATE = "common.populate";
	public static final String MODEL_ROLE = "auth.role";
	public static final String MODEL_RSA_KEY = "crypto.rsaKey";
	public static final String MODEL_RULE = "policy.rule";
	public static final String MODEL_SIMPLE_BYTE_STORE = "file.simpleByteStore";
	public static final String MODEL_UNKNOWN = "unknown";
	public static final String MODEL_SPOOL = "message.spool";
	public static final String MODEL_USER = "system.user";
	
	public static final String MODEL_QUERY = "io.query";
	public static final String MODEL_QUERY_PLAN = "io.queryPlan";
	public static final String MODEL_QUERY_FIELD = "io.queryField";
	public static final String MODEL_QUERY_RESULT = "io.queryResult";
	
	public static final String MODEL_TOKEN = "auth.token";
	
	public static final String MODEL_PARAMETER_LIST = "dev.parameterList";
	public static final String MODEL_PARAMETER = "dev.parameter";
	
	public static final String MODEL_VAULT = "crypto.vault";
	public static final String MODEL_VAULT_EXT = "crypto.vaultExt";
	
	public static final String MODEL_INDEX_STORE = "file.indexStore";
	public static final String MODEL_INDEX2 = "file.index2";
	public static final String MODEL_INDEX_ENTRY2 = "file.indexEntry2";
	public static final String MODEL_INDEX_ENTRY_VALUE2 = "file.indexEntryValue2";
	
	public static final String MODEL_AUTHENTICATION_REQUEST = "auth.authenticationRequest";
	public static final String MODEL_AUTHENTICATION_RESPONSE = "auth.authenticationResponse";
	
	public static final String MODEL_REFERENCE = "common.reference";
	public static final String MODEL_REQUEST = "access.request";
	public static final String MODEL_ENTITLEMENT = "auth.entitlement";
	public static final String MODEL_ACCESS_REQUEST = "access.accessRequest";
	
	public static final String MODEL_STREAM = "data.stream";
	public static final String MODEL_STREAM_SEGMENT = "data.streamSegment";
	
	public static final String MODEL_CONTENT_TYPE = "data.contentType";
	public static final String MODEL_THUMBNAIL = "data.thumbnail";
	public static final String MODEL_VALIDATION_RULE = "policy.validationRule";

	public static final String MODEL_FUNCTION = "policy.function";
	public static final String MODEL_TAG = "data.tag";
	public static final String MODEL_SOCKET_MESSAGE = "message.socketMessage";
	public static final String MODEL_NOTE = "data.note";
	
	public static final String MODEL_FIELD_LOCK = "system.fieldLock";
	
	
	public static final String MODEL_MODEL_CATEGORY = "system.modelCategory";
	
	public static final String MODEL_DEBUG = "dev.debug";
	
	public static final String MODEL_COLOR = "data.color";
	public static final String MODEL_WORD = "data.word";
	public static final String MODEL_CENSUS_WORD = "data.cenWord";
	public static final String MODEL_WORD_NET = "data.wordNet";
	public static final String MODEL_WORD_NET_POINTER = "data.wordNetPointer";
	public static final String MODEL_WORD_NET_ALT = "data.wordNetAlt";
	
	public static final String MODEL_TRAIT = "data.trait";
	public static final String MODEL_LOCATION = "data.location";
	public static final String MODEL_GEO_LOCATION = "data.geoLocation";
	public static final String MODEL_ALIGNMENT = "common.alignment";
	
	public static final String MODEL_VECTOR_EXT = "common.vectorExt";
	public static final String MODEL_VECTOR_MODEL_STORE = "data.vectorModelStore";
	public static final String MODEL_VECTOR_LIST = "data.vectorList";
	public static final String MODEL_VECTOR_CHUNK = "data.vectorChunk";
	
	public static final String MODEL_TASK_REQUEST = "system.taskRequest";
	public static final String MODEL_TASK_RESPONSE = "system.taskResponse";
	
	public static final String MODEL_PLAN = "tool.plan";
	public static final String MODEL_PLAN_STEP = "tool.planStep";
	public static final String MODEL_CHAIN_EVENT = "tool.chainEvent";
	
	public static final String MODEL_VOICE = "identity.voice";
	
	public static List<String> MODELS = Arrays.asList(
		MODEL_ORGANIZATION, MODEL_SIMPLE_BYTE_STORE, MODEL_USER, MODEL_ACCOUNT, MODEL_PERSON, MODEL_GROUP, MODEL_ATTRIBUTE, MODEL_ROLE,
		MODEL_PERMISSION, MODEL_DATA, MODEL_CONTROL, MODEL_CREDENTIAL, MODEL_OPERATION, MODEL_CONTACT, MODEL_ADDRESS,  MODEL_HASH, MODEL_KEY, MODEL_CIPHER_KEY,
		MODEL_KEY_SET, MODEL_RSA_KEY,  MODEL_POLICY, MODEL_RULE, MODEL_PATTERN, MODEL_FACT, MODEL_FACT_PARAMETER, MODEL_POLICY_REQUEST, MODEL_POLICY_RESPONSE, MODEL_POLICY_DEFINITION, MODEL_PARTICIPATION,
		MODEL_QUERY, MODEL_QUERY_FIELD, MODEL_QUERY_RESULT, MODEL_QUERY_PLAN, MODEL_JOURNAL, MODEL_JOURNAL_ENTRY, MODEL_PARTICIPATION_LIST, MODEL_PARTICIPATION_ENTRY, MODEL_SPOOL, MODEL_TOKEN, MODEL_PARAMETER, MODEL_PARAMETER_LIST,
		MODEL_INDEX2, MODEL_INDEX_ENTRY2, MODEL_INDEX_ENTRY_VALUE2, MODEL_INDEX_STORE, MODEL_KEY_STORE, MODEL_VAULT, MODEL_AUTHENTICATION_REQUEST, MODEL_AUTHENTICATION_RESPONSE,
		MODEL_AUDIT, MODEL_CONTACT_INFORMATION, MODEL_ACCESS_REQUEST, MODEL_MODEL_SCHEMA, MODEL_STREAM, MODEL_STREAM_SEGMENT, MODEL_THUMBNAIL, MODEL_VALIDATION_RULE, MODEL_APPLICATION_PROFILE,
		MODEL_FUNCTION, MODEL_TAG, MODEL_MODEL_CATEGORY, MODEL_NOTE, MODEL_SOCKET_MESSAGE, MODEL_FIELD_LOCK, MODEL_TRAIT, MODEL_WORD, MODEL_WORD_NET, MODEL_CENSUS_WORD, MODEL_WORD_NET_POINTER, MODEL_WORD_NET_ALT,
		MODEL_LOCATION, MODEL_GEO_LOCATION,  MODEL_PERSONALITY, MODEL_SOCIAL_BEHAVIOR,MODEL_COLOR, MODEL_SOCIAL_GROUP, MODEL_BEHAVIOR, MODEL_PROFILE, MODEL_VECTOR_MODEL_STORE, MODEL_VECTOR_LIST, MODEL_VECTOR_CHUNK,
		MODEL_TASK_REQUEST, MODEL_TASK_RESPONSE, MODEL_PLAN, MODEL_PLAN_STEP, MODEL_CHAIN_EVENT, MODEL_VOICE
	).stream().collect(Collectors.toList());
	
	public static void addModels(List<String> modelNames) {
		logger.info("Adding " + modelNames.size() + " models");
		if(!MODELS.addAll(modelNames)) {
			logger.error("Failed to add " + modelNames.size() + " models");
		}
	}
	
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
		if(!IOSystem.isInitialized()) {
			return names;
		}
		OrganizationContext org = IOSystem.getActiveContext().getOrganizationContext("/System", null);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_MODEL_SCHEMA, FieldNames.FIELD_ORGANIZATION_ID, org.getOrganizationId());
		q.setRequest(new String[] {FieldNames.FIELD_NAME});
		QueryResult qr = null;
		try {
			qr = IOSystem.getActiveContext().getSearch().find(q);
		} catch (ReaderException e) {
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
