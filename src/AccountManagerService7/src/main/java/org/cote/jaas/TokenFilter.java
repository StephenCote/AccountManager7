package org.cote.jaas;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.security.AM7SigningKeyResolver;

import io.jsonwebtoken.Jwts;

public class TokenFilter implements Filter{
	private static final Logger logger = LogManager.getLogger(TokenFilter.class);
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException{
	    HttpServletRequest req = (HttpServletRequest) request;
	    String stringToken = req.getHeader("Authorization");
	    boolean didChain = false;
	    
	    int idx = -1;
	    if (stringToken != null && (idx = stringToken.indexOf("Bearer")) > -1) {
	    	String token = stringToken.substring(idx + 7, stringToken.length()).trim();
	    	String urn = Jwts.parser().setSigningKeyResolver(new AM7SigningKeyResolver()).parseClaimsJws(token).getBody().getId();
	    	BaseRecord user = IOSystem.getActiveContext().getRecordUtil().getRecordByUrn(null, ModelNames.MODEL_USER, urn);
	    	if(user != null){
	    		didChain = true;
	    		chain.doFilter(new AM7RequestWrapper(user, (HttpServletRequest)request), response);
	    	}
	    	else {
	    		logger.warn("Null user for urn " + urn);
	    	}
	    }
	    else {
	    	logger.warn("No bearer");
	    }
	    if(didChain == false){
	    	chain.doFilter(request, response);
	    }
	  }

	@Override
	public void destroy() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void init(FilterConfig arg0) throws ServletException {
		// TODO Auto-generated method stub
		
	}
}
