package org.cote.accountmanager.io.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.DatabaseException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.MemoryReader;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.SearchBase;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.JSONUtil;

public class DBSearch extends SearchBase {
	public static final Logger logger = LogManager.getLogger(DBSearch.class);

	private final DBReader reader;

	public DBSearch(DBReader reader) {
		this.reader = reader;
	}

	public int count(final Query iquery) {

		int count = 0;

		/// Copy the query as it's likely reused to build paginated lists,
		/// And then drop any sort key and request fields since it's not needed on the count
		final Query query = new Query(iquery.copyRecord());
		query.releaseKey();
		query.setRequest(new String[] {FieldNames.FIELD_ID});
		DBStatementMeta sql = null;
		
		try (Connection con = reader.getDataSource().getConnection()){
			query.set(FieldNames.FIELD_SORT_FIELD, null);
			stats.add(query);
			
			sql = StatementUtil.getCountTemplate(query);
			PreparedStatement statement = con.prepareStatement(sql.getSql());
			StatementUtil.setStatementParameters(query, statement);
			ResultSet rset = statement.executeQuery();
			if(rset.next()) {
				count = rset.getInt(1);
			}
			rset.close();
			statement.close();
			
		} catch (NullPointerException | ModelException | FieldException | DatabaseException | SQLException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			e.printStackTrace();
			if(sql != null) {
				logger.error(JSONUtil.exportObject(sql));
			}
		}
		
		
		return count;
	}
	
	public QueryResult find(final Query query) throws ReaderException {
		
		if(useAlternateIO(query)) {
			return findAlternate(query);
		}
		
		String model = query.get(FieldNames.FIELD_TYPE);
		List<BaseRecord> recs = new ArrayList<>();
		DBStatementMeta sql = null;
		MemoryReader memReader = new MemoryReader();
		if(query.hasQueryField(FieldNames.FIELD_ORGANIZATION_ID) && query.getQueryFields().size() == 1 && query.getJoins().size() == 0) {
			String type = query.get(FieldNames.FIELD_TYPE);
			if(!ModelNames.MODEL_MODEL_SCHEMA.equals(type)) {
				logger.warn("Searching with only an Organization filter for " + type);
			}
		}
		
		stats.add(query);

		try (Connection con = reader.getDataSource().getConnection()){
			sql = StatementUtil.getSelectTemplate(query);

			PreparedStatement statement = con.prepareStatement(sql.getSql());
			StatementUtil.setStatementParameters(query, statement);
			
			if((boolean)query.get(FieldNames.FIELD_DEBUG) == true){
				logger.info(statement);
			}
			
			ResultSet rset = statement.executeQuery();
			boolean inspect = query.get(FieldNames.FIELD_INSPECT);
			while(rset.next()) {
				BaseRecord rec = RecordFactory.newInstance(model, sql.getColumns().toArray(new String[0]));
				StatementUtil.populateRecord(sql, rset, rec);
				if(inspect) {
					memReader.inspect(rec);
				}
				else {
					memReader.read(rec);
				}
				deepRead(model, rec);
				recs.add(rec);
			}
			
			rset.close();
			statement.close();
			query.set(FieldNames.FIELD_EXECUTED, true);
			
		} catch (NullPointerException | ModelException | FieldException | DatabaseException | SQLException | ModelNotFoundException | ValueException e) {
			logger.error(e);
			if((boolean)query.get(FieldNames.FIELD_DEBUG) == true){
				if(sql != null) {
					logger.error(JSONUtil.exportObject(sql));
				}
				
			}
			e.printStackTrace();
			throw new ReaderException(e);
		}
		
		final QueryResult res = new QueryResult(query, recs.toArray(new BaseRecord[0]));
		
		int recordCount = query.get(FieldNames.FIELD_RECORD_COUNT);
		if(recordCount > 0) {
			res.setTotalCount(count(query));
		}
		
		return res;
	}
	
	private void deepRead(String model, BaseRecord rec) {
		MemoryReader mr = new MemoryReader();
		ModelSchema ms = RecordFactory.getSchema(rec.getModel());
		for(FieldType f : rec.getFields()) {
			FieldSchema fs = ms.getFieldSchema(f.getName());
			if(fs.getBaseModel() != null) {
				List<BaseRecord> srec = new ArrayList<>();
				if(fs.getType().equals("model")) {
					srec.add(rec.get(f.getName()));
				}
				else if (fs.getType().equals("list")) {
					srec = new ArrayList<>(rec.get(f.getName()));
				}
				for(BaseRecord c : srec) {
					if(c != null) {
						try {
							mr.read(c);
						} catch (ReaderException e) {
							logger.warn(e.getMessage());
							
						}
						deepRead(fs.getBaseModel(), c);
					}
				}
			}
		}
	}
	

}
