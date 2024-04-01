package org.cote.accountmanager.console.actions;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.util.ParameterUtil;

public class AdminAction extends CommonAction implements IAction {
	
	public AdminAction() {
		
	}
	
	@Override
	public void addOptions(Options options) {
		// TODO Auto-generated method stub
		options.addOption("adminPassword",true,"AccountManager admin password");
		options.addOption("addUser", false, "Add a new user");
	}

	@Override
	public void handleCommand(CommandLine cmd) {
		IOContext ioContext = IOSystem.getActiveContext();
		if(cmd.hasOption("adminPassword")) {

			if(cmd.hasOption("addUser") || cmd.hasOption("resetPassword")) {
				BaseRecord admin = ActionUtil.login(cmd.getOptionValue("organization"), Factory.ADMIN_USER_NAME, cmd.getOptionValue("adminPassword"));
				if(admin != null) {
					Query q = QueryUtil.createQuery(ModelNames.MODEL_USER, FieldNames.FIELD_ORGANIZATION_ID, admin.get(FieldNames.FIELD_ORGANIZATION_ID));
					q.field(FieldNames.FIELD_NAME, cmd.getOptionValue("username"));
					BaseRecord newUser = ioContext.getSearch().findRecord(q);
					if(cmd.hasOption("addUser") && newUser == null) {
						logger.info("Creating user " + cmd.getOptionValue("username"));
						newUser = ioContext.getFactory().getCreateUser(admin, cmd.getOptionValue("username"), admin.get(FieldNames.FIELD_ORGANIZATION_ID));
						logger.info("Created " + cmd.getOptionValue("username"));
					}
					if(newUser != null) {
						String credStr = cmd.getOptionValue("password");
						ParameterList plist = ParameterUtil.newParameterList("password", credStr);
						plist.parameter(FieldNames.FIELD_TYPE, CredentialEnumType.HASHED_PASSWORD.toString().toLowerCase());
						BaseRecord newCred = null;
						try {
							newCred = ioContext.getFactory().newInstance(ModelNames.MODEL_CREDENTIAL, newUser, null, plist);
						} catch (FactoryException e) {
							logger.error(e);
							e.printStackTrace();
						}
						IOSystem.getActiveContext().getRecordUtil().createRecord(newCred);
						logger.info("Set credential for " + cmd.getOptionValue("username"));
					}
					else {
						logger.warn("User " + cmd.getOptionValue("username") + " already exists");
					}
				}
				else {
					logger.warn("Failed to find admin user in " + cmd.getOptionValue("organization"));
				}
			}
		}
	}

	@Override
	public void handleCommand(CommandLine cmd, BaseRecord user) {
		// TODO Auto-generated method stub
		
	}

}
