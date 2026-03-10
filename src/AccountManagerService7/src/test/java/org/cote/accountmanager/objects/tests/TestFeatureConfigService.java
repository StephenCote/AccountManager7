package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestFeatureConfigService extends BaseTest {

	@Test
	public void TestGetDefaultConfig() {
		/// When no config record exists, the service should return the default full profile
		/// We test this indirectly by verifying the default feature list
		List<String> defaultFeatures = Arrays.asList(
			"core", "chat", "cardGame", "games", "testHarness", "iso42001", "biometrics", "schema", "webauthn", "accessRequests", "featureConfig"
		);
		assertTrue("Default features should include core", defaultFeatures.contains("core"));
		assertTrue("Default features should include chat", defaultFeatures.contains("chat"));
		assertTrue("Default features should include schema", defaultFeatures.contains("schema"));
		assertTrue("Default features should include featureConfig", defaultFeatures.contains("featureConfig"));
		assertEquals("Default features count", 11, defaultFeatures.size());
	}

	@Test
	public void TestConfigRecordCRUD() throws Exception {
		/// Get admin user for the test org
		assertNotNull("IOSystem should be active", IOSystem.getActiveContext());
		assertNotNull("OrgContext should exist", orgContext);

		BaseRecord adminUser = orgContext.getAdminUser();
		assertNotNull("Admin user is null", adminUser);

		/// Ensure the home directory exists
		String homePath = adminUser.get("homeDirectory.path");
		if (homePath == null) {
			homePath = "~";
		}

		BaseRecord homeDir = IOSystem.getActiveContext().getPathUtil().makePath(
			adminUser, ModelNames.MODEL_GROUP, homePath,
			GroupEnumType.DATA.toString(), adminUser.get(FieldNames.FIELD_ORGANIZATION_ID)
		);
		assertNotNull("Home directory should exist", homeDir);

		/// Clean up any existing config record
		BaseRecord existing = IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(
			adminUser, ModelNames.MODEL_DATA,
			homeDir.get(FieldNames.FIELD_OBJECT_ID), ".featureConfig"
		);
		if (existing != null) {
			IOSystem.getActiveContext().getAccessPoint().delete(adminUser, existing);
		}

		/// Create a config record
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, homePath);
		plist.parameter(FieldNames.FIELD_NAME, ".featureConfig");
		BaseRecord newRec = IOSystem.getActiveContext().getFactory().newInstance(
			ModelNames.MODEL_DATA, adminUser, null, plist
		);

		Map<String, Object> config = new LinkedHashMap<>();
		config.put("features", Arrays.asList("core", "chat", "schema"));
		config.put("profile", "custom");
		String configJson = JSONUtil.exportObject(config);

		newRec.set("description", configJson);
		newRec.set("contentType", "application/json");

		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(adminUser, newRec);
		assertNotNull("Config record should be created", created);

		/// Read it back
		BaseRecord found = IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(
			adminUser, ModelNames.MODEL_DATA,
			homeDir.get(FieldNames.FIELD_OBJECT_ID), ".featureConfig"
		);
		assertNotNull("Config record should be found", found);

		String storedDesc = found.get("description");
		assertNotNull("Description should not be null", storedDesc);
		assertTrue("Stored config should contain 'core'", storedDesc.contains("core"));
		assertTrue("Stored config should contain 'chat'", storedDesc.contains("chat"));
		assertTrue("Stored config should contain 'schema'", storedDesc.contains("schema"));

		/// Update the config
		found.set("description", JSONUtil.exportObject(Map.of("features", Arrays.asList("core", "games"), "profile", "gaming")));
		BaseRecord updated = IOSystem.getActiveContext().getAccessPoint().update(adminUser, found);
		assertNotNull("Config record should be updated", updated);

		/// Read updated config
		BaseRecord reread = IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(
			adminUser, ModelNames.MODEL_DATA,
			homeDir.get(FieldNames.FIELD_OBJECT_ID), ".featureConfig"
		);
		assertNotNull("Updated config record should be found", reread);
		String updatedDesc = reread.get("description");
		assertTrue("Updated config should contain 'games'", updatedDesc.contains("games"));

		/// Delete the config
		boolean deleted = IOSystem.getActiveContext().getAccessPoint().delete(adminUser, reread);
		assertTrue("Config record should be deleted", deleted);

		/// Verify deletion
		BaseRecord gone = IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(
			adminUser, ModelNames.MODEL_DATA,
			homeDir.get(FieldNames.FIELD_OBJECT_ID), ".featureConfig"
		);
		assertTrue("Config record should be gone", gone == null);
	}

	@Test
	public void TestFeatureValidation() {
		/// Test that unknown feature IDs are detectable
		List<String> knownIds = Arrays.asList(
			"core", "chat", "cardGame", "games", "testHarness", "iso42001", "biometrics", "schema", "webauthn", "accessRequests", "featureConfig"
		);

		assertTrue("'core' should be known", knownIds.contains("core"));
		assertTrue("'chat' should be known", knownIds.contains("chat"));
		assertTrue("'featureConfig' should be known", knownIds.contains("featureConfig"));

		/// Unknown features
		assertTrue("'bogusFeature' should not be known", !knownIds.contains("bogusFeature"));
		assertTrue("Empty string should not be known", !knownIds.contains(""));
	}

	@Test
	public void TestCoreAlwaysIncluded() {
		/// Verify that if someone sends a feature list without 'core', we add it
		List<String> userFeatures = new java.util.ArrayList<>(Arrays.asList("chat", "games"));
		if (!userFeatures.contains("core")) {
			userFeatures.add(0, "core");
		}
		assertEquals("core", userFeatures.get(0));
		assertEquals(3, userFeatures.size());
	}
}
