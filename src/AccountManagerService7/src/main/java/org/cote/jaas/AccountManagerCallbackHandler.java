
package org.cote.jaas;

import java.io.IOException;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.CredentialEnumType;


public class AccountManagerCallbackHandler implements CallbackHandler {
	private static final Logger logger = LogManager.getLogger(AccountManagerCallbackHandler.class);
	String name;
	String password;

	public AccountManagerCallbackHandler(BaseRecord credential){
		CredentialEnumType credType = org.cote.accountmanager.schema.type.CredentialEnumType.valueOf(credential.get(FieldNames.FIELD_TYPE));
		if(credential != null && (credType.equals(CredentialEnumType.ENCRYPTED_PASSWORD) || credType.equals(CredentialEnumType.HASHED_PASSWORD))){
			logger.info("CREDENTIALTYPE");
			name = credential.get(FieldNames.FIELD_ORGANIZATION_PATH) + "/" + credential.get(FieldNames.FIELD_NAME);
			password = new String((byte[])credential.get(FieldNames.FIELD_CREDENTIAL));
		}
		else{
			logger.error("Invalid credential");
		}
	}
	public AccountManagerCallbackHandler(String name, String password) {
		logger.info("BASIC FORM");
		this.name = name;
		this.password = password;
	}

	public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
		System.out.println("Callback Handler - handle called: " + name + " / " + password);
		for (int i = 0; i < callbacks.length; i++) {
			if (callbacks[i] instanceof NameCallback) {
				NameCallback nameCallback = (NameCallback) callbacks[i];
				nameCallback.setName(name);
			} else if (callbacks[i] instanceof PasswordCallback) {
				PasswordCallback passwordCallback = (PasswordCallback) callbacks[i];
				passwordCallback.setPassword(password.toCharArray());
			} else {
				System.out.println("Unsupported callback at " + i);
				throw new UnsupportedCallbackException(callbacks[i], "The submitted Callback is unsupported");
			}
		}
	}
}
