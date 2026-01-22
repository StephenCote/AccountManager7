package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.parsers.GenericParser;
import org.cote.accountmanager.parsers.ParseConfiguration;
import org.cote.accountmanager.parsers.ParseMap;
import org.cote.accountmanager.parsers.data.DataParseWriter;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelSchema;
import org.junit.Test;

public class TestGenericData extends BaseTest {
	
	@Test
	public void TestStructuredDataImport() {
		//RecordFactory.releaseCustomSchema("lookupTest");
		ModelSchema ms = getTestSchema1();
		assertNotNull("Schema is null", ms);
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Data");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		ParseConfiguration cfg = newTestSchemaParseConfiguration(testUser1, "~/Lookup", "c:/tmp/AuthR", -1);
		assertNotNull("Parse configuration is null", cfg);
		
		List<BaseRecord> recs = GenericParser.parseFile(cfg, new DataParseWriter());
		logger.info("Parsed " + recs.size());
		
	}
	
	public static ParseConfiguration newTestSchemaParseConfiguration(BaseRecord user, String groupPath, String basePath, int maxLines) {
		logger.info("New Test Schema Parse Configuration");
		
		BaseRecord template = null;
		try {
			template = RecordFactory.newInstance("lookupTest");
		}
		catch(ModelNotFoundException | FieldException e) {
			logger.error(e);
		}
		
		List<ParseMap> map = new ArrayList<>();
		map.add(new ParseMap("stringValue", 0));
		map.add(new ParseMap("dateValue", 2, "dd-MMM-yy"));

		ParseConfiguration cfg = new ParseConfiguration();
		cfg.setSchema("lookupTest");
		cfg.setCsvFormat(CSVFormat.Builder.create().setDelimiter(',').setSkipHeaderRecord(true).setQuote('"').setTrim(true).build());
		cfg.setFields(map.toArray(new ParseMap[0]));
		cfg.setFilePath(basePath + "/tmp.csv");
		cfg.setGroupPath(groupPath);
		cfg.setMaxCount(maxLines);
		cfg.setOwner(user);
		cfg.setTemplate(template);
		
		return cfg;
	}
	
	public ModelSchema getTestSchema1() {

		ModelSchema ms = RecordFactory.getSchema("lookupTest");
		if(ms != null) return ms;
		return RecordFactory.importSchemaFromUser("lookupTest", """
{
"name": "lookupTest",
"shortName": "lt",
"inherits": ["data.directory"],
"group": "Lookup",
"version": "1.0",
"fields": [
	
	{
		"name": "stringValue",
		"type": "string",
		"maxLength": 32
	},

	{
		"name": "dateValue",
		"type": "timestamp"
	}

]}
		""");
	}
}
