
package org.cote.accountmanager.util;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class UserUtil {
	
	public static final Logger logger = LogManager.getLogger(UserUtil.class);

	protected static void addDefaultProfileAttributes(BaseRecord data) throws ModelException, FieldException, ModelNotFoundException, ValueException{
		AttributeUtil.addAttribute(data, null, null);
		AttributeUtil.addAttribute(data, "blog.title", "My blog title");
		AttributeUtil.addAttribute(data, "blog.subtitle", "My blog subtitle");
		AttributeUtil.addAttribute(data, "blog.author", "My pen name");
		AttributeUtil.addAttribute(data, "blog.signature", "My signature");
	}
	public static BaseRecord getProfile(BaseRecord user){
		BaseRecord data = null;
		try{
			IOSystem.getActiveContext().getReader().populate(user);
			BaseRecord[] profileL = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_DATA, user.get(FieldNames.FIELD_HOME_DIRECTORY_FIELD_ID), ".profile");
			if(profileL.length == 0){
				data = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user);
				data.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
				data.set(FieldNames.FIELD_GROUP_ID, user.get(FieldNames.FIELD_HOME_DIRECTORY_FIELD_ID));
				data.set(FieldNames.FIELD_NAME, ".profile");
				addDefaultProfileAttributes(data);
				data = IOSystem.getActiveContext().getAccessPoint().create(user, data);
			}
			else {
				data = profileL[0];
			}
		}
		catch(ReaderException | FieldException | ValueException | ModelNotFoundException | ModelException | FactoryException e) {
			logger.error(e.getMessage());
		}
		return data;
	}

	
}
