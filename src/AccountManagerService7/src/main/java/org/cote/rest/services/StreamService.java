package org.cote.rest.services;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.record.BaseRecord;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;


@DeclareRoles({"admin","user"})
@Path("/stream")
public class StreamService {
	private static final Logger logger = LogManager.getLogger(StreamService.class);

	
	@RolesAllowed({"admin","user"})
	@GET
	@Path("/{objectId:[0-9A-Za-z\\-]+}/{startIndex:[\\d]+}/{length:[\\d]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getStreamSegment(@PathParam("objectId") String objectId,@PathParam("name") String name, @PathParam("startIndex") long startIndex, @PathParam("length") int length, @Context HttpServletRequest request){
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
