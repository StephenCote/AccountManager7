package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbOlioContextUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * A book shared in the product's shape - the per-book Writer role PLUS the universe Reader role, which is
 * what {@code PbSharingUtil.shareBook} enrols and refuses to do half of - must remain readable by the
 * non-owner it was shared with, with the book's foreign {@code world} populated.
 * <p>
 * Why this needs its own test: {@code PbBookUtil.readBook} projects {@code world}, and
 * {@code PolicyUtil.getSchemaRules} turns every populated, followed foreign field into a dynamic
 * "reading the parent requires reading what it links to" rule. The {@code olio.world} record lives in
 * {@code /Olio/Universes/Books/Worlds}, which is NOT one of the book's own groups: it is a child of the
 * universe container, so the only grant that reaches it is the universe tier's
 * ({@code configureWorldAuthorization(universe, ..., universePath, false)}). Until the policy cache was
 * keyed by resource content (2026-10-08) the query-shape policy answered the full-record evaluation and
 * that rule was never applied in a find, so nothing measured whether the universe tier actually covers
 * the world record. {@code TestPbSecurity} asserts the two-tier split negatively (case 6) and never
 * asserts that a shared user can read a book at all. This does.
 * <p>
 * No LLM and no SD server: {@code PbBookUtil.createBook} builds the world skeleton only. Enrolment is
 * performed by the organization admin purely as the fixture grantor (the same shape as
 * {@code TestPbSecurity.case06}); every read under test is made by a non-admin.
 */
public class TestPbSharedBookRead extends BaseTest {

	/** The already-seeded Books universe org that TestPbSecurity uses (avoids the multi-minute seed). */
	private static final String ORG_A = "/Development/World Building";

	private static final String OWNER_NAME = "pbShrOwner";
	private static final String READER_NAME = "pbShrReader";
	/// Never enrolled in the universe tier by any test, so the book-tier-only measurement stays honest
	/// across runs (role membership persists in the database).
	private static final String BOOK_TIER_READER_NAME = "pbShrBookTier";

	@Before
	public void sharedReadSetup() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	private OrganizationContext org() {
		return getTestOrganization(ORG_A);
	}

	private BaseRecord user(String name) {
		OrganizationContext o = org();
		BaseRecord u = ioContext.getFactory().getCreateUser(o.getAdminUser(), name, o.getOrganizationId());
		assertNotNull("Failed to resolve test user " + name, u);
		return u;
	}

	private String dataPath() {
		return testProperties.getProperty("test.datagen.path");
	}

	private BaseRecord role(String path) {
		OrganizationContext o = org();
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, o.getOrganizationId());
		assertNotNull("No olio principal", olioUser);
		return ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE, path,
			RoleEnumType.USER.toString(), o.getOrganizationId());
	}

	@Test
	public void sharedUserInBothTiersCanReadTheBookWithItsWorld() throws Exception {
		String dataPath = dataPath();
		assertNotNull("test.datagen.path must be set", dataPath);
		OrganizationContext o = org();
		BaseRecord owner = user(OWNER_NAME);
		BaseRecord reader = user(READER_NAME);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		assertTrue("Both users must share the organization",
			((Number) reader.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue() == orgId);
		assertFalse("Precondition: the reader must not be an administrator, or the read proves nothing",
			ioContext.getAuthorizationUtil().isModelAdministrator(ModelNames.MODEL_GROUP, reader));

		String slug = "pb-shr-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord created = PbBookUtil.createBook(owner, dataPath, slug, "Shared read fixture " + slug);
		assertNotNull("Failed to create book " + slug, created);
		String bookOid = created.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull(bookOid);

		/// Positive control: the creator reads the book with its world populated.
		BaseRecord byOwner = PbBookUtil.readBook(owner, bookOid, orgId);
		assertNotNull("The creator must be able to read the book it created", byOwner);
		assertNotNull("The creator's read must carry the world", byOwner.get(OlioFieldNames.FIELD_PB_WORLD));

		/// Precondition: before sharing, the reader holds neither tier and cannot read the book.
		BaseRecord writerRole = role(PbOlioContextUtil.writerRolePath(slug));
		BaseRecord uniReader = role(PbOlioContextUtil.universeReaderRolePath());
		assertNotNull(writerRole);
		assertNotNull(uniReader);
		assertFalse("Precondition: the reader is not yet in the book Writer role",
			ioContext.getMemberUtil().isMember(reader, writerRole, null));
		CacheUtil.clearCache();
		BaseRecord beforeShare = PbBookUtil.readBook(reader, bookOid, orgId);
		assertTrue("Precondition: an un-enrolled user must not read the book (got " + beforeShare + ")", beforeShare == null);

		/// Enrol the reader in BOTH tiers - the shape shareBook produces - as the org admin (fixture grantor).
		OlioContext ctx = PbOlioContextUtil.getCreateBookContext(owner, dataPath, slug);
		assertTrue(ctx.isAuthorizationConfigured());
		assertTrue("Failed to enrol the reader in the book tier", ctx.registerUser(o.getAdminUser(), reader, false));
		assertTrue("Failed to enrol the reader in the universe tier", ctx.registerUniverseUser(o.getAdminUser(), reader, false));
		assertTrue(ioContext.getMemberUtil().isMember(reader, writerRole, null));
		assertTrue(ioContext.getMemberUtil().isMember(reader, uniReader, null));
		CacheUtil.clearCache();

		/// The assertion: the shared non-owner reads the book, and the read carries the world record, which
		/// is reachable only through the universe tier's grant on /Olio/Universes/Books/Worlds.
		BaseRecord byReader = PbBookUtil.readBook(reader, bookOid, orgId);
		assertNotNull("A user enrolled in both tiers must be able to read the shared book", byReader);
		BaseRecord world = byReader.get(OlioFieldNames.FIELD_PB_WORLD);
		assertNotNull("The shared read must carry the book's world - the foreign-read rule on world is what"
			+ " the universe tier has to satisfy", world);
		logger.info("Shared read OK: book {} world id={} read by {}", slug, world.get(FieldNames.FIELD_ID),
			reader.get(FieldNames.FIELD_NAME));
	}

	/**
	 * The book tier ALONE must be enough to read the book with its world. Before 2026-10-09 it was not:
	 * the only grant that reached the {@code olio.world} record was the universe tier's grant on the
	 * {@code Worlds} container, so a holder of just the per-book role failed the foreign-read rule on
	 * {@code olio.pb.book.world}. {@code configureWorldAuthorization} now gives both per-book roles
	 * {@code Read} (DATA) on the world record itself. This measures both the entitlement as written and
	 * the read it is meant to unlock, with a user who is verifiably NOT in the universe Reader role.
	 */
	@Test
	public void bookTierAloneCanReadTheBookWithItsWorld() throws Exception {
		String dataPath = dataPath();
		assertNotNull("test.datagen.path must be set", dataPath);
		OrganizationContext o = org();
		BaseRecord owner = user(OWNER_NAME);
		BaseRecord reader = user(BOOK_TIER_READER_NAME);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		assertFalse("Precondition: the reader must not be an administrator, or the read proves nothing",
			ioContext.getAuthorizationUtil().isModelAdministrator(ModelNames.MODEL_GROUP, reader));

		String slug = "pb-bt-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord created = PbBookUtil.createBook(owner, dataPath, slug, "Book-tier read fixture " + slug);
		assertNotNull("Failed to create book " + slug, created);
		String bookOid = created.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord byOwner = PbBookUtil.readBook(owner, bookOid, orgId);
		assertNotNull(byOwner);
		BaseRecord world = byOwner.get(OlioFieldNames.FIELD_PB_WORLD);
		assertNotNull("The creator's read must carry the world", world);

		BaseRecord writerRole = role(PbOlioContextUtil.writerRolePath(slug));
		BaseRecord adminRole = role(PbOlioContextUtil.adminRolePath(slug));
		BaseRecord uniReader = role(PbOlioContextUtil.universeReaderRolePath());
		assertNotNull(writerRole);
		assertNotNull(adminRole);
		assertNotNull(uniReader);

		/// The entitlement as written: Read (DATA) held by each per-book role directly on the world record.
		/// Resolving the permission constant as the org admin is fixture lookup, the same lookup
		/// setEntitlement itself performs.
		BaseRecord readPerm = ioContext.getPathUtil().findPath(o.getAdminUser(), ModelNames.MODEL_PERMISSION,
			"/Read", PermissionEnumType.DATA.toString(), orgId);
		assertNotNull("No /Read DATA permission", readPerm);
		assertTrue("The book Writer role must hold Read (DATA) on the world record",
			ioContext.getAuthorizationUtil().checkEntitlement(writerRole, readPerm, world));
		assertTrue("The book Admin role must hold Read (DATA) on the world record",
			ioContext.getAuthorizationUtil().checkEntitlement(adminRole, readPerm, world));

		/// Un-enrolled: no read, and no entitlement on the world through any role.
		assertFalse(ioContext.getMemberUtil().isMember(reader, writerRole, null));
		assertFalse("Precondition: the reader must not be in the universe Reader role",
			ioContext.getMemberUtil().isMember(reader, uniReader, null));
		assertFalse("Precondition: an un-enrolled user holds no Read on the world record",
			ioContext.getAuthorizationUtil().checkEntitlement(reader, readPerm, world));
		CacheUtil.clearCache();
		assertTrue("Precondition: an un-enrolled user must not read the book",
			PbBookUtil.readBook(reader, bookOid, orgId) == null);

		/// Book tier ONLY, as the org admin (fixture grantor). registerUniverseUser is deliberately not called.
		OlioContext ctx = PbOlioContextUtil.getCreateBookContext(owner, dataPath, slug);
		assertTrue(ctx.isAuthorizationConfigured());
		assertTrue("Failed to enrol the reader in the book tier", ctx.registerUser(o.getAdminUser(), reader, false));
		assertTrue(ioContext.getMemberUtil().isMember(reader, writerRole, null));
		assertFalse("The reader must still not be in the universe Reader role, or this measures the old path",
			ioContext.getMemberUtil().isMember(reader, uniReader, null));
		assertTrue("Book-tier membership must confer Read on the world record through the role",
			ioContext.getAuthorizationUtil().checkEntitlement(reader, readPerm, world));
		CacheUtil.clearCache();

		BaseRecord byReader = PbBookUtil.readBook(reader, bookOid, orgId);
		assertNotNull("A user enrolled in the book tier alone must be able to read the book", byReader);
		BaseRecord readWorld = byReader.get(OlioFieldNames.FIELD_PB_WORLD);
		assertNotNull("The book-tier read must carry the book's world - the record grant is what satisfies"
			+ " the foreign-read rule without the universe tier", readWorld);
		logger.info("Book-tier read OK: book {} world id={} read by {}", slug, readWorld.get(FieldNames.FIELD_ID),
			reader.get(FieldNames.FIELD_NAME));
	}
}
