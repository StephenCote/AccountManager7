package org.cote.accountmanager.io;

import javax.sql.DataSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.StoreException;
import org.cote.accountmanager.io.db.DBPathUtil;
import org.cote.accountmanager.io.db.DBReader;
import org.cote.accountmanager.io.db.DBSearch;
import org.cote.accountmanager.io.db.DBWriter;
import org.cote.accountmanager.io.db.cache.CacheDBSearch;
import org.cote.accountmanager.io.file.FilePathUtil;
import org.cote.accountmanager.io.file.FileReader;
import org.cote.accountmanager.io.file.FileSearch;
import org.cote.accountmanager.io.file.FileStore;
import org.cote.accountmanager.io.file.FileWriter;
import org.cote.accountmanager.io.file.cache.CacheFileReader;
import org.cote.accountmanager.io.file.cache.CacheFileSearch;
import org.cote.accountmanager.io.file.cache.CacheFileWriter;
import org.cote.accountmanager.policy.CachePolicyUtil;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.security.AuthorizationUtil;
import org.cote.accountmanager.util.MemberUtil;
import org.cote.accountmanager.util.RecordUtil;

public class IOFactory {
	public static final Logger logger = LogManager.getLogger(IOFactory.class);
	public static String DEFAULT_FILE_BASE = "./am7";

	public static FileStore getStore(String name) {
		return getStore(DEFAULT_FILE_BASE, name);
	}
	
	public static FileStore getStore(String base, String name) {
		// FileIndexer.clearCache();
		/*
		if(IOSystem.getActiveContext() != null && IOSystem.getActiveContext().getIndexManager() != null) {
			IOSystem.getActiveContext().getIndexManager().clearCache();
		}
		*/
		FileStore store = new FileStore(base);
		store.setStorageFileName(name);
		try {
			store.initialize();
		} catch (IndexException e) {
			logger.error(e);
		}
		return store;
	}
	
	public static IReader getReader(RecordIO io) {
		return getReader(io, DEFAULT_FILE_BASE, null, false);
	}

	public static IReader getReader(RecordIO io, String base, FileStore store, boolean followForeignKeys) {
		IReader reader = null;
		if(io == RecordIO.FILE) {
			CacheFileReader creader = new CacheFileReader(base);
			try {
				creader.setStore(store);
			} catch (StoreException e) {
				logger.error(e);
			}
			creader.setFollowForeignKeys(followForeignKeys);
			reader = creader;
		}
		return reader;
	}
	public static IReader getReader(RecordIO io, DataSource source) {
		IReader reader = null;
		if(io == RecordIO.DATABASE) {
			reader = new DBReader(source);
		}
		return reader;
	}
	public static IWriter getWriter(RecordIO io, DataSource source) {
		IWriter reader = null;
		if(io == RecordIO.DATABASE) {
			reader = new DBWriter(source);
		}
		return reader;
	}
	public static IWriter getWriter(RecordIO io) {
		return getWriter(io, null, DEFAULT_FILE_BASE, null);
	}
	public static IWriter getWriter(RecordIO io, IReader reader, String base, FileStore store) {
		IWriter writer = null;
		if(io == RecordIO.FILE) {
			CacheFileWriter cwriter = new CacheFileWriter(base);
			try {
				cwriter.setStore(store);
			} catch (StoreException e) {
				logger.error(e);
			}
			writer = cwriter;
		}
		return writer;
	}
	
	public static ISearch getSearch(IReader reader) {
		if(reader.getRecordIo().equals(RecordIO.FILE)) {
			return new CacheFileSearch((FileReader)reader);
		}
		else if(reader.getRecordIo().equals(RecordIO.DATABASE)) {
			return new CacheDBSearch((DBReader)reader);
		}
		logger.error("getSearch: Unhandled IO type: " + reader.getRecordIo().toString());
		return null;
	}
	
	public static IPath getPathUtil(IReader reader) {
		return getPathUtil(reader, null, null);
	}
	public static IPath getPathUtil(IReader reader, IWriter writer, ISearch search) {
		if(reader.getRecordIo().equals(RecordIO.FILE)) {
			return new FilePathUtil((FileReader)reader, (FileWriter)writer, (FileSearch)search);
		}
		else if(reader.getRecordIo().equals(RecordIO.DATABASE)) {
			return new DBPathUtil((DBReader)reader, (DBWriter)writer, (DBSearch)search);
		}
		logger.error("getPathUtil: Unhandled IO type: " + reader.getRecordIo().toString());
		return null;
	}

	public static MemberUtil getMemberUtil(IReader reader, IWriter writer, ISearch search) {
		return new MemberUtil(reader, writer, search);
	}
	public static AuthorizationUtil getAuthorizationUtil(IReader reader, IWriter writer, ISearch search) {
		return new AuthorizationUtil(reader, writer, search);
	}

	public static RecordUtil getRecordUtil(IReader reader, IWriter writer, ISearch search) {
		return new RecordUtil(reader, writer, search);
	}
	
	public static CachePolicyUtil getPolicyUtil(IReader reader, IWriter writer, ISearch search) {
		return new CachePolicyUtil(reader, writer, search);
	}
	
}
