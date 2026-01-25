package org.cote.accountmanager.io;

import java.io.File;
import java.security.Security;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.StoreException;
import org.cote.accountmanager.exceptions.SystemException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.io.file.FileIndexManager;
import org.cote.accountmanager.io.file.FileReader;
import org.cote.accountmanager.io.file.FileStore;
import org.cote.accountmanager.io.file.FileWriter;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.util.DirectoryUtil;
import org.cote.accountmanager.util.RecordUtil;

public class IOSystem {

	public static final Logger logger = LogManager.getLogger(IOSystem.class);
	private static boolean followForeignKeys = false;
	private static IOContext activeContext = null;
	private static boolean open = false;
	
	static {
		Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
	}
	
	private IOSystem() {

	}
	
	
	
	public static boolean isOpen() {
		return open;
	}

	public static boolean isInitialized() {
		return (getActiveContext() != null && getActiveContext().isInitialized());
	}


	public static IOContext open(RecordIO ioType) throws SystemException {
		return open(ioType, null);
	}

	public static IOContext getActiveContext() {
		return activeContext;
	}
	
	private static void cleanupDirectory(String subPath) {
		DirectoryUtil du = new DirectoryUtil(IOFactory.DEFAULT_FILE_BASE + subPath);
		List<File> files = du.dir(null, true);
		for(File f : files) {
			logger.info("Cleanup: " + f.getName());
			if(!f.delete()) {
				logger.warn("Failed to delete " + f.getName());
			}
		}		
	}

	public static IOContext open(RecordIO ioType, IOProperties properties) throws SystemException {
		
		open = false;
		
		if(activeContext != null) {
			logger.error("An active context already exists");
			return null;
		}
		
		IReader reader = null;
		IWriter writer = null;
		ISearch search = null;
		FileIndexManager fim = null;
		FileStore store = null;
		DBUtil dbUtil = null;
		boolean checkPersistedSchema = false;
		ModelNames.loadModels();
		
		if(properties.isReset()) {
			cleanupDirectory("/.queue/");
			cleanupDirectory("/.jks/");
			cleanupDirectory("/.streams/");
			cleanupDirectory("/.vault/");
		}

		if(ioType == RecordIO.FILE) {
			
			fim = new FileIndexManager(IOFactory.DEFAULT_FILE_BASE);
			reader = IOFactory.getReader(ioType, IOFactory.DEFAULT_FILE_BASE, store, followForeignKeys);
			writer = IOFactory.getWriter(ioType, reader, IOFactory.DEFAULT_FILE_BASE, store);
			search = IOFactory.getSearch(reader);
		}
		else if(ioType == RecordIO.DATABASE) {
			if(properties == null || (properties.getDataSourceUrl() == null && properties.getJndiName() == null)) {
				logger.error("Missing IOProperties");
				return null;
			}
			dbUtil = DBUtil.getInstance(properties);
			if(!dbUtil.testConnection()) {
				throw new SystemException("Failed to establish database connection");
			}
			if(!dbUtil.haveTable(ModelNames.MODEL_ORGANIZATION) || properties.isSchemaCheck() || properties.isReset()) {
				logger.info("Scanning model schemas");
				dbUtil.createExtensions();
				checkPersistedSchema = true;
				for(String m : ModelNames.MODELS) {
					ModelSchema schema = RecordFactory.getSchema(m);
					if(RecordUtil.isIdentityModel(schema)) {
						if(!dbUtil.isConstrained(schema) && (!dbUtil.haveTable(schema, m) || properties.isReset())) {
							String dbSchema = (properties.isReset() ? dbUtil.generateSchema(schema) : dbUtil.generateNewSchemaOnly(schema));
							if(dbSchema != null) {
								dbUtil.execute(dbSchema);
							}
							else {
								logger.error("**** Schema not defined for " + m);
							}
						}
						else if(!dbUtil.isConstrained(schema) && !properties.isReset()) {
							/// Patch existing tables with missing columns
							List<String> patches = dbUtil.generatePatchSchema(schema);
							for(String patch : patches) {
								logger.info("Schema patch: " + patch);
								dbUtil.execute(patch);
							}
						}
					}
					else {
						logger.debug("Skip model without identity: " + m);
					}
				}

			}
			
			
			
			
			reader = IOFactory.getReader(ioType, dbUtil.getDataSource());
			writer = IOFactory.getWriter(ioType, dbUtil.getDataSource());
			search = IOFactory.getSearch(reader);
		}
		// logger.info("Initialize context");
		activeContext = new IOContext(ioType, reader, writer, search);
		activeContext.setIndexManager(fim);
		activeContext.setDbUtil(dbUtil);
		
		if(ioType == RecordIO.FILE) {

			if(properties != null && properties.getDataSourceName() != null) {
				store = IOFactory.getStore(properties.getDataSourceName());
			}
			try {
				((FileReader)reader).setStore(store);
				((FileWriter)writer).setStore(store);
				activeContext.setStore(store);
				fim.setStore(store);
			} catch (StoreException e) {
				logger.error(e);
			}
		}
		
		for(String org : OrganizationContext.DEFAULT_ORGANIZATIONS) {
			OrganizationContext oc = IOSystem.getActiveContext().getOrganizationContext(org, OrganizationEnumType.valueOf(org.substring(1).toUpperCase()));
			if(!oc.isInitialized()) {
				logger.info("Creating " + OrganizationEnumType.valueOf(org.substring(1).toUpperCase()) + " " + org);
				try {
					oc.createOrganization();
				} catch (NullPointerException | SystemException e) {
					logger.error(e);
					e.printStackTrace();
				}
			}
		}
		
		if(checkPersistedSchema && ioType == RecordIO.DATABASE) {
			List<String> models = ModelNames.listCustomModels();
			if(models.size() == 0) {
				logger.info("Loading models");
				for(String m : ModelNames.MODELS) {
					ModelSchema schema = RecordFactory.getCustomSchemaFromResource(m, m);
				}
			}
		}
		
		/// Custom models are stored in the /System organization
		///
		for(String s : ModelNames.getCustomModelNames()) {
			RecordFactory.model(s);
		}
		
		open = true;
		
		return activeContext;
	}
	
	public static void close() {
		close(activeContext);
	}
	public static void close(IOContext context) {
		
		open = false;
		
		if(context != null) {
			try {
				if(context.getQueue() != null) {
					context.getQueue().requestStop();
				}
				if(context.getTaskQueue() != null) {
					context.getTaskQueue().requestStop();
				}
				if(context.getIndexManager() != null) {
					context.getIndexManager().flush();
					context.getIndexManager().clearCache();
				}
				if(context.getReader() != null) {
					context.getReader().close();
				}
				if(context.getSearch() != null) {
					context.getSearch().close();
				}
				if(context.getPolicyUtil() != null) {
					context.getPolicyUtil().close();
				}
				if(context.getStore() != null) {
					context.getStore().close();
				}
				else if(context.getIoType() == RecordIO.FILE) {
					//FileIndexer.clearCache();
				}
				if(context.getWriter() != null) {
					context.getWriter().close();
				}

			}
			catch(ReaderException | WriterException e) {
				logger.error(e);
				
			}
		}
		CacheUtil.clearCache();
		activeContext = null;
	}
	
}
