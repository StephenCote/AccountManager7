package org.cote.accountmanager.console.actions;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.SystemException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConnectionEnumType;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.security.CredentialUtil;
import org.cote.accountmanager.util.ParameterUtil;


public class AdminAction extends CommonAction implements IAction {
	
	public AdminAction() {
		
	}
	
	@Override
	public void addOptions(Options options) {
		// TODO Auto-generated method stub
		options.addOption("adminPassword",true,"AccountManager admin password");
		options.addOption("addUser", false, "Add a new user");
		options.addOption("setup", false, "Setup AM7");
		options.addOption("db", false, "Apply DB schema patches");
		options.addOption("cleanup", false, "Run cleanup routines");
	}

	@Override
	public void handleCommand(CommandLine cmd) {
		IOContext ioContext = IOSystem.getActiveContext();
		if(cmd.hasOption("adminPassword")) {
			if(cmd.hasOption("setup")) {
				for(String org : OrganizationContext.DEFAULT_ORGANIZATIONS) {
					logger.info("Configuring " + OrganizationEnumType.valueOf(org.substring(1).toUpperCase()) + " " + org);
					OrganizationContext oc = IOSystem.getActiveContext().getOrganizationContext(org, OrganizationEnumType.valueOf(org.substring(1).toUpperCase()));
					try {
						if(!oc.isInitialized()) {
							oc.createOrganization();
						}
						BaseRecord admin = oc.getAdminUser();
						BaseRecord cred = CredentialUtil.getLatestCredential(admin);
						if(cred == null) {
							String credStr = cmd.getOptionValue("adminPassword");
							ParameterList plist = ParameterUtil.newParameterList("password", credStr);
							plist.parameter(FieldNames.FIELD_TYPE, CredentialEnumType.HASHED_PASSWORD.toString().toLowerCase());
							BaseRecord newCred = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CREDENTIAL, admin, null, plist);
							logger.info("New credential created");
							IOSystem.getActiveContext().getRecordUtil().createRecord(newCred);
						}
						else {
							logger.warn("Administrative credential already set");
						}

					} catch (NullPointerException | SystemException | FactoryException e) {
						logger.error(e);
						e.printStackTrace();
					}

				}
			}
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
		if(cmd.hasOption("db") && cmd.hasOption("patch")) {
			logger.info("Patching DB Schema");
			IOSystem.getActiveContext().getAuthorizationUtil().createAuthorizationSchema();
		}
		if(cmd.hasOption("cleanup")) {
			logger.info("Cleaning up orphans ...");
			RecordFactory.cleanupOrphans(null);
			if(IOSystem.getActiveContext().getIoType() == RecordIO.DATABASE) {
				DBUtil util = IOSystem.getActiveContext().getDbUtil();
				
				try (Connection con = util.getDataSource().getConnection();
				   	Statement st = con.createStatement();
				){
					if(util.getConnectionType() == ConnectionEnumType.POSTGRE) {
						logger.info("Vacuuming ...");

						st.execute("vacuum(full, analyze, verbose);");
						
					}
					
				}
				catch (SQLException e) {
					logger.error(e);
			    }
			}
		}
	}

	@Override
	public void handleCommand(CommandLine cmd, BaseRecord user) {
		// TODO Auto-generated method stub
		
	}

}
