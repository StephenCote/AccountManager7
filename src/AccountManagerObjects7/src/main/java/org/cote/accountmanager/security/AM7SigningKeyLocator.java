package org.cote.accountmanager.security;

import java.security.Key;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Locator;

public class AM7SigningKeyLocator implements Locator<Key>{

	public static final Logger logger = LogManager.getLogger(AM7SigningKeyLocator.class);

	/*
	public Key resolveSigningKey(JwsHeader arg0, Claims arg1) {
		String urn = arg1.getId();
		String modelName = ModelNames.MODEL_USER;
		String fetStr = arg1.get("subjectType", String.class);
		String issueUrn = arg1.getIssuer();
		Boolean bUseIssuer = arg1.get("sbi", Boolean.class);
		boolean useIssuer = (bUseIssuer != null && bUseIssuer.booleanValue());
		if(!useIssuer && fetStr != null && fetStr.length() > 0) modelName = fetStr;
		if(useIssuer) {
			logger.info("Restoring issuer key for: " + arg1.getIssuer());
			urn = arg1.getIssuer();
		}
		Key key = null;
		if(IOSystem.getActiveContext() == null) {
			logger.error("Active context is null");
			return null;
		}
		if(urn != null){
			BaseRecord persona = null;
			try {
				persona = IOSystem.getActiveContext().getReader().readByUrn(modelName, urn);
			} catch (ReaderException e) {
				logger.error(e);
				
			}
			if(persona != null){
				CryptoBean bean = TokenService.getCreateCipher(persona);
				if(bean != null && bean.getSecretKey() != null){
					key = bean.getSecretKey();
				}
				else {
					logger.error("Invalid security key for " + urn);
				}
			}
			else {
				logger.error("Failed to retrieve persona by urn " + urn);
			}
		}
		else {
			logger.error("No URN provided in claims for key resolution");
		}
		return key;
	}
	*/

	public Key resolveSigningKey(JwsHeader arg0, String arg1) {
		return null;
	}

	@Override
	public Key locate(Header header) {

		String urn = (String)header.get("kid");
		String modelName = ModelNames.MODEL_USER;
		String fetStr = (String)header.get("subjectType");

		Boolean bUseIssuer = (Boolean)header.get("sbi");
		boolean useIssuer = (bUseIssuer != null && bUseIssuer.booleanValue());
		if(!useIssuer && fetStr != null && fetStr.length() > 0) modelName = fetStr;
		if(useIssuer) {
			urn = (String)header.get("issuerUrn");
			logger.info("Restoring issuer key for: " + urn);
		}
		Key key = null;
		if(IOSystem.getActiveContext() == null) {
			logger.error("Active context is null");
			return null;
		}
		if(urn != null){
			BaseRecord persona = null;
			try {
				persona = IOSystem.getActiveContext().getReader().readByUrn(modelName, urn);
			} catch (ReaderException e) {
				logger.error(e);
				
			}
			if(persona != null){
				CryptoBean bean = TokenService.getCreateCipher(persona);
				if(bean != null && bean.getSecretKey() != null){
					key = bean.getSecretKey();
					logger.info("Retrieved key for " + urn);
				}
				else {
					logger.error("Invalid security key for " + urn);
				}
			}
			else {
				logger.error("Failed to retrieve persona by urn " + urn);
			}
		}
		else {
			logger.error("No URN provided in header for key resolution");
			logger.error(JSONUtil.exportObject(header));
		}
		return key;
		
		
	}
	
}
