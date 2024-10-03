
package org.cote.jaas;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.data.security.RolePrincipal;
import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.VerificationEnumType;
import org.cote.accountmanager.security.CredentialUtil;
import org.cote.accountmanager.util.ParameterUtil;

public class AM7LoginModule implements LoginModule {

	private static final Logger logger = LogManager.getLogger(AM7LoginModule.class);
	
    protected Subject subject;
    protected CallbackHandler callbackHandler;
    
    @SuppressWarnings("rawtypes")
	protected Map sharedState;

    @SuppressWarnings("rawtypes")
	protected Map options;

    protected boolean succeeded;
    protected boolean commitSucceeded;
    protected String name;
    protected String orgPath;
    protected Principal[] authPrincipals;
    protected static Map<String, String> roleMap = null;
    protected static String authenticatedRole = null;

    public static void setAuthenticatedRole(String s){
    	authenticatedRole = s;
    }
    
    /// NOTE: RoleMap set in RestServiceConfig
    ///
    public static Map<String, String> getRoleMap(){
    	if(roleMap != null) return roleMap;
    	roleMap = new HashMap<>();
    	return roleMap;
    }
    
    public static void setRoleMap(Map<String,String> map){
    	roleMap = map;
    }

    @SuppressWarnings("rawtypes")
	public void initialize(Subject sub, CallbackHandler handler, Map state, Map opts) {
        this.subject = sub;
        this.callbackHandler = handler;
        this.sharedState = state;
        this.options = opts;

        // debug = "true".equalsIgnoreCase((String) options.get("debug"));

    }
 
 
    protected Principal[] getAuthPrincipals() {
        return authPrincipals;
    }
 
    private BaseRecord getLatestCredential(BaseRecord user) {
    	return CredentialUtil.getLatestCredential(user);
    }
    /*
		Query query = QueryUtil.createQuery(ModelNames.MODEL_CREDENTIAL, FieldNames.FIELD_REFERENCE_TYPE, user.getModel());
		QueryResult res = null;
		try {
			query.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_CREATED_DATE);
			query.set(FieldNames.FIELD_ORDER, OrderEnumType.DESCENDING);
			query.field(FieldNames.FIELD_REFERENCE_ID, user.get(FieldNames.FIELD_ID));
			query.set(FieldNames.FIELD_RECORD_COUNT, 1);
			res = IOSystem.getActiveContext().getSearch().find(query);
		} catch (IndexException | ReaderException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		BaseRecord cred = null;
		if(res.getResults().length > 0) {
			cred = res.getResults()[0];
		}
		return cred;
    }
    */
    public boolean login() throws LoginException {
 
        if (callbackHandler == null) {
            throw new LoginException("Error: no CallbackHandler available to garner authentication information from the user");
        }
        Callback[] callbacks = new Callback[] {
            new NameCallback("Username: "),
            new PasswordCallback("Password: ", false)
        };
 
        try {
            callbackHandler.handle(callbacks);
        } catch (Exception e) {
            succeeded = false;
            throw new LoginException(e.getMessage());
        }

        String username = ((NameCallback)callbacks[0]).getName();
        String password = new String(((PasswordCallback)callbacks[1]).getPassword());
        String orgPath = null;
        if(username.indexOf("/") > -1){
        	orgPath = username.substring(0, username.lastIndexOf("/"));
        	username = username.substring(username.lastIndexOf("/")+1,username.length());
        }
        else{
        	orgPath = "/Public";
        }

        if(orgPath == null || orgPath.length() == 0){
        	throw new LoginException("Null organization path");
        }
        logger.info("Login: " + orgPath + "/" + username);
        BaseRecord orgType = IOSystem.getActiveContext().getPathUtil().findPath(null, ModelNames.MODEL_ORGANIZATION, orgPath, null, 0);
        BaseRecord user = null;
        if(orgType != null) {
        	user = IOSystem.getActiveContext().getRecordUtil().getRecord(null, ModelNames.MODEL_USER, username, 0L, 0L, orgType.get(FieldNames.FIELD_ID));
        	if(user != null) {
        		VerificationEnumType vet = VerificationEnumType.UNKNOWN;
        		BaseRecord cred = getLatestCredential(user);
        		if(cred != null) {
	        		try {
						vet = IOSystem.getActiveContext().getFactory().verify(user, getLatestCredential(user), ParameterUtil.newParameterList("password", password));
					} catch (FactoryException e) {
						logger.error(e);
					}
        		}
        		else {
        			logger.warn("Null credential");
        		}
        		if(vet != VerificationEnumType.VERIFIED) {
        			user = null;
        		}
        	}
        }
        if(user != null){
        	succeeded = true;
        	name = username;
        	authPrincipals = new UserPrincipal[1];
        	authPrincipals[0] = new UserPrincipal(user.get(FieldNames.FIELD_ID), username, orgPath);
        }
 
        ((PasswordCallback)callbacks[1]).clearPassword();
        callbacks[0] = null;
        callbacks[1] = null;
 
        if (!succeeded)
            throw new LoginException("Authentication failed: Password does not match");
 
        return true; 
    }
 
    public boolean commit() throws LoginException {
        try {
 
            if (succeeded == false) {
                return false;
            }
 
            if (subject.isReadOnly()) {
                throw new LoginException("Subject is ReadOnly");
            }
            if (getAuthPrincipals() != null) {
                for (int i = 0; i < getAuthPrincipals().length; i++) {
                    if(!subject.getPrincipals().contains(getAuthPrincipals()[i])){
                    	logger.debug("Adding principle to subject: '" + getAuthPrincipals()[i].getName());
                        subject.getPrincipals().add(getAuthPrincipals()[i]);
                        subject.getPrincipals().addAll(getRoleSets((UserPrincipal)getAuthPrincipals()[0]));
                    }
                    else{
                    	logger.debug("Don't add principle to subject: '" + getAuthPrincipals()[i].getName());
                    }
                }
            }

            cleanup();
 
            commitSucceeded = true;
            return true;
 
        }
        catch (Throwable t) {
            throw new LoginException(t.toString());
        }
    }
 

    public static List<RolePrincipal> getRoleSets(UserPrincipal uprince) throws LoginException, FactoryException
    {
		Map<String,String> map = getRoleMap();
		List<RolePrincipal> oroles = new ArrayList<>();
		List<BaseRecord> roles = new ArrayList<>();
		try {
			List<BaseRecord> entsp = IOSystem.getActiveContext().getMemberUtil().getParticipations(uprince, ModelNames.MODEL_ROLE);
			roles = entsp.stream().filter(o -> {
				if(o.inherits(ModelNames.MODEL_PARTICIPATION_ENTRY)) {
					String type = o.get(FieldNames.FIELD_TYPE);
					return ModelNames.MODEL_ROLE.equals(type);
				}
				else {
					// String type = o.get(FieldNames.FIELD_PARTICIPATION_MODEL);
					// return ModelNames.MODEL_ROLE.equals(type);
					return true;
				}
			}).collect(Collectors.toList());
		}
		catch(ReaderException | IndexException e) {
			logger.error(e);
		}
		try {
	        for(BaseRecord b : roles) {
	        	BaseRecord brole = null;
	        	if(b.inherits(ModelNames.MODEL_PARTICIPATION_ENTRY)) {
	        		long id = b.get(FieldNames.FIELD_PART_ID);
	        		brole = IOSystem.getActiveContext().getReader().read(b.get(FieldNames.FIELD_TYPE), id);
					if(brole == null) {
						logger.error("Failed to retrieve role #" + id);
						continue;
					}
	        	}
	        	else {
	        		brole = b;
	        	}

	        	String name = brole.get(FieldNames.FIELD_NAME);
	        	if(map.containsKey(name)){
	        		// logger.info("Mapping role for " + uprince.getName() + ": " + name + " -> " + map.get(name));
	        		RolePrincipal role = new RolePrincipal(map.get(name));
	        		oroles.add(role);
	        	}
	        }
		}
		catch(ReaderException e) {
			logger.error(e);
		}
        if(authenticatedRole != null && map.containsKey(authenticatedRole)){
        	oroles.add(new RolePrincipal(map.get(authenticatedRole)));
        }
        logger.debug("Returning " + oroles.size() + " roles mapped against " + roleMap.size());
        return oroles;
    }

     public boolean abort() throws LoginException {

        if (succeeded == false) {
            cleanup();
            return false;
        } else if (succeeded && commitSucceeded == false) {
            // Login succeeded but authentication failed
            succeeded = false;
            cleanup();
        } else {
            // Authentication succeeded and commit succeeded,
            // but a commit failed
            logout();
        }
        return true;
    }
 
 
    protected void cleanup() {
        name = null;
    }
 
 
    protected void cleanupAll() {
        cleanup();
 
        if (getAuthPrincipals() != null) {
            for (int i = 0; i < getAuthPrincipals().length; i++) {
                subject.getPrincipals().remove(getAuthPrincipals()[i]);
            }
        }
    }
 
    public boolean logout() throws LoginException {
        succeeded = false;
        commitSucceeded = false;
        cleanupAll();
        return true;
    }
 
    @SuppressWarnings("rawtypes")
	protected static void printSet(Set s) {
        try {
            Iterator principalIterator = s.iterator();
            while (principalIterator.hasNext()) {
                Principal p = (Principal) principalIterator.next();
                logger.info("\t\t\t" + p.toString());
            }
        } catch (Throwable t) {
        }
    }
 
 
    @SuppressWarnings("rawtypes")
	protected static void printSubject(Subject subject) {
        try {
            if (subject == null) {
                return;
            }
            Set s = subject.getPrincipals();
            if ((s != null) && (s.size() != 0)) {
                logger.info("\t\t[AM5LoginModule] added the following Principals:");
                printSet(s);
            }
 
            s = subject.getPublicCredentials();
            if ((s != null) && (s.size() != 0)) {
                logger.info("\t\t[AM5LoginModule] added the following Public Credentials:");
                printSet(s);
            }
        } catch (Throwable t) {
        }
    }
}
