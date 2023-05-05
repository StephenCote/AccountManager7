package org.cote.accountmanager.record;

import com.fasterxml.jackson.databind.module.SimpleModule;

public class RecordDeserializerConfig {
	/*
	private static SimpleModule unfilteredModule = null;
	private static SimpleModule filteredModule = null;
	*/
	public static SimpleModule getModule() {
		return getForeignModule(null);
	}

	public static SimpleModule getForeignModule() {
		return getForeignModule(null);
	}
	public static SimpleModule getForeignModule(String[] foreignFields) {

		RecordDeserializer<LooseRecord> ser = new RecordDeserializer<>();
		//ser.setReader(reader);
		ser.setFkImportFields(foreignFields);
		ser.setAccessForeignKey(true);
		SimpleModule mod = new SimpleModule();
		mod.addDeserializer(LooseRecord.class, ser);
		ser.applyModule(mod);
		return mod;
	}
	
	public static SimpleModule getUnfilteredModule() {
		SimpleModule unfilteredModule = null;
		//if(unfilteredModule == null) {
			RecordDeserializer<LooseRecord> ser = new RecordDeserializer<>();
			unfilteredModule = new SimpleModule();
			unfilteredModule.addDeserializer(LooseRecord.class, ser);
			ser.applyModule(unfilteredModule);
		//}
		return unfilteredModule;
	}
	public static SimpleModule getFilteredModule() {
		SimpleModule filteredModule = null;
		//if(filteredModule == null) {
			RecordDeserializer<LooseRecord> ser = new RecordDeserializer<>();
			//ser.setFilterVirtual(true);
			filteredModule = new SimpleModule();
			filteredModule.addDeserializer(LooseRecord.class, ser);
			ser.applyModule(filteredModule);
		//}
		return filteredModule;
	}
}
