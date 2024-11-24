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
package org.cote.accountmanager.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.CryptoFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.IWriter;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.SpoolBucketEnumType;
import org.cote.accountmanager.schema.type.SpoolNameEnumType;
import org.cote.accountmanager.schema.type.ValueEnumType;
import org.cote.accountmanager.util.RecordUtil;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.CompressionCodecs;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

public class TokenService {
	public static final Logger logger = LogManager.getLogger(TokenService.class);
	private static final Pattern userType = Pattern.compile("^user$");
	private static final Pattern personaType = Pattern.compile("^(system\\.user|identity\\.person|identity\\.account)$");
	
	public static final String CLAIM_TOKEN_ID = "tokenId";
	public static final String CLAIM_OBJECT_ID = "objectId";
	public static final String CLAIM_ORGANIZATION_PATH = "organizationPath";
	public static final String CLAIM_SCOPES = "scopes";
	public static final String CLAIM_SUBJECT_TYPE = "subjectType";
	public static final String CLAIM_SBI = "sbi";
	public static final String CLAIM_RESOURCE_TYPE = "resourceType";
	public static final String CLAIM_RESOURCE_ID = "resourceId";
	public static final int TOKEN_EXPIRY_1_MINUTE = 60;
	public static final int TOKEN_EXPIRY_10_MINUTES = TOKEN_EXPIRY_1_MINUTE * 10;
	public static final int TOKEN_EXPIRY_1_HOUR = TOKEN_EXPIRY_10_MINUTES * 6;
	public static final int TOKEN_EXPIRY_6_HOURS = TOKEN_EXPIRY_1_HOUR * 6;
	public static final int TOKEN_EXPIRY_1_DAY = TOKEN_EXPIRY_6_HOURS * 4;
	public static final int TOKEN_EXPIRY_1_WEEK = TOKEN_EXPIRY_1_DAY * 7;
	private static int DEFAULT_TOKEN_EXPIRY_HOURS = TOKEN_EXPIRY_6_HOURS;
	public static final String DEFAULT_REFERENCE_SUFFIX = "jwt";
	private static final SignatureAlgorithm SIGNATURE_ALGORITHM = SignatureAlgorithm.HS256;
	private static final String JWT_HASH_SPEC = "HmacSHA256";
	private static final String JWT_KEY_SPEC = "HmacSHA256";
	private static final int JWT_KEY_SIZE = 256;

	public static CryptoBean getCreateCipher(BaseRecord actor){
		return getCreateCipher(actor, DEFAULT_REFERENCE_SUFFIX);
	}
	

	
	public static CryptoBean getCreateCipher(BaseRecord actor, String referenceName){
		BaseRecord tokenType = null;
		CryptoBean outBean = null;
		String actorType = actor.getModel();
		if(!actorType.equals(ModelNames.MODEL_USER) && !actorType.equals(ModelNames.MODEL_PERSON) && !actorType.equals(ModelNames.MODEL_ACCOUNT)) {
			logger.error("Actor type not supported: {}", actorType);
			return null;
		}

		try{
			IReader reader = IOSystem.getActiveContext().getReader();
			IWriter writer = IOSystem.getActiveContext().getWriter();

			reader.populate(actor);
			long ownerId = 0L;
			BaseRecord dir = null;
			if(actorType.equals(ModelNames.MODEL_USER)) {
				dir = actor.get(FieldNames.FIELD_HOME_DIRECTORY);
				IOSystem.getActiveContext().getRecordUtil().populate(dir);
				ownerId = actor.get(FieldNames.FIELD_ID);
			}
			else{
				dir = reader.read(ModelNames.MODEL_GROUP, (long)actor.get(FieldNames.FIELD_GROUP_ID));
				ownerId = actor.get(FieldNames.FIELD_OWNER_ID);
			}
			
	    	Query q = QueryUtil.createQuery(ModelNames.MODEL_SPOOL, FieldNames.FIELD_NAME, referenceName);
	    	q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
	    	q.planMost(true);
	    	tokenType = IOSystem.getActiveContext().getRecordUtil().getRecordByQuery(q);

	    	if(tokenType == null){

				CryptoBean bean = new CryptoBean();
				bean.set(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC, JWT_KEY_SPEC);
				bean.set(FieldNames.FIELD_CIPHER_FIELD_KEYSIZE, JWT_KEY_SIZE);
				//bean.set(FieldNames.FIELD_HASH_FIELD_ALGORITHM, JWT_HASH_SPEC);
				if(JWT_KEY_SPEC.equals("AES")) {
					logger.warn("Generate secret key");
					CryptoFactory.getInstance().generateSecretKey(bean);
				}
				else {
					String key = CryptoFactory.getInstance().randomKey(JWT_KEY_SIZE);
					logger.warn("Set pass key: " + key);
					CryptoFactory.getInstance().setPassKey(bean, key, false);
				}

				tokenType = TokenService.newSecurityToken(actor.get(FieldNames.FIELD_OBJECT_ID), actor.get(FieldNames.FIELD_ORGANIZATION_ID));
				
				tokenType.set(FieldNames.FIELD_OWNER_ID, ownerId);
				tokenType.set(FieldNames.FIELD_ORGANIZATION_ID, actor.get(FieldNames.FIELD_ORGANIZATION_ID));
				tokenType.set(FieldNames.FIELD_NAME, referenceName);
				tokenType.set(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
				tokenType.set(FieldNames.FIELD_DATA, CryptoFactory.getInstance().serialize(bean, false, false, true, true, true).getBytes());
				
				if(!writer.write(tokenType)){
					logger.error("Failed to persist tokens");
					tokenType = null;
				}
				else{
					logger.info("Created token");
					outBean = bean;
				}
			}
			else{
				outBean = new CryptoBean();
				CryptoFactory.getInstance().importCryptoBean(outBean, tokenType.get(FieldNames.FIELD_DATA), false);
			}
		}
		catch(ReaderException | FieldException | ValueException | ModelNotFoundException | WriterException e){
			logger.error(e.getMessage());
		}
		return outBean;
	}
	
	public static Jws<Claims> extractJWTClaims(String token){
		return Jwts.parserBuilder().setSigningKeyResolver(new AM7SigningKeyResolver()).build().parseClaimsJws(token);
	}
	
	public static String validateTokenToSubject(String token){
		logger.info("Validating token: '" + token + "'");
		return Jwts.parserBuilder().setSigningKeyResolver(new AM7SigningKeyResolver()).build().parseClaimsJws(token).getBody().getSubject();
	}
	
	public static Claims validateSpooledJWTToken(String token) throws IndexException, ReaderException {
		return validateSpooledJWTToken(token, false, false);
	}
	public static Claims validateSpooledJWTToken(String token, boolean skipExpirationCheck, boolean skipSpoolCheck) throws IndexException, ReaderException {
		Claims c = extractJWTClaims(token).getBody();
		Date now = Calendar.getInstance().getTime();

		if(!skipExpirationCheck && c.getExpiration() != null && c.getExpiration().getTime() < now.getTime()) {
			logger.error("Token for " + c.getSubject() + " has expired");
			return null;
		}
		if(!skipSpoolCheck) {
			String tokenId = c.get(CLAIM_TOKEN_ID, String.class);
			String organizationPath = c.get(CLAIM_ORGANIZATION_PATH, String.class);
			if(tokenId != null && organizationPath != null) {
				//OrganizationType org = oF.find(organizationPath);
				BaseRecord org = IOSystem.getActiveContext().getPathUtil().findPath(null, ModelNames.MODEL_ORGANIZATION, organizationPath, null, 0L);
				if(org != null) {
					BaseRecord sst = getGlobalJWTSecurityToken(tokenId, org.get(FieldNames.FIELD_ID));
					if(sst == null) {
						logger.error("Null token");
						return null;
					}
					boolean expires = sst.get(FieldNames.FIELD_EXPIRES);
					Date expiryDate = sst.get(FieldNames.FIELD_EXPIRY_DATE);
					if(sst != null &&
							(
								!expires
								||
								(expiryDate.getTime() > now.getTime())
							)
					) {
						logger.info("Token not expired");
					}
					else {
						logger.warn("Persisted token type has expired: " + expiryDate + " is less than now " + now);
						c = null;
					}
				}
				else {
					logger.warn("Invalid organization path: " + organizationPath);
					c = null;
				}
			}
			else {
				logger.warn("Rejecting token without a specified tokenId or organization path");
				c = null;
			}
		}
		
		return c;
	}

	public static BaseRecord newSpooledJWTToken(BaseRecord contextUser, int expiryMinutes) throws ReaderException, FieldException, ValueException, ModelNotFoundException, WriterException, IndexException {
		return newSpooledJWTToken(contextUser, contextUser, expiryMinutes);
	}
	public static BaseRecord newSpooledJWTToken(BaseRecord contextUser, BaseRecord persona) throws ReaderException, FieldException, ValueException, ModelNotFoundException, WriterException, IndexException {
		return newSpooledJWTToken(contextUser, persona, TOKEN_EXPIRY_10_MINUTES);
	}
	public static BaseRecord newSpooledJWTToken(BaseRecord contextUser, BaseRecord persona, int expiryMinutes) throws ReaderException, FieldException, ValueException, ModelNotFoundException, WriterException, IndexException {
		String tokenId = UUID.randomUUID().toString();
		String token = createJWTToken(contextUser, persona, tokenId, expiryMinutes);
		if(token == null) {
			logger.error("Failed to create JWT token");
			return null;
		}
		return newSecurityToken(contextUser, SpoolNameEnumType.AUTHORIZATION, tokenId + " " + DEFAULT_REFERENCE_SUFFIX, token.getBytes(StandardCharsets.UTF_8), (expiryMinutes * 60));
	}
	
	private static String[] getScope(BaseRecord persona, String[] resourceClaims) throws ReaderException, IndexException{
		if(resourceClaims != null && resourceClaims.length > 0) {
			return resourceClaims;
		}
		List<String> buff = new ArrayList<>();
		List<BaseRecord> entsp = IOSystem.getActiveContext().getMemberUtil().getParticipations(persona, ModelNames.MODEL_ROLE);
		List<BaseRecord> entsl = entsp.stream().filter(o -> {
			// logger.info(o.toString());
			if(o.inherits(ModelNames.MODEL_PARTICIPATION_ENTRY)) {
				String type = o.get(FieldNames.FIELD_TYPE);
				return ModelNames.MODEL_ROLE.equals(type);
			}
			else {
				return true;
			}
		}).collect(Collectors.toList());
		

		for(BaseRecord b : entsl) {
			long id = 0L;
			String model = null;

			BaseRecord rb = null;
			if(b.inherits(ModelNames.MODEL_PARTICIPATION_ENTRY)) {
				id = b.get(FieldNames.FIELD_PART_ID);
				model = b.get(FieldNames.FIELD_TYPE);
				if(id > 0L && model != null) {
					rb = IOSystem.getActiveContext().getReader().read(model, id);
				}
			}
			else {
				rb = b;
			}
			if(rb != null) {
				buff.add(rb.get(FieldNames.FIELD_NAME));
			}
		}
		
		return buff.toArray(new String[0]);
	}
	
	public static String createAuthorizationToken(BaseRecord authorizingUser, BaseRecord persona) throws ReaderException, IndexException{
		return createAuthorizationToken(authorizingUser, persona, null, new String[0], UUID.randomUUID().toString(), TOKEN_EXPIRY_10_MINUTES);
	}
	public static String createAuthorizationToken(BaseRecord authorizingUser, BaseRecord persona, BaseRecord resource, String[] resourceClaims, String tokenId, int expiryMinutes) throws ReaderException, IndexException{
		if(!userType.matcher(authorizingUser.getModel()).find()){
			logger.error("Unsupported user type: {0}", authorizingUser.getModel());
			return null;
		}
		if(!personaType.matcher(persona.getModel()).find()){
			logger.error("Unsupported persona type: {0}", persona.getModel());
			return null;
		}
		CryptoBean bean = getCreateCipher(authorizingUser);
		if(bean == null){
			logger.error("Null security bean");
			return null;
		}
		if(bean.getSecretKey() == null){
			logger.error("Null secret key for Authorization Token");
			return null;
		}
		
		String[] buff = getScope(persona, resourceClaims);
		
	    Claims claims = Jwts.claims().setSubject(persona.get(FieldNames.FIELD_NAME));
	    claims.put(CLAIM_SCOPES, Arrays.asList(buff));
		claims.put(CLAIM_OBJECT_ID, persona.get(FieldNames.FIELD_OBJECT_ID));
		claims.put(CLAIM_TOKEN_ID, tokenId);
		claims.put(CLAIM_ORGANIZATION_PATH, persona.get(FieldNames.FIELD_ORGANIZATION_PATH));
		claims.put(CLAIM_SUBJECT_TYPE,persona.getModel());
		if(resource != null) {
			if(RecordUtil.isIdentityRecord(resource)) {
				claims.put(CLAIM_RESOURCE_TYPE, resource.getModel());
				claims.put(CLAIM_RESOURCE_ID, resource.get(FieldNames.FIELD_OBJECT_ID));
			}
			else {
				logger.error("Cannot authorize a resource without an identity");
			}
		}
		claims.put(CLAIM_SBI, true);
		Calendar cal = Calendar.getInstance();
		Date now = cal.getTime();
		cal.add(Calendar.MINUTE, expiryMinutes);
		Date expires = cal.getTime();
		return Jwts.builder()
		  //.setId(UUID.randomUUID().toString())
		  .setClaims(claims)
		  .setIssuer(authorizingUser.get(FieldNames.FIELD_URN))
		  .setIssuedAt(now)
		  .setExpiration(expires)
		  .setSubject(persona.get(FieldNames.FIELD_NAME))
		  .setId(persona.get(FieldNames.FIELD_URN))
		  .compressWith(CompressionCodecs.GZIP)
		  .signWith(bean.getSecretKey(), SIGNATURE_ALGORITHM)
		  .compact();
	}
	
	
	/// This is still (hopefully obvious) very loose per the spec and likely very wrong
	/// however, it's being used primarily for node-to-node communication versus trying to provide third party access
	///
	public static String createJWTToken(BaseRecord contextUser) throws ReaderException, IndexException{
		return createJWTToken(contextUser, contextUser);
	}
	public static String createJWTToken(BaseRecord contextUser, BaseRecord persona) throws ReaderException, IndexException{
		return createJWTToken(contextUser, persona, UUID.randomUUID().toString(), TOKEN_EXPIRY_10_MINUTES);
	}
	public static String createJWTToken(BaseRecord contextUser, BaseRecord persona, String tokenId, int expiryMinutes) throws ReaderException, IndexException{
		if(!personaType.matcher(persona.getModel()).find()){
			logger.error("Unsupported persona type: {0}", persona.getModel());
			return null;
		}
		CryptoBean bean = getCreateCipher(persona);
		if(bean == null){
			logger.error("Null security bean");
			return null;
		}
		if(bean.getSecretKey() == null){
			logger.error("Null secret key to create JWT Token");
			return null;
		}
		

		List<BaseRecord> entsp = IOSystem.getActiveContext().getMemberUtil().getParticipations(persona, ModelNames.MODEL_ROLE);
		List<BaseRecord> entsl = entsp.stream().filter(o -> {
			if(o.inherits(ModelNames.MODEL_PARTICIPATION_ENTRY)) {
				String type = o.get(FieldNames.FIELD_TYPE);
				return ModelNames.MODEL_ROLE.equals(type);
			}
			else {
				return true;
			}
		}).collect(Collectors.toList());
		
		List<String> buff = new ArrayList<>();
		for(BaseRecord b : entsl) {
			BaseRecord rb = null;
			if(b.inherits(ModelNames.MODEL_PARTICIPATION_ENTRY)) {
				long id = b.get(FieldNames.FIELD_PART_ID);
				rb = IOSystem.getActiveContext().getReader().read(b.get(FieldNames.FIELD_TYPE), id);
			}
			else {
				rb = b;
			}
			
			 
			if(rb != null) {
				buff.add(rb.get(FieldNames.FIELD_NAME));
			}
		}
		
	    Claims claims = Jwts.claims().setSubject(persona.get(FieldNames.FIELD_NAME));
	    claims.put(CLAIM_SCOPES, Arrays.asList(buff));
		claims.put(CLAIM_OBJECT_ID, persona.get(FieldNames.FIELD_OBJECT_ID));
		claims.put(CLAIM_TOKEN_ID, tokenId);
		claims.put(CLAIM_ORGANIZATION_PATH, persona.get(FieldNames.FIELD_ORGANIZATION_PATH));
		claims.put(CLAIM_SUBJECT_TYPE,persona.getModel());
		Calendar cal = Calendar.getInstance();
		Date now = cal.getTime();
		cal.add(Calendar.MINUTE, expiryMinutes);
		Date expires = cal.getTime();
		
		return Jwts.builder()
		  //.setId(UUID.randomUUID().toString())
		  .setClaims(claims)
		  .setIssuer(contextUser.get(FieldNames.FIELD_URN))
		  .setIssuedAt(now)
		  .setExpiration(expires)
		  .setSubject(persona.get(FieldNames.FIELD_NAME))
		  .setId(persona.get(FieldNames.FIELD_URN))
		  .compressWith(CompressionCodecs.GZIP)
		  .signWith(bean.getSecretKey(), SIGNATURE_ALGORITHM)
		  .compact();
	}

	public static String createSimpleJWTToken(BaseRecord user){
		
		CryptoBean bean = getCreateCipher(user);
		if(bean == null){
			logger.error("Null security bean");
			return null;
		}
		if(bean.getSecretKey() == null){
			logger.error("Null secret key for Simple JWT Token");
			logger.error(bean.toString());
			return null;
		}
		
		Map<String,Object> claims = new HashMap<>();
		claims.put(FieldNames.FIELD_OBJECT_ID, (String)user.get(FieldNames.FIELD_OBJECT_ID));
		claims.put(FieldNames.FIELD_ORGANIZATION_PATH, (String)user.get(FieldNames.FIELD_ORGANIZATION_PATH));
		return Jwts.builder()
		  //.setId(UUID.randomUUID().toString())
		  .setClaims(claims)
		  .setSubject(user.get(FieldNames.FIELD_NAME))
		  .setId(user.get(FieldNames.FIELD_URN))
		  .compressWith(CompressionCodecs.GZIP)
		  .signWith(bean.getSecretKey(), SIGNATURE_ALGORITHM)
		  .compact();
	}
	
	public static BaseRecord getGlobalJWTSecurityToken(String name, long organizationId) throws IndexException, ReaderException  {
		BaseRecord[] recs = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_SPOOL, 0L, name  + " " + DEFAULT_REFERENCE_SUFFIX, organizationId);
		return (recs.length > 0 ? recs[0] : null);
	}
	/*
	 * The "Security Token" is a spool entry under the SECURITY_TOKEN message bucket
	 */
	public static BaseRecord newSecurityToken(BaseRecord owner) throws FieldException, ValueException, ModelNotFoundException, WriterException{
		return newSecurityToken(owner, new byte[0], TOKEN_EXPIRY_10_MINUTES);
	}
	
	public static BaseRecord newSecurityToken(BaseRecord owner, byte[] data, int expirySeconds) throws FieldException, ValueException, ModelNotFoundException, WriterException{
		return newSecurityToken(owner, SpoolNameEnumType.GENERAL,"Security Token", data,  expirySeconds);
	}
	
	public static BaseRecord newSecurityToken(String referenceId, long organizationId) throws FieldException, ValueException, ModelNotFoundException {
		if ( organizationId <= 0L) throw new ValueException("Invalid organization");

		BaseRecord newToken = RecordFactory.newInstance(ModelNames.MODEL_SPOOL);
		newToken.set(FieldNames.FIELD_SPOOL_BUCKET_TYPE, SpoolBucketEnumType.SECURITY_TOKEN.toString());
		newToken.set(FieldNames.FIELD_SPOOL_BUCKET_NAME, SpoolNameEnumType.GENERAL.toString());
		newToken.set(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		newToken.set(FieldNames.FIELD_NAME, referenceId);
		newToken.set(FieldNames.FIELD_VALUE_TYPE, ValueEnumType.STRING.toString());

		Date now = new Date();
		GregorianCalendar cal = new GregorianCalendar();
	    cal.setTime(now);
		cal.add(GregorianCalendar.HOUR, DEFAULT_TOKEN_EXPIRY_HOURS);
		newToken.set(FieldNames.FIELD_EXPIRES, true);
		newToken.set(FieldNames.FIELD_EXPIRY_DATE, cal.getTime());
		newToken.set(FieldNames.FIELD_CREATED_DATE, now);
		newToken.set(FieldNames.FIELD_MODIFIED_DATE, now);
		return newToken;
	}
	
	private static BaseRecord newSecurityToken(BaseRecord owner, SpoolNameEnumType bucketNameType, String name, byte[] data, int expirySeconds) throws FieldException, ValueException, ModelNotFoundException, WriterException {
		BaseRecord tokenType = newSecurityToken(name, owner.get(FieldNames.FIELD_ORGANIZATION_ID));
		tokenType.set(FieldNames.FIELD_OWNER_ID, owner.get(FieldNames.FIELD_ID));
		tokenType.set(FieldNames.FIELD_SPOOL_BUCKET_NAME, bucketNameType.toString());
		if(expirySeconds > 0){
			Calendar cal = Calendar.getInstance();
			cal.add(Calendar.SECOND, expirySeconds);
			tokenType.set(FieldNames.FIELD_EXPIRY_DATE, cal.getTime());
			tokenType.set(FieldNames.FIELD_EXPIRES, true);
		}
		tokenType.set(FieldNames.FIELD_DATA, data);
		if(IOSystem.getActiveContext().getWriter().write(tokenType) == false){
			logger.error("Failed to persist tokens");
			tokenType = null;
		}
		else{
			logger.info("Created new token with guid: " + tokenType.get(FieldNames.FIELD_OBJECT_ID));
		}
		return tokenType;
	}
	
}
