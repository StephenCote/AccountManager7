package org.cote.accountmanager.parsers;

import java.util.List;

import org.cote.accountmanager.record.BaseRecord;

public interface IParseWriter {
	public int getBatchSize();
	public int write(ParseConfiguration cfg, List<BaseRecord> records);
}
