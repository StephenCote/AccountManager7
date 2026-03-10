package org.cote.rest.services;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.SchemaUtil;
import org.cote.accountmanager.util.JSONUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@DeclareRoles({"admin","user"})
@Path("/schema")
public class SchemaService {
	private static final Logger logger = LogManager.getLogger(SchemaService.class);

	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getSchema(@Context HttpServletRequest request, @Context HttpServletResponse response){
		return Response.status(200).entity(SchemaUtil.getSchemaJSON()).build();
	}

	@RolesAllowed({"admin"})
	@GET
	@Path("/models")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getModelNames(@Context HttpServletRequest request, @Context HttpServletResponse response){
		return Response.status(200).entity(JSONUtil.exportObject(ModelNames.MODELS)).build();
	}

	@RolesAllowed({"admin"})
	@GET
	@Path("/{type:[A-Za-z\\.]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getModelSchema(@PathParam("type") String type, @Context HttpServletRequest request, @Context HttpServletResponse response){
		ModelSchema ms = RecordFactory.getSchema(type);
		if(ms == null) {
			return Response.status(404).entity(null).build();
		}
		return Response.status(200).entity(JSONUtil.exportObject(ms)).build();
	}

	@RolesAllowed({"admin"})
	@GET
	@Path("/{type:[A-Za-z\\.]+}/fields")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getModelFields(@PathParam("type") String type, @Context HttpServletRequest request, @Context HttpServletResponse response){
		ModelSchema ms = RecordFactory.getSchema(type);
		if(ms == null) {
			return Response.status(404).entity(null).build();
		}
		List<FieldSchema> fields = ms.getFields();
		return Response.status(200).entity(JSONUtil.exportObject(fields)).build();
	}

	/// POST /rest/schema — Create a new user-defined model type
	/// Request body: JSON model schema definition string
	@RolesAllowed({"admin"})
	@POST
	@Path("/")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response createModel(String json, @Context HttpServletRequest request, @Context HttpServletResponse response) {
		if(json == null || json.isEmpty()) {
			return Response.status(400).entity("{\"error\":\"Empty request body\"}").build();
		}

		ModelSchema incoming = JSONUtil.importObject(json, ModelSchema.class);
		if(incoming == null || incoming.getName() == null || incoming.getName().isEmpty()) {
			return Response.status(400).entity("{\"error\":\"Invalid schema or missing name\"}").build();
		}

		String name = incoming.getName();

		/// Check if model already exists
		ModelSchema existing = RecordFactory.getSchema(name);
		if(existing != null) {
			return Response.status(409).entity("{\"error\":\"Model already exists: " + name + "\"}").build();
		}

		/// Validate name format (namespace.modelName)
		if(!name.matches("[a-zA-Z][a-zA-Z0-9]*(\\.[a-zA-Z][a-zA-Z0-9]*)+")) {
			return Response.status(400).entity("{\"error\":\"Invalid model name format. Must be namespaced (e.g. custom.myModel)\"}").build();
		}

		/// Create via importSchemaFromUser which handles DB table creation
		ModelSchema created = RecordFactory.importSchemaFromUser(name, json);
		if(created == null) {
			return Response.status(500).entity("{\"error\":\"Failed to create model\"}").build();
		}

		ModelNames.releaseCustomModelNames();
		return Response.status(200).entity(JSONUtil.exportObject(created)).build();
	}

	/// PUT /rest/schema/{type} — Update model (add user-defined fields)
	/// Request body: JSON with a "fields" array of FieldSchema objects to add
	@RolesAllowed({"admin"})
	@PUT
	@Path("/{type:[A-Za-z\\.]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response updateModel(@PathParam("type") String type, String json, @Context HttpServletRequest request, @Context HttpServletResponse response) {
		ModelSchema ms = RecordFactory.getSchema(type);
		if(ms == null) {
			return Response.status(404).entity("{\"error\":\"Model not found: " + type + "\"}").build();
		}

		if(json == null || json.isEmpty()) {
			return Response.status(400).entity("{\"error\":\"Empty request body\"}").build();
		}

		/// Parse incoming schema update — expects a ModelSchema with fields to add
		ModelSchema incoming = JSONUtil.importObject(json, ModelSchema.class);
		if(incoming == null) {
			return Response.status(400).entity("{\"error\":\"Invalid schema JSON\"}").build();
		}

		List<FieldSchema> newFields = incoming.getFields();
		if(newFields == null || newFields.isEmpty()) {
			return Response.status(400).entity("{\"error\":\"No fields provided to add\"}").build();
		}

		int added = 0;
		StringBuilder errors = new StringBuilder();
		for(FieldSchema fs : newFields) {
			if(fs.getName() == null || fs.getName().isEmpty()) {
				errors.append("Skipping field with empty name. ");
				continue;
			}
			/// Check for duplicate field name
			if(ms.hasField(fs.getName())) {
				errors.append("Field '" + fs.getName() + "' already exists. ");
				continue;
			}
			/// Validate field type
			if(fs.getType() == null || fs.getType().isEmpty()) {
				errors.append("Field '" + fs.getName() + "' missing type. ");
				continue;
			}
			/// Mark as user-defined
			fs.setSystem(false);
			fs.setInherited(false);

			if(!RecordFactory.addFieldToSchema(ms, fs)) {
				errors.append("Failed to add field '" + fs.getName() + "'. ");
				continue;
			}
			added++;
		}

		/// Reload schema
		ModelSchema updated = RecordFactory.getSchema(type);

		if(added == 0) {
			return Response.status(400).entity("{\"error\":\"No fields were added. " + errors.toString().trim() + "\"}").build();
		}

		String result = "{\"added\":" + added;
		if(errors.length() > 0) {
			result += ",\"warnings\":\"" + errors.toString().trim().replace("\"", "'") + "\"";
		}
		result += ",\"schema\":" + JSONUtil.exportObject(updated) + "}";
		return Response.status(200).entity(result).build();
	}

	/// DELETE /rest/schema/{type} — Delete a non-system (user-defined) model
	@RolesAllowed({"admin"})
	@DELETE
	@Path("/{type:[A-Za-z\\.]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response deleteModel(@PathParam("type") String type, @Context HttpServletRequest request, @Context HttpServletResponse response) {
		ModelSchema ms = RecordFactory.getSchema(type);
		if(ms == null) {
			return Response.status(404).entity("{\"error\":\"Model not found: " + type + "\"}").build();
		}

		/// Prevent deletion of system models
		if(ms.isSystem()) {
			return Response.status(403).entity("{\"error\":\"Cannot delete system model: " + type + "\"}").build();
		}

		boolean released = RecordFactory.releaseCustomSchema(type);
		if(!released) {
			return Response.status(500).entity("{\"error\":\"Failed to delete model: " + type + "\"}").build();
		}

		ModelNames.releaseCustomModelNames();
		return Response.status(200).entity("{\"deleted\":true,\"model\":\"" + type + "\"}").build();
	}

	/// DELETE /rest/schema/{type}/field/{fieldName} — Remove a non-system field from a model
	@RolesAllowed({"admin"})
	@DELETE
	@Path("/{type:[A-Za-z\\.]+}/field/{fieldName:[A-Za-z][A-Za-z0-9]*}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response deleteField(@PathParam("type") String type, @PathParam("fieldName") String fieldName, @Context HttpServletRequest request, @Context HttpServletResponse response) {
		ModelSchema ms = RecordFactory.getSchema(type);
		if(ms == null) {
			return Response.status(404).entity("{\"error\":\"Model not found: " + type + "\"}").build();
		}

		FieldSchema fs = ms.getFieldSchema(fieldName);
		if(fs == null) {
			return Response.status(404).entity("{\"error\":\"Field not found: " + fieldName + "\"}").build();
		}

		/// Prevent deletion of system fields
		if(fs.isSystem()) {
			return Response.status(403).entity("{\"error\":\"Cannot delete system field: " + fieldName + "\"}").build();
		}

		/// Prevent deletion of identity fields
		if(fs.isIdentity()) {
			return Response.status(403).entity("{\"error\":\"Cannot delete identity field: " + fieldName + "\"}").build();
		}

		if(!RecordFactory.removeFieldFromSchema(ms, fieldName)) {
			return Response.status(500).entity("{\"error\":\"Failed to remove field: " + fieldName + "\"}").build();
		}

		return Response.status(200).entity("{\"deleted\":true,\"field\":\"" + fieldName + "\",\"model\":\"" + type + "\"}").build();
	}

}
