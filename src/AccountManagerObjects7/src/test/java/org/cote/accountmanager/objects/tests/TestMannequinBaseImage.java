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
import org.cote.accountmanager.util.ResourceUtil;
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

	/// The actual drift guard, over EVERY asset pair rather than "whatever currently resolves".
	///
	/// It previously compared getMannequinBaseImage(gender) against the x512 filename. That silently
	/// coupled the drift check to the resolver's choice, so when the resolver started preferring the
	/// x1024 pair it compared 1024 bytes to the 512 file and failed for a reason that had nothing to do
	/// with drift. Naming each pair explicitly keeps the check about what it is actually for: Objects7's
	/// classpath copy and Ux752's served copy must be the same bytes, for every size that exists.
	@Test
	public void classpathCopiesAreByteIdenticalToUx752() throws Exception {
		for(String file : new String[] { "maleModelx512.png", "femaleModelx512.png",
				"maleModelx1024.png", "femaleModelx1024.png" }) {
			byte[] classpath = ResourceUtil.getInstance()
				.getBinaryResource(SDUtil.MANNEQUIN_BASE_RESOURCE_PATH + file);
			assertNotNull("Objects7 is missing " + file + " under " + SDUtil.MANNEQUIN_BASE_RESOURCE_PATH
				+ " — Ux752 has it, so the server and the UI would use different bases", classpath);
			assertArrayEquals("Objects7's " + file + " has drifted from Ux752's copy — the server would "
				+ "seed img2img from a different base than the UI previews", ux752Copy(file), classpath);
		}
	}

	/// The invariant that now matters: whatever the resolver picks must match the generation size
	/// EXACTLY, so the init image reaches SD with no scaling and no letterboxing.
	///
	/// This replaced an assertion that the stored bases were 512x512. That was true when x512 was the
	/// only pair, but generation moved to SDXL-native 1024x1024 (512 produced flat catalog-grid garbage)
	/// and Stephen added a matching x1024 pair, so "the base is 512" stopped being the contract.
	@Test
	public void resolvedBaseMatchesTheGenerationSizeExactly() throws Exception {
		for(String gender : new String[] { "male", "female" }) {
			byte[] base = SDUtil.getMannequinBaseImage(gender);
			assertNotNull(base);
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(base));
			assertEquals("the resolved " + gender + " base must match the generation width, so no "
				+ "scaling or padding is needed", SDUtil.MANNEQUIN_IMAGE_WIDTH, img.getWidth());
			assertEquals("the resolved " + gender + " base must match the generation height",
				SDUtil.MANNEQUIN_IMAGE_HEIGHT, img.getHeight());
		}
	}

	/// Every stored base is SQUARE, at both sizes. The mannequin generation is square for exactly this
	/// reason (it was 512x768, which forced either letterboxing the asset or stretching it), so if a
	/// non-square asset is ever added this is where the mismatch surfaces.
	@Test
	public void storedBasesAreSquare() throws Exception {
		for(String file : new String[] { "maleModelx512.png", "femaleModelx512.png",
				"maleModelx1024.png", "femaleModelx1024.png" }) {
			byte[] data = ResourceUtil.getInstance()
				.getBinaryResource(SDUtil.MANNEQUIN_BASE_RESOURCE_PATH + file);
			assertNotNull(file + " must resolve off the classpath", data);
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
			assertNotNull(file + " must decode as an image", img);
			assertEquals(file + " must be square", img.getWidth(), img.getHeight());
			/// The filename encodes the size (Stephen's convention, {gender}Modelx{SIZE}) and the
			/// resolver looks assets up by it, so a mismatch would make the resolver pick a base whose
			/// real dimensions differ from the generation size.
			int declared = Integer.parseInt(file.replaceAll("^.*Modelx(\\d+)\\.png$", "$1"));
			assertEquals(file + " must actually be the size its name declares", declared, img.getWidth());
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

	/// Aspect must be preserved, not stretched, when fitting IS needed.
	///
	/// Deliberately uses the 512 asset against a 2:3 target rather than the resolved base against the
	/// generation size: the resolved base now matches the generation size exactly, so that combination
	/// is a passthrough and this test would assert nothing while still passing green. Fitting 512x512
	/// into 512x768 scales to 512x512 centered, leaving 128px of white letterbox top and bottom.
	@Test
	public void fitLetterboxesRatherThanStretching() throws Exception {
		byte[] src = ResourceUtil.getInstance()
			.getBinaryResource(SDUtil.MANNEQUIN_BASE_RESOURCE_PATH + "maleModelx512.png");
		assertNotNull(src);
		byte[] fitted = SDUtil.fitMannequinBase(src, 512, 768);
		assertNotNull(fitted);
		BufferedImage img = ImageIO.read(new ByteArrayInputStream(fitted));
		assertEquals(512, img.getWidth());
		assertEquals(768, img.getHeight());

		int white = 0xFFFFFF;
		assertEquals("Top letterbox band must be the pad colour", white, img.getRGB(5, 5) & 0xFFFFFF);
		assertEquals("Top letterbox band must be padded at the far edge", white, img.getRGB(507, 5) & 0xFFFFFF);
		assertEquals("Bottom letterbox band must be the pad colour", white, img.getRGB(5, 763) & 0xFFFFFF);

		// The source occupies y=128..640. Require real content in that band, i.e. the mannequin was
		// drawn inside the canvas rather than scaled away.
		boolean foundContent = false;
		for(int y = 130; y < 638 && !foundContent; y++) {
			if((img.getRGB(256, y) & 0xFFFFFF) != white) foundContent = true;
		}
		assertTrue("The mannequin must be drawn inside the letterboxed band", foundContent);
	}

	/// An already-correct base must pass through untouched rather than be re-encoded. Uses the resolved
	/// base at the generation size, which is the combination the pipeline actually hits - and which
	/// must be a passthrough, since re-encoding Stephen's asset would be pointless lossy work.
	@Test
	public void fitIsAPassthroughWhenDimensionsAlreadyMatch() {
		byte[] base = SDUtil.getMannequinBaseImage("female");
		assertTrue("the resolved base at the generation size must be returned as the SAME array - the "
			+ "pipeline should hand SD the provided png untouched",
			base == SDUtil.fitMannequinBase(base, SDUtil.MANNEQUIN_IMAGE_WIDTH, SDUtil.MANNEQUIN_IMAGE_HEIGHT));
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
