package org.cote.accountmanager.record;

import com.fasterxml.jackson.databind.module.SimpleModule;

public class RecordSerializerConfig {

	public static SimpleModule getCondensedUnfilteredModule() {
		RecordSerializer ser = new RecordSerializer();
		ser.setFilterVirtual(false);
		ser.setFilterForeign(false);
		ser.setFilterEphemeral(false);
		ser.setCondenseFields(true);
		SimpleModule unfilteredModule = new SimpleModule();
		unfilteredModule.addSerializer(LooseRecord.class, ser);
		
		return unfilteredModule;
	}
	
	public static SimpleModule getUnfilteredModule() {
		RecordSerializer ser = new RecordSerializer();
		ser.setFilterVirtual(false);
		ser.setFilterForeign(true);
		SimpleModule unfilteredModule = new SimpleModule();
		unfilteredModule.addSerializer(LooseRecord.class, ser);
		return unfilteredModule;
	}
	public static SimpleModule getUncondensedModule() {
		RecordSerializer ser = new RecordSerializer();
		ser.setFilterVirtual(false);
		ser.setFilterForeign(true);
		ser.setCondenseModelDeclarations(false);
		SimpleModule unfilteredModule = new SimpleModule();
		unfilteredModule.addSerializer(LooseRecord.class, ser);
		return unfilteredModule;
	}
	public static SimpleModule getFilteredModule() {
		RecordSerializer ser = new RecordSerializer();
		ser.setFilterVirtual(true);
		ser.setFilterForeign(false);
		ser.setFilterEphemeral(true);
		SimpleModule filteredModule = new SimpleModule();
		filteredModule.addSerializer(LooseRecord.class, ser);
		return filteredModule;
	}
	public static SimpleModule getForeignModule() {
		RecordSerializer ser = new RecordSerializer();
		ser.setFilterForeign(true);
		SimpleModule foreignModule = new SimpleModule();
		foreignModule.addSerializer(LooseRecord.class, ser);
		return foreignModule;
	}
	public static SimpleModule getForeignFilteredModule() {
		RecordSerializer ser = new RecordSerializer();
		ser.setFilterVirtual(true);
		ser.setFilterForeign(true);
		ser.setFilterEphemeral(true);
		SimpleModule foreignFilteredModule = new SimpleModule();
		foreignFilteredModule.addSerializer(LooseRecord.class, ser);

		return foreignFilteredModule;
	}
	
	public static SimpleModule getForeignUnfilteredModule() {
		RecordSerializer ser = new RecordSerializer();
		ser.setFilterVirtual(false);
		ser.setFilterForeign(false);
		ser.setFilterEphemeral(false);
		ser.setDecompressByteStore(true);
		SimpleModule  foreignFilteredModule = new SimpleModule();
		foreignFilteredModule.addSerializer(LooseRecord.class, ser);
		
		return foreignFilteredModule;
	}
	
	public static SimpleModule getHiddenForeignUnfilteredModule() {
		RecordSerializer ser = new RecordSerializer();
		ser.setFilterVirtual(true);
		ser.setFilterForeign(false);
		ser.setFilterEphemeral(true);
		ser.setDecompressByteStore(true);
		ser.setHideEmitModel(true);
		SimpleModule  foreignFilteredModule = new SimpleModule();
		foreignFilteredModule.addSerializer(LooseRecord.class, ser);
		
		return foreignFilteredModule;
	}

	
	public static SimpleModule getForeignUnfilteredModuleRecurse() {
		RecordSerializer ser = new RecordSerializer();
		ser.setFilterVirtual(false);
		ser.setFilterForeign(false);
		ser.setFilterEphemeral(false);
		ser.setDecompressByteStore(true);
		ser.setStopRecursion(false);
		ser.setCondenseFields(false);
		SimpleModule  foreignFilteredModule = new SimpleModule();
		foreignFilteredModule.addSerializer(LooseRecord.class, ser);
		
		return foreignFilteredModule;
	}
	
}
