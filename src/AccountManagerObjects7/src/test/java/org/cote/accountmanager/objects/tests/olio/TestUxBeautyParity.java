package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.MemoryReader;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.StatisticsUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// The Ux beauty selector duplicates the composite chain in JavaScript so it can solve for a band on
/// unsaved state without a round trip - the same tradeoff formDef.js already makes for bodyShape
/// against BodyStatsProvider's classifier. Duplication is only safe if something fails when the two
/// drift apart.
///
/// This test and the Vitest test uxBeautyParity.test.js read the SAME fixture,
/// src/test/resources/olio/beautyParityFixture.json. This side runs each stat block through the real
/// provider chain and asserts the fixture is what the server actually computes; the Vitest side
/// asserts formDef.js's computeBeautyFromStats reproduces it. A change to either implementation alone
/// turns one of them red.
public class TestUxBeautyParity extends BaseTest {

	private static final String FIXTURE = "./src/test/resources/olio/beautyParityFixture.json";

	private BaseRecord statsFrom(JsonNode stats) throws FieldException, ModelNotFoundException, ValueException {
		OlioModelNames.use();
		BaseRecord rec = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_STATISTICS);
		for(String f : StatisticsUtil.getBaseStatisticNames()) {
			assertTrue("Fixture stat block is missing base statistic " + f, stats.has(f));
			rec.set(f, stats.get(f).asInt());
		}
		return rec;
	}

	@Test
	public void TestFixtureMatchesProviderChain() {
		try {
			File f = new File(FIXTURE);
			assertTrue("Parity fixture not found at " + f.getAbsolutePath(), f.exists());
			String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			JsonNode root = new ObjectMapper().readTree(json);
			assertNotNull("Parity fixture did not parse", root);
			JsonNode cases = root.get("cases");
			assertNotNull("Parity fixture has no 'cases'", cases);
			assertTrue("Parity fixture has no cases", cases.size() > 0);

			List<String> drift = new ArrayList<>();
			for(JsonNode c : cases) {
				String name = c.get("name").asText();
				BaseRecord rec = statsFrom(c.get("stats"));
				new MemoryReader().inspect(rec);
				int beauty = rec.get("beauty");
				String label = NarrativeUtil.getBeautyLabel(beauty);
				int expectedBeauty = c.get("beauty").asInt();
				String expectedLabel = c.get("label").asText();
				if(beauty != expectedBeauty || !label.equals(expectedLabel)) {
					drift.add(name + ": fixture says beauty " + expectedBeauty + " (" + expectedLabel
						+ "), provider chain computed " + beauty + " (" + label + ")");
				}
			}
			if(!drift.isEmpty()) {
				StringBuilder buff = new StringBuilder("Ux parity fixture no longer matches the server composite chain:\n");
				drift.forEach(d -> buff.append("  ").append(d).append("\n"));
				buff.append("If the change to the composite was intended, regenerate the fixture and the Ux\n");
				buff.append("formDef.js computeBeautyFromStats chain together.");
				throw new AssertionError(buff.toString());
			}
			logger.info("Ux beauty parity: " + cases.size() + " stat blocks match the provider chain");
		}
		catch(FieldException | ModelNotFoundException | ValueException | ReaderException | java.io.IOException e) {
			logger.error(e);
			throw new RuntimeException(e);
		}
	}

	/// The fixture is only a useful guard if it actually spans the bands. A fixture that only covered
	/// the middle would let the ends of the scale drift unnoticed.
	@Test
	public void TestFixtureCoversEveryBand() {
		try {
			String json = new String(Files.readAllBytes(new File(FIXTURE).toPath()), StandardCharsets.UTF_8);
			JsonNode cases = new ObjectMapper().readTree(json).get("cases");
			List<String> seen = new ArrayList<>();
			for(JsonNode c : cases) {
				String l = c.get("label").asText();
				if(!seen.contains(l)) seen.add(l);
			}
			for(String expected : new String[] {"hideous", "homely", "bland", "comely", "pretty", "beautiful", "gorgeous"}) {
				assertTrue("Parity fixture never exercises the '" + expected + "' band", seen.contains(expected));
			}
			assertEquals("Parity fixture should not contain the fallback label", false, seen.contains("indescribable"));
		}
		catch(java.io.IOException e) {
			logger.error(e);
			throw new RuntimeException(e);
		}
	}
}
