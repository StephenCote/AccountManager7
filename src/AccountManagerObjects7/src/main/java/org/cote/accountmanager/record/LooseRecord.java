package org.cote.accountmanager.record;

import org.cote.accountmanager.model.field.FieldType;

/*
@JsonSerialize(using = RecordSerializer.class)
@JsonDeserialize(using = RecordDeserializer.class)
*/
public class LooseRecord extends BaseRecord {
	public LooseRecord() {
		//Stream.of(fields, inFields).toList();
		super("loosebase", new FieldType[0]);
	}
}
