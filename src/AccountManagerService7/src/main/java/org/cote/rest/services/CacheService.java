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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.policy.CachePolicyUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.StreamUtil;
import org.cote.service.util.ServiceUtil;
import org.cote.sockets.WebSocketService;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

@DeclareRoles({"admin","user"})
@Path("/cache")
public class CacheService {


	@Context
	ServletContext context;
	
	@Context
	SecurityContext securityCtx;
	
	private static final Logger logger = LogManager.getLogger(CacheService.class);
	
	public static void clearCaches() {
		ChatUtil.clearCache();
		CacheUtil.clearCache();
		ServiceUtil.clearCache();
		StreamUtil.clearAllUnboxedStreams();
		OlioUtil.clearCache();
		VoiceService.clearCache();
	}
	
	public static void clearAuthorizationCache(HttpServletRequest request) {
		((CachePolicyUtil)IOSystem.getActiveContext().getPolicyUtil()).clearCache();
	}
	
	@RolesAllowed({"admin","user"})
	@GET
	@Path("/clearAll")
	@Produces(MediaType.APPLICATION_JSON)
	public Response clearFactoryCaches(@PathParam("type") String type, @Context HttpServletRequest request){
		logger.info("Request to clear all caches");
		clearAuthorizationCache(request);
		clearCaches();
		WebSocketService.chirpAllSessions(new String[] {"clearCache", "all"});
		return Response.status(200).entity(true).build();
	}

	/**
	 * Evict every cached Olio context for one world, <b>within the caller's organization only</b>.
	 * Transport only - the cache key shape and the org filter live in Objects7
	 * ({@code OlioContextUtil.evictByWorld(long, String)}), not here.
	 * <p>
	 * Admin-gated, and deliberately NOT part of {@code /cache/clearAll}: that path is reachable by any
	 * authenticated {@code user}-role caller, and dropping every Olio context process-wide is not a
	 * user-reachable operation.
	 * <p>
	 * The organization is derived from the principal and never accepted from the client, and it is
	 * PASSED to the evict rather than merely logged: the Olio context cache is process-wide, so an
	 * unscoped evict would let an administrator of one organization drop another organization's
	 * contexts.
	 */
	@RolesAllowed({"admin"})
	@DELETE
	@Path("/olio/context/{objectId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response evictOlioContext(@PathParam("objectId") String objectId, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if(user == null) {
			return Response.status(401).entity(false).build();
		}
		Long organizationId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		if(organizationId == null) {
			return Response.status(401).entity(false).build();
		}
		logger.info("Request to evict Olio contexts for world " + objectId + " (" + organizationId + ")");
		int evicted = OlioContextUtil.evictByWorld(organizationId.longValue(), objectId);
		return Response.status(200).entity(evicted).build();
	}

	@RolesAllowed({"admin","user"})
	@GET
	@Path("/clearAuthorization")
	@Produces(MediaType.APPLICATION_JSON)
	public Response clearAuthorizationCacheService(@Context HttpServletRequest request){
		logger.info("Request to clear authorization cache");
		
		return Response.status(200).entity(true).build();
	}
	
	@RolesAllowed({"admin","user"})
	@GET
	@Path("/clear/{type:[\\.A-Za-z]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response clearFactoryCache(@PathParam("type") String type, @Context HttpServletRequest request){
		logger.info("Request to clear cache on: " + type);

		CacheUtil.clearCacheByModel(type);
		clearAuthorizationCache(request);
		WebSocketService.chirpAllSessions(new String[] {"clearCache", type});
		return Response.status(200).entity(true).build();
	}


}
