package org.cote.accountmanager.util;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class GraphicsUtil {
	
	public static final Logger logger = LogManager.getLogger(GraphicsUtil.class);
	
	public static final String IMAGE_FORMAT_JPG = "jpg";
	public static final String IMAGE_FORMAT_PNG = "png";
	public static final String IMAGE_FORMAT = IMAGE_FORMAT_PNG;
	
	
	public static byte[] createThumbnail(byte[] sourceBytes, int maximumWidth, int maximumHeight) throws IOException {
		
		byte[] outBytes = new byte[0];
		
		Image image = ImageIO.read(new ByteArrayInputStream(sourceBytes));
		if(image == null) {
			logger.error("Image could not be constructed");
			return outBytes;
		}
		
		int width = image.getWidth(null);
		int height = image.getHeight(null);
		
		if(width < maximumWidth && height < maximumHeight){
			logger.warn("Undersized: " + width + " < " + maximumWidth + " / " + height + " < " + maximumHeight);
			return outBytes;
		}
		
		double scale = (double)maximumHeight/(double)height;


		if (width > height){
			scale = (double)maximumWidth/(double)width;
		}

		int scaleWidth = (int)(scale * width);
		int scaleHeight = (int)(scale * height);

		BufferedImage imageOut = new BufferedImage(scaleWidth, scaleHeight,BufferedImage.TYPE_INT_ARGB);

		AffineTransform at = new AffineTransform();

		if (scale < 1.0d){
			at.scale(scale, scale);
		}
		Graphics2D g2d = imageOut.createGraphics();

		imageOut.flush();
		g2d.drawImage(image, at, null);
		g2d.dispose();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		ImageIO.write(imageOut, IMAGE_FORMAT, baos);

		return baos.toByteArray();
	}

}
