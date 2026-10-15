package org.cote.accountmanager.olio.illustration;

import org.cote.accountmanager.schema.type.PbPageFormatEnumType;

/// Resolves the explicit canvas a plan renders at (PictureBook V3 design §12: the canvas is always
/// explicit on the plan, never inferred by the renderer). Page format first, config dimensions as
/// the fallback when the style chain declares nothing, the preset's resolution scale applied, then
/// snapped to the renderer's 64 px grid and clamped to sane bounds.
public final class CanvasResolver {

	public static final int GRID = 64;
	public static final int MIN_EDGE = 64;
	public static final int MAX_EDGE = 16384;
	public static final int SQUARE_EDGE = 1024;
	public static final int PORTRAIT_PAGE_W = 832;
	public static final int PORTRAIT_PAGE_H = 1216;
	public static final int LANDSCAPE_PAGE_W = 1216;
	public static final int LANDSCAPE_PAGE_H = 832;

	private CanvasResolver() { }

	/// {@code format} null/UNKNOWN means "no style says": the config's width/height when both are
	/// positive, otherwise the square default. CUSTOM needs both custom dimensions, otherwise it is
	/// the square default too. {@code scale} <= 0 is treated as 1.0.
	public static RenderPlan.Canvas resolve(PbPageFormatEnumType format, int customWidth, int customHeight, double scale, int configWidth, int configHeight) {
		int w;
		int h;
		PbPageFormatEnumType f = (format == null ? PbPageFormatEnumType.UNKNOWN : format);
		switch(f) {
			case SQUARE:
				w = SQUARE_EDGE;
				h = SQUARE_EDGE;
				break;
			case PORTRAIT_PAGE:
				w = PORTRAIT_PAGE_W;
				h = PORTRAIT_PAGE_H;
				break;
			case LANDSCAPE_PAGE:
				w = LANDSCAPE_PAGE_W;
				h = LANDSCAPE_PAGE_H;
				break;
			case CUSTOM:
				if(customWidth > 0 && customHeight > 0) {
					w = customWidth;
					h = customHeight;
				}
				else {
					w = SQUARE_EDGE;
					h = SQUARE_EDGE;
				}
				break;
			default:
				if(configWidth > 0 && configHeight > 0) {
					w = configWidth;
					h = configHeight;
				}
				else {
					w = SQUARE_EDGE;
					h = SQUARE_EDGE;
				}
		}
		double s = (scale > 0 ? scale : 1.0);
		return new RenderPlan.Canvas(snap(w * s), snap(h * s));
	}

	static int snap(double v) {
		long g = Math.round(v / GRID) * GRID;
		if(g < MIN_EDGE) {
			return MIN_EDGE;
		}
		if(g > MAX_EDGE) {
			return MAX_EDGE;
		}
		return (int) g;
	}
}
