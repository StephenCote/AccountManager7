
package org.cote.jaas;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import javax.security.auth.login.LoginException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.data.security.RolePrincipal;
import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

public class AM7RequestWrapper extends HttpServletRequestWrapper {
	public static final Logger logger = LogManager.getLogger(AM7RequestWrapper.class);
 
  private UserPrincipal principal = null;
  private List<String> roles = new ArrayList<>();
   
  public AM7RequestWrapper(BaseRecord user, HttpServletRequest inRequest) {
    super(inRequest);
    this.principal = new UserPrincipal(user.get(FieldNames.FIELD_ID), user.get(FieldNames.FIELD_NAME), user.get(FieldNames.FIELD_ORGANIZATION_PATH));
    try {
		List<RolePrincipal> rpl = AM7LoginModule.getRoleSets(principal);
		for(RolePrincipal r : rpl){
			roles.add(r.getName());
		}
    }
    catch(FactoryException | LoginException e) {
    	logger.error(e);
    }




  }
 
  @Override
  public boolean isUserInRole(String role) {
    return roles.contains(role);
  }
 
  @Override
  public Principal getUserPrincipal() {
	 return principal;
  }
}