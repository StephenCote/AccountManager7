package org.cote.accountmanager.olio.illustration;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/// The reserved title band's canvas arithmetic and padding (V3 §40, §24 I-4 / 236 i). A plan that reserves a
/// band renders its figure stages (BASE, the ACTOR_REGION passes) on the <b>figure canvas</b> - the plan's canvas
/// with the band's rows removed - so no figure can occupy the band, whatever the prompt said; the TITLE_BAND pass
/// then paints the band itself over the figure image {@link #pad padded} back to the full canvas, through the
/// family's masked init-image graph with the band as the mask. The band's pixel height is a multiple of 16 so
/// both canvases stay sizes the renderer accepts.
public final class BandCanvas {

	/// The renderer's size grain: both canvases are kept to multiples of it.
	public static final int GRAIN = 16;

	/// The band pass's mask feather (Swarm {@code maskblur}, pixels): the band's geometry is exact, so the mask is
	/// not grown, and it is blurred one grain. Measured on FLUX.2 covers (TestPbCoverRenderLive runs 73 / 75 / 77,
	/// 2026-11-14): the seam below the band is a tone step - the painted band lands 9 to 15 / 255 darker than the
	/// figure's own sky whatever the pass starts from (a flat fill or the extended edge) and at creativity 1.0 or
	/// the family's 0.6 - and a wider feather (32) only turned the one step into a 32 px strip with an edge at each
	/// side (Swarm's blur has a hard kernel edge). The tone is aligned after the pass by {@link #matchSeam}; the
	/// feather only softens the composite edge.
	public static final int FEATHER = GRAIN;

	/// The painted cover with its band's tone aligned to the figure's: the mean colour of the painted rows one
	/// {@link #FEATHER} inside the seam is compared with the figure's rows one feather outside it (each over
	/// {@link #GRAIN} rows), and the per-channel difference is added to the band, in full beyond the feather and
	/// fading linearly to nothing across the two feathers centred on the seam (the rows the composite blended).
	/// The band keeps its own gradient and texture; only its level moves. The input bytes come back untouched when
	/// there is no band, the image is not the canvas size (nothing to measure against), the canvas is too small to
	/// sample both sides, or the difference is under half a level.
	public static SeamMatch matchSeam(byte[] coverPng, int width, int height, RenderPlan.Box band) {
		BufferedImage img = MaskImage.decode(coverPng);
		int[] bp = bandPixelsBox(width, height, band);
		if(img == null || bp[3] == 0 || img.getWidth() != width || img.getHeight() != height) {
			return new SeamMatch(coverPng, null);
		}
		boolean top = band.isTop();
		int seam = (top ? bp[3] : bp[1]);
		long[] painted = new long[4];
		long[] figure = new long[4];
		for(int y = 0; y < height; y++) {
			int into = into(top, seam, y);
			long[] acc = null;
			if(into > FEATHER && into <= FEATHER + GRAIN) {
				acc = painted;
			}
			else if(into <= -FEATHER && into > -FEATHER - GRAIN) {
				acc = figure;
			}
			if(acc != null) {
				for(int x = 0; x < width; x++) {
					int rgb = img.getRGB(x, y);
					acc[0] += (rgb >> 16) & 0xFF;
					acc[1] += (rgb >> 8) & 0xFF;
					acc[2] += rgb & 0xFF;
					acc[3]++;
				}
			}
		}
		if(painted[3] == 0 || figure[3] == 0) {
			return new SeamMatch(coverPng, null);
		}
		double[] offset = new double[3];
		double maxAbs = 0.0;
		for(int c = 0; c < 3; c++) {
			offset[c] = figure[c] / (double) figure[3] - painted[c] / (double) painted[3];
			maxAbs = Math.max(maxAbs, Math.abs(offset[c]));
		}
		if(maxAbs < 0.5) {
			return new SeamMatch(coverPng, offset);
		}
		for(int y = 0; y < height; y++) {
			double w = weight(into(top, seam, y));
			if(w <= 0.0) {
				continue;
			}
			for(int x = 0; x < width; x++) {
				int rgb = img.getRGB(x, y);
				int r = clamp(((rgb >> 16) & 0xFF) + offset[0] * w);
				int g = clamp(((rgb >> 8) & 0xFF) + offset[1] * w);
				int b = clamp((rgb & 0xFF) + offset[2] * w);
				img.setRGB(x, y, (rgb & 0xFF000000) | (r << 16) | (g << 8) | b);
			}
		}
		return new SeamMatch(MaskImage.png(img), offset);
	}

	/// The result of {@link #matchSeam}: the bytes to deliver and the per-channel offset that was measured (null
	/// when nothing could be measured; applied only when {@link #isApplied}).
	public static final class SeamMatch {
		private final byte[] bytes;
		private final double[] offset;

		SeamMatch(byte[] bytes, double[] offset) {
			this.bytes = bytes;
			this.offset = offset;
		}

		public byte[] getBytes() { return bytes; }
		public double[] getOffset() { return offset; }
		public boolean isApplied() {
			return offset != null && (Math.abs(offset[0]) >= 0.5 || Math.abs(offset[1]) >= 0.5 || Math.abs(offset[2]) >= 0.5);
		}

		@Override
		public String toString() {
			if(offset == null) {
				return "SeamMatch{not measured}";
			}
			return String.format(java.util.Locale.ROOT, "SeamMatch{%s r%+.1f g%+.1f b%+.1f}", (isApplied() ? "applied" : "nil"), offset[0], offset[1], offset[2]);
		}
	}

	/// Signed rows from the seam into the band: the band row next to the seam is 1, the figure row next to it 0,
	/// deeper figure rows negative.
	private static int into(boolean top, int seam, int y) {
		return (top ? seam - y : y - seam + 1);
	}

	/// The share of the offset a row takes: all of it one feather or more into the band, none one feather or
	/// more into the figure, linear between.
	private static double weight(int into) {
		if(into >= FEATHER) {
			return 1.0;
		}
		if(into <= -FEATHER) {
			return 0.0;
		}
		return (into + FEATHER) / (2.0 * FEATHER);
	}

	private static int clamp(double v) {
		return (int) Math.max(0, Math.min(255, Math.round(v)));
	}

	private BandCanvas() { }

	/// The band's height in pixels on a canvas {@code height} tall: {@code band.h * height} rounded to the nearest
	/// multiple of {@link #GRAIN}, never less than one grain and never leaving less than one grain of figure canvas.
	/// 0 when there is no band.
	public static int bandPixels(int height, RenderPlan.Box band) {
		if(band == null || band.getH() <= 0.0 || height < 2 * GRAIN) {
			return 0;
		}
		int px = (int) Math.round(band.getH() * height / GRAIN) * GRAIN;
		return Math.max(GRAIN, Math.min(height - GRAIN, px));
	}

	/// The figure canvas height: the canvas height less {@link #bandPixels}.
	public static int figureHeight(int height, RenderPlan.Box band) {
		return height - bandPixels(height, band);
	}

	/// The band as pixels {x, y, w, h} on the full canvas, from the same rounding as {@link #bandPixels}: the top
	/// rows for a top band, the bottom rows otherwise.
	public static int[] bandPixelsBox(int width, int height, RenderPlan.Box band) {
		int px = bandPixels(height, band);
		if(px == 0) {
			return new int[] {0, 0, 0, 0};
		}
		return new int[] {0, (band.isTop() ? 0 : height - px), width, px};
	}

	/// The band as a normalised box matching {@link #bandPixelsBox} exactly, so the mask the compiler rasterises
	/// ({@link MaskImage#rect}) covers precisely the padded rows.
	public static RenderPlan.Box bandBox(int width, int height, RenderPlan.Box band) {
		int[] px = bandPixelsBox(width, height, band);
		if(px[3] == 0) {
			return null;
		}
		return new RenderPlan.Box(0.0, px[1] / (double) height, 1.0, px[3] / (double) height);
	}

	/**
	 * The figure image padded back to the full canvas: {@code figurePng} is drawn into the figure rows (scaled to
	 * fit them when its size differs) and each column of the band's rows is filled with the mean colour of that
	 * column's {@link #GRAIN} figure rows next to the band - the figure's edge extended, continuous with the figure
	 * at the seam and keeping its horizontal gradient (a dusk sky's pink on one side), which is what the inpaint
	 * pass refines at the family's creativity (it is also what the viewer sees when the pass fails,
	 * TITLE_BAND_FAILED). Returns null when the bytes do not decode or there is no band.
	 */
	public static byte[] pad(byte[] figurePng, int width, int height, RenderPlan.Box band) {
		BufferedImage figure = MaskImage.decode(figurePng);
		int[] bp = bandPixelsBox(width, height, band);
		if(figure == null || bp[3] == 0 || width < 1 || height < 1) {
			return null;
		}
		int figureH = height - bp[3];
		int figureY = (band.isTop() ? bp[3] : 0);
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(figure, 0, figureY, width, figureY + figureH, 0, 0, figure.getWidth(), figure.getHeight(), null);
		}
		finally {
			g.dispose();
		}
		int edgeRow = (band.isTop() ? figureY : figureY + figureH - 1);
		int dir = (band.isTop() ? 1 : -1);
		for(int x = 0; x < width; x++) {
			int rgb = columnEdgeMean(out, x, edgeRow, dir).getRGB();
			for(int y = bp[1]; y < bp[1] + bp[3]; y++) {
				out.setRGB(x, y, rgb);
			}
		}
		return MaskImage.png(out);
	}

	/// The mean colour of column {@code x} over up to {@link #GRAIN} rows starting at {@code row} and stepping
	/// {@code dir} (into the figure).
	private static Color columnEdgeMean(BufferedImage img, int x, int row, int dir) {
		long r = 0, gr = 0, b = 0, n = 0;
		for(int i = 0; i < GRAIN; i++) {
			int y = row + i * dir;
			if(y < 0 || y >= img.getHeight()) {
				break;
			}
			int rgb = img.getRGB(x, y);
			r += (rgb >> 16) & 0xFF;
			gr += (rgb >> 8) & 0xFF;
			b += rgb & 0xFF;
			n++;
		}
		if(n == 0) {
			return Color.GRAY;
		}
		return new Color((int) (r / n), (int) (gr / n), (int) (b / n));
	}
}
