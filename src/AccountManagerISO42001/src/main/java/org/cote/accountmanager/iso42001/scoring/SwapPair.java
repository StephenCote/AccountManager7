package org.cote.accountmanager.iso42001.scoring;

/**
 * An unordered pair of demographic groups along a {@link SwapDimension}, used to
 * generate the two prompt variants A/B of a swap test (design §5.2).
 */
public class SwapPair {

	private final SwapDimension dimension;
	private final String groupA;
	private final String groupB;

	public SwapPair(SwapDimension dimension, String groupA, String groupB) {
		this.dimension = dimension;
		this.groupA = groupA;
		this.groupB = groupB;
	}

	public SwapDimension getDimension() { return dimension; }
	public String getGroupA() { return groupA; }
	public String getGroupB() { return groupB; }

	@Override
	public String toString() {
		return dimension + ":" + groupA + "<->" + groupB;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof SwapPair)) return false;
		SwapPair p = (SwapPair) o;
		return dimension == p.dimension && groupA.equals(p.groupA) && groupB.equals(p.groupB);
	}

	@Override
	public int hashCode() {
		return java.util.Objects.hash(dimension, groupA, groupB);
	}
}
