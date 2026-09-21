package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.WorldUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.junit.Test;

/// Series-world over-deletion guard (binding plan §5 HIGH / §8).
///
/// The N-series cast model (Q6) puts ONE shared Olio world under a series; every chapter references
/// it (book.world = series.universe) and holds its cast there (a canonical baseline plus per-chapter
/// shadow copies). Deleting that shared world therefore destroys the baseline cast AND every other
/// chapter's shadow cast, population and events in one call - WorldUtil.deleteWorld runs cleanupWorld
/// across the whole population group. The plan makes "a chapter/world delete must NEVER wipe the
/// shared series world" an EXPLICIT constraint on every delete path touching a chapter book.
///
/// These tests lock down the detection every guard keys on (PbSeriesUtil.findSeriesByWorld) and the
/// refusal in PictureBookUtil.teardownBookWorld - the single WorldUtil.deleteWorld path in the
/// package. The teardown case sets the book's slug EQUAL to the series world's name so
/// findWorld(bookWorldPath(), slug) RESOLVES the shared world (world != null inside teardown) - the
/// exact chapterSlug == seriesSlug landmine the guard exists to seal; without the guard that path
/// calls WorldUtil.deleteWorld and the world is gone. The guard's own refusal log line is asserted
/// so the branch is proven taken, not merely inferred from survival.
///
/// Real DB, no LLM, runs as a test user (never admin). Lives in the production package because
/// teardownBookWorld is package-private (same convention as the other tests here).
public class TestSeriesWorldDeleteGuard extends BaseTest {

	private BaseRecord testUser;
	private BaseRecord olioUser;
	private long orgId;
	private String groupPath;

	private void prepare() {
		OlioModelNames.use();
		testUser = getCreateUser("pbSeriesGuardUser");
		assertNotNull("test user", testUser);
		orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		groupPath = "~/PbSeriesGuardTests";
		// The shared series world lives under the olio-principal-owned /Olio/Universes/Books/Worlds tree;
		// a normal user cannot makePath there. Resolve the olio principal for tests that must place a
		// world at bookWorldPath() (it is the legitimate owner of those rows AND the actor production uses
		// for the physical delete - this is not the org admin).
		olioUser = IOSystem.getActiveContext().getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
	}

	/// A world referenced by a series' universe FK is detected; an unrelated world is not.
	@Test
	public void TestFindSeriesByWorldDetectsBackingSeries() throws Exception {
		prepare();
		String tag = shortId();

		BaseRecord seriesWorld = createWorld(testUser, "guardworld-" + tag);
		BaseRecord otherWorld = createWorld(testUser, "plainworld-" + tag);
		BaseRecord series = createSeries(testUser, "Series guardseries-" + tag, seriesWorld);

		BaseRecord found = PbSeriesUtil.findSeriesByWorld(seriesWorld);
		assertNotNull("the series world must resolve its backing series", found);
		assertTrue("resolved series must be the one we created",
			((String) series.get(FieldNames.FIELD_OBJECT_ID)).equals(found.get(FieldNames.FIELD_OBJECT_ID)));
		assertTrue("isSeriesWorld agrees", PbSeriesUtil.isSeriesWorld(seriesWorld));

		assertNull("a world no series references must not resolve a series",
			PbSeriesUtil.findSeriesByWorld(otherWorld));
		assertFalse("isSeriesWorld agrees on the plain world", PbSeriesUtil.isSeriesWorld(otherWorld));
		assertNull("null world yields null", PbSeriesUtil.findSeriesByWorld(null));
	}

	/// LANDMINE case (chapterSlug == seriesSlug). teardownBookWorld must REFUSE to delete the shared
	/// series world AND must NOT wipe its directory tree. The world is created at bookWorldPath()/slug so
	/// findWorld() inside teardown RESOLVES it (world != null) and bookContainerPath(slug) IS the shared
	/// world's own container - the exact collision the strengthened guard exists to seal. The world's
	/// container group (which holds the shared cast/population/events subgroups) MUST survive: asserting
	/// only that the world RECORD survives is insufficient, because that row lives in the parent Worlds
	/// group and outlives a deleteGroupRecursive of its container - the container is the real proof.
	///
	/// Runs as the olio principal because the shared world must live under the olio-owned Books-universe
	/// tree (bookWorldPath()), which a normal user cannot write, and because that principal is the actual
	/// owner of the world/series/book rows AND the actor teardownBookWorld uses for the physical deletes
	/// (steps 2-4). This is the olio SYSTEM user, not the org admin. If the org has no olio principal
	/// (never ran Olio), the precondition can't be built, so the case is skipped rather than faked.
	@Test
	public void TestTeardownDoesNotWipeSharedSeriesWorldWhenSlugCollides() throws Exception {
		prepare();
		org.junit.Assume.assumeTrue("olio principal required to stage a world at bookWorldPath()", olioUser != null);
		String slug = "guardchapter" + shortId();

		// The shared world lives where teardownBookWorld resolves it (bookWorldPath()), named by the
		// slug, so the book's slug == world name and findWorld() inside teardown returns non-null.
		BaseRecord world = WorldUtil.getCreateWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug, new String[0]);
		assertNotNull("shared series world", world);
		BaseRecord series = createSeries(olioUser, "Series " + slug, world);
		assertTrue("world is series-backed before teardown", PbSeriesUtil.isSeriesWorld(world));

		// The world's directory tree lives at bookContainerPath(slug); capture it before teardown.
		BaseRecord containerBefore = findGroup(PbBookUtil.bookContainerPath(slug));
		assertNotNull("the shared world's container must exist before teardown", containerBefore);

		BaseRecord book = createChapterBook(olioUser, slug, series);

		CountingAppender app = new CountingAppender("seriesGuardRefusal");
		DeleteResult result = runWithAppenderOn(PictureBookUtil.class, app,
			() -> PictureBookUtil.teardownBookWorld(olioUser, book, orgId));

		assertNotNull("teardown returned a result", result);
		assertTrue("scoped teardown of a series chapter must still succeed (deleted=true), reason="
			+ result.reason, result.deleted);

		boolean refused = app.messages.stream().anyMatch(m -> m != null
			&& m.contains("REFUSING to delete the shared series world"));
		assertTrue("teardown must log the shared-series-world refusal", refused);

		BaseRecord after = WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug);
		assertNotNull("the shared series world record MUST survive a chapter teardown", after);
		assertTrue("the surviving world is still series-backed", PbSeriesUtil.isSeriesWorld(after));

		// The real proof: the shared world's directory tree (its container group) is untouched. Without the
		// world != null branch in the guard, deleteGroupRecursive(bookContainerPath(slug)) removes it.
		BaseRecord containerAfter = findGroup(PbBookUtil.bookContainerPath(slug));
		assertNotNull("the shared series world's container (and its cast/population/events) MUST survive",
			containerAfter);
	}

	/// NORMAL case (chapterSlug != seriesSlug). The shared world is named by the SERIES slug, so
	/// findWorld(bookWorldPath(), chapterSlug) MISSES it (world == null in teardown) and the refusal fires
	/// on the book's series FK. The guard must (a) leave the shared world's container intact and (b) clear
	/// THIS chapter's own distinct container. This is the realistic production topology.
	@Test
	public void TestTeardownLeavesSharedWorldAndClearsOwnContainer() throws Exception {
		prepare();
		org.junit.Assume.assumeTrue("olio principal required to stage a world at bookWorldPath()", olioUser != null);
		String seriesSlug = "guardseries" + shortId();
		String chapterSlug = "guardchap" + shortId();

		// Shared world named by the SERIES slug (its container = bookContainerPath(seriesSlug)).
		BaseRecord world = WorldUtil.getCreateWorld(olioUser, PbOlioContextUtil.bookWorldPath(), seriesSlug, new String[0]);
		assertNotNull("shared series world", world);
		BaseRecord series = createSeries(olioUser, "Series " + seriesSlug, world);

		// The chapter's OWN container, distinct from the shared world's - as createBook(...series...) would
		// makePath it. Give it a Book subgroup so there is a tree to recursively remove.
		BaseRecord chapterContainer = makeGroup(PbBookUtil.bookGroupPath(chapterSlug));
		assertNotNull("chapter's own Book group", chapterContainer);
		assertNotNull("chapter's own container exists before teardown",
			findGroup(PbBookUtil.bookContainerPath(chapterSlug)));

		// The chapter book carries the series FK and its OWN slug.
		BaseRecord book = createChapterBook(olioUser, chapterSlug, series);

		CountingAppender app = new CountingAppender("seriesGuardNormal");
		DeleteResult result = runWithAppenderOn(PictureBookUtil.class, app,
			() -> PictureBookUtil.teardownBookWorld(olioUser, book, orgId));

		assertNotNull("teardown returned a result", result);
		assertTrue("scoped teardown must succeed (deleted=true), reason=" + result.reason, result.deleted);

		boolean refused = app.messages.stream().anyMatch(m -> m != null
			&& m.contains("REFUSING to delete the shared series world"));
		assertTrue("teardown must log the shared-series-world refusal even when findWorld misses", refused);

		// The shared world (named by the series slug) and its container survive untouched.
		assertNotNull("the shared series world MUST survive tearing down a differently-slugged chapter",
			WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), seriesSlug));
		assertNotNull("the shared world's container MUST survive",
			findGroup(PbBookUtil.bookContainerPath(seriesSlug)));

		// The chapter's OWN container is cleared (world == null branch).
		assertNull("the chapter's own container MUST be removed by the scoped teardown",
			findGroup(PbBookUtil.bookContainerPath(chapterSlug)));
	}

	// ─────────────────────────────── fixtures ───────────────────────────────

	private BaseRecord findGroup(String path) {
		return IOSystem.getActiveContext().getPathUtil().findPath(olioUser, ModelNames.MODEL_GROUP, path,
			GroupEnumType.DATA.toString(), orgId);
	}

	private BaseRecord makeGroup(String path) {
		return IOSystem.getActiveContext().getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, path,
			GroupEnumType.DATA.toString(), orgId);
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private BaseRecord createWorld(BaseRecord owner, String name) throws Exception {
		BaseRecord world = RecordFactory.newInstance(OlioModelNames.MODEL_WORLD);
		ioContext.getRecordUtil().applyNameGroupOwnership(owner, world, name, groupPath, orgId);
		assertTrue("create world " + name, ioContext.getRecordUtil().createRecord(world));
		return world;
	}

	private BaseRecord createSeries(BaseRecord owner, String name, BaseRecord universeWorld) throws Exception {
		BaseRecord series = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SERIES);
		ioContext.getRecordUtil().applyNameGroupOwnership(owner, series, name, groupPath, orgId);
		series.set(OlioFieldNames.FIELD_PB_UNIVERSE, universeWorld);
		series.set(OlioFieldNames.FIELD_PB_BOOK_COUNT, Integer.valueOf(0));
		assertTrue("create series " + name, ioContext.getRecordUtil().createRecord(series));
		return series;
	}

	private BaseRecord createChapterBook(BaseRecord owner, String slug, BaseRecord series) throws Exception {
		BaseRecord book = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		ioContext.getRecordUtil().applyNameGroupOwnership(owner, book, "Chapter " + slug, groupPath, orgId);
		book.set(OlioFieldNames.FIELD_PB_SLUG, slug);
		book.set(OlioFieldNames.FIELD_PB_SERIES, series);
		book.set(OlioFieldNames.FIELD_PB_CHAPTER, Integer.valueOf(1));
		assertTrue("create chapter book " + slug, ioContext.getRecordUtil().createRecord(book));
		return book;
	}

	// ─────────────────────────────── log capture ───────────────────────────────

	private static final class CountingAppender extends AbstractAppender {
		final List<String> messages = Collections.synchronizedList(new ArrayList<String>());

		CountingAppender(String name) {
			super(name, null, null, true, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(LogEvent event) {
			messages.add(event.getMessage().getFormattedMessage());
		}
	}

	private interface ThrowingSupplier<T> {
		T get() throws Exception;
	}

	private static <T> T runWithAppenderOn(Class<?> loggerOwner, CountingAppender app, ThrowingSupplier<T> body)
			throws Exception {
		org.apache.logging.log4j.core.Logger target =
			(org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerOwner);
		app.start();
		target.addAppender(app);
		try {
			return body.get();
		} finally {
			target.removeAppender(app);
			app.stop();
		}
	}
}
