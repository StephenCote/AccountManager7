/// Parameters
///    logger

/// ValidationUtil injected and serialized parameters
///    let model = {model json};
///	   let field = {field json};
///    ioContext

let fieldNames = org.cote.accountmanager.schema.FieldNames;
let parentId = record.get(fieldNames.FIELD_PARENT_ID);

function checkParent(baseId, currentId){
	
}

logger.info("Not In Parent: " + parentId);
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
true;