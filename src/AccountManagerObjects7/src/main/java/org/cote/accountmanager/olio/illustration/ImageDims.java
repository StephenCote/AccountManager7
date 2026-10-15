package org.cote.accountmanager.olio.illustration;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/// Measures image bytes. The request states what was asked for; the bytes say what came back, and
/// the plan's canvas assertion (design §12) is about the latter.
public final class ImageDims {

	private static final Logger logger = LogManager.getLogger(ImageDims.class);

	private ImageDims() { }

	/// Decoded {width, height}, or {0, 0} when the bytes are absent or not a decodable image.
	public static int[] decode(byte[] bytes) {
		if(bytes == null || bytes.length == 0) {
			return new int[] {0, 0};
		}
		try {
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
			if(img == null) {
				return new int[] {0, 0};
			}
			return new int[] {img.getWidth(), img.getHeight()};
		}
		catch(Exception e) {
			logger.warn("Could not decode image bytes to measure them: " + e.getMessage());
			return new int[] {0, 0};
		}
	}
}
