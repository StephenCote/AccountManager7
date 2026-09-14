package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/// Slug extraction from a book-world gallery path — the link that routes a character's images back
/// to the book that owns them.
///
/// Context: a book character's images are written to the BOOK world's gallery (named by the
/// charPerson's pbImageGalleryPath attribute), while the reimage REST flow resolves the DEFAULT
/// OlioContext when its request carries no universe/world ids. The grant then applied the wrong
/// world's roles — or, with the isolation guard, none — so a book's own Writer could not read its
/// portraits. resolveOwningBookContext closes that by deriving the owning book from this path.
/// Pure function, no DB, no LLM.
public class TestBookSlugFromGalleryPath {

	@Test
	public void TestExtractsTheSlugFromABookWorldGallery() {
		assertEquals("the-big-way-out-pdf", PbOlioContextUtil.bookSlugFromGalleryPath(
			"/Olio/Universes/Books/Worlds/the-big-way-out-pdf/Gallery"));
	}

	/// The write path appends /Characters/<name>, so deeper paths must still resolve the book.
	@Test
	public void TestExtractsTheSlugFromADeeperPath() {
		assertEquals("the-big-way-out-pdf", PbOlioContextUtil.bookSlugFromGalleryPath(
			"/Olio/Universes/Books/Worlds/the-big-way-out-pdf/Gallery/Characters/Darby"));
	}

	/// A NON-book gallery must yield null so the caller keeps its own context rather than trying to
	/// open a book that does not exist.
	@Test
	public void TestNonBookGalleryYieldsNull() {
		assertNull(PbOlioContextUtil.bookSlugFromGalleryPath(
			"/Olio/Universes/My Grid Universe/Worlds/My Grid World/Gallery"));
	}

	@Test
	public void TestMalformedInputYieldsNullRatherThanThrowing() {
		assertNull(PbOlioContextUtil.bookSlugFromGalleryPath(null));
		assertNull(PbOlioContextUtil.bookSlugFromGalleryPath(""));
		assertNull(PbOlioContextUtil.bookSlugFromGalleryPath("/Olio/Universes/Books/Worlds/"));
		assertNull(PbOlioContextUtil.bookSlugFromGalleryPath("not a path at all"));
	}
}
