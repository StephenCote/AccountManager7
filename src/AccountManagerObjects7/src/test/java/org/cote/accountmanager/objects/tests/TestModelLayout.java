package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import java.util.stream.Collectors;

import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelSchema;
import org.junit.Test;

public class TestModelLayout extends BaseTest {

	@Test
	public void TestResourceHierarchy() {
		logger.info("Test loading a model that inherits a dependency in a psuedo-package location");
		ModelSchema ms = RecordFactory.getCustomSchemaFromResource("layout", "layout");
		assertNotNull("Schema is null", ms);
		logger.info(ms.getInherits().stream().collect(Collectors.joining(", ")));

	}
	
}
