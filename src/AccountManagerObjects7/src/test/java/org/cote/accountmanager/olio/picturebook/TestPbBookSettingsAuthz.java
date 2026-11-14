package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 §24 I-5 (216 d): the book-settings write is gated on the PERSISTED book like every other write into a
 * book's graph - {@link PbBookUtil#requireBookUpdate} (re-read as the caller with the 286 {@code authorizingView}
 * shape, UPDATE required) - so a user who may READ the book but not update it is refused with a 403 before
 * anything is written, and a stranger with a 404, never a leak and never a 500 from the config row's own
 * create/update failing late.
 * <p>
 * Three users in {@code /Development/ChapBook Tests} (the same stable org as {@code TestChapBookBookSettings},
 * so the Olio seed is already there): the <b>owner</b> creates the ChapBook; the <b>reader</b> is granted, by
 * the organization's own admin, Read (DATA + GROUP) on the book group and Read (DATA) on the book's world and
 * nothing else - the {@code TestPbCompositionMigration.grantedLimitedUser} shape minus its Update on the book
 * row; the <b>stranger</b> holds no grant at all. Live {@code am7db}; no LLM, no GPU. Every count is an
 * uncached {@code AccessPoint.count} as the owner.
 * <ol>
 * <li>The reader READS the book settings (null on a fresh book, the stored row once the owner set it) - the
 * read side is untouched by the gate.</li>
 * <li>The reader's set is a 403 on a fresh book (no row created) and again once a row exists (the row
 * unchanged).</li>
 * <li>The stranger's set is a 404 through {@code ChapBookUtil} (cannot resolve the book) AND through
 * {@code PbBookUtil.setBookSdConfig} handed the owner's own book record (the gate re-reads as the caller).</li>
 * <li>The owner's set still works before, between and after the refusals.</li>
 * </ol>
 */
public class TestPbBookSettingsAuthz extends BaseTest {

	private static final String ORG_PATH = "/Development/ChapBook Tests";

	private static final String POEM_TEXT =
		"Outside, all is pristine,\n" +
		"From cobalt skies of charcoal unity\n" +
		"Descending upon snow canvassed green\n" +
		"To silver veins of icy sheens,\n" +
		"Born of spells and sorcery.";

	private OrganizationContext ctx;
	private BaseRecord owner;
	private BaseRecord reader;
	private BaseRecord stranger;
	private long orgId;

	@Before
	public void setUpUsers() {
		ctx = getTestOrganization(ORG_PATH);
		assertNotNull(ctx);
		orgId = ctx.getOrganizationId();
		owner = IOSystem.getActiveContext().getFactory().getCreateUser(ctx.getAdminUser(), "pbSettingsAuthzOwner", orgId);
		reader = IOSystem.getActiveContext().getFactory().getCreateUser(ctx.getAdminUser(), "pbSettingsAuthzReader", orgId);
		stranger = IOSystem.getActiveContext().getFactory().getCreateUser(ctx.getAdminUser(), "pbSettingsAuthzStranger", orgId);
		assertNotNull("owner must be created", owner);
		assertNotNull("reader must be created", reader);
		assertNotNull("stranger must be created", stranger);
	}

	@Test
	public void readerIs403StrangerIs404OwnerWrites() throws Exception {
		IOContext ioContext = IOSystem.getActiveContext();
		Fixture fx = createFixture("authz");
		grantReadOnly(fx);

		// 1. The reader can READ the settings of a fresh book: null, not a 404 - the read side is the control.
		assertNull("the reader must read the fresh book's settings as null (readable, no row yet)",
			ChapBookUtil.getBookSdConfig(reader, fx.bookOid));
		assertEquals("no row may pre-exist", 0, countBookConfigRows(fx.bookGroupId));

		// 2a. The reader's set on a fresh book: 403 before anything is written.
		BaseRecord readerSparse = sparse(new String[] { OlioFieldNames.FIELD_SD_STEPS });
		readerSparse.setValue(OlioFieldNames.FIELD_SD_STEPS, Integer.valueOf(99));
		assertStatus("reader set on a fresh book", 403, () -> ChapBookUtil.setBookSdConfig(reader, fx.bookOid, readerSparse));
		assertEquals("a refused set must not have created a row", 0, countBookConfigRows(fx.bookGroupId));

		// 4a. The owner's set works.
		BaseRecord ownerSparse = sparse(new String[] { OlioFieldNames.FIELD_SD_STEPS });
		ownerSparse.setValue(OlioFieldNames.FIELD_SD_STEPS, Integer.valueOf(21));
		BaseRecord stored = ChapBookUtil.setBookSdConfig(owner, fx.bookOid, ownerSparse);
		assertNotNull("the owner's set must return the stored config", stored);
		assertEquals("steps must round-trip for the owner", Integer.valueOf(21), stored.get(OlioFieldNames.FIELD_SD_STEPS));
		assertEquals("exactly one row after the owner's set", 1, countBookConfigRows(fx.bookGroupId));

		// 1b. The reader now READS the stored row (Read on the book group is Read on its config).
		BaseRecord readerView = ChapBookUtil.getBookSdConfig(reader, fx.bookOid);
		assertNotNull("the reader must read the stored settings", readerView);
		assertEquals("the reader sees the owner's steps", Integer.valueOf(21), readerView.get(OlioFieldNames.FIELD_SD_STEPS));

		// 2b. The reader's set on the existing row: 403, and the row is untouched. ONE bracketed PBAC trace so
		// the run log shows which decision refused it (the Update gate on the book, not the config row).
		System.out.println("=== TRACE: reader setBookSdConfig on " + fx.bookOid + " (expect 403 from the book Update gate) ===");
		ioContext.getPolicyUtil().setTrace(true);
		try {
			assertStatus("reader set on an existing row", 403, () -> ChapBookUtil.setBookSdConfig(reader, fx.bookOid, readerSparse));
		}
		finally {
			ioContext.getPolicyUtil().setTrace(false);
		}
		System.out.println("=== TRACE end ===");
		BaseRecord after = ChapBookUtil.getBookSdConfig(owner, fx.bookOid);
		assertNotNull(after);
		assertEquals("the refused set must leave the stored steps unchanged", Integer.valueOf(21), after.get(OlioFieldNames.FIELD_SD_STEPS));
		assertEquals("still exactly one row", 1, countBookConfigRows(fx.bookGroupId));

		// 3. The stranger: 404 by objectId, and 404 handed the owner's own record (the gate re-reads as the caller).
		BaseRecord strangerSparse = sparse(new String[] { OlioFieldNames.FIELD_SD_STEPS });
		strangerSparse.setValue(OlioFieldNames.FIELD_SD_STEPS, Integer.valueOf(7));
		assertStatus("stranger set via ChapBookUtil", 404, () -> ChapBookUtil.setBookSdConfig(stranger, fx.bookOid, strangerSparse));
		BaseRecord ownerBook = PbBookUtil.readBook(owner, fx.bookOid, orgId);
		assertNotNull("the owner reads the book", ownerBook);
		assertStatus("stranger set via PbBookUtil with the owner's record", 404,
			() -> PbBookUtil.setBookSdConfig(stranger, ownerBook, strangerSparse));
		assertStatus("stranger get via ChapBookUtil", 404, () -> ChapBookUtil.getBookSdConfig(stranger, fx.bookOid));
		BaseRecord afterStranger = ChapBookUtil.getBookSdConfig(owner, fx.bookOid);
		assertEquals("the stranger's refused sets must leave the stored steps unchanged", Integer.valueOf(21), afterStranger.get(OlioFieldNames.FIELD_SD_STEPS));
		assertEquals("still exactly one row after the stranger", 1, countBookConfigRows(fx.bookGroupId));

		// 4b. The owner still writes after every refusal: a second sparse set patches the SAME row.
		BaseRecord ownerAgain = sparse(new String[] { OlioFieldNames.FIELD_SD_CFG });
		ownerAgain.setValue(OlioFieldNames.FIELD_SD_CFG, Integer.valueOf(6));
		BaseRecord stored2 = ChapBookUtil.setBookSdConfig(owner, fx.bookOid, ownerAgain);
		assertNotNull("the owner's second set must return the stored config", stored2);
		assertEquals("the second set patches the same row", (String) stored.get(FieldNames.FIELD_OBJECT_ID), (String) stored2.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals("cfg patched", Integer.valueOf(6), stored2.get(OlioFieldNames.FIELD_SD_CFG));
		assertEquals("steps kept", Integer.valueOf(21), stored2.get(OlioFieldNames.FIELD_SD_STEPS));
		assertEquals("one row at the end", 1, countBookConfigRows(fx.bookGroupId));

		System.out.println("=== I-5 216d PROOF: book=" + fx.bookOid + " reader(Read group+world)=403 x2, stranger=404 x3, owner=200 x2, rows=1");
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private interface Call {
		void run() throws Exception;
	}

	private static void assertStatus(String what, int expected, Call call) throws Exception {
		try {
			call.run();
		}
		catch(PictureBookException e) {
			assertEquals(what + " must be a " + expected + ", got " + e.getStatus() + ": " + e.getMessage(), expected, e.getStatus());
			return;
		}
		fail(what + " must throw " + expected + " but returned");
	}

	private static final class Fixture {
		String bookOid;
		long bookGroupId;
		String slug;
		BaseRecord world;
	}

	private Fixture createFixture(String tag) {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		Fixture fx = new Fixture();
		long ts = System.currentTimeMillis();
		fx.slug = "cb-authz-" + tag + "-" + ts;

		BaseRecord poem = createPoem(owner, "~/Data/ChapBookAuthz-" + tag + "-" + ts, "Poem Authz " + tag + " " + ts, POEM_TEXT);
		assertNotNull("Poem must be created", poem);
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poem.get(FieldNames.FIELD_OBJECT_ID));

		BaseRecord book = ChapBookUtil.createChapBook(owner, dataPath, fx.slug, "ChapBook Authz " + tag + " " + ts, poemOids, 5, null);
		assertNotNull("createChapBook must return a book", book);
		fx.bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Book must have an objectId", fx.bookOid);
		BaseRecord full = PbBookUtil.readBook(owner, fx.bookOid, orgId);
		assertNotNull("the owner must read the book back", full);
		Long gid = full.get(FieldNames.FIELD_GROUP_ID);
		assertNotNull("Book must carry a groupId", gid);
		fx.bookGroupId = gid.longValue();
		fx.world = full.get(OlioFieldNames.FIELD_PB_WORLD);
		assertNotNull("a ChapBook carries its world", fx.world);
		return fx;
	}

	/** Read (DATA + GROUP) on the book group and Read (DATA) on the world for the reader, as the org admin; NO Update anywhere. */
	private void grantReadOnly(Fixture fx) throws Exception {
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord olio = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal", olio);
		BaseRecord bookGroup = ioContext.getPathUtil().findPath(olio, ModelNames.MODEL_GROUP,
			PbBookUtil.bookGroupPath(fx.slug), GroupEnumType.DATA.toString(), orgId);
		assertNotNull("book group " + PbBookUtil.bookGroupPath(fx.slug), bookGroup);
		assertEquals("the book row lives in the book group", fx.bookGroupId, ((Number) bookGroup.get(FieldNames.FIELD_ID)).longValue());
		ioContext.getAuthorizationUtil().setEntitlement(ctx.getAdminUser(), reader,
			new BaseRecord[] { bookGroup }, new String[] { "Read" },
			new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() });
		ioContext.getAuthorizationUtil().setEntitlement(ctx.getAdminUser(), reader,
			new BaseRecord[] { fx.world }, new String[] { "Read" }, new String[] { PermissionEnumType.DATA.toString() });
		CacheUtil.clearCache();
		System.out.println("reader " + reader.get(FieldNames.FIELD_NAME) + " granted Read(DATA,GROUP) on group " + fx.bookGroupId
			+ " and Read(DATA) on world " + fx.world.get(FieldNames.FIELD_ID) + "; nothing on the book row");
		assertFalse("the reader is not the owner", ((Number) reader.get(FieldNames.FIELD_ID)).longValue() == ((Number) owner.get(FieldNames.FIELD_ID)).longValue());
	}

	private BaseRecord sparse(String[] fields) throws Exception {
		return RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG, fields);
	}

	private int countBookConfigRows(long groupId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_SD_CONFIG, FieldNames.FIELD_NAME, PbBookUtil.BOOK_SD_CONFIG_NAME);
		q.field(FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().count(owner, q);
	}

	private BaseRecord createPoem(BaseRecord user, String groupPath, String name, String text) {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord poem = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CB_POEM, user, null, plist);
			poem.set("text", text);
			poem.set("title", name);
			return IOSystem.getActiveContext().getAccessPoint().create(user, poem);
		}
		catch(Exception e) {
			logger.error("createPoem failed: {}", e.getMessage(), e);
			return null;
		}
	}
}
