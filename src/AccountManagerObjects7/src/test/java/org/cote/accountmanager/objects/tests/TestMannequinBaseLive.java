package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.List;

import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.swarm.SWImageResponse;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.olio.WearLevelEnumType;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.junit.Test;

import jakarta.ws.rs.core.MediaType;

/// Live diagnostic for the BLANK WHITE mannequin images.
///
/// Reported 2026-08-07: mannequin apparel images came back as blank white pictures. The reported
/// generation carried "Init Image Creativity: 0.75" and completed in 4.55s. Two causes were found by
/// reading the code, and this test exists to settle the second one by measurement rather than theory:
///
///  1. CONFIRMED BY INSPECTION: the intended 0.6 could never apply. denoisingStrength carries a 0.75
///     schema default, so `get() != null ? get() : 0.6` never reached the fallback. Fixed with a
///     dedicated mannequinCreativity field.
///  2. TO BE MEASURED HERE: whether seeding img2img from the mannequin base produces white at all.
///     The base asset is a white mannequin on a white ground, the prompt's most-weighted token is
///     ((white seamless background)), and getMannequinNegativePrompt negates the entire human form
///     (skin, hands, fingers, feet, toes, skin texture). That combination plausibly converges on
///     plain white regardless of denoise.
///
/// Generation is ~4.5s, so a sweep is cheap. Every output is written to media/mannequin-live/ to be
/// LOOKED AT - "an image came back" is not a pass for an image pipeline, and a blank white PNG decodes
/// perfectly. whiteFraction() below is the objective check that a human eye would otherwise have to
/// make; it is reported for every variant, and the no-init baseline is the control.
///
/// Run: MANNEQUIN_LIVE=1 mvn -o -pl AccountManagerObjects7 -Dtest=TestMannequinBaseLive test
public class TestMannequinBaseLive {
	public static final Logger logger = LogManager.getLogger(TestMannequinBaseLive.class);

	private static final String OUT = "./media/mannequin-live/";

	/// Built by the REAL NarrativeUtil.getMannequinPrompt from a synthetic apparel record, not a
	/// hardcoded copy - a copy would let the test keep passing against prompt text the pipeline no
	/// longer sends, which is exactly how a prompt regression hides. The garments match the reported
	/// failure so the comparison stays like-for-like.
	private static final String[][] OUTFIT = {
		{ "Dark Yellow Gore-Tex t-shirt", "BASE" },
		{ "Tan Holland cloth cargo pants", "BASE" },
		{ "Rackley Satin socks", "BASE" },
		{ "Tan Serge shoes", "ON" },
		{ "Cornell Red Shot silk leather jacket", "SUIT" }
	};

	private static String prompt() throws Exception {
		OlioModelNames.use();
		RecordFactory.model(OlioModelNames.MODEL_APPAREL);
		RecordFactory.model(OlioModelNames.MODEL_WEARABLE);
		BaseRecord apparel = RecordFactory.newInstance(OlioModelNames.MODEL_APPAREL);
		List<BaseRecord> wears = apparel.get(OlioFieldNames.FIELD_WEARABLES);
		for(String[] o : OUTFIT) {
			BaseRecord w = RecordFactory.newInstance(OlioModelNames.MODEL_WEARABLE);
			w.set(FieldNames.FIELD_NAME, o[0]);
			w.set(OlioFieldNames.FIELD_LEVEL, o[1]);
			wears.add(w);
		}
		/// style "art" on purpose: the book config pins a style, and appending it raw is what turned
		/// this into flat illustration. The assertion below proves it no longer leaks in.
		RecordFactory.model(OlioModelNames.MODEL_SD_CONFIG);
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		cfg.set("style", "art");
		return NarrativeUtil.getMannequinPrompt(apparel, WearLevelEnumType.SUIT, cfg);
	}

	private static final String NEG_PROMPT = NarrativeUtil.getMannequinNegativePrompt();

	private static int envInt(String name, int def) {
		String v = System.getenv(name);
		if(v == null || v.isBlank()) return def;
		try { return Integer.parseInt(v.trim()); } catch(NumberFormatException e) { return def; }
	}

	private static boolean liveEnabled() {
		return "1".equals(System.getenv("MANNEQUIN_LIVE")) || "1".equals(System.getProperty("mannequin.live"));
	}

	/// Fraction of near-white pixels. A blank/white-collapsed render sits near 1.0; a mannequin on a
	/// white studio ground still leaves a substantial non-white figure.
	private static double whiteFraction(BufferedImage img) {
		int white = 0;
		int total = 0;
		for(int y = 0; y < img.getHeight(); y += 2) {
			for(int x = 0; x < img.getWidth(); x += 2) {
				int rgb = img.getRGB(x, y);
				int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
				if(r > 243 && g > 243 && b > 243) white++;
				total++;
			}
		}
		return total == 0 ? 1.0 : (double) white / total;
	}

	private static double runVariant(SDUtil sdu, String server, String label, String prompt, String base64, Double creativity)
			throws Exception {
		SWTxt2Img s2i = new SWTxt2Img();
		s2i.setPrompt(prompt);
		s2i.setNegativePrompt(NEG_PROMPT);
		s2i.setWidth(envInt("MANNEQUIN_W", SDUtil.MANNEQUIN_IMAGE_WIDTH));
		s2i.setHeight(envInt("MANNEQUIN_H", SDUtil.MANNEQUIN_IMAGE_HEIGHT));
		s2i.setSteps(envInt("MANNEQUIN_STEPS", 20));
		s2i.setModel(SDUtil.resolveModel(null));
		s2i.setScheduler("Karras");
		s2i.setSampler("dpmpp_2m");
		s2i.setCfgScale(7);
		s2i.setSeed(424242);
		s2i.setImages(1);
		s2i.setRefinerControlPercentage(0.0);
		if(base64 != null) {
			s2i.setInitImage(base64);
			s2i.setInitImageCreativity(creativity);
		}

		SWImageResponse rep = sdu.txt2img(s2i);
		assertNotNull("no response from Swarm for " + label, rep);
		assertNotNull("Swarm error for " + label + ": " + rep.getError(), rep.getImages());
		assertTrue("no images for " + label, !rep.getImages().isEmpty());

		byte[] data = ClientUtil.get(byte[].class, ClientUtil.getResource(server + "/" + rep.getImages().get(0)),
			null, MediaType.APPLICATION_OCTET_STREAM_TYPE);
		assertNotNull("could not fetch " + label, data);
		BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
		assertNotNull(label + " did not decode", img);
		double wf = whiteFraction(img);

		new File(OUT).mkdirs();
		File f = new File(OUT + label + ".png");
		Files.write(f.toPath(), data);
		logger.info("MANNEQUIN VARIANT " + label + ": " + img.getWidth() + "x" + img.getHeight()
			+ " whiteFraction=" + String.format("%.3f", wf) + " -> " + f.getName());
		return wf;
	}

	@Test
	public void sweepInitImageCreativityAndCompareToTextOnly() throws Exception {
		if(!liveEnabled()) {
			logger.info("Skipping live mannequin sweep (set MANNEQUIN_LIVE=1 to run)");
			return;
		}
		String server = System.getenv("MANNEQUIN_SERVER");
		if(server == null || server.isEmpty()) server = "http://localhost:7801";
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, server);

		byte[] base = SDUtil.getMannequinBaseImage("male");
		assertNotNull("the provided mannequin png must load off the classpath", base);
		BufferedImage baseImg = ImageIO.read(new ByteArrayInputStream(base));
		logger.info("MANNEQUIN BASE (as provided, unmodified): " + baseImg.getWidth() + "x" + baseImg.getHeight()
			+ " whiteFraction=" + String.format("%.3f", whiteFraction(baseImg)));
		String base64 = BinaryUtil.toBase64Str(base);

		String p = prompt();
		logger.info("MANNEQUIN PROMPT (from NarrativeUtil.getMannequinPrompt): " + p);
		assertFalse("the raw sdConfig style must no longer leak into the mannequin prompt - appending "
			+ "', art' to a photography prompt is what rendered flat illustration", p.endsWith(", art"));
		assertFalse("((fashion catalog)) must be gone - it asked for a catalog PAGE and produced a grid "
			+ "of separate garments with no wearer", p.contains("fashion catalog"));

		/// Control first: no init image at all. This is the path that was producing usable mannequins,
		/// so its whiteFraction is the number every other variant has to be judged against.
		double control = runVariant(sdu, server, "00-no-init-image", p, null, null);

		for(double c : new double[] { 0.40, 0.55, 0.70, 0.85, 0.95 }) {
			runVariant(sdu, server, "init-creativity-" + String.format("%.2f", c), p, base64, c);
		}

		logger.info("MANNEQUIN SWEEP done. Control (text-only) whiteFraction=" + String.format("%.3f", control)
			+ ". Inspect " + new File(OUT).getAbsolutePath() + " - a variant whose whiteFraction is far "
			+ "above the control is the blank-white failure.");
	}
}
