package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.Test;

/// V3 Phase 5, P5-1: the mask and placeholder PNGs a raw graph carries (design §38.2).
public class TestMaskImage {

	@Test
	public void rectMaskPixels() {
		RenderPlan.Box b = new RenderPlan.Box(0.25, 0.25, 0.5, 0.5);
		assertArrayEquals(new int[] {25, 20, 50, 40}, MaskImage.pixels(b, 100, 80));
		byte[] png = MaskImage.rect(List.of(b), 100, 80);
		assertEquals(0.25, MaskImage.coverage(png), 0.0001);
		assertEquals(Color.BLACK, MaskImage.cornerColor(png));
		BufferedImage img = MaskImage.decode(png);
		assertEquals(100, img.getWidth());
		assertEquals(80, img.getHeight());
		assertEquals(Color.WHITE, new Color(img.getRGB(50, 40)));
		assertEquals(Color.WHITE, new Color(img.getRGB(25, 20)));
		assertEquals(Color.BLACK, new Color(img.getRGB(24, 20)));
		assertEquals(Color.BLACK, new Color(img.getRGB(75, 60)));
		assertEquals(Color.WHITE, new Color(img.getRGB(74, 59)));
	}

	@Test
	public void boxesClampToTheCanvas() {
		assertArrayEquals(new int[] {90, 70, 10, 10}, MaskImage.pixels(new RenderPlan.Box(0.9, 0.875, 0.5, 0.5), 100, 80));
		assertArrayEquals(new int[] {0, 0, 100, 80}, MaskImage.pixels(new RenderPlan.Box(0, 0, 1, 1), 100, 80));
		assertEquals(1.0, MaskImage.coverage(MaskImage.rect(List.of(new RenderPlan.Box(0, 0, 1, 1)), 64, 64)), 0.0);
		/// Two boxes overlapping: union, not sum.
		byte[] two = MaskImage.rect(List.of(new RenderPlan.Box(0, 0, 0.5, 1), new RenderPlan.Box(0.25, 0, 0.5, 1)), 100, 10);
		assertEquals(0.75, MaskImage.coverage(two), 0.0001);
	}

	@Test
	public void emptyMaskIsAllBlack() {
		assertEquals(0.0, MaskImage.coverage(MaskImage.rect(List.of(), 64, 48)), 0.0);
		assertEquals(0.0, MaskImage.coverage(MaskImage.rect(null, 64, 48)), 0.0);
		try {
			MaskImage.rect(List.of(), 0, 48);
			fail();
		}
		catch(IllegalArgumentException e) {
			/// expected
		}
	}

	@Test
	public void solidPlaceholderKeepsItsColour() {
		byte[] red = MaskImage.solid(1024, 768, Color.RED);
		assertEquals(Color.RED, MaskImage.cornerColor(red));
		assertEquals("solid red is fully lit on the red channel", 1.0, MaskImage.coverage(red), 0.0);
		byte[] blue = MaskImage.solid(8, 8, Color.BLUE);
		assertEquals(Color.BLUE, MaskImage.cornerColor(blue));
		assertEquals(0.0, MaskImage.coverage(blue), 0.0);
		Color custom = new Color(90, 120, 150);
		assertEquals(custom, MaskImage.cornerColor(MaskImage.solid(3, 2, custom)));
		BufferedImage img = MaskImage.decode(red);
		assertNotNull(img);
		assertEquals(1024, img.getWidth());
		assertEquals(768, img.getHeight());
	}

	@Test
	public void undecodableBytesAreReportedNotThrown() {
		assertNull(MaskImage.decode(null));
		assertNull(MaskImage.decode(new byte[0]));
		assertNull(MaskImage.decode("not a png".getBytes()));
		assertNull(MaskImage.cornerColor("not a png".getBytes()));
		assertEquals(-1.0, MaskImage.coverage("not a png".getBytes()), 0.0);
	}
}
