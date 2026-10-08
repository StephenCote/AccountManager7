
package org.cote.accountmanager.data.security;

import java.io.Serializable;
import java.security.Principal;
import java.util.Objects;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class UserPrincipal extends LooseRecord implements Principal,Serializable {
		private static final long serialVersionUID = 11110L;  
	 
		
		public UserPrincipal() {
			try {
				RecordFactory.newInstance(ModelNames.MODEL_USER, this, null);
				set(FieldNames.FIELD_ORGANIZATION_PATH, "/Public");
			} catch (FieldException | ModelNotFoundException | ValueException e) {
				/// ignore
			}
		}
	    public UserPrincipal(String name){
	    	this();
	    	try {
				this.set(FieldNames.FIELD_NAME, name);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				/// ignore
			}
	    	
	    }
	    public UserPrincipal(String name, String organizationPath) {
	    	this(name);
	    	try {
				this.set(FieldNames.FIELD_ORGANIZATION_PATH, organizationPath);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				/// ignore
			}
	    }
	    public UserPrincipal(long id, String name, String organizationPath) {
	        this(name, organizationPath);
	    	try {
				this.set(FieldNames.FIELD_ID, id);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				/// ignore
			}
	    }
	 

	    /// Identity is (organizationPath, name), NOT name alone. A user name is only unique within an
	    /// organization, and ServiceUtil.principalCache is keyed by this object: with name-only identity,
	    /// /Development/admin and /System/admin collided and every REST call after the second login was
	    /// served as whichever user was cached first (confirmed live 2026-10-05). organizationPath is what
	    /// every constructor site (AM7LoginModule, AM7RequestWrapper) actually populates; id is optional.
	    public boolean equals(Object o) {
	        if (o == null)
	            return false;

	        if (this == o)
	            return true;

	        if (!(o instanceof UserPrincipal))
	            return false;
	        UserPrincipal that = (UserPrincipal)o;

	        return Objects.equals(this.getName(), that.getName())
	        	&& Objects.equals(this.getOrganizationPath(), that.getOrganizationPath());
	    }

	    public String getName() {
	    	return get(FieldNames.FIELD_NAME);
	    }

	    public String getOrganizationPath() {
	    	return get(FieldNames.FIELD_ORGANIZATION_PATH);
	    }

	    @Override
	    public int hashCode() {
	    	return Objects.hash(getOrganizationPath(), getName());
	    }
	 
	    @Override
	    public String toString() {
	        return "[UserPrincipal] : " + get(FieldNames.FIELD_ORGANIZATION_PATH) + "/" + get(FieldNames.FIELD_NAME);
	    }
	 
	 
	}

