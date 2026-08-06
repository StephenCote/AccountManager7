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
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.AuthorizationSchema;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConnectionEnumType;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.accountmanager.util.SetupUtil;


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
		options.addOption("refreshViews", false, "Refresh DB materialized views");
		options.addOption("cleanup", false, "Run cleanup routines");
	}

	@Override
	public void handleCommand(CommandLine cmd) {
		IOContext ioContext = IOSystem.getActiveContext();
		if(cmd.hasOption("adminPassword")) {
			if(cmd.hasOption("setup")) {
				/// DELEGATED, but to the IDEMPOTENT REPAIR path, not the latch-gated first-run
				/// sequence. The duplicated organization/credential loop that used to live here is
				/// gone — SetupUtil owns the only copy — but -setup must remain safely re-runnable.
				///
				/// Routing it through runSetup() was a backward-compatibility break: runSetup opens
				/// with assertSetupIncomplete(), so once ANY default org's admin held a credential the
				/// whole sequence threw and nothing ran. That removed the partial-provisioning repair
				/// capability operators relied on, and turned a previously clean no-op into an ERROR
				/// that trips log-scraping CI.
				///
				/// repairProvisioning() is not latch-gated because the latch guards against an
				/// UNAUTHENTICATED REMOTE caller; a CLI operator already holds the DB credentials and
				/// filesystem access the latch protects. It still never overwrites a credential and
				/// still refuses to write the marker unless one exists. The password is validated
				/// lazily, only where a credential must actually be created, so a re-run against a
				/// healthy deployment does not fail on it.
				{
					try {
						SetupUtil.SetupResult res = SetupUtil.repairProvisioning(cmd.getOptionValue("adminPassword"));
						for(String warning : res.getWarnings()) {
							logger.warn(warning);
						}
						if(res.isOk()) {
							logger.info("Setup complete");
							/// Say so explicitly: this is new behavior. The CLI never used to write the
							/// .setupState marker, so running -setup from the console now permanently
							/// closes the web setup page. Intended, but the operator must know.
							logger.warn("The " + SetupUtil.SETUP_MARKER_NAME + " marker has been written."
								+ " The web setup page (/rest/setup) is now PERMANENTLY CLOSED for this"
								+ " database. This is new behavior: the console did not previously write"
								+ " the marker.");
							/// ISO 42001 role provisioning is intentionally NOT reachable from here:
							/// Objects7 must never depend on the ISO module, so SetupUtil cannot call
							/// ISO42001Provisioning. Service7 self-heals on its next boot.
							logger.warn("ISO 42001 roles are NOT provisioned by console setup — Objects7"
								+ " cannot reference the ISO module by design. They are provisioned"
								+ " idempotently the next time AccountManagerService7 starts"
								+ " (RestServiceEventListener.provisionDefaultOrganizations). Start or"
								+ " restart Tomcat before using any ISO 42001 feature.");
						}
						else {
							logger.error("Setup did NOT complete — see the warnings above.");
						}
					}
					catch (SystemException e) {
						/// Only an unusable IO context reaches here now; the latch no longer aborts
						/// this path.
						logger.error("Setup was not run: " + e.getMessage());
					}
				}
			}
			/// NOTE: -addUser is deliberately NOT latch-gated. It is a day-2 operation against an
			/// arbitrary organization (-organization), run by an authenticated administrator. The
			/// setup latch is CLOSED once setup has completed, so gating this would permanently
			/// break existing CLI behavior.
			/// NOTE: cmd.hasOption("resetPassword") below is a PRE-EXISTING DEAD BRANCH — AdminAction
			/// never registers a "resetPassword" option (ConsoleMain does, so it parses, but this
			/// action's contract does not include it). Left exactly as found; not in scope to fix.
			if(cmd.hasOption("addUser") || cmd.hasOption("resetPassword")) {
				/// ORDER OF OPERATIONS — validate the password BEFORE creating anything.
				///
				/// This check used to sit after getCreateUser(), so a rejected password produced a
				/// user with NO credential: a partial state strictly worse than either the old
				/// behavior or a clean failure. Fail here, before the user exists.
				///
				/// THRESHOLD — deliberately weaker than the setup paths: -addUser is a day-2
				/// operation whose pre-existing contract had NO minimum length, so applying the
				/// 8-character setup minimum here would itself be a compatibility break for existing
				/// provisioning scripts. Only null/blank is refused (CredentialFactory checks merely
				/// `pwd != null`, so a blank password would otherwise become a real HASHED_PASSWORD
				/// over the empty string). The 8-character minimum stays on the setup paths, where
				/// this feature introduced it.
				String credStr = cmd.getOptionValue("password");
				String pwError = SetupUtil.validatePasswordPresent(credStr);
				if(pwError != null) {
					logger.error("Refusing to add a user: " + pwError
						+ " (-password is required for -addUser). No user was created.");
				}
				/// Guarded rather than an early return: -db / -cleanup / -refreshViews are handled
				/// further down in this same method and must not be skipped by a bad -addUser.
				BaseRecord admin = (pwError != null ? null
					: ActionUtil.login(cmd.getOptionValue("organization"), Factory.ADMIN_USER_NAME, cmd.getOptionValue("adminPassword")));
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
						/// DAY-2 credential write, kept SEPARATE from the bootstrap write in
						/// SetupUtil.createBootstrapCredential on purpose. The two are not collapsed
						/// into one helper, so neither path can silently inherit the other's looser
						/// or stricter treatment.
						{
							ParameterList plist = ParameterUtil.newParameterList("password", credStr);
							plist.parameter(FieldNames.FIELD_TYPE, CredentialEnumType.HASHED_PASSWORD.toString().toLowerCase());
							BaseRecord newCred = null;
							try {
								newCred = ioContext.getFactory().newInstance(ModelNames.MODEL_CREDENTIAL, newUser, null, plist);
							} catch (FactoryException e) {
								logger.error(e);
							}
							if(newCred != null && SetupUtil.isRealCredential(newCred)) {
								IOSystem.getActiveContext().getRecordUtil().createRecord(newCred);
								logger.info("Set credential for " + cmd.getOptionValue("username"));
							}
							else {
								logger.error("Failed to build a usable credential for " + cmd.getOptionValue("username"));
							}
						}
					}
					else {
						logger.warn("User " + cmd.getOptionValue("username") + " already exists");
					}
				}
				else if(pwError == null) {
					/// Only a genuine login failure; the bad-password case was already reported.
					logger.warn("Failed to find admin user in " + cmd.getOptionValue("organization"));
				}
			}
		}
		if(cmd.hasOption("db")) {
			if(cmd.hasOption("patch")) {
				logger.info("Patching DB Schema");
				IOSystem.getActiveContext().getAuthorizationUtil().createAuthorizationSchema();
			}
	
			if(cmd.hasOption("cleanup")) {
				logger.info("Cleaning up orphans ...");
				RecordFactory.cleanupOrphans(null);
				if(IOSystem.getActiveContext().getIoType() == RecordIO.DATABASE) {
					DBUtil util = IOSystem.getActiveContext().getDbUtil();
					util.vacuum();

				}
			}
			if(cmd.hasOption("refreshViews")) {
				logger.info("Refreshing DB Materialized Views");
				AuthorizationSchema.refreshMaterializedViews();
			}
		}
	}

	@Override
	public void handleCommand(CommandLine cmd, BaseRecord user) {
		// TODO Auto-generated method stub
		
	}

}
