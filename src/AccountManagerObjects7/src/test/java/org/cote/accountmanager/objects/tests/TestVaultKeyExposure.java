package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Base64;
import java.util.UUID;

import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.model.field.VaultBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.security.VaultService;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/// The decrypted vault private key must never be reachable through the VaultBean's record fields.
///
/// VaultService.getVaultKey() decrypts the vault key into a CryptoBean and hands it to VaultBean.setVaultKey().
/// That setter used to ALSO store the CryptoBean in the record's "vaultKey" model field, which put the raw
/// private key bytes on every generic path that walks the record's fields - toString(), toFullString(),
/// JSONUtil.exportObject(...) and get("vaultKey") - with the @JsonIgnore on the Java accessor doing nothing,
/// because RecordSerializer iterates getFields(), not bean getters.
///
/// The key still has to be available to VaultService / ByteModelUtil / StreamUtil via getVaultKey(), so the
/// fix keeps it on the transient Java field only. This test proves the record-field route is closed.
public class TestVaultKeyExposure extends BaseTest {

	@Test
	public void TestDecryptedVaultKeyIsNotOnTheRecord() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/VaultExposure");
		BaseRecord testUser = getCreateUser("vaultExposureUser", testOrgContext);
		assertNotNull("Test user is null", testUser);
		long orgId = testUser.get(FieldNames.FIELD_ORGANIZATION_ID);

		String vaultName = "Exposure Vault - " + UUID.randomUUID().toString();
		VaultBean vault = VaultService.getInstance().getCreateVault(testUser, vaultName, orgId);
		assertNotNull("Vault is null", vault);

		CryptoBean key = VaultService.getInstance().getVaultKey(vault);
		assertNotNull("Vault key is null", key);
		assertNotNull("Vault key private key is null", key.getPrivateKey());
		byte[] privBytes = key.get(FieldNames.FIELD_PRIVATE_FIELD_KEY);
		assertNotNull("Private key bytes are null", privBytes);
		assertTrue("Private key bytes are empty", privBytes.length > 0);
		String privB64 = Base64.getEncoder().encodeToString(privBytes);

		/// The key is still available to the service layer through the accessor.
		assertNotNull("Service accessor must still return the key", vault.getVaultKey());
		assertTrue("Accessor must return the same key instance", vault.getVaultKey() == key);

		/// But it must not live in the record's fields.
		BaseRecord recKey = vault.get(FieldNames.FIELD_VAULT_KEY);
		assertNull("Decrypted vault key must not be stored in the record's vaultKey field", recKey);

		/// And no serializer module may be able to emit it.
		String[] serials = new String[] {
			vault.toString(),
			vault.toFullString(),
			JSONUtil.exportObject(vault, RecordSerializerConfig.getUnfilteredModule()),
			JSONUtil.exportObject(vault, RecordSerializerConfig.getForeignUnfilteredModule()),
			JSONUtil.exportObject(vault, RecordSerializerConfig.getForeignUnfilteredModuleRecurse()),
			JSONUtil.exportObject(vault, RecordSerializerConfig.getCondensedUnfilteredModule())
		};
		for(int i = 0; i < serials.length; i++) {
			assertNotNull("Serialization " + i + " is null", serials[i]);
			assertFalse("Serialization " + i + " leaks the decrypted private key", serials[i].contains(privB64));
			assertFalse("Serialization " + i + " emits a vaultKey field", serials[i].contains("\"" + FieldNames.FIELD_VAULT_KEY + "\""));
		}

		/// Copying the bean into a fresh VaultBean (the listVaultsByOwner / getVaultByObjectId path) must not carry it either.
		VaultBean copy = new VaultBean(vault);
		assertNull("Copied bean must not carry the decrypted key on the record", copy.get(FieldNames.FIELD_VAULT_KEY));
		assertFalse(copy.toFullString().contains(privB64));

		/// Clearing the key clears both views.
		vault.setVaultKey(null);
		assertNull(vault.getVaultKey());
		assertNull(vault.get(FieldNames.FIELD_VAULT_KEY));

		try {
			VaultService.getInstance().deleteVault(vault);
		} catch (IndexException | ReaderException e) {
			logger.error(e);
		}
	}
}
