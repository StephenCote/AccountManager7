package org.cote.accountmanager.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ZipUtil {
	public static final Logger logger = LogManager.getLogger(ZipUtil.class);
	public static byte[] gzipStream(InputStream in){
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		try{
			GZIPOutputStream gzout = new GZIPOutputStream(baos);
			StreamUtil.copyStream(in, gzout);
			gzout.close();
		}
		catch(IOException e){
			logger.error(e);
		}
		return baos.toByteArray();
	}
	
	public static byte[] gzipBytes(byte[] inBytes){
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		try{
			GZIPOutputStream gzout = new GZIPOutputStream(baos);
			gzout.write(inBytes,0,inBytes.length);
			gzout.close();

		}
		catch(IOException e){
			logger.error("ZipUtil:: gzipBytes: " + e);
		}
		return baos.toByteArray();

	}
	public static byte[] gunzipBytes(byte[] inBytes){
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		try{
			ByteArrayInputStream bais=new ByteArrayInputStream(inBytes);
			GZIPInputStream gzin = new GZIPInputStream(bais);
			StreamUtil.copyStream(gzin, baos);
			gzin.close();
			bais.close();
		}
		catch(IOException e){
			logger.error("ZipUtil:: gunzipBytes: " + e);
		}
		return baos.toByteArray();

	}

	/// Multi-entry ZIP archive support (KI-17, Gallery/Group export). Unlike gzip* above (single-stream
	/// compression), these build a real archive with one named entry per input — e.g. one file per
	/// exported data.data object, or a "<name>.json" entry for anything without extractable byte content.
	/// Entry order follows the map's iteration order — pass a LinkedHashMap (see newOrderedEntries())
	/// if a deterministic/insertion order in the resulting archive matters to the caller.
	public static byte[] createArchive(Map<String, byte[]> entries) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (ZipOutputStream zout = new ZipOutputStream(baos)) {
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				byte[] content = (e.getValue() != null ? e.getValue() : new byte[0]);
				zout.putNextEntry(new ZipEntry(e.getKey()));
				zout.write(content, 0, content.length);
				zout.closeEntry();
			}
		}
		catch (IOException e) {
			logger.error("ZipUtil:: createArchive: " + e);
		}
		return baos.toByteArray();
	}

	/// Convenience for callers that want to build up entries in insertion order (createArchive's
	/// ordering guarantee only holds for a Map that itself preserves insertion order).
	public static Map<String, byte[]> newOrderedEntries() {
		return new LinkedHashMap<>();
	}

	/// Streaming counterpart to createArchive(Map), added to fix an OutOfMemoryError exporting large
	/// groups (KI-17 follow-up): createArchive(Map<String,byte[]>) requires every entry's FULL content to
	/// be resident in memory SIMULTANEOUSLY (the caller must build the whole map first) in addition to
	/// the ZipOutputStream's own growing in-memory buffer - for a >1GB group that's the whole export's
	/// content plus a second full copy, easily exhausting the heap regardless of any single file's size.
	/// A ZipEntrySource defers acquiring its bytes until writeTo() is called, one entry at a time, so a
	/// caller (see GroupExportUtil) can write each entry directly into the destination OutputStream
	/// (ideally a FileOutputStream, not another in-memory buffer) - at any point in time only the
	/// current entry's own chunking discipline (e.g. StreamSegmentUtil paging a stream-backed file in
	/// fixed-size chunks) plus the ZipOutputStream's fixed-size internal deflater buffer are resident,
	/// never the sum of every entry's content.
	public interface ZipEntrySource {
		String getName();
		void writeTo(OutputStream out) throws IOException;
	}

	/// Writes `sources` into `out` as a ZIP archive, one entry at a time, closing neither `out` nor the
	/// ZipOutputStream's underlying stream beyond what ZipOutputStream itself requires (callers own
	/// `out`'s lifecycle - typically a FileOutputStream over a temp file so the finished archive is
	/// never fully materialized in memory either). Does not catch/log I/O failures - unlike
	/// createArchive(Map), a caller writing a real (potentially large) export needs to know a write
	/// failed rather than silently getting a truncated archive.
	public static void writeArchive(OutputStream out, List<ZipEntrySource> sources) throws IOException {
		try (ZipOutputStream zout = new ZipOutputStream(out)) {
			for (ZipEntrySource source : sources) {
				zout.putNextEntry(new ZipEntry(source.getName()));
				source.writeTo(zout);
				zout.closeEntry();
			}
		}
	}
}
