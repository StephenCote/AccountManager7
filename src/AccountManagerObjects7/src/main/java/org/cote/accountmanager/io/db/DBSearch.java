package org.cote.accountmanager.io.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.DatabaseException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.MemoryReader;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.SearchBase;
import org.cote.accountmanager.io.db.cache.CacheDBSearch;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;

public class DBSearch extends SearchBase {
	public static final Logger logger = LogManager.getLogger(DBSearch.class);
	public static boolean ENABLE_STATISTICS = false;
	public static List<String> STATISTICS = new CopyOnWriteArrayList<>();

	public static void enableStatistics(boolean enabled) {
		ENABLE_STATISTICS = enabled;
		CacheDBSearch.ENABLE_STATISTICS = enabled;
		STATISTICS.clear();
		CacheDBSearch.STATISTICS.clear();
	}

	private final DBReader reader;

	public DBSearch(DBReader reader) {
		this.reader = reader;
	}

	public int count(final Query iquery) {

		int count = 0;

		/// Copy the query as it's likely reused to build paginated lists,
		/// And then drop any sort key since it's not needed on the count
		final Query query = new Query(iquery.copyRecord());
		query.releaseKey();
		
		DBStatementMeta sql = null;
		
		try (Connection con = reader.getDataSource().getConnection()){
			query.set(FieldNames.FIELD_SORT_FIELD, null);
			if(ENABLE_STATISTICS) {
				STATISTICS.add(query.key());
			}
			
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
			if(sql != null) {
				logger.error(JSONUtil.exportObject(sql));
			}
			e.printStackTrace();
		}
		
		
		return count;
	}
	
	public QueryResult find(final Query query) throws IndexException, ReaderException {
		
		// logger.info(query.toFullString());
		
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
		if(ENABLE_STATISTICS) {
			STATISTICS.add(query.key());
		}
		
		try (Connection con = reader.getDataSource().getConnection()){
			sql = StatementUtil.getSelectTemplate(query);
			
			PreparedStatement statement = con.prepareStatement(sql.getSql());
			StatementUtil.setStatementParameters(query, statement);
			if(ModelNames.MODEL_AUDIT.equals(query.get(FieldNames.FIELD_TYPE))){
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
				recs.add(rec);
			}
			
			rset.close();
			statement.close();
			query.set(FieldNames.FIELD_EXECUTED, true);
			
		} catch (NullPointerException | ModelException | FieldException | DatabaseException | SQLException | ModelNotFoundException | ValueException e) {
			logger.error(e);
			e.printStackTrace();
			if(sql != null) {
				logger.error(JSONUtil.exportObject(sql));
			}
			throw new ReaderException(e);
		}
		
		final QueryResult res = new QueryResult(query, recs.toArray(new BaseRecord[0]));
		
		/// Only compute the total count if a pagination limit was provided
		int recordCount = query.get(FieldNames.FIELD_RECORD_COUNT);
		if(recordCount > 0) {
			res.setTotalCount(count(query));
		}
		
		return res;
	}
	

}
