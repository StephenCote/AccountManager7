package org.cote.rest.services;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.security.DeclareRoles;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.service.util.ServiceUtil;

@DeclareRoles({"admin","user"})
@Path("/model")
public class ModelService {
	private static final Logger logger = LogManager.getLogger(ModelService.class);

	@POST
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response createModel(String json, @Context HttpServletRequest request, @Context HttpServletResponse response){
		
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord imp = JSONUtil.importObject(json,  LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		if(imp == null) {
			return Response.status(404).entity(null).build();
		}
		ModelSchema schema = RecordFactory.getSchema(imp.getModel());
		BaseRecord op = null;
		BaseRecord oop = null;
		List<String> outFields = new ArrayList<>();
		//PolicyType pol = null;
		try {
			

			ParameterList plst = ParameterUtil.newParameterList();
			if(imp.hasField(FieldNames.FIELD_NAME)) {
				ParameterUtil.newParameter(plst, FieldNames.FIELD_NAME, imp.get(FieldNames.FIELD_NAME));
			}
			if(imp.hasField(FieldNames.FIELD_GROUP_PATH)) {
				ParameterUtil.newParameter(plst, FieldNames.FIELD_PATH, imp.get(FieldNames.FIELD_GROUP_PATH));
			}
			if(imp.hasField(FieldNames.FIELD_PATH)) {
				ParameterUtil.newParameter(plst, FieldNames.FIELD_PATH, imp.get(FieldNames.FIELD_PATH));
			}


			op = IOSystem.getActiveContext().getFactory().newInstance(imp.getModel(), user, null, plst);

			for(FieldType f : imp.getFields()) {
				FieldSchema sf = schema.getFieldSchema(f.getName());
				if(!sf.isIdentity() && !sf.isVirtual()) {
					logger.info("Mapping " + f.getName());
					op.set(f.getName(),  imp.get(f.getName()));
				}
			}
			// logger.info(JSONUtil.exportObject(user, RecordSerializerConfig.getUnfilteredModule()));
			// logger.info(JSONUtil.exportObject(op, RecordSerializerConfig.getUnfilteredModule()));
			if(IOSystem.getActiveContext().getPolicyUtil().createPermitted(user, user, op)) {
				if(IOSystem.getActiveContext().getRecordUtil().createRecord(op)) {
					
					for(FieldSchema f : schema.getFields()) {
						if(f.isIdentity()) {
							outFields.add(f.getName());
						}
					}
					
				}
				else {
					logger.error("Failed to create record");
					op = null;
				}
			}
			else {
				logger.error("Create is not permitted");
				op = null;
			}

			
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}

		String ops = null;
		if(op != null) {
			ops = JSONUtil.exportObject(op.copyRecord(outFields.toArray(new String[0])), RecordSerializerConfig.getFilteredModule());
		}
		return Response.status(200).entity(ops).build();
	}
}
