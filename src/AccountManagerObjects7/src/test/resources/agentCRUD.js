/// Parameters
///    logger
///    contextUser (BaseRecord)
///    ioReader
///    ioSearch
///    request
console.log("Agent CRUD Function");

/// org.cote.accountmanager
org.cote.accountmanager.io.Query q = org.cote.accountmanager.io.QueryUtil.createQuery();
null;
/*
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
*/