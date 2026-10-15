package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertEquals;

import org.cote.accountmanager.schema.type.PbPageFormatEnumType;
import org.junit.Test;

/** Pure tests for {@link CanvasResolver} (P2-4, design §12): explicit canvas, config fallback, scale, snap, clamp. */
public class TestCanvasResolver {

	private static void assertCanvas(int w, int h, RenderPlan.Canvas c) {
		assertEquals("width", w, c.getWidth());
		assertEquals("height", h, c.getHeight());
	}

	@Test
	public void pageFormatsAreExplicit() {
		assertCanvas(1024, 1024, CanvasResolver.resolve(PbPageFormatEnumType.SQUARE, 0, 0, 1.0, 512, 512));
		assertCanvas(832, 1216, CanvasResolver.resolve(PbPageFormatEnumType.PORTRAIT_PAGE, 0, 0, 1.0, 512, 512));
		assertCanvas(1216, 832, CanvasResolver.resolve(PbPageFormatEnumType.LANDSCAPE_PAGE, 0, 0, 1.0, 512, 512));
		assertCanvas(1536, 640, CanvasResolver.resolve(PbPageFormatEnumType.CUSTOM, 1536, 640, 1.0, 512, 512));
	}

	@Test
	public void customWithoutBothDimensionsIsSquare() {
		assertCanvas(1024, 1024, CanvasResolver.resolve(PbPageFormatEnumType.CUSTOM, 1536, 0, 1.0, 512, 512));
		assertCanvas(1024, 1024, CanvasResolver.resolve(PbPageFormatEnumType.CUSTOM, 0, 640, 1.0, 512, 512));
	}

	@Test
	public void noFormatFallsBackToConfigThenSquare() {
		assertCanvas(768, 1152, CanvasResolver.resolve(null, 0, 0, 1.0, 768, 1152));
		assertCanvas(768, 1152, CanvasResolver.resolve(PbPageFormatEnumType.UNKNOWN, 0, 0, 1.0, 768, 1152));
		assertCanvas(1024, 1024, CanvasResolver.resolve(null, 0, 0, 1.0, 768, 0));
		assertCanvas(1024, 1024, CanvasResolver.resolve(null, 0, 0, 1.0, 0, 0));
	}

	@Test
	public void scaleAppliesThenSnapsToGrid() {
		/// DRAFT 0.75 x 1024 = 768; x 832 = 624 -> 640; x 1216 = 912 -> 896
		assertCanvas(768, 768, CanvasResolver.resolve(PbPageFormatEnumType.SQUARE, 0, 0, 0.75, 0, 0));
		assertCanvas(640, 896, CanvasResolver.resolve(PbPageFormatEnumType.PORTRAIT_PAGE, 0, 0, 0.75, 0, 0));
		/// FINAL 1.5 x 1024 = 1536
		assertCanvas(1536, 1536, CanvasResolver.resolve(PbPageFormatEnumType.SQUARE, 0, 0, 1.5, 0, 0));
		/// a non-positive scale is 1.0
		assertCanvas(1024, 1024, CanvasResolver.resolve(PbPageFormatEnumType.SQUARE, 0, 0, 0, 0, 0));
		/// an off-grid config size snaps
		assertCanvas(704, 1024, CanvasResolver.resolve(null, 0, 0, 1.0, 700, 1000));
	}

	@Test
	public void clampsToSaneBounds() {
		assertCanvas(64, 64, CanvasResolver.resolve(PbPageFormatEnumType.CUSTOM, 8, 8, 1.0, 0, 0));
		assertCanvas(16384, 16384, CanvasResolver.resolve(PbPageFormatEnumType.CUSTOM, 100000, 100000, 1.0, 0, 0));
		assertEquals(64, CanvasResolver.snap(1));
		assertEquals(16384, CanvasResolver.snap(1e9));
		assertEquals(1024, CanvasResolver.snap(1024));
		assertEquals(1088, CanvasResolver.snap(1056));
		assertEquals(1024, CanvasResolver.snap(1055));
	}
}
