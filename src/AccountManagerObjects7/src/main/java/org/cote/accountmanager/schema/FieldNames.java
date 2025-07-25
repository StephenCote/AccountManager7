package org.cote.accountmanager.schema;

public class FieldNames {

		/// Field Suffix
		public static final String FIELD_SUFFIX_FOREIGN_KEY = "_FK";
	
		/// Base Fields
		public static final String FIELD_MODEL_NAME = "modelName";
		public static final String FIELD_ORGANIZATION_PATH = "organizationPath";
		public static final String FIELD_ORGANIZATION_ID = "organizationId";
		public static final String FIELD_URN = "urn";
		public static final String FIELD_NAME = "name";
		public static final String FIELD_ALIAS = "alias";
		public static final String FIELD_ID = "id";
		public static final String FIELD_OBJECT_ID = "objectId";
		public static final String FIELD_OWNER_ID = "ownerId";
		public static final String FIELD_PARENT_ID = "parentId";
		public static final String FIELD_TYPE = "type";
		public static final String FIELD_VALUE = "value";
		public static final String FIELD_ATTRIBUTES = "attributes";
		public static final String FIELD_TAGS = "tags";
		public static final String FIELD_POPULATED = "populated";
		public static final String FIELD_POPULATED_FIELDS = "populatedFields";
		public static final String FIELD_STATUS = "status";
		public static final String FIELD_DESCRIPTION = "description";
		public static final String FIELD_DEBUG = "debug";
		public static final String FIELD_TEXT = "text";
		/// Journal
		public static final String FIELD_JOURNAL = "journal";
		public static final String FIELD_JOURNALED = "journaled";
		public static final String FIELD_JOURNAL_HASH = "journalHash";
		public static final String FIELD_JOURNAL_VERSION = "journalVersion";
		public static final String FIELD_JOURNAL_ENTRIES = "journalEntries";
		public static final String FIELD_JOURNAL_ENTRY_MODIFIED = "modified";
		public static final String FIELD_JOURNAL_ENTRY_DATE = "journalDate";
		public static final String FIELD_JOURNAL_FIELD_ID = FIELD_JOURNAL + "." + FIELD_ID;
		/// Extension
		public static final String FIELD_PATH = "path";
		public static final String FIELD_GROUP_PATH = "groupPath";
		public static final String FIELD_GROUP_ID = "groupId";
		
		public static final String FIELD_HOME_DIRECTORY = "homeDirectory";
		public static final String FIELD_HOME_DIRECTORY_FIELD_ID = FIELD_HOME_DIRECTORY + "." + FIELD_ID;
		public static final String FIELD_HOME_DIRECTORY_FIELD_PATH = FIELD_HOME_DIRECTORY + "." + FIELD_PATH;
		
		/// Dates
		public static final String FIELD_CREATED_DATE = "createdDate";
		public static final String FIELD_MODIFIED_DATE = "modifiedDate";
		public static final String FIELD_EXPIRY_DATE = "expiryDate";
		
		/// Policy Fields
		public static final String FIELD_ENABLED = "enabled";
		public static final String FIELD_CONTEXT_USER = "contextUser";
		public static final String FIELD_CONTEXT_USER_OBJECT_ID = "contextUser." + FIELD_OBJECT_ID;
		public static final String FIELD_RULES = "rules";
		public static final String FIELD_PATTERNS = "patterns";
		public static final String FIELD_PARAMETERS = "parameters";
		public static final String FIELD_PATTERN = "pattern";
		public static final String FIELD_MESSAGE = "message";
		public static final String FIELD_MESSAGES = "messages";
		public static final String FIELD_FACTS = "facts";
		public static final String FIELD_MATCH = "match";
		public static final String FIELD_FACT = "fact";
		public static final String FIELD_MODEL_TYPE = "modelType";
		public static final String FIELD_FACT_FIELD_MODEL_TYPE = FIELD_FACT + "." + FIELD_MODEL_TYPE;
		public static final String FIELD_SOURCE_URN = "sourceUrn";
		public static final String FIELD_SOURCE_URL = "sourceUrl";
		public static final String FIELD_CONTROLS = "controls";
		
		public static final String FIELD_SCORE = "score";
		public static final String FIELD_DECISION_AGE = "decisionAge";
		public static final String FIELD_SOURCE_DATA = "sourceData";
		
		/// Depracet sourceType?
		public static final String FIELD_SOURCE_TYPE = "sourceType";
		/// Deprecate sourceDataType?
		public static final String FIELD_SOURCE_DATA_TYPE = "sourceDataType";
		public static final String FIELD_FACT_DATA = "factData";
		public static final String FIELD_FACT_DATA_TYPE = "factDataType";
		public static final String FIELD_FACT_TYPE = "factType";
		public static final String FIELD_COMPARATOR = "comparator";
		public static final String FIELD_CONDITION = "condition";
		public static final String FIELD_OPERATION_URN = "operationUrn";
		// public static final String FIELD_OPERATION_CLASS = "operationClass";
		public static final String FIELD_OPERATION = "operation";
		public static final String FIELD_PATTERN_CHAIN = "patternChain";
		public static final String FIELD_RULE_CHAIN = "ruleChain";
		
		public static final String FIELD_SUBJECT = "subject";
		public static final String FIELD_SUBJECT_TYPE = "subjectType";
		public static final String FIELD_RESOURCE_TYPE = "resourceType";
		public static final String FIELD_RESOURCE_DATA = "resourceData";
		
	   /// Fields defined in cryptoByteStoreModel.json
	   ///
	   public static final String FIELD_BYTE_STORE = "dataBytesStore";
	   public static final String FIELD_CIPHER = "cipher";
	   public static final String FIELD_KEYS = "cipherKey";
	   public static final String FIELD_ENCIPHERED = "enciphered";
	   public static final String FIELD_ENCRYPT = "encrypt";
	   public static final String FIELD_VAULTED = "vaulted";
	   public static final String FIELD_VAULT_ID = "vaultId";
	   public static final String FIELD_COMPRESSION_TYPE = "compressionType";
	   public static final String FIELD_READ_BYTE_STORE = "readDataBytes";
	   public static final String FIELD_READ = "read";
	   public static final String FIELD_CONTENT_TYPE = "contentType";
	   public static final String FIELD_DATA_HASH = "dataHash";
	   public static final String FIELD_HASH = "hash";
	   public static final String FIELD_SIZE = "size";
	   public static final String FIELD_IV = "iv";
	   public static final String FIELD_KEY = "key";
	   public static final String FIELD_KEY_ID = "keyId";
	   public static final String FIELD_KEY_SET = "keySet";
	   public static final String FIELD_STORE = "store";
	   
	   /// Fields Crypto
	   public static final String FIELD_CIPHER_FIELD_ENCRYPT = FIELD_CIPHER + "." + FIELD_ENCRYPT;
	   public static final String FIELD_CIPHER_FIELD_KEY = FIELD_CIPHER + "." + FIELD_KEY;
	   public static final String FIELD_CIPHER_FIELD_IV = FIELD_CIPHER + "." + FIELD_IV;

	   public static final String FIELD_CIPHER_FIELD_KEY_ID = FIELD_CIPHER + "." + FIELD_KEY_ID;
		public static final String FIELD_CIPHER_FIELD_KEYSIZE = "cipher.keySize";
		public static final String FIELD_CIPHER_FIELD_KEYMODE = "cipher.keyMode";
		public static final String FIELD_CIPHER_FIELD_KEYSPEC = "cipher.keySpec";
		public static final String FIELD_HASH_FIELD_KEYFUNCTION = "hash.keyFunction";
		public static final String FIELD_HASH_FIELD_PRNG = "hash.prng";
		public static final String FIELD_HASH_FIELD_SALT = "hash.salt";
		public static final String FIELD_HASH_FIELD_ALGORITHM = "hash.algorithm";
		public static final String FIELD_CURVE_NAME = "curveName";
		public static final String FIELD_PUBLIC = "public";
		public static final String FIELD_SALT = "salt";
		public static final String FIELD_PRIVATE = "private";
		public static final String FIELD_KEY_SPEC = "keySpec";
		public static final String FIELD_KEY_SIZE = "keySize";
		public static final String FIELD_KEY_MODE = "keyMode";
		public static final String FIELD_PUBLIC_FIELD_KEY = FIELD_PUBLIC + "." + FIELD_KEY;
		public static final String FIELD_PUBLIC_FIELD_KEYSIZE = FIELD_PUBLIC + "." + FIELD_KEY_SIZE;
		public static final String FIELD_PRIVATE_FIELD_KEYSIZE = FIELD_PRIVATE + "." + FIELD_KEY_SIZE;
		public static final String FIELD_PRIVATE_FIELD_KEY = FIELD_PRIVATE + "." + FIELD_KEY;
		public static final String FIELD_PUBLIC_FIELD_KEYSPEC = FIELD_PUBLIC + "." + FIELD_KEY_SPEC;
		public static final String FIELD_PRIVATE_FIELD_KEYSPEC = FIELD_PRIVATE + "." + FIELD_KEY_SPEC;
		public static final String FIELD_PUBLIC_FIELD_KEYMODE = FIELD_PUBLIC + "." + FIELD_KEY_MODE;
		public static final String FIELD_PRIVATE_FIELD_KEYMODE = FIELD_PRIVATE + "." + FIELD_KEY_MODE;

		public static final String FIELD_RSA_MODULUS = "modulus";
		public static final String FIELD_RSA_EXPONENT = "exponent";
		public static final String FIELD_RSA_IEXPONENT = "d";
		public static final String FIELD_AGREEMENTSPEC = "agreementSpec";
		
		// Participation
		public static final String FIELD_PARTICIPATION = "participation";
		public static final String FIELD_PARTICIPATION_ID = "participationId";
		public static final String FIELD_PARTICIPATION_MODEL = "participationModel";
		public static final String FIELD_PARTICIPANT_ID = "participantId";
		public static final String FIELD_PARTICIPANT_MODEL = "participantModel";
		public static final String FIELD_EFFECT_TYPE = "effectType";
		public static final String FIELD_PERMISSION_ID = "permissionId";
		public static final String FIELD_PARTS = "parts";
		public static final String FIELD_PART_ID = "partId";
		
		// Queries
		public static final String FIELD_FIELDS = "fields";
		public static final String FIELD_REQUEST = "request";
		public static final String FIELD_ORDER = "order";
		public static final String FIELD_SORT_FIELD = "sortField";
		public static final String FIELD_RECORD_COUNT = "recordCount";
		public static final String FIELD_START_RECORD = "startRecord";
		public static final String FIELD_QUERY_HASH = "queryHash";
		public static final String FIELD_QUERY_KEY = "queryKey";
		public static final String FIELD_QUERIES = "queries";
		public static final String FIELD_JOINS = "joins";
		public static final String FIELD_JOIN_KEY = "joinKey";
		public static final String FIELD_RESULTS = "results";
		public static final String FIELD_RESPONSE = "response";
		public static final String FIELD_ACTION = "action";
		public static final String FIELD_COUNT = "count";
		public static final String FIELD_INSPECT = "inspect";
		public static final String FIELD_TOTAL_COUNT = "totalCount";
		public static final String FIELD_EXECUTED = "executed";
		
		// Supplemental - Person
		public static final String FIELD_USERS = "users";
		public static final String FIELD_ACCOUNTS = "accounts";
		public static final String FIELD_DEPENDENTS = "dependents";
		public static final String FIELD_PARTNERS = "partners";
		public static final String FIELD_FIRST_NAME = "firstName";
		public static final String FIELD_MIDDLE_NAME = "middleName";
		public static final String FIELD_LAST_NAME = "lastName";
		public static final String FIELD_AGE = "age";
		public static final String FIELD_VOICE = "voice";
		
		// Spool
		public static final String FIELD_SPOOL_BUCKET_TYPE = "spoolBucketType";
		public static final String FIELD_SPOOL_BUCKET_NAME = "spoolBucketName";
		public static final String FIELD_SPOOL_STATUS = "spoolStatus";
		public static final String FIELD_VALUE_TYPE = "valueType";
		public static final String FIELD_EXPIRES = "expires";
		public static final String FIELD_DATA = "data";
		
		// Credential
		public static final String FIELD_CREDENTIAL = "credential";
		public static final String FIELD_CHECK_CREDENTIAL = "checkCredential";
		public static final String FIELD_CREDENTIAL_TYPE = "credentialType";
		public static final String FIELD_CHECK_CREDENTIAL_TYPE = "checkCredentialType";
		public static final String FIELD_REFERENCE_TYPE = "referenceModel";
		public static final String FIELD_REFERENCE_ID = "referenceId";
		
		/// Index
		public static final String FIELD_ENTRIES = "entries";
		public static final String FIELD_VALUES = "values";
		public static final String FIELD_LAST_ID = "lastId";
		public static final String FIELD_INDEX_MODEL = "indexModel";
		
		/// Vault
		
		public static final String FIELD_NAME_HASH = "nameHash";
		public static final String FIELD_ACTIVE_KEY = "activeKey";
		public static final String FIELD_ACTIVE_KEY_ID = "activeKeyId";
		public static final String FIELD_VAULT_KEY = "vaultKey";
		public static final String FIELD_SERVICE_USER = "serviceUser";
		public static final String FIELD_PROTECTED_CREDENTIAL = "protectedCredential";
		public static final String FIELD_PROTECTED_CREDENTIAL_PATH = "protectedCredentialPath";
		public static final String FIELD_VAULT_PATH = "vaultPath";
		public static final String FIELD_KEY_PATH = "keyPath";
		public static final String FIELD_VAULT_GROUP = "vaultGroup";
		public static final String FIELD_GROUP_NAME = "groupName";
		
		public static final String FIELD_VAULTED_FIELDS = "vaultedFields";
		public static final String FIELD_UNVAULTED_FIELDS = "unvaultedFields";
		
		//public static final String FIELD_VAULT_DATA = "vaultData";
		public static final String FIELD_VAULT_LINK = "vaultLink";
		public static final String FIELD_KEY_EXTENSION = "keyExtension";
		public static final String FIELD_KEY_PREFIX = "keyPrefix";
		public static final String FIELD_KEY_PROTECTED_PREFIX = "keyProtectedPrefix";
		public static final String FIELD_HAVE_VAULT_KEY = "haveVaultKey";
		public static final String FIELD_HAVE_CREDENTIAL = "haveCredential";
		public static final String FIELD_PROTECTED = "protected";
		public static final String FIELD_DN = "dn";
		public static final String FIELD_INITIALIZED = "initialized";
		public static final String FIELD_PRIMARY_KEY = "primaryKey";
		public static final String FIELD_GLOBAL_KEY = "globalKey";
		
		public static  final String FIELD_RESOURCE = "resource";
		public static final String FIELD_TOP_COUNT = "topCount";
		public static final String FIELD_GROUP_CLAUSE = "groupBy";
		public static final String FIELD_HAVING_CLAUSE = "having";
		
		public static final String FIELD_SIGNATURE = "signature";
		public static final String FIELD_POLICY = "policy";
		public static final String FIELD_VERBOSE = "verbose";
		public static final String FIELD_QUERY = "query";
		public static final String FIELD_PASSWORD = "password";
		
		public static final String FIELD_APPROVAL_STATUS = "approvalStatus";
		
		public static final String FIELD_SCHEMA = "schemaData";
		
		public static final String FIELD_START_POSITION = "startPosition";
		public static final String FIELD_LENGTH = "length";
		public static final String FIELD_STREAM = "stream";
		public static final String FIELD_STREAM_ID = "streamId";
		public static final String FIELD_STREAM_SOURCE = "streamSource";
		public static final String FIELD_SEGMENTS = "segments";
		
		public static  final String FIELD_TOKEN = "token";
		public static  final String FIELD_WIDTH = "width";
		public static  final String FIELD_HEIGHT = "height";
		
		public static final String FIELD_COMPARISON = "comparison";
		public static final String FIELD_ALLOW_NULL = "allowNull";
		public static final String FIELD_EXPRESSION = "expression";
		public static final String FIELD_REPLACEMENT_VALUE = "replacementValue";
		public static final String FIELD_SYSTEM_ROLES = "systemRoles";
		public static final String FIELD_USER_ROLES = "userRoles";
		public static final String FIELD_SYSTEM_PERMISSIONS = "systemPermissions";
		
		public static final String FIELD_USER = "user";
		public static final String FIELD_PERSON = "person";
		
		public static final String FIELD_FIELD_NAME = "fieldName";
		
		public static final String FIELD_FUNCTION = "function";
		
		public static final String FIELD_CLASS = "class";
		public static final String FIELD_CACHE = "cache";
		/// Access Approval
		public static final String FIELD_ENTITLEMENT = "entitlement";
		public static final String FIELD_ENTITLEMENT_TYPE = "entitlementType";
		public static final String FIELD_REQUESTER = "requester";
		public static final String FIELD_REQUESTER_TYPE = "requesterType";
		public static final String FIELD_SUBMITTER = "requester";
		public static final String FIELD_SUBMITTER_TYPE = "requesterType";
		
		/// Import
		public static final String FIELD_ALIGNMENT = "alignment";
		public static final String FIELD_CITY = "city";
		public static final String FIELD_STATE = "state";
		public static final String FIELD_REGION = "region";
		public static final String FIELD_POST = "postalCode";
		public static final String FIELD_STREET = "street";
		public static final String FIELD_STREET2 = "street2";
		public static final String FIELD_POSTAL_CODE = "postalCode";
		public static final String FIELD_COUNTRY = "country";
		
		public static final String FIELD_GENDER = "gender";
		public static final String FIELD_BIRTH_DATE = "birthDate";
		public static final String FIELD_BEHAVIOR = "behavior";
		public static final String FIELD_PERSONALITY = "personality";
		public static final String FIELD_PROFILE = "profile";
		
		public static final String FIELD_CONTACT_INFORMATION = "contactInformation";
		public static final String FIELD_CONTACT_TYPE = "contactType";
		public static final String FIELD_CONTACT_VALUE = "contactValue";
		
		// Location
		public static final String FIELD_PREFERRED = "preferred";
		public static final String FIELD_LOCATION = "location";
		public static final String FIELD_LOCATION_TYPE = "locationType";
		public static final String FIELD_GROUPS = "groups";
		public static final String FIELD_FEATURE = "feature";
		public static final String FIELD_LOCATIONS = "locations";
		public static final String FIELD_TERRAIN = "terrain";
		public static final String FIELD_TERRAIN_TYPE = "terrainType";
		public static final String FIELD_GRID_ZONE = "gridZone";
		public static final String FIELD_AREA = "area";
		public static final String FIELD_KIDENT = "kident";
		public static final String FIELD_GEOTYPE = "geoType";
		
		public static final String FIELD_EAST = "east";
		public static final String FIELD_NORTH = "north";
		public static final String FIELD_EASTINGS = "eastings";
		public static final String FIELD_NORTHINGS = "northings";
		public static final String FIELD_CURRENT_EAST = "currentEast";
		public static final String FIELD_CURRENT_NORTH = "currentNorth";
		public static final String FIELD_CLASSIFICATION = "classification";
		
		public static final String FIELD_GEOGRAPHY_TYPE = "geographyType";
		public static final String FIELD_GEONAMEID = "geonameid";
		public static final String FIELD_ALT_TYPE = "altType";
		
		// Vector
		public static final String FIELD_VECTOR_REFERENCE = "vectorReference";
		public static final String FIELD_VECTOR_REFERENCE_TYPE = "vectorReferenceType";
		public static final String FIELD_CHUNK = "chunk";
		public static final String FIELD_CHUNK_COUNT = "chunkCount";
		public static final String FIELD_EMBEDDING = "embedding";
		public static final String FIELD_CONTENT = "content";
		public static final String FIELD_VECTORS = "vectors";
}
