package org.cote.accountmanager.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

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
}
