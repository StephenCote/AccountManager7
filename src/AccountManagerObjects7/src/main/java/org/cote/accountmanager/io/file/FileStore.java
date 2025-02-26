package org.cote.accountmanager.io.file;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.JsonWriter;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.JSONUtil;

import net.sf.sevenzipjbinding.ExtractOperationResult;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.IInStream;
import net.sf.sevenzipjbinding.IOutCreateArchive7z;
import net.sf.sevenzipjbinding.IOutCreateCallback;
import net.sf.sevenzipjbinding.IOutItemAllFormats;
import net.sf.sevenzipjbinding.IOutUpdateArchive;
import net.sf.sevenzipjbinding.ISequentialInStream;
import net.sf.sevenzipjbinding.ISequentialOutStream;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipException;
import net.sf.sevenzipjbinding.impl.OutItemFactory;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream;
import net.sf.sevenzipjbinding.simple.ISimpleInArchive;
import net.sf.sevenzipjbinding.simple.ISimpleInArchiveItem;
import net.sf.sevenzipjbinding.util.ByteArrayStream;

/// Based a lot on the example code here : https://sevenzipjbind.sourceforge.net/compression_snippets.html#update-add-remove-items
public class FileStore {
	public static final Logger logger = LogManager.getLogger(FileStore.class);

	private String storageBase = null;
	private String storageFile = null;
	private JsonWriter writer = null;
	// private FileIndexer indexer = null;
	private String storageFileName = "am6data.7z";
	private boolean initialized = false;
	
	public FileStore(String storageBase) {
		this.storageBase = storageBase;
		this.storageFile = storageBase + "/" + storageFileName;
		this.writer = new JsonWriter();
	}

	public boolean isInitialized() {
		return initialized;
	}

	public boolean initialize() throws IndexException {
		initialized = true;
		return initialized;
	}
	
	public void close() {
		this.initialized = false;
	}
	
	public String getStorageFileName() {
		return storageFileName;
	}
	
	

	

	public FileIndexer getIndexer(String model) {
		return IOSystem.getActiveContext().getIndexManager().getInstance(model);
	}

	public void setStorageFileName(String storageFileName) {
		this.storageFileName = storageFileName;
		this.storageFile = storageBase + "/" + storageFileName;
	}

	public String getStoreName(BaseRecord rec) {
		String name = null;
		if(rec.hasField(FieldNames.FIELD_OBJECT_ID)) {

			name = rec.get(FieldNames.FIELD_OBJECT_ID);
		}
		else if(rec.hasField(FieldNames.FIELD_ID)) {
			name = rec.getAMModel() + "-" + Long.toString(rec.get(FieldNames.FIELD_ID));
		}
		return name;
		
	}
	public String getStoreName(IndexEntry entry) {
		String name = null;
		if(entry.getValue(FieldNames.FIELD_OBJECT_ID, null) != null) {

			name = entry.getValue(FieldNames.FIELD_OBJECT_ID, null);
		}
		else if(entry.getValue(FieldEnumType.LONG, FieldNames.FIELD_ID, 0L) > 0L) {
			name = entry.get(FieldNames.FIELD_TYPE) + "-" + Long.toString(entry.getValue(FieldEnumType.LONG, FieldNames.FIELD_ID, 0L));
		}
		return name;
	}

	public synchronized boolean remove(String objectId) {
		boolean outBool = false;
		RandomAccessFile raf = null;
		IInArchive inArchive = null;
		RandomAccessFile outRaf = null;
		IOutUpdateArchive<IOutItemAllFormats> outArchive = null;
		List<Closeable> closeables = new ArrayList<Closeable>();
		File f = new File(storageFile);
		if(!f.exists()) {
			logger.error("Storage file " + storageFile + " doesn't exist");
			return false;
		}
		try {
			raf = new RandomAccessFile(storageFile, "r");
			closeables.add(raf);
			inArchive = SevenZip.openInArchive(null, new RandomAccessFileInStream(raf));
			int index = -1;
			ISimpleInArchive simpleInArchive = inArchive.getSimpleInterface();
			for (int i = 0; i < simpleInArchive.getArchiveItems().length; i++) {
				ISimpleInArchiveItem item = simpleInArchive.getArchiveItem(i);
				String fileName = objectId + ".json";
				if (!item.isFolder() && item.getPath().equals(fileName)) {
					index = i;
					break;
				}
			}
			if (index > -1) {
				outRaf = new RandomAccessFile(storageFile, "rw");
				closeables.add(outRaf);
				outArchive = inArchive.getConnectedOutArchive();

				outArchive.updateItems(new RandomAccessFileOutStream(outRaf), inArchive.getNumberOfItems() - 1,
						new RemoveCallback(index));
				outBool = true;
			}

		} catch (Exception e) {
			logger.error(e);
			
		} finally {
			for (int i = closeables.size() - 1; i >= 0; i--) {
				try {
					closeables.get(i).close();
				} catch (Throwable e) {
					System.err.println("Error closing resource: " + e);

				}
			}
			if(inArchive != null) {
				try {
					inArchive.close();
				} catch (SevenZipException e) {
					logger.error(e);
				}
			}
		}
		return outBool;
	}

	public synchronized byte[] get(String objectId) {
		RandomAccessFile randomAccessFile = null;
		IInArchive inArchive = null;
		final byte[][] outData = new byte[1][];
		File f = new File(storageFile);
		if(!f.exists()) {
			logger.warn("Storage file " + storageFile + " doesn't exist");
			return null;
		}
		try {
			randomAccessFile = new RandomAccessFile(storageFile, "r");
			inArchive = SevenZip.openInArchive(null, new RandomAccessFileInStream(randomAccessFile));
			ISimpleInArchive simpleInArchive = inArchive.getSimpleInterface();

			for (ISimpleInArchiveItem item : simpleInArchive.getArchiveItems()) {
				String fileName = objectId + ".json";
				if (!item.isFolder() && item.getPath().equals(fileName)) {
					ExtractOperationResult result;

					result = item.extractSlow(new ISequentialOutStream() {
						public int write(byte[] data) throws SevenZipException {
							outData[0] = data;
							return data.length;
						}
					});

					if (result != ExtractOperationResult.OK) {
						logger.error("Error extracting item: " + result);
					}
				}
			}

		} catch (Exception e) {
			logger.error(e);
			
		} finally {
			if (inArchive != null) {
				try {
					inArchive.close();
				} catch (SevenZipException e) {
					logger.error("Error closing archive: " + e);
				}
			}
			if (randomAccessFile != null) {
				try {
					randomAccessFile.close();
				} catch (IOException e) {
					logger.error("Error closing file: " + e);
				}
			}
		}
		return outData[0];
	}
	
	public synchronized boolean update(BaseRecord[] records) throws IndexException {
		boolean outBool = false;
		RandomAccessFile raf = null;
		IInArchive inArchive = null;
		RandomAccessFile outRaf = null;
		IOutUpdateArchive<IOutItemAllFormats> outArchive = null;
		List<Closeable> closeables = new ArrayList<Closeable>();
		File f = new File(storageFile);
		int[] indices = new int[records.length];
		
		if(!f.exists()) {
			logger.error("Storage file " + storageFile + " doesn't exist");
			return false;
		}
		for(BaseRecord rec : records) {
			String mod = rec.getAMModel();
			if(rec.hasField(FieldNames.FIELD_INDEX_MODEL) && rec.get(FieldNames.FIELD_INDEX_MODEL) != null) {
				mod = rec.get(FieldNames.FIELD_INDEX_MODEL);
			}
			IndexEntry entry = getIndexer(mod).findIndexEntry(rec);
			if(entry == null) {
				logger.error(rec.toString());
				throw new IndexException(IndexException.INDEX_ENTRY_NOT_FOUND);
			}
		}

		try {
			raf = new RandomAccessFile(storageFile, "r");
			closeables.add(raf);
			inArchive = SevenZip.openInArchive(null, new RandomAccessFileInStream(raf));
			int match = 0;
			ISimpleInArchive simpleInArchive = inArchive.getSimpleInterface();
			for (int i = 0; i < simpleInArchive.getArchiveItems().length; i++) {
				ISimpleInArchiveItem item = simpleInArchive.getArchiveItem(i);
				for(int p = 0; p < records.length; p++) {
					BaseRecord record = records[p];
					String fileName = getStoreName(record) + ".json";
					if (!item.isFolder() && item.getPath().equals(fileName)) {
						indices[p] = i;
						match++;
						break;
					}
				}
				if(match == records.length) {
					break;
				}
			}
			if (match > 0) {
				outRaf = new RandomAccessFile(storageFile, "rw");
				closeables.add(outRaf);
				outArchive = inArchive.getConnectedOutArchive();
				outArchive.updateItems(new RandomAccessFileOutStream(outRaf), inArchive.getNumberOfItems(), new UpdateCallback(records, indices));
				outBool = true;
			}

		} catch (Exception e) {
			logger.error(e);
			
		} finally {
			for(Closeable c : closeables) {
				try {
					c.close();
				} catch (IOException e) {
					logger.error(e);
				}
			}
			if(inArchive != null) {
				try {
					inArchive.close();
				} catch (SevenZipException e) {
					logger.error(e);
				}
			}
		}
		return outBool;
	}

	public synchronized void add(BaseRecord[] records) throws IOException {

		File f = new File(storageFile);
		if (!f.exists()) {
			addToNewArchive(records);
			return;
		}

		RandomAccessFile raf = null;
		RandomAccessFile outRaf = null;
		IInArchive archive;
		IOutUpdateArchive<IOutItemAllFormats> outArchive = null;
		List<Closeable> closeables = new ArrayList<Closeable>();
		try {
			raf = new RandomAccessFile(storageFile, "r");
			closeables.add(raf);
			IInStream inStream = new RandomAccessFileInStream(raf);
			archive = SevenZip.openInArchive(null, inStream);
			closeables.add(archive);

			outRaf = new RandomAccessFile(storageFile, "rw");
			closeables.add(outRaf);
			outArchive = archive.getConnectedOutArchive();

			outArchive.updateItems(new RandomAccessFileOutStream(outRaf), archive.getNumberOfItems() + records.length, new AddCallback(records, archive.getNumberOfItems(), true));
		} catch (SevenZipException e) {
			logger.error(e);
		} finally {
			for(Closeable c : closeables) {
				try {
					c.close();
				} catch (IOException e) {
					logger.error(e);
				}
			}
		}
	}

	private synchronized void addToNewArchive(BaseRecord[] records) throws IOException {
		// logger.info("Adding " + records.length + " records to new " + storageFile);
		RandomAccessFile raf = null;
		IOutCreateArchive7z outArchive = null;
		try {
			raf = new RandomAccessFile(storageFile, "rw");
			outArchive = SevenZip.openOutArchive7z();
			outArchive.setLevel(5);
			outArchive.createArchive(new RandomAccessFileOutStream(raf), records.length, new AddCallback(records));
		} catch (SevenZipException e) {
			logger.error(e);
		} finally {
			if (outArchive != null) {
				try {
					outArchive.close();
				} catch (IOException e) {
					System.err.println("Error closing archive: " + e);
				}
			}
			if (raf != null) {
				try {
					raf.close();
				} catch (IOException e) {
					System.err.println("Error closing file: " + e);
				}
			}
		}
	}

	private final class RemoveCallback implements IOutCreateCallback<IOutItemAllFormats> {

		private int removeIndex = 0;

		public RemoveCallback(int index) {
			this.removeIndex = index;
		}

		public void setOperationResult(boolean operationResultOk) {
			// called for each archive item
		}

		public void setTotal(long total) {
			// Track operation progress here
		}

		public void setCompleted(long complete) {
			// Track operation progress here
		}
		public IOutItemAllFormats getItemInformation(int index, OutItemFactory<IOutItemAllFormats> outItemFactory)
				throws SevenZipException {

			if (index < removeIndex) {
				return outItemFactory.createOutItem(index);
			}
			else if (index == removeIndex) {
				// logger.info("Skip index " + index);
			}
			return outItemFactory.createOutItem(index + 1);
		}

		public ISequentialInStream getStream(int i) throws SevenZipException {
			return null;
		}
	}
	
	private final class UpdateCallback implements IOutCreateCallback<IOutItemAllFormats> {

		private final int[] updateIndices;
		private final BaseRecord[] records;
		private final byte[][] buff;

		public UpdateCallback(BaseRecord[] records, int[] indices) {
			this.records = records;
			this.updateIndices = indices;
			buff = new byte[records.length][];
			for (int i = 0; i < records.length; i++) {
				BaseRecord record = records[i];
				buff[i] = JSONUtil.exportObject(record, RecordSerializerConfig.getFilteredModule()).getBytes();
			}
		}
		

		public void setOperationResult(boolean operationResultOk) {
			// called for each archive item
		}

		public void setTotal(long total) {
			// Track operation progress here
		}

		public void setCompleted(long complete) {
			// Track operation progress here
		}
		
		public IOutItemAllFormats getItemInformation(int index, OutItemFactory<IOutItemAllFormats> outItemFactory)
				throws SevenZipException {

			int uI = indexAt(index);
			if(uI == -1) {
                return outItemFactory.createOutItem(index);
            }

            IOutItemAllFormats item;
            item = outItemFactory.createOutItemAndCloneProperties(index);

            item.setUpdateIsNewProperties(true);
            item.setPropertyLastModificationTime(new Date());
            item.setUpdateIsNewData(true);
            item.setDataSize((long) buff[uI].length);

			return item;
		}

		public ISequentialInStream getStream(int i) throws SevenZipException {
			int uI = indexAt(i);
			
			if(uI == -1) {
				return null;
			}
			return new ByteArrayStream(buff[uI], true);
		}
		
		private int indexAt(int i) {
			int outIdx = -1;
			for(int p = 0; p < updateIndices.length; p++) {
				if(updateIndices[p] == i) {
					outIdx = p;
					break;
				}
			}
			return outIdx;
		}
	}

	class AddCallback implements IOutCreateCallback<IOutItemAllFormats> {
		private final BaseRecord[] records;
		private final byte[][] buff;
		private boolean update = false;
		private int offset = 0;

		private AddCallback(BaseRecord[] records, int offset, boolean update) {
			this(records);
			this.update = update;
			this.offset = offset;
		}

		private AddCallback(BaseRecord[] records) {
			this.records = records;
			buff = new byte[records.length][];
			for (int i = 0; i < records.length; i++) {
				BaseRecord record = records[i];
				buff[i] = JSONUtil.exportObject(record, RecordSerializerConfig.getFilteredModule()).getBytes();
			}
		}

		public void setOperationResult(boolean operationResultOk) {
			// called for each archive item
		}

		public void setTotal(long total) {
			// Track operation progress here
		}

		public void setCompleted(long complete) {
			// Track operation progress here
		}

		public IOutItemAllFormats getItemInformation(int index, OutItemFactory<IOutItemAllFormats> outItemFactory)
				throws SevenZipException {
			IOutItemAllFormats outItem = outItemFactory.createOutItem();

			int idx = index;
			
			if (update) {
				if (index < offset) {
					// logger.warn("Recreate existing item: " + index + " :: " + offset);
					return outItemFactory.createOutItem(index);
				} else {
					idx = index - offset;
				}
			}

			try {
				BaseRecord rec = records[idx];
				byte[] data = buff[idx];
				IndexEntry entry = null;
				try {
					String mod = rec.getAMModel();
					if(rec.hasField(FieldNames.FIELD_INDEX_MODEL) && rec.get(FieldNames.FIELD_INDEX_MODEL) != null) {
						mod = rec.get(FieldNames.FIELD_INDEX_MODEL);
					}
					entry = getIndexer(mod).findIndexEntry(rec);
				} catch (IndexException e) {
					logger.error(e);
				}
				if(entry == null) {
					logger.error("Entry is null for record");
					return null;
				}
				outItem.setDataSize((long) data.length);
	
				String entryName = getStoreName(entry) + ".json";
				outItem.setPropertyPath(entryName);
				outItem.setPropertyCreationTime(new Date());
			}
			catch(Exception e) {
				logger.error(e);
				
			}
			return outItem;
		}

		public ISequentialInStream getStream(int index) {
			
			int idx = index;
			if (update) {
				if (index < offset) {
					// logger.warn("Skip stream for low index: " + index + ":" + offset);
					return null;
				} else {
					// logger.info("Adjust index for update: " + idx + " -> " + (index - offset));
					idx = index - offset;
				}
			}
			// logger.info("Stream " + buff[idx].length + " bytes");
			return new ByteArrayStream(buff[idx], true);
		}
	}

}
