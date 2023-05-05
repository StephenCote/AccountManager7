package org.cote.accountmanager.record;

import com.fasterxml.jackson.databind.module.SimpleModule;

public class RecordSerializerConfig {

	/*
	private static SimpleModule unfilteredModule = null;
	private static SimpleModule filteredModule = null;
	private static SimpleModule foreignModule = null;
	private static SimpleModule foreignFilteredModule = null;
	*/
	public static SimpleModule getUnfilteredModule() {
		SimpleModule unfilteredModule = null;
		if(unfilteredModule == null) {
			RecordSerializer ser = new RecordSerializer();
			ser.setFilterVirtual(false);
			ser.setFilterForeign(true);
			unfilteredModule = new SimpleModule();
			unfilteredModule.addSerializer(LooseRecord.class, ser);
		}
		return unfilteredModule;
	}
	public static SimpleModule getUncondensedModule() {
		SimpleModule unfilteredModule = null;
		if(unfilteredModule == null) {
			RecordSerializer ser = new RecordSerializer();
			ser.setFilterVirtual(false);
			ser.setFilterForeign(true);
			ser.setCondenseModelDeclarations(false);
			unfilteredModule = new SimpleModule();
			unfilteredModule.addSerializer(LooseRecord.class, ser);
		}
		return unfilteredModule;
	}
	public static SimpleModule getFilteredModule() {
		SimpleModule filteredModule = null;
		if(filteredModule == null) {
			RecordSerializer ser = new RecordSerializer();
			ser.setFilterVirtual(true);
			ser.setFilterForeign(false);
			filteredModule = new SimpleModule();
			filteredModule.addSerializer(LooseRecord.class, ser);
		}
		return filteredModule;
	}
	public static SimpleModule getForeignModule() {
		SimpleModule foreignModule = null;
		if(foreignModule == null) {
			RecordSerializer ser = new RecordSerializer();
			//ser.setFilterVirtual(true);
			ser.setFilterForeign(true);
			foreignModule = new SimpleModule();
			foreignModule.addSerializer(LooseRecord.class, ser);
		}
		return foreignModule;
	}
	public static SimpleModule getForeignFilteredModule() {
		SimpleModule foreignFilteredModule = null;
		if(foreignFilteredModule == null) {
			RecordSerializer ser = new RecordSerializer();
			ser.setFilterVirtual(true);
			ser.setFilterForeign(true);
			ser.setFilterEphemeral(true);
			foreignFilteredModule = new SimpleModule();
			foreignFilteredModule.addSerializer(LooseRecord.class, ser);
		}
		return foreignFilteredModule;
	}
	
	public static SimpleModule getForeignUnfilteredModule() {
		SimpleModule foreignFilteredModule = null;
		if(foreignFilteredModule == null) {
			RecordSerializer ser = new RecordSerializer();
			ser.setFilterVirtual(false);
			ser.setFilterForeign(false);
			ser.setFilterEphemeral(false);
			foreignFilteredModule = new SimpleModule();
			foreignFilteredModule.addSerializer(LooseRecord.class, ser);
		}
		return foreignFilteredModule;
	}
	
}
