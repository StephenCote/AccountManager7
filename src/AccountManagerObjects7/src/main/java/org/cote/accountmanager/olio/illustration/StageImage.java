package org.cote.accountmanager.olio.illustration;

/// One image a stage produced: the bytes, the seed the backend reports for it ({@link #SEED_UNKNOWN}
/// when it reported none and no fallback applied) and the decoded dimensions.
public final class StageImage {

	public static final int SEED_UNKNOWN = -1;

	private final byte[] bytes;
	private final int seed;
	private final boolean seedReported;
	private final int width;
	private final int height;

	public StageImage(byte[] bytes, int seed, boolean seedReported, int width, int height) {
		this.bytes = bytes;
		this.seed = seed;
		this.seedReported = seedReported;
		this.width = width;
		this.height = height;
	}

	public byte[] getBytes() { return bytes; }
	public int getSeed() { return seed; }
	/// False when the seed is the adapter's fallback (previous seed + 1) rather than the backend's.
	public boolean isSeedReported() { return seedReported; }
	public int getWidth() { return width; }
	public int getHeight() { return height; }
	public int getLength() { return bytes == null ? 0 : bytes.length; }
}
