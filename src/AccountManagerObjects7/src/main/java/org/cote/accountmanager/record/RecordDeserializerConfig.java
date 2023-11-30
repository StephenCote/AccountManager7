package org.cote.accountmanager.record;

import com.fasterxml.jackson.databind.module.SimpleModule;

public class RecordDeserializerConfig {

	public static SimpleModule getModule() {
		return getForeignModule(null);
	}

	public static SimpleModule getForeignModule() {
		return getForeignModule(null);
	}
	public static SimpleModule getForeignModule(String[] foreignFields) {
		RecordDeserializer<LooseRecord> ser = new RecordDeserializer<>();
		ser.setFkImportFields(foreignFields);
		ser.setAccessForeignKey(true);
		SimpleModule mod = new SimpleModule();
		mod.addDeserializer(LooseRecord.class, ser);
		ser.applyModule(mod);
		return mod;
	}
	
	public static SimpleModule getUnfilteredModule() {
		RecordDeserializer<LooseRecord> ser = new RecordDeserializer<>();
		SimpleModule unfilteredModule = new SimpleModule();
		unfilteredModule.addDeserializer(LooseRecord.class, ser);
		ser.applyModule(unfilteredModule);
		return unfilteredModule;
	}
	public static SimpleModule getFilteredModule() {
		RecordDeserializer<LooseRecord> ser = new RecordDeserializer<>();
		SimpleModule filteredModule = new SimpleModule();
		filteredModule.addDeserializer(LooseRecord.class, ser);
		ser.applyModule(filteredModule);
		return filteredModule;
	}
}
