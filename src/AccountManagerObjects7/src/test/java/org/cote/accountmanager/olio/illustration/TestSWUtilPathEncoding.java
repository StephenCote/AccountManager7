package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertEquals;

import org.cote.accountmanager.util.ClientUtil;
import org.junit.Test;

/// V3 Phase 5, P5-1 (architect F4): what happens to the relative image path Swarm returns from a raw
/// render (`View/local/raw/<date>/<n>-<prompt words>-<seed>.png`) when `SDUtil.fetchImageBytes` hands
/// it to `ClientUtil.getResource(server + "/" + path)`. Measured 2026-10-24 against Jersey's
/// `WebTarget.getUri()` - no server involved - and asserted as observed so a client-library change
/// surfaces here rather than as a silent 404 on the first prompt with a space in it.
public class TestSWUtilPathEncoding {

	private static final String SERVER = "http://127.0.0.1:9";

	private static String uri(String relativePath) {
		return ClientUtil.getResource(SERVER + "/" + relativePath).getUri().toString();
	}

	@Test
	public void spacesArePercentEncodedOnce() {
		assertEquals(SERVER + "/View/local/raw/a%20b.png", uri("View/local/raw/a b.png"));
		assertEquals(SERVER + "/View/local/raw/2026-10-24/0123-a%20lean%20man-424242.png", uri("View/local/raw/2026-10-24/0123-a lean man-424242.png"));
		/// An already-encoded path is not encoded again.
		assertEquals(SERVER + "/View/local/raw/a%20b.png", uri("View/local/raw/a%20b.png"));
	}

	@Test
	public void nonAsciiIsUtf8PercentEncoded() {
		assertEquals(SERVER + "/View/local/raw/%C3%A9.png", uri("View/local/raw/" + (char) 0xE9 + ".png"));
	}

	@Test
	public void plainCharactersPassThrough() {
		assertEquals(SERVER + "/View/local/raw/a+b.png", uri("View/local/raw/a+b.png"));
		assertEquals(SERVER + "/View/local/raw/a-b_c.png", uri("View/local/raw/a-b_c.png"));
	}

	/// Measured, not desired: `#` and `?` are NOT encoded by the template path, so a file name carrying
	/// either would be cut at that character (fragment / query). Swarm's file names come from the
	/// prompt's leading words and the seed; neither character has appeared in a captured path. If one
	/// ever does, encode in `SDUtil.fetchImageBytes` - this test is the record of why.
	@Test
	public void hashAndQuestionMarkAreNotEncoded() {
		assertEquals(SERVER + "/View/local/raw/a#b.png", uri("View/local/raw/a#b.png"));
		assertEquals(SERVER + "/View/local/raw/a?b.png", uri("View/local/raw/a?b.png"));
	}
}
