package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldFactory;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestSchemaModification extends BaseTest {

	/*
	@Test
	public void TestSchemaCreateDelete() {
		ModelSchema ms = RecordFactory.getCustomSchemaFromResource("junk", "junk");
		assertNotNull("Schema was null", ms);
		//logger.info(JSONUtil.exportObject(ms));
		RecordFactory.releaseCustomSchema("junk");
	}
	*/
	
	@Test
	public void TestSchemaCUDField() {
		RecordFactory.releaseCustomSchema("junk");
		ModelSchema ms = RecordFactory.getCustomSchemaFromResource("junk", "junk");
		assertNotNull("Schema was null", ms);
		ModelSchema ms2 = JSONUtil.importObject(JSONUtil.exportObject(ms), ModelSchema.class);
		assertNotNull("Schema was null", ms2);
		FieldSchema newField = new FieldSchema();
		newField.setName("junkField");
		newField.setType("boolean");
		newField.setDefaultValue(false);
		ms2.getFields().add(newField);
		ms2.setFields(ms2.getFields().stream().filter(f -> !f.getName().equals("deleteJunk")).collect(Collectors.toList()));
		ms2.getFieldSchema("modifyJunk").setMaxLength(127);
		mergeSchemaChanges(ms, ms2);
		RecordFactory.releaseCustomSchema("junk");
	}
	
	private void mergeSchemaChanges(ModelSchema src, ModelSchema mod) {
		if(!src.getName().equals(mod.getName())) {
			logger.error("Unable to change the schema name - yet");
			return;
		}
		
		List<String> srcNames = src.getFields().stream().map(f -> f.getName()).collect(Collectors.toList());
		List<String> modNames = mod.getFields().stream().map(f -> f.getName()).collect(Collectors.toList());
		logger.info(srcNames.stream().collect(Collectors.joining(", ")));
		logger.info(modNames.stream().collect(Collectors.joining(", ")));
		List<String> addFields = mod.getFields().stream().filter(f -> !srcNames.contains(f.getName())).map(f -> f.getName()).collect(Collectors.toList());
		List<String> removeFields = src.getFields().stream().filter(f -> !modNames.contains(f.getName())).map(f -> f.getName()).collect(Collectors.toList());
		
		logger.info(srcNames.size() + " to " + modNames.size());
		logger.info(addFields.size() + " to add; " + removeFields.size() + " to remove");
		/*
		List<FieldSchema> srcFlds = src.getFields();
		List<FieldSchema> addFlds = mod.get
		List<FieldSchema> srcFlds = src.getFields();
		*/
		assertTrue("Expected at least one  field to add", addFields.size() > 0);
		assertTrue("Expected at least one field to remove", removeFields.size() > 0);
		
		assertTrue(addField(src, mod.getFieldSchema(addFields.get(0))));
		assertTrue(removeField(src, src.getFieldSchema(addFields.get(0))));
		
		List<String> changedFields = new ArrayList<>();
		for(FieldSchema f : src.getFields()) {
			if(f.isIdentity() || addFields.contains(f.getName()) || removeFields.contains(f.getName())){
				continue;
			}

			FieldSchema f2 = mod.getFieldSchema(f.getName());
			if(f2.getMaxLength() != f.getMaxLength()) {
				changedFields.add(f2.getName());
			}
		}
		assertTrue("Expected at least one  field to modify", changedFields.size() > 0);
		assertTrue(changeField(src, src.getFieldSchema(changedFields.get(0))));
		updateSchema(src);
	} 
	
	private boolean changeField(ModelSchema ms, FieldSchema  fld) {
		boolean up = executeUpdate(getChangeField(ms, fld));
		assertTrue("Expected an update", up);
		return up;
	}
	
	private boolean addField(ModelSchema ms, FieldSchema fs) {
		boolean up = executeUpdate(getAddField(ms, fs));
		assertTrue("Expected an update", up);
		ms.getFields().add(fs);
		return up;
	}
	
	private boolean removeField(ModelSchema ms, FieldSchema fs) {
		boolean up = executeUpdate(getRemoveField(ms, fs));
		assertTrue("Expected an update", up);
		ms.setFields(ms.getFields().stream().filter(f -> !f.getName().equals(fs.getName())).collect(Collectors.toList()));
		
		return up;
	}

	
	private boolean updateSchema(ModelSchema ms) {
		Query q = RecordFactory.getSchemaQuery(ms.getName());
		q.planMost(false);
		BaseRecord ioSchema = IOSystem.getActiveContext().getSearch().findRecord(q);
		if(ioSchema == null) {
			logger.error("Failed to find schema");
			return false;
		}
		ioSchema.setValue(FieldNames.FIELD_SCHEMA, JSONUtil.exportObject(ms).getBytes());
		return ioContext.getRecordUtil().updateRecord(ioSchema);

	}
	
	private boolean executeUpdate(String sql) {
		boolean up = false;
		try (Connection con = IOSystem.getActiveContext().getDbUtil().getDataSource().getConnection(); Statement st = con.createStatement();){
			st.executeUpdate(sql);
			up = true;
		}
		catch (SQLException e) {
			logger.error(e);
	    }
		return up;
	}

	private String getChangeField(ModelSchema ms, FieldSchema fld) {
		String table = ioContext.getDbUtil().getTableName(ms.getName());
		return "ALTER TABLE " + table + " ALTER " + fld.getName() + " TYPE " + ioContext.getDbUtil().getDataType(fld, fld.getFieldType()) + ";";
	}
	
	private String getAddField(ModelSchema ms, FieldSchema fld) {
		String table = ioContext.getDbUtil().getTableName(ms.getName());
		String col = ioContext.getDbUtil().generateSchemaLine(null,  ms, fld);
		return "ALTER TABLE " + table + " ADD " + col + ";";
	}
	
	private String getRemoveField(ModelSchema ms, FieldSchema fld) {
		String table = ioContext.getDbUtil().getTableName(ms.getName());
		return "ALTER TABLE " + table + " DROP " + fld.getName() + ";";
	}

}
