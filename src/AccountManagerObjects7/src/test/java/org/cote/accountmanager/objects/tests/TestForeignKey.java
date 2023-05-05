package org.cote.accountmanager.objects.tests;


import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.IWriter;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestForeignKey extends BaseTest {
	
	/*
	 * The ForeignKey option works as follows:
	 * Instead of deeply embedding complex objects (via type == 'model'), or carrying around only the foreign key (via propId=###), the model can dynamically switch between both when getting and setting values, and serializing and deserializing:
	 * Given some object:
	 * {
	 * 	model: "link",
	 * 	fields: [
	 * 		{
	 * 			name: "link",
	 * 			baseModel: "$self",
	 * 			type: "model",
	 * 			foreign: true
	 * 		}
	 * 	]
	 * }
	 * 
	 * When the field schema
	 * 
	 * 
	 */

	private BaseRecord newLink(String name) {
		BaseRecord rec = null;
		try {
			rec = RecordFactory.model("link").newInstance();
			rec.set(FieldNames.FIELD_NAME, name);
			rec.set(FieldNames.FIELD_ORGANIZATION_ID, orgContext.getOrganizationId());
		} catch (FieldException | ModelNotFoundException | ValueException  e) {
			logger.error(e);
		}
		return rec;
	}
	
	@Test
	public void TestGenesisLink() {
		
		BaseRecord link = null;
		BaseRecord upLink = null;
		BaseRecord downLink = null;
		BaseRecord leftLink = null;
		BaseRecord rightLink = null;
		
		BaseRecord item1 = null;
		BaseRecord item2 = null;
		BaseRecord item3 = null;
		
		boolean error = false;
		IWriter writer = ioContext.getWriter();
		IReader reader = ioContext.getReader();
		String nameSuffix = UUID.randomUUID().toString();
		RecordFactory.importSchema("link");
		try {
			link = newLink("link-" + nameSuffix);
			upLink = newLink("up-" + nameSuffix);
			downLink = newLink("down-" + nameSuffix);
			leftLink = newLink("left-" + nameSuffix);
			rightLink = newLink("right-" + nameSuffix);
			item1 = newLink("Item 1-" + nameSuffix);
			item2 = newLink("Item 2-" + nameSuffix);
			item3 = newLink("Item 3-" + nameSuffix);

			writer.write(upLink);
			writer.write(downLink);
			writer.write(leftLink);
			writer.write(rightLink);
			
			writer.write(item1);
			writer.write(item2);
			writer.write(item3);

			link.set("up.id", upLink);
			link.set("down", downLink);
			link.set("previous", leftLink);
			link.set("next", rightLink);
			
			List<BaseRecord> lst = link.get("list");
			lst.add(item1);
			lst.add(item2);
			lst.add(item3);
			
			writer.write(link);
			
			writer.flush();
			
		} catch (ClassCastException | ArrayIndexOutOfBoundsException | FieldException | ModelNotFoundException | ValueException | WriterException e) {
			logger.error(e.getMessage());
			
			error = true;
		}
		assertFalse("Encountered an error", error);
		FieldType f = link.getField("up");
		assertNotNull("Field is null", f);
		
		long upfk = link.get("up.id");
		assertTrue("Id was null", upfk > 0L);
		logger.info("Embedded value = " + upfk);
		
		//logger.info("Test: " + "testFK".endsWith("FK") + " " + "testFK".substring(0, "testFK".lastIndexOf("FK")));
		
		String ser = JSONUtil.exportObject(link, RecordSerializerConfig.getForeignModule());
		logger.info(ser);
		
		BaseRecord irec = JSONUtil.importObject(ser, LooseRecord.class, RecordDeserializerConfig.getForeignModule());
		List<BaseRecord> ilst = irec.get("list");
		logger.info("Retrieved: " + ilst.size());
		//String ser2 = JSONUtil.exportObject(irec, RecordSerializerConfig.getUnfilteredModule());
		
	}
}
