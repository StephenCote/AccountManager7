package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/// PB1 scene group path -> book-world Population path.
///
/// THE BUG this guards: since the per-book-world change, createCharPerson routes a book's
/// charPersons into the WORLD's population group, but resolveSceneCharacter still looked only in
/// the legacy PB1 <book>/Characters group. Measured 2026-09-14 on "The Big Way Out.pdf" — PB1
/// Characters (group 619) held 0 charPersons while the book world's Population (group 580) held
/// all 6 including Darby. Result: 85 "Could not resolve scene character" warns,
/// "Stage 1 complete: 0 portraits generated", and composites running with refs=0 /
/// hasPromptImages=false — i.e. character portraits silently absent from every scene image.
///
/// Pure derivation, no DB: the group lookup itself is exercised live.
public class TestBookPopulationPath {

    private static final String WORLDS = "/Olio/Universes/Books/Worlds";

    private static List<String> paths(String scenePath, String slug) {
        return PictureBookUtil.bookPopulationPathCandidates(scenePath, slug);
    }

    /// THE LIVE CASE. The world that actually exists is "the-big-way-out-pdf" — the CLIENT slug
    /// rule (pictureBook.js generateSlug, `[^a-z0-9]+ -> '-'`, dots stripped). The SERVER rule
    /// (PbPipelineUtil.deriveSlug, `[^a-z0-9._-]+ -> '-'`) keeps the dot and yields
    /// "the-big-way-out.pdf", so a server-only derivation misses by one character for every
    /// "<name>.pdf" book. Both spellings must be offered until the two generators are converged.
    @Test
    public void TestOffersBothSlugSpellingsBecauseClientAndServerRulesDisagree() {
        List<String> c = paths("/home/steve/Data/PictureBooks/The Big Way Out.pdf/Scenes", null);
        assertTrue("must offer the CLIENT spelling (the one that created the live world): " + c,
            c.contains(WORLDS + "/the-big-way-out-pdf/Population"));
        assertTrue("must still offer the SERVER spelling for books created that way: " + c,
            c.contains(WORLDS + "/the-big-way-out.pdf/Population"));
    }

    /// An explicit slug is authoritative and must be tried FIRST — it is what the book was created
    /// with, so guessing ahead of it risks resolving a DIFFERENT book's world.
    @Test
    public void TestExplicitSlugWinsAndComesFirst() {
        List<String> c = paths("/home/steve/Data/PictureBooks/The Big Way Out.pdf/Scenes", "explicit-slug");
        assertEquals(WORLDS + "/explicit-slug/Population", c.get(0));
    }

    /// No duplicate probes when the two rules agree.
    @Test
    public void TestNoDuplicateCandidatesWhenRulesAgree() {
        List<String> c = paths("/home/u/Data/PictureBooks/My Book Title/Scenes", null);
        assertEquals("both rules give my-book-title: " + c, 1, c.size());
        assertEquals(WORLDS + "/my-book-title/Population", c.get(0));
    }

    /// Not a PB1 scenes group: no candidates, so the caller keeps its prior behaviour instead of
    /// probing an invented path.
    @Test
    public void TestNonSceneGroupPathYieldsNoCandidates() {
        assertTrue(paths("/home/steve/Data/PictureBooks/The Big Way Out.pdf", null).isEmpty());
        assertTrue(paths(null, null).isEmpty());
        assertTrue(paths("", null).isEmpty());
    }

    /// A name that slugs to nothing contributes no candidate — not a malformed path.
    @Test
    public void TestUnslugabbleBookNameYieldsNoCandidates() {
        assertTrue(paths("/home/u/Data/PictureBooks/!!!/Scenes", null).isEmpty());
    }
}
