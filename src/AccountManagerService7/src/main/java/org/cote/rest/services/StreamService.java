package org.cote.rest.services;

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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.record.BaseRecord;


@DeclareRoles({"admin","user"})
@Path("/stream")
public class StreamService {
	private static final Logger logger = LogManager.getLogger(StreamService.class);

	
	@RolesAllowed({"admin","user"})
	@GET
	@Path("/{objectId:[0-9A-Za-z\\-]+}/{startIndex:[\\d]+}/{length:[\\d]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getStreamSegment(@PathParam("objectId") String objectId,@PathParam(FieldNames.FIELD_NAME) String name, @PathParam("startIndex") long startIndex, @PathParam("length") int length, @Context HttpServletRequest request){
		BaseRecord rseg = null;
		try{
			rseg = IOSystem.getActiveContext().getReader().read(new StreamSegmentUtil().newSegment(objectId, startIndex, length));
		}
		catch(ReaderException e) {
			logger.error(e);
		}
		return Response.status((rseg == null ? 404 : 200)).entity((rseg != null ? rseg.toFullString() : null)).build();
	}

}
