package org.cote.rest.services;

import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RolesAllowed;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.service.util.ServiceUtil;

@DeclareRoles({"admin","user"})
@Path("/olio")
public class OlioService {

	@RolesAllowed({"user"})
	@GET
	@Path("/{type:[A-Za-z\\.]+}/{objectId:[0-9A-Za-z\\\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getObjectNarrative(@PathParam("type") String type, @PathParam("objectId") String objectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		Query q = QueryUtil.createQuery(type, FieldNames.FIELD_OBJECT_ID, objectId);
		q.setValue(FieldNames.FIELD_LIMIT_FIELDS, false);
		BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if(rec == null) {
			return Response.status(404).entity(null).build();
		}
		PersonalityProfile pp = ProfileUtil.getProfile(null, rec);
		if(pp != null) {
			BaseRecord nar = NarrativeUtil.getNarrative(pp);
			if(nar != null) {
				return Response.status(200).entity(nar.toFullString()).build();		
			}
		}
		return Response.status(404).entity(null).build();
	}

}
