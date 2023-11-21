package org.cote.accountmanager.parsers.data;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.parsers.IParseWriter;
import org.cote.accountmanager.parsers.ParseConfiguration;
import org.cote.accountmanager.record.BaseRecord;

public class DataParseWriter implements IParseWriter {
	public static final Logger logger = LogManager.getLogger(DataParseWriter.class);
	private int batchSize = 2000;
	
	@Override
	public int getBatchSize() {
		// TODO Auto-generated method stub
		return batchSize;
	}

	@Override
	public int write(ParseConfiguration cfg, List<BaseRecord> records) {
		long start = System.currentTimeMillis();
		
		int created = IOSystem.getActiveContext().getAccessPoint().create(cfg.getOwner(), records.toArray(new BaseRecord[0]), true);
		long stop = System.currentTimeMillis();
		// logger.info("Wrote: " + created + " in " + (stop - start) + "ms");
		return created;
	}

}
