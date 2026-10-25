package org.cote.accountmanager.olio.illustration;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

/// Mask and placeholder PNGs for a raw graph (design §38.2). A mask is white rectangles on black at
/// canvas size - Swarm's ImageToMask reads the red channel, and the graph's SwarmMaskGrow /
/// SwarmMaskBlur do the feathering, so the mask itself is hard-edged. A placeholder is a solid-colour
/// PNG the compiler hands the generator in place of a real image so the generated graph can be
/// patched by colour ({@link WorkflowCompiler}).
public final class MaskImage {

	private MaskImage() { }

	/// White rectangles (the boxes, as normalised fractions of the canvas) on black, RGB PNG.
	public static byte[] rect(List<RenderPlan.Box> boxes, int width, int height) {
		if(width < 1 || height < 1) {
			throw new IllegalArgumentException("Mask size must be positive: " + width + "x" + height);
		}
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		try {
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, width, height);
			g.setColor(Color.WHITE);
			if(boxes != null) {
				for(RenderPlan.Box b : boxes) {
					if(b == null) continue;
					int[] px = pixels(b, width, height);
					if(px[2] > 0 && px[3] > 0) {
						g.fillRect(px[0], px[1], px[2], px[3]);
					}
				}
			}
		}
		finally {
			g.dispose();
		}
		return png(img);
	}

	/// A box as clamped integer pixels {x, y, w, h}.
	public static int[] pixels(RenderPlan.Box b, int width, int height) {
		int x = clamp((int) Math.round(b.getX() * width), 0, width);
		int y = clamp((int) Math.round(b.getY() * height), 0, height);
		int x2 = clamp((int) Math.round((b.getX() + b.getW()) * width), 0, width);
		int y2 = clamp((int) Math.round((b.getY() + b.getH()) * height), 0, height);
		return new int[] {x, y, Math.max(0, x2 - x), Math.max(0, y2 - y)};
	}

	/// A solid-colour RGB PNG.
	public static byte[] solid(int width, int height, Color color) {
		if(width < 1 || height < 1) {
			throw new IllegalArgumentException("Image size must be positive: " + width + "x" + height);
		}
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		try {
			g.setColor(color);
			g.fillRect(0, 0, width, height);
		}
		finally {
			g.dispose();
		}
		return png(img);
	}

	/// Fraction of pixels whose red channel is above 127 (the mask's coverage).
	public static double coverage(byte[] png) {
		BufferedImage img = decode(png);
		if(img == null) {
			return -1.0;
		}
		long lit = 0;
		for(int y = 0; y < img.getHeight(); y++) {
			for(int x = 0; x < img.getWidth(); x++) {
				if(((img.getRGB(x, y) >> 16) & 0xFF) > 127) {
					lit++;
				}
			}
		}
		return lit / (double) (img.getWidth() * (long) img.getHeight());
	}

	/// The colour at (0,0), or null when the bytes do not decode.
	public static Color cornerColor(byte[] png) {
		BufferedImage img = decode(png);
		return (img == null ? null : new Color(img.getRGB(0, 0)));
	}

	public static BufferedImage decode(byte[] png) {
		if(png == null || png.length == 0) {
			return null;
		}
		try {
			return ImageIO.read(new ByteArrayInputStream(png));
		}
		catch(IOException e) {
			return null;
		}
	}

	static byte[] png(BufferedImage img) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			if(!ImageIO.write(img, "png", baos)) {
				throw new IllegalStateException("No PNG writer available");
			}
		}
		catch(IOException e) {
			throw new IllegalStateException("PNG encode failed: " + e.getMessage(), e);
		}
		return baos.toByteArray();
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
