/*******************************************************************************
 * Copyright (C) 2002, 2020 Stephen Cote Enterprises, LLC. All rights reserved.
 * Redistribution without modification is permitted provided the following conditions are met:
 *
 *    1. Redistribution may not deviate from the original distribution,
 *        and must reproduce the above copyright notice, this list of conditions
 *        and the following disclaimer in the documentation and/or other materials
 *        provided with the distribution.
 *    2. Products may be derived from this software.
 *    3. Redistributions of any form whatsoever must retain the following acknowledgment:
 *        "This product includes software developed by Stephen Cote Enterprises, LLC"
 *
 * THIS SOFTWARE IS PROVIDED BY STEPHEN COTE ENTERPRISES, LLC ``AS IS''
 * AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THIS PROJECT OR ITS CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, 
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY 
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *******************************************************************************/
package org.cote.rest.services;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RolesAllowed;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;

@DeclareRoles({"admin","user"})
@Path("/list/{type:[A-Za-z]+}")
public class ListService {

	@Context
	ServletContext context;
	
	@Context
	SecurityContext securityCtx;
	
	private static final Logger logger = LogManager.getLogger(ListService.class);

	/*
	 * countMembers:
	 * 	type --- base type for the participation factory
	 * searchRequest.participationType --- the member type [unknown - all; or specific]
	 */
	/*
	@RolesAllowed({"user"})
	@POST
	@Path("/participants/count")
	@Produces(MediaType.APPLICATION_JSON)
	public Response countMembers(@PathParam("type") String type, ParticipationSearchRequest searchRequest, @Context HttpServletRequest request){
		AuditEnumType auditType = AuditEnumType.valueOf(type);

		int count = 0;
		if(searchRequest == null || searchRequest.getParticipationList().size() == 0) {
			logger.warn("Null or empty request");
			return Response.status(200).entity(count).build();
		}
		UserType user = ServiceUtil.getUserFromSession(request);
		try{
			int canRead = 0;
			searchRequest.getParticipations().clear();
			for(String objectId : searchRequest.getParticipationList()) {
				NameIdType obj = BaseService.readByObjectId(auditType, objectId, user);
				if(obj != null) {
					searchRequest.getParticipations().add(obj);
					canRead++;
				}
			}
			if(canRead != searchRequest.getParticipations().size()) {
				logger.error("One or more provided participations is not visible to the current user");
				return Response.status(200).entity(count).build();	
			}
			
			/// get the participation factory
			///
			IParticipationFactory pFact = Factories.getParticipationFactory(FactoryEnumType.valueOf(type + "PARTICIPATION"));

			count = pFact.countParticipations(searchRequest.getParticipations().toArray(new NameIdType[0]), searchRequest.getParticipantFactoryType());
			
		}
		catch(FactoryException f){
			logger.error(f);
		}
		return Response.status(200).entity(count).build();
	}
	
	@RolesAllowed({"user"})
	@POST
	@Path("/participants")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listMembers(@PathParam("type") String type, ParticipationSearchRequest searchRequest, @Context HttpServletRequest request){
		AuditEnumType auditType = AuditEnumType.valueOf(type);
		List<Object> objs = new ArrayList<>();
		if(searchRequest == null || searchRequest.getParticipationList().size() == 0) {
			logger.warn("Null or empty request");
			return Response.status(200).entity(objs).build();
		}
		UserType user = ServiceUtil.getUserFromSession(request);
		try{
			int canRead = 0;
			searchRequest.getParticipations().clear();
			for(String objectId : searchRequest.getParticipationList()) {
				NameIdType obj = BaseService.readByObjectId(auditType, objectId, user);
				if(obj != null) {
					searchRequest.getParticipations().add(obj);
					canRead++;
				}
			}
			if(canRead != searchRequest.getParticipations().size()) {
				logger.error("One or more provided participations is not visible to the current user");
				return Response.status(200).entity(objs).build();	
			}
			
			/// get the participation factory
			///
			IParticipationFactory pFact = Factories.getParticipationFactory(FactoryEnumType.valueOf(type + "PARTICIPATION"));
			
			objs = pFact.listParticipations(searchRequest.getParticipantFactoryType(), searchRequest.getParticipations().toArray(new NameIdType[0]), searchRequest.getStartRecord(), searchRequest.getRecordCount(), user.getOrganizationId());
		}
		catch(FactoryException | ArgumentException f){
			logger.error(f);
		}

		return Response.status(200).entity(objs).build();
	}
	
	@RolesAllowed({"user"})
	@POST
	@Path("/participations/count")
	@Produces(MediaType.APPLICATION_JSON)
	public Response countParticipations(@PathParam("type") String type, ParticipantSearchRequest searchRequest, @Context HttpServletRequest request){
		AuditEnumType auditType = AuditEnumType.valueOf(type);

		int count = 0;
		if(searchRequest == null || searchRequest.getParticipantList().size() == 0) {
			logger.warn("Null or empty request");
			return Response.status(200).entity(count).build();
		}
		if(
				searchRequest.getParticipantType() == null || searchRequest.getParticipantType() == ParticipantEnumType.UNKNOWN
				||
				searchRequest.getParticipantFactoryType() == null || searchRequest.getParticipantFactoryType() == ParticipantEnumType.UNKNOWN
		) {
			logger.warn("Expected a participant type and participant factory type");
			return Response.status(200).entity(count).build();
		}
		UserType user = ServiceUtil.getUserFromSession(request);
		try{
			int canRead = 0;
			searchRequest.getParticipants().clear();
			AuditEnumType partFact = AuditEnumType.valueOf(searchRequest.getParticipantFactoryType().toString());
			for(String objectId : searchRequest.getParticipantList()) {
				NameIdType obj = BaseService.readByObjectId(partFact, objectId, user);
				if(obj != null) {
					searchRequest.getParticipants().add(obj);
					canRead++;
				}
			}
			if(canRead != searchRequest.getParticipants().size()) {
				logger.error("One or more provided participations is not visible to the current user");
				return Response.status(200).entity(count).build();	
			}
			
			/// get the participation factory
			///
			IParticipationFactory pFact = Factories.getParticipationFactory(FactoryEnumType.valueOf(type + "PARTICIPATION"));

			count = pFact.countParticipants(searchRequest.getParticipants().toArray(new NameIdType[0]), searchRequest.getParticipantType());
			
		}
		catch(FactoryException f){
			logger.error(f);
		}
		return Response.status(200).entity(count).build();
	}
	
	@RolesAllowed({"user"})
	@POST
	@Path("/participations")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listParticipations(@PathParam("type") String type, ParticipantSearchRequest searchRequest, @Context HttpServletRequest request){
		AuditEnumType auditType = AuditEnumType.valueOf(type);
		List<Object> objs = new ArrayList<>();
		if(searchRequest == null || searchRequest.getParticipantList().size() == 0) {
			logger.warn("Null or empty request");
			return Response.status(200).entity(objs).build();
		}
		if(
				searchRequest.getParticipantType() == null || searchRequest.getParticipantType() == ParticipantEnumType.UNKNOWN
				||
				searchRequest.getParticipantFactoryType() == null || searchRequest.getParticipantFactoryType() == ParticipantEnumType.UNKNOWN
		) {
			logger.warn("Expected a participant type and participant factory type");
			return Response.status(200).entity(objs).build();
		}
		
		UserType user = ServiceUtil.getUserFromSession(request);
		try{
			int canRead = 0;
			searchRequest.getParticipants().clear();
			AuditEnumType partFact = AuditEnumType.valueOf(searchRequest.getParticipantFactoryType().toString());
			for(String objectId : searchRequest.getParticipantList()) {
				NameIdType obj = BaseService.readByObjectId(partFact, objectId, user);
				if(obj != null) {
					searchRequest.getParticipants().add(obj);
					canRead++;
				}
			}
			if(canRead != searchRequest.getParticipants().size()) {
				logger.error("One or more provided participants is not visible to the current user");
				return Response.status(200).entity(objs).build();	
			}
			
			/// get the participation factory
			///
			IParticipationFactory pFact = Factories.getParticipationFactory(FactoryEnumType.valueOf(type + "PARTICIPATION"));
			
			objs = pFact.listParticipants(searchRequest.getParticipationFactoryType(), searchRequest.getParticipantType(), searchRequest.getParticipants().toArray(new NameIdType[0]), searchRequest.getStartRecord(), searchRequest.getRecordCount(), user.getOrganizationId());
		}
		catch(FactoryException | ArgumentException f){
			logger.error(f);
		}

		return Response.status(200).entity(objs).build();
	}
	*/
	
	@RolesAllowed({"user"})
	@GET
	@Path("/{parentId:[0-9A-Za-z\\-]+}/{name: [\\(\\)@%\\sa-zA-Z_0-9\\-\\.]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getObjectByNameInParent(@PathParam("type") String type, @PathParam("parentId") String parentId,@PathParam("name") String name,@Context HttpServletRequest request){
		QueryResult qr = generateQueryResponse(type, parentId, name, 0, 1, request);
		return Response.status((qr == null ? 500 : 200)).entity(JSONUtil.exportObject((qr != null ? qr.getResults() : null), RecordSerializerConfig.getUnfilteredModule())).build();
	}
	


	
	/// Specifically to allow for the variation where a factory is clustered by both group and parent
	/// To retrieve an object using a parent id vs. the group id
	///
	@RolesAllowed({"user"})
	@GET
	@Path("/parent/{parentId:[0-9A-Za-z\\-]+}/{name: [\\(\\)@%\\sa-zA-Z_0-9\\-\\.]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getGroupedObjectByNameInParent(@PathParam("type") String type, @PathParam("parentId") String parentId,@PathParam("name") String name,@Context HttpServletRequest request){
		QueryResult qr = generateQueryResponse(type, parentId, name, 0, 1, request);
		return Response.status((qr == null ? 500 : 200)).entity(JSONUtil.exportObject((qr != null ? qr.getResults() : null), RecordSerializerConfig.getUnfilteredModule())).build();
	}
	
	
	@RolesAllowed({"user"})
	@GET
	@Path("/{objectId:[0-9A-Za-z\\-]+}/{startIndex:[\\d]+}/{count:[\\d]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listObjects(@PathParam("type") String type, @PathParam("objectId") String objectId, @PathParam("startIndex") long startIndex, @PathParam("count") int recordCount, @Context HttpServletRequest request){

		QueryResult qr = generateQueryResponse(type, objectId, null, startIndex, recordCount, request);
		return Response.status((qr == null ? 500 : 200)).entity(JSONUtil.exportObject((qr != null ? qr.getResults() : null), RecordSerializerConfig.getUnfilteredModule())).build();
	}
	
	private QueryResult generateQueryResponse(String type, String objectId, String name, long startIndex, int recordCount, HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		Query q = QueryUtil.buildQuery(user, type, objectId, name, startIndex, recordCount);
		if(q == null) {
			logger.error("Invalid query object for " + type + " " + objectId);
			return null;	
		}

		return IOSystem.getActiveContext().getAccessPoint().list(user, q);
	}
	

	/*
	@RolesAllowed({"user"})
	@GET
	@Path("/parent/{objectId:[0-9A-Za-z\\-]+}/{startIndex:[\\d]+}/{count:[\\d]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listObjectsInParent(@PathParam("type") String type, @PathParam("objectId") String objectId, @PathParam("startIndex") long startIndex, @PathParam("count") int recordCount, @Context HttpServletRequest request){

		AuditEnumType auditType = AuditEnumType.valueOf(type);
		List<Object> objs = new ArrayList<>();
		try{
			INameIdFactory iFact = BaseService.getFactory(auditType);
			if(iFact.isClusterByParent()){
				logger.info("Request to list " + type + " objects by parent in " + type + " " + objectId);
				objs = BaseService.listByParentObjectId(auditType, "UNKNOWN", objectId, startIndex, recordCount, request);
			}
		}
		catch(FactoryException f){
			logger.error(f);
		}
		return Response.status(200).entity(objs).build();
	}
	
	@RolesAllowed({"user"})
	@GET
	@Path("/parent/{objectId:[0-9A-Za-z\\-]+}/count")
	@Produces(MediaType.APPLICATION_JSON)
	public Response countObjectsInParent(@PathParam("type") String type, @PathParam("objectId") String objectId, @Context HttpServletRequest request){
		AuditEnumType auditType = AuditEnumType.valueOf(type);

		int count = 0;
		try{
			INameIdFactory iFact = BaseService.getFactory(auditType);
			if(iFact.isClusterByParent()){
				NameIdType parent = (NameIdType)BaseService.readByObjectId(auditType, objectId, request);
				if(parent != null){
					logger.debug("Counting " + type + " objects in parent " + parent.getUrn());
					count = BaseService.countInParent(auditType, parent, request);
				}
			}
		}
		catch(FactoryException f){
			logger.error(f);
		}
		return Response.status(200).entity(count).build();
	}
	*/
	@RolesAllowed({"user"})
	@GET
	@Path("/{objectId:[0-9A-Za-z\\-]+}/count")
	@Produces(MediaType.APPLICATION_JSON)
	public Response countObjects(@PathParam("type") String type, @PathParam("objectId") String objectId, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		Query q = QueryUtil.buildQuery(user, type, objectId, null, 0L, 0);
		if(q == null) {
			logger.error("Invalid query object for " + type + " " + objectId);
			return Response.status(404).entity(null).build();	
		}
		int count = IOSystem.getActiveContext().getAccessPoint().count(user, q);
		return Response.status(200).entity(count).build();
	}
	
	
}
