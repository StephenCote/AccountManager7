package org.cote.accountmanager.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
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
}
