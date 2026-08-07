package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.junit.Test;

/// Pure, deterministic unit tests (no DB / no LLM / no SD server) for the mannequin base images that
/// SDUtil.generateMannequinImages seeds img2img from.
///
/// Objects7 needs these on its classpath so mannequin generation works wherever the jar runs (Tomcat,
/// the Docker image), not just from a dev checkout. Ux752 needs its own copies under public/media
/// because it serves them over HTTP and cannot read inside Objects7's jar. That duplication is
/// deliberate and these tests are the guard on it: if someone edits or replaces one copy, the server
/// would start seeding from a different base than the UI previews, and the only symptom would be
/// subtly wrong images. Byte equality is asserted rather than "both files exist".
public class TestMannequinBaseImage {
	public static final Logger logger = LogManager.getLogger(TestMannequinBaseImage.class);

	/// Tests run with the module directory as CWD, so Ux752 is a sibling of AccountManagerObjects7.
	private static final String UX752_MEDIA = "../AccountManagerUx752/public/media/";

	private static byte[] ux752Copy(String fileName) throws Exception {
		File f = new File(UX752_MEDIA + fileName);
		assertTrue("Ux752's copy of " + fileName + " must exist at " + UX752_MEDIA
			+ " — if it moved, Objects7's copy under olio/media/ has to move with it", f.exists());
		return Files.readAllBytes(f.toPath());
	}

	@Test
	public void bothBaseImagesResolveOffTheClasspath() {
		byte[] male = SDUtil.getMannequinBaseImage("male");
		byte[] female = SDUtil.getMannequinBaseImage("female");
		assertNotNull("The male mannequin base must resolve from " + SDUtil.MANNEQUIN_BASE_RESOURCE_PATH, male);
		assertNotNull("The female mannequin base must resolve from " + SDUtil.MANNEQUIN_BASE_RESOURCE_PATH, female);
		assertTrue("The male base must not be empty", male.length > 0);
		assertTrue("The female base must not be empty", female.length > 0);
		assertTrue("The two bases must be different images", !java.util.Arrays.equals(male, female));
	}

	/// The actual drift guard.
	@Test
	public void classpathCopiesAreByteIdenticalToUx752() throws Exception {
		assertArrayEquals("Objects7's maleModelx512.png has drifted from Ux752's copy — the server would "
			+ "seed img2img from a different base than the UI previews",
			ux752Copy(SDUtil.MANNEQUIN_BASE_MALE), SDUtil.getMannequinBaseImage("male"));
		assertArrayEquals("Objects7's femaleModelx512.png has drifted from Ux752's copy — the server would "
			+ "seed img2img from a different base than the UI previews",
			ux752Copy(SDUtil.MANNEQUIN_BASE_FEMALE), SDUtil.getMannequinBaseImage("female"));
	}

	/// The stored bases are SQUARE (512x512) even though the file name says "x512" and Ux752's
	/// getMannequinBaseUrl hands them out for its "512x768" size. Pinned here because
	/// fitMannequinBase's whole reason to exist is this mismatch — if someone later replaces these
	/// with genuinely 512x768 art, fitMannequinBase becomes a no-op passthrough and this test is
	/// where that shows up.
	@Test
	public void storedBasesAreSquare512() throws Exception {
		for(String gender : new String[] { "male", "female" }) {
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(SDUtil.getMannequinBaseImage(gender)));
			assertNotNull("The " + gender + " base must decode as an image", img);
			assertEquals("The " + gender + " base width", 512, img.getWidth());
			assertEquals("The " + gender + " base height", 512, img.getHeight());
		}
	}

	/// The real guard: whatever the stored base is, what actually reaches SD as the init image must
	/// match generateMannequinImages' requested output exactly. A 1:1 init image against a 2:3
	/// request gets stretched by the backend, which elongates the mannequin — wrong in a way that
	/// looks like a prompt problem.
	@Test
	public void fittedBaseMatchesTheGeneratedOutputDimensions() throws Exception {
		for(String gender : new String[] { "male", "female" }) {
			byte[] fitted = SDUtil.fitMannequinBase(SDUtil.getMannequinBaseImage(gender),
				SDUtil.MANNEQUIN_IMAGE_WIDTH, SDUtil.MANNEQUIN_IMAGE_HEIGHT);
			assertNotNull("fitMannequinBase must produce an image for " + gender, fitted);
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(fitted));
			assertEquals("Fitted " + gender + " base width must equal the requested output width",
				SDUtil.MANNEQUIN_IMAGE_WIDTH, img.getWidth());
			assertEquals("Fitted " + gender + " base height must equal the requested output height",
				SDUtil.MANNEQUIN_IMAGE_HEIGHT, img.getHeight());
		}
	}

	/// Aspect must be preserved, not stretched. A 512x512 source into a 512x768 canvas scales to
	/// 512x512 centered, leaving 128px of white letterbox top and bottom. Sampling the corners proves
	/// the padding is white (matching the prompt's "white seamless background") and the vertical
	/// centre band still carries actual mannequin content.
	@Test
	public void fittedBaseIsLetterboxedNotStretched() throws Exception {
		byte[] fitted = SDUtil.fitMannequinBase(SDUtil.getMannequinBaseImage("male"),
			SDUtil.MANNEQUIN_IMAGE_WIDTH, SDUtil.MANNEQUIN_IMAGE_HEIGHT);
		BufferedImage img = ImageIO.read(new ByteArrayInputStream(fitted));

		int white = 0xFFFFFF;
		assertEquals("Top letterbox band must be white", white, img.getRGB(5, 5) & 0xFFFFFF);
		assertEquals("Top letterbox band must be white at the far edge", white,
			img.getRGB(SDUtil.MANNEQUIN_IMAGE_WIDTH - 5, 5) & 0xFFFFFF);
		assertEquals("Bottom letterbox band must be white", white,
			img.getRGB(5, SDUtil.MANNEQUIN_IMAGE_HEIGHT - 5) & 0xFFFFFF);

		// The source is drawn from y=128 to y=640. Scan the centre column across that band and
		// require at least one non-white pixel, i.e. the mannequin actually landed inside the canvas.
		boolean foundContent = false;
		for(int y = 130; y < 638 && !foundContent; y++) {
			if((img.getRGB(SDUtil.MANNEQUIN_IMAGE_WIDTH / 2, y) & 0xFFFFFF) != white) foundContent = true;
		}
		assertTrue("The mannequin must be drawn inside the letterboxed band, not scaled away", foundContent);
	}

	/// An already-correct base must pass through untouched rather than be re-encoded.
	@Test
	public void fitIsAPassthroughWhenDimensionsAlreadyMatch() {
		byte[] base = SDUtil.getMannequinBaseImage("female");
		assertTrue("A base already at the target size must be returned as the same array",
			base == SDUtil.fitMannequinBase(base, 512, 512));
	}

	/// A missing/undecodable base must return null so generateMannequinImages can fall back to
	/// text-only generation instead of throwing mid-apparel-set.
	@Test
	public void fitReturnsNullOnUnusableInput() {
		org.junit.Assert.assertNull("null in, null out", SDUtil.fitMannequinBase(null, 512, 768));
		org.junit.Assert.assertNull("empty in, null out", SDUtil.fitMannequinBase(new byte[0], 512, 768));
		org.junit.Assert.assertNull("non-image bytes must not throw",
			SDUtil.fitMannequinBase("not an image".getBytes(), 512, 768));
	}

	/// Gender resolution mirrors Ux752's getMannequinBaseUrl, which is a plain
	/// `(gender === "male") ? maleModel : femaleModel` — so everything that is not "male" (including
	/// null and "unisex", both real values for apparel.gender) must take the female base. This is the
	/// contract, not an accident; assert it so a future "improvement" to the fallback is a test failure.
	@Test
	public void nonMaleGendersFallBackToTheFemaleBase() {
		byte[] female = SDUtil.getMannequinBaseImage("female");
		assertArrayEquals("null gender must fall back to the female base", female, SDUtil.getMannequinBaseImage(null));
		assertArrayEquals("'unisex' must fall back to the female base", female, SDUtil.getMannequinBaseImage("unisex"));
		assertArrayEquals("an unrecognized gender must fall back to the female base", female,
			SDUtil.getMannequinBaseImage("nonbinary"));
	}

	/// apparel.gender is free-text (olio/apparelModel.json: string, maxLength 6, "Male, female,
	/// unisex") and ApparelUtil writes lowercase, but the model's own description capitalizes "Male".
	/// Case-insensitive matching is therefore required, not optional.
	@Test
	public void maleMatchIsCaseInsensitive() {
		byte[] male = SDUtil.getMannequinBaseImage("male");
		assertArrayEquals("'Male' (as the apparel model's description spells it) must resolve the male base",
			male, SDUtil.getMannequinBaseImage("Male"));
		assertArrayEquals("'MALE' must resolve the male base", male, SDUtil.getMannequinBaseImage("MALE"));
	}
}
