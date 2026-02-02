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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.ApplicationUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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
	
	public static BaseRecord getPersonForUser(BaseRecord contextUser, BaseRecord targetUser) {
		if (contextUser == null || targetUser == null) {
			logger.error("Context user or target user were null");
			return null;
		}
		OrganizationContext oc = IOSystem.getActiveContext().getOrganizationContext(contextUser.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		if (oc == null) return null;
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().findPath(oc.getAdminUser(), ModelNames.MODEL_GROUP, "/Persons", GroupEnumType.DATA.toString(), oc.getOrganizationId());
		if (dir == null) return null;
		return IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(contextUser, ModelNames.MODEL_PERSON, dir.get(FieldNames.FIELD_OBJECT_ID), targetUser.get(FieldNames.FIELD_NAME));
	}

	public BaseRecord getPerson(String objectId, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord contUser = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, ModelNames.MODEL_USER, objectId);
		return getPersonForUser(user, contUser);
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
			app = ApplicationUtil.getApplicationProfile(user);
			if(app != null) {
				profiles.put(urn, app);
			}
		}
		else {
			app = profiles.get(urn);
		}
		return Response.status(200).entity((app == null ? null : JSONUtil.exportObject(app, RecordSerializerConfig.getForeignUnfilteredModuleRecurse()))).build();
	}
	
	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getSelf(@Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if(user != null) {
			IOSystem.getActiveContext().getReader().populate(user, 2);
			IOSystem.getActiveContext().getReader().populate(user, new String[] {FieldNames.FIELD_ATTRIBUTES});
		}
		return Response.status(200).entity((user == null ? null : user.toFullString())).build();
	}

}
