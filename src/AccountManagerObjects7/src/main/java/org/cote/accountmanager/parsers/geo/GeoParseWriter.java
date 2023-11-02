package org.cote.accountmanager.parsers.geo;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.parsers.IParseWriter;
import org.cote.accountmanager.parsers.ParseConfiguration;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

public class GeoParseWriter implements IParseWriter {
	public static final Logger logger = LogManager.getLogger(GeoParseWriter.class);
	private int batchSize = 2000;
	
	@Override
	public int getBatchSize() {
		// TODO Auto-generated method stub
		return batchSize;
	}

	@Override
	public int write(ParseConfiguration cfg, List<BaseRecord> records) {
		long start = System.currentTimeMillis();
		
		if(cfg.getParentQuery() != null) {
			QueryResult qr = cfg.getParentQueryResult();
			if(qr == null) {
				logger.info("Parent query: " + cfg.getParentQuery().toFullString());
				qr = IOSystem.getActiveContext().getAccessPoint().list(cfg.getOwner(), cfg.getParentQuery());
				cfg.setParentQueryResult(qr);
			}
			//logger.info("Mapping " + qr.getCount() + " possible parent items");
			List<BaseRecord> qra = Arrays.asList(qr.getResults());
			for(BaseRecord rec : records) {
				if(cfg.getInterceptor() != null) {
					cfg.getInterceptor().filterParent(cfg, qra, rec);
				}
				else {
					String mapF = rec.get(cfg.getMapField());
					if(mapF != null && mapF.indexOf(".") > -1) {
						try {
							String parentCode = mapF.substring(0, mapF.lastIndexOf("."));
							Optional<BaseRecord> orec = qra.stream().filter(p -> parentCode.equals(p.get(cfg.getParentMapField()))).findFirst();
							if(orec.isPresent()) {
								BaseRecord prec = orec.get();
								rec.set(FieldNames.FIELD_PARENT_ID, prec.get(FieldNames.FIELD_ID));
							}
						} catch (ArrayIndexOutOfBoundsException | FieldException | ValueException | ModelNotFoundException e) {
							logger.error(e);
							e.printStackTrace();
							break;
						}
					}
				}
			}
		}
		
		int created = IOSystem.getActiveContext().getAccessPoint().create(cfg.getOwner(), records.toArray(new BaseRecord[0]), true);
		long stop = System.currentTimeMillis();
		// logger.info("Wrote: " + created + " in " + (stop - start) + "ms");
		return created;
	}

}
