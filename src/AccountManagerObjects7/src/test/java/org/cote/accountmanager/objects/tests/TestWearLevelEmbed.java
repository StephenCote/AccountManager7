package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.WearLevelEnumType;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/// The embedded wearable format "category:name:LEVEL:gender:location" accepts the WearLevelEnumType
/// name (what the chat wardrobe tokens teach the LLM, aiDocs/ChatWardrobeTokensDesign.md §3.5) as
/// well as the numeric value the clothing/jewelry catalogs use. Exercises the real builder
/// (ApparelUtil.constructApparelFromEmbedded -> applyEmbeddedWearable), in memory, not persisted.
public class TestWearLevelEmbed extends BaseTest {

	@Test
	public void TestFromToken() {
		assertEquals(WearLevelEnumType.BASE, WearLevelEnumType.fromToken("BASE"));
		assertEquals(WearLevelEnumType.SUIT, WearLevelEnumType.fromToken("suit"));
		assertEquals(WearLevelEnumType.FULL_BODY, WearLevelEnumType.fromToken("full body"));
		assertEquals(WearLevelEnumType.FULL_BODY, WearLevelEnumType.fromToken("FULL_BODY"));
		assertEquals(WearLevelEnumType.BASE, WearLevelEnumType.fromToken("4"));
		assertEquals(WearLevelEnumType.OVER, WearLevelEnumType.fromToken(" 9 "));
		assertEquals(WearLevelEnumType.UNKNOWN, WearLevelEnumType.fromToken("99"));
		assertEquals(WearLevelEnumType.UNKNOWN, WearLevelEnumType.fromToken("underwear"));
		assertEquals(WearLevelEnumType.UNKNOWN, WearLevelEnumType.fromToken(""));
		assertEquals(WearLevelEnumType.UNKNOWN, WearLevelEnumType.fromToken(null));
	}

	@Test
	public void TestEmbeddedOutfitWithEnumNames() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/WearLevelEmbed");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "wearEmbedUser", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);
		long ownerId = testUser.get(FieldNames.FIELD_ID);

		/// The exact example the prompt templates teach, plus the corsage, a numeric (catalog) entry,
		/// and one bad level that must be skipped rather than abort the outfit.
		String[] embedded = new String[] {
			"clothing:boxer shorts:BASE:m:waist",
			"clothing:undershirt:BASE:m:torso",
			"clothing:tuxedo pants:SUIT:m:hip+leg",
			"clothing:tuxedo shirt:SUIT:m:torso+arm",
			"clothing:tuxedo jacket:OVER:m:torso+arm",
			"clothing:shoes:SUIT:m:foot",
			"jewelry:corsage:ACCESSORY:u:chest",
			"clothing:socks:6:m:foot",
			"clothing:mystery:NOT_A_LEVEL:m:torso"
		};
		BaseRecord app = ApparelUtil.constructApparelFromEmbedded(null, ownerId, "male", embedded);
		assertNotNull("Apparel should be constructed", app);

		List<BaseRecord> wears = app.get(OlioFieldNames.FIELD_WEARABLES);
		Map<String, WearLevelEnumType> byName = new HashMap<>();
		for (BaseRecord w : wears) {
			byName.put(w.get(FieldNames.FIELD_NAME), w.getEnum(OlioFieldNames.FIELD_LEVEL));
		}
		logger.info("Constructed wearables: " + byName);

		assertEquals("8 valid entries, the bad level skipped: " + byName, 8, wears.size());
		assertEquals(WearLevelEnumType.BASE, byName.get("boxer shorts"));
		assertEquals(WearLevelEnumType.BASE, byName.get("undershirt"));
		assertEquals(WearLevelEnumType.SUIT, byName.get("tuxedo pants"));
		assertEquals(WearLevelEnumType.OVER, byName.get("tuxedo jacket"));
		assertEquals(WearLevelEnumType.ACCESSORY, byName.get("corsage"));
		assertEquals("Numeric catalog levels still work", WearLevelEnumType.SUIT, byName.get("socks"));
		assertTrue("Bad level must be skipped", !byName.containsKey("mystery"));

		BaseRecord pants = wears.stream().filter(w -> "tuxedo pants".equals(w.get(FieldNames.FIELD_NAME))).findFirst().get();
		List<String> locs = pants.get(FieldNames.FIELD_LOCATION);
		assertTrue("hip+leg should split into two locations: " + locs, locs.contains("hip") && locs.contains("leg"));
	}
}
