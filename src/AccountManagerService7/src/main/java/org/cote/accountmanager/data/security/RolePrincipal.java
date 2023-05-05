package org.cote.accountmanager.data.security;

import java.io.Serializable;
import java.security.Principal;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;



public class RolePrincipal extends LooseRecord implements Principal, Serializable {
		private static final long serialVersionUID = 11110L;  
	 
		public RolePrincipal() {
			try {
				RecordFactory.newInstance(ModelNames.MODEL_ROLE, this, null);
				set(FieldNames.FIELD_ORGANIZATION_PATH, "/Public");
			} catch (FieldException | ModelNotFoundException | ValueException e) {
				/// ignore
			}
		}
	    public RolePrincipal(String name){
	    	this();
	    	try {
				this.set(FieldNames.FIELD_NAME, name);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				/// ignore
			}
	    	
	    }
	    public RolePrincipal(String name, String organizationPath) {
	    	this(name);
	    	try {
				this.set(FieldNames.FIELD_ORGANIZATION_PATH, organizationPath);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				/// ignore
			}
	    }
	    public RolePrincipal(long id, String name, String organizationPath) {
	        this(name, organizationPath);
	    	try {
				this.set(FieldNames.FIELD_ID, id);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				/// ignore
			}
	    }
	 

	    public boolean equals(Object o) {
	        if (o == null)
	            return false;

	        if (this == o)
	            return true;

	        if (!(o instanceof RolePrincipal))
	            return false;
	        RolePrincipal that = (RolePrincipal)o;

	        if (this.getName().equals(that.getName()))
	            return true;
	        return false;
	    }
	    
	    public String getName() {
	    	return get(FieldNames.FIELD_NAME);
	    }
	 
	    @Override
	    public int hashCode() {
	    	String name = get(FieldNames.FIELD_NAME);
	    	return name.hashCode();
	    }
	 
	    @Override
	    public String toString() {
	        return "[RolePrincipal] : " + get(FieldNames.FIELD_NAME);
	    }
	 
	}

