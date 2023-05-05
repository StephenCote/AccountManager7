/// Parameters
///    logger
///    contextUser (BaseRecord)

/// FactUtil injected and serialized parameters
///    let fact = {fact json};
///    ioReader
///    ioSearch
///    (disabled) let factData = {object referenced by sourceUrn}
///       Optionally, use ioSearch to find by sourceUrn
///    (limited to only inline script)
///    let responseType = org.cote.accountmanager.schema.type.OperationResponseEnumType;\n"
///    let fieldNames = org.cote.accountmanager.schema.FieldNames;\n"
///    let modelNames = org.cote.accountmanager.schema.ModelNames;\n"
logger.info("Owner Policy Function");
let recs = ioSearch.findByUrn(fact.modelType, fact.sourceUrn);
let resp = responseType.UNKNOWN;
if(recs.length == 0){
	logger.warn(fact.modelType + " urn= " + fact.sourceUrn + " not found");
	resp = responseType.FAILED;
}
else{
	let ownerId = recs[0].get(fieldNames.FIELD_OWNER_ID);
	let contextId = contextUser.get(fieldNames.FIELD_ID);
	if(ownerId == contextId){
		resp = responseType.SUCCEEDED;
	}
	else{
		resp = responseType.FAILED;
	}
		
}
resp.toString();