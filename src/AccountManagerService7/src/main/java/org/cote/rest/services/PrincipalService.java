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

import java.security.Principal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RolesAllowed;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.service.util.ServiceUtil;

@DeclareRoles({"user"})
@Path("/principal")
public class PrincipalService {
	private static final Logger logger = LogManager.getLogger(Principal.class);
	private static Map<String, BaseRecord> profiles = Collections.synchronizedMap(new HashMap<>());	
	
	public static void clearCache() {
		profiles.clear();
	}
	
	@RolesAllowed({"user"})
	@GET
	@Path("/anonymous")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getDocumentControl(@Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OrganizationContext oc = IOSystem.getActiveContext().getOrganizationContext(user.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		BaseRecord docUser = null;
		if(oc != null) {
			docUser = oc.getDocumentControl();
			IOSystem.getActiveContext().getReader().populate(docUser);
		}
		return Response.status(200).entity(docUser.toFilteredString()).build();
	}

	
	
	@RolesAllowed({"user"})
	@GET
	@Path("/person/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getOtherPerson(@PathParam("objectId") String objectId,@Context HttpServletRequest request){
		BaseRecord person = getPerson(objectId, request);
		return Response.status(200).entity((person == null ? null : person.toFilteredString())).build();
	}
	
	public BaseRecord getPerson(String objectId, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord contUser = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, ModelNames.MODEL_USER, objectId);
		OrganizationContext oc = IOSystem.getActiveContext().getOrganizationContext(user.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		BaseRecord person = null;
		if(contUser != null && user != null){
			BaseRecord dir = IOSystem.getActiveContext().getPathUtil().findPath(oc.getAdminUser(), ModelNames.MODEL_GROUP, "/Persons", GroupEnumType.DATA.toString(), oc.getOrganizationId());
			person = IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(user, ModelNames.MODEL_PERSON, dir.get(FieldNames.FIELD_OBJECT_ID), contUser.get(FieldNames.FIELD_NAME));
		}
		else {
			logger.error("Context user or user were null");
		}
		return person;
	}
	
	
	@RolesAllowed({"user"})
	@GET
	@Path("/person")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getSelfPerson(@Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord person = getPerson(user.get(FieldNames.FIELD_OBJECT_ID), request);
		return Response.status(200).entity(person.toFilteredString()).build();
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/application")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getApplicationProfile(@Context HttpServletRequest request){
		BaseRecord app = null;
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		String urn = user.get(FieldNames.FIELD_URN);
		if(!profiles.containsKey(urn)) {

			try {
				app = RecordFactory.newInstance(ModelNames.MODEL_APPLICATION_PROFILE);
				long organizationId = user.get(FieldNames.FIELD_ORGANIZATION_ID);

				BaseRecord[] roles = IOSystem.getActiveContext().getSearch().findRecords(QueryUtil.createQuery(ModelNames.MODEL_ROLE, FieldNames.FIELD_PARENT_ID, 0L, organizationId));
				BaseRecord[] permissions = IOSystem.getActiveContext().getSearch().findRecords(QueryUtil.createQuery(ModelNames.MODEL_PERMISSION, FieldNames.FIELD_PARENT_ID, 0L, organizationId));
				List<BaseRecord> aroles = app.get(FieldNames.FIELD_SYSTEM_ROLES);
				List<BaseRecord> uroles = app.get(FieldNames.FIELD_USER_ROLES);
				List<BaseRecord> aperms = app.get(FieldNames.FIELD_SYSTEM_PERMISSIONS);
				aroles.addAll(Arrays.asList(roles));
				aperms.addAll(Arrays.asList(permissions));

				app.set(FieldNames.FIELD_USER, user);
				app.set(FieldNames.FIELD_PERSON, getPerson(user.get(FieldNames.FIELD_OBJECT_ID), request));
				List<BaseRecord> usrRoles = IOSystem.getActiveContext().getMemberUtil().getParticipations(user, ModelNames.MODEL_ROLE);
				uroles.addAll(usrRoles);
				app.set(FieldNames.FIELD_ORGANIZATION_PATH, user.get(FieldNames.FIELD_ORGANIZATION_PATH));
				profiles.put(urn, app);
			} catch (FieldException | ValueException | ModelNotFoundException | IndexException | ReaderException e1) {
				logger.error(e1);
			}

			
		}
		else {
			app = profiles.get(urn);
		}
		return Response.status(200).entity((app == null ? null : app.toFullString())).build();
	}
	
	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getSelf(@Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if(user != null) {
			IOSystem.getActiveContext().getReader().populate(user, 2);
		}
		return Response.status(200).entity((user == null ? null : user.toFullString())).build();
	}
	
	/*
	private UserType getSelfUser(HttpServletRequest request){
		Principal principal = request.getUserPrincipal();
		UserType outUser = null;
		if(principal != null && principal instanceof UserPrincipal){
			UserPrincipal userp = (UserPrincipal)principal;
			logger.info("UserPrincipal: " + userp.toString());
			try {
				OrganizationType org = ((OrganizationFactory)Factories.getFactory(FactoryEnumType.ORGANIZATION)).findOrganization(userp.getOrganizationPath());

				UserType user = Factories.getNameIdFactory(FactoryEnumType.USER).getById(userp.getId(), org.getId());
				if(user != null){
					outUser = user;
					if(BaseService.getEnableExtendedAttributes()){
						Factories.getAttributeFactory().populateAttributes(outUser);
					}
				}
				else {
					logger.warn("User is null for " + userp.getId() + " in " + org.getId());
				}
			} catch (FactoryException | ArgumentException e) {
				
				logger.error(FactoryException.LOGICAL_EXCEPTION,e);
			}
		}
		else{
			logger.debug("Don't know what: " + (principal == null ? "Null" : "Uknown") + " principal");
		}
		return outUser;
	}
	*/
}
