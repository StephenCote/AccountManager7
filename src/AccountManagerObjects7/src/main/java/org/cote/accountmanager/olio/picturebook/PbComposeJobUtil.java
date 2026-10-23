package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PbLocationUtil.LocationPassResult;
import org.cote.accountmanager.olio.picturebook.PbNodeExecutor.PbStageCancelledException;
import org.cote.accountmanager.olio.picturebook.PbRenderPlanUtil.PlanOptions;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.thread.AsyncJobLimitException;
import org.cote.accountmanager.thread.AsyncJobRegistry;
import org.cote.accountmanager.thread.AsyncJobWork;
import org.cote.accountmanager.util.JSONUtil;

/**
 * The whole-book compose job and the whole-book location-pass job (V3 §9.1 ratified A, §21; P4-4).
 * <p>
 * <b>Compose ({@link PbRenderJobUtil#KIND_COMPOSE}).</b> Every scene of the book that
 * {@link PbComposeUtil#needsCompose needs a composition} (or every scene, with {@code force}) is composed
 * in story order on the registry's worker, one LLM call per scene through
 * {@link PbComposeUtil#composeScene(BaseRecord, BaseRecord, BaseRecord, BaseRecord, boolean, SummarizeProgress)};
 * the pass ends with the whole-book location pass ({@link PbLocationUtil#proposeLocations}, ruling 182 (9)).
 * With {@code quickStart} each scene composed by this job is also rendered once at the {@code DRAFT}
 * preset, inline on the same worker, through {@link PbServiceFacade#executePlanScene} under the backend's
 * {@link PbRenderLanes} permit - so the user sees a first picture of each scene as the book composes.
 * ChapBook pages are never composed (a page is text over one landscape; its place reaches the location
 * pass through {@code sdPrompt} / stanza, ruling 182 (15-22)) - a CHAPBOOK book's compose job is the
 * location pass alone.
 * <p>
 * <b>Progress and cancel.</b> {@code total} is the number of scenes the job will visit plus one for the
 * location pass; {@code phase} names the scene ("compose 3/12 The ferry") or "quick start 3/12 …" or
 * "locations chunk 1/2". The cancel flag is observed before every scene, after every LLM call (the
 * registry's cancel scope aborts the stream and {@code composeScene} returns {@code cancelled} with
 * nothing written - never a MIGRATED fallback, architect M1), between the compose and its DRAFT render,
 * while waiting for the render lane, and at every chunk of the location pass. Compositions written before
 * the cancel stay; the job returns what it did and the registry marks it CANCELLED. A per-scene failure
 * (LLM unavailable with an existing composition, a DRAFT render refused) is recorded on that scene and
 * the loop continues; only a throwable outside the loop (the book no longer updatable, the scene list
 * unreadable) fails the job.
 * <p>
 * <b>Shutdown.</b> A container stop between scenes, or an abandoned lane wait, ends the job
 * {@code interrupted}: compositions already written stay, and re-submitting the job resumes from the
 * first scene without one (nothing is re-composed). A DRAFT render in flight at shutdown is a render
 * node the boot sweep ({@link PbRecoveryUtil}) reconciles; the compose job keeps no run row of its own.
 * <p>
 * <b>One live job per book, per JVM.</b> A second submit for a book whose compose (or location-pass) job is
 * still queued or running is answered with the live job when it belongs to the caller
 * ({@code reused:true}) and refused with a 409 otherwise - the refusal names no job, user or book id
 * (architect S3). The claim is per-JVM like {@link PbNodeInflight}; a second Service7 against the same
 * database cannot see it, which is the bound every in-process registry here already states.
 */
public final class PbComposeJobUtil {

	private static final Logger logger = LogManager.getLogger(PbComposeJobUtil.class);

	/** Plan options of a Quick-start render: the book's style at the DRAFT preset, nothing overridden. */
	static final PlanOptions QUICK_START_OPTIONS = new PlanOptions(null, false, PbQualityPresetEnumType.DRAFT, null);

	static final String NOTE_RESUME = "Re-submitting the compose job resumes from the first scene without a composition;"
		+ " a Quick-start render interrupted by shutdown is reconciled by the next boot's recovery sweep.";

	/**
	 * The live job on a claim key: who submitted it, what kind, and - once the registry has it - its id. The
	 * key is the book's objectId for the compose and location-pass jobs and the apparel's objectId for a
	 * wardrobe reference ({@link #submitApparelReference}); the field keeps its original name.
	 */
	static final class Claim {
		final String bookObjectId;
		final String ownerObjectId;
		final String kind;
		volatile String jobId;

		Claim(String bookObjectId, String ownerObjectId, String kind) {
			this.bookObjectId = bookObjectId;
			this.ownerObjectId = ownerObjectId;
			this.kind = kind;
		}
	}

	private static final ConcurrentHashMap<String, Claim> INFLIGHT = new ConcurrentHashMap<>();
	/// Check-then-claim-then-submit runs under one lock so two submits for one book cannot both pass the
	/// check; the registry's own cap check is inside submit and still answers 429 on its own.
	private static final Object SUBMIT_LOCK = new Object();

	private PbComposeJobUtil() {
	}

	// ─────────────────────────────── submit: compose ───────────────────────────────

	/**
	 * Submit the whole-book compose job.
	 * <p>
	 * The caller ({@code PbServiceFacade.requestCompose}) has already authorized the book for update and
	 * required the V3 render path; this method trusts that record. {@code chatConfigName} is resolved as
	 * {@code user} (404 when named but not found; null/blank for the organization's {@code generalChat}).
	 * {@code quickStart} needs an SD backend: a missing server or an unknown API type is a 500 before
	 * anything is queued.
	 * <p>
	 * A book with nothing to compose (every scene composed and not {@code force}, or a CHAPBOOK book) is
	 * <b>not</b> submitted: the result says {@code submitted:false} with the reason and the counts, so the
	 * idempotent compose-on-open a client issues costs no LLM call on a book that is already composed. The
	 * location pass alone is the {@link #submitProposeLocations} job.
	 *
	 * @return {@code submitted}, {@code jobId}, {@code jobStatus}, {@code kind}, {@code reused},
	 *         {@code bookObjectId}, {@code sceneCount}, {@code scenesToCompose}, {@code scenesCurrent},
	 *         {@code pagesSkipped}, {@code quickStart}, {@code force}; or {@code submitted:false} + {@code reason}
	 * @throws PictureBookException 400 missing input; 404 chat config; 409 the book has no resolvable world,
	 *         another principal's job holds the book, or the caller's own job of the other kind does; 429 the
	 *         principal's job cap; 500 no SD backend for Quick start, or the principal cannot be keyed
	 */
	public static Map<String, Object> submitCompose(BaseRecord user, BaseRecord book, boolean quickStart, boolean force,
			String chatConfigName, String sdApiType, String sdServer) {
		return submitCompose(user, book, quickStart, force, chatConfigName, sdApiType, sdServer, null);
	}

	/**
	 * {@link #submitCompose(BaseRecord, BaseRecord, boolean, boolean, String, String, String)} with the
	 * deployment's {@code datagen.path}, which the compose job needs to open the book's world as an
	 * {@link org.cote.accountmanager.olio.OlioContext} when the LLM reports an outfit change (V3 §6.1): the
	 * alternate is built by the apparel generator as the olio principal. Without it (null) an outfit is
	 * still honoured when the world context is already cached in this JVM, and otherwise recorded as a
	 * skipped outfit on the scene's outcome - never a failed composition.
	 */
	public static Map<String, Object> submitCompose(BaseRecord user, BaseRecord book, boolean quickStart, boolean force,
			String chatConfigName, String sdApiType, String sdServer, String dataPath) {
		BaseRecord cfg = resolveChatConfig(user, chatConfigName);
		SDUtil sdu = null;
		if(quickStart) {
			try {
				sdu = PbNodeExecutor.toSdUtil(sdApiType, sdServer);
			}
			catch(IllegalArgumentException e) {
				logger.error("SD server API type '" + sdApiType + "' is not recognised");
				throw new PictureBookException(500, "SD server API type is not recognised");
			}
			if(sdu == null) {
				throw new PictureBookException(500, "SD server not configured; Quick start renders a draft of every composed scene");
			}
		}
		return submitCompose(user, book, quickStart, force, cfg, sdu, sdServer, dataPath);
	}

	/**
	 * {@link #submitCompose(BaseRecord, BaseRecord, boolean, boolean, String, String, String)} with the chat
	 * config and SD client already resolved - the seam a test uses to point Quick start at an observable
	 * backend. {@code sdServer} still keys the render lane.
	 */
	static Map<String, Object> submitCompose(BaseRecord user, BaseRecord book, boolean quickStart, boolean force,
			BaseRecord chatConfig, SDUtil sdu, String sdServer) {
		return submitCompose(user, book, quickStart, force, chatConfig, sdu, sdServer, null);
	}

	static Map<String, Object> submitCompose(BaseRecord user, BaseRecord book, boolean quickStart, boolean force,
			BaseRecord chatConfig, SDUtil sdu, String sdServer, String dataPath) {
		if(user == null || book == null) {
			throw new PictureBookException(400, "A user and a book are required");
		}
		if(quickStart && sdu == null) {
			throw new PictureBookException(500, "SD server not configured; Quick start renders a draft of every composed scene");
		}
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		if(bookOid == null || bookOid.isBlank()) {
			throw new PictureBookException(400, "The book has no objectId");
		}
		/// 409 at the route for a world-less (PB1 bare) book, as the synchronous location pass always was - not
		/// a job that fails on its first scene.
		PbLocationUtil.requireBookContext(user, book);

		Tally tally = tally(user, book, force);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bookObjectId", bookOid);
		out.put("kind", PbRenderJobUtil.KIND_COMPOSE);
		out.put("quickStart", Boolean.valueOf(quickStart));
		out.put("force", Boolean.valueOf(force));
		out.put("sceneCount", Integer.valueOf(tally.sceneCount));
		out.put("scenesToCompose", Integer.valueOf(tally.toCompose));
		out.put("scenesCurrent", Integer.valueOf(tally.current));
		out.put("pagesSkipped", Integer.valueOf(tally.pages));
		if(tally.toCompose == 0) {
			out.put("submitted", Boolean.FALSE);
			out.put("reused", Boolean.FALSE);
			out.put("reason", (tally.chapBook
				? "ChapBook pages are not composed; run the location pass to cluster their places"
				: (tally.sceneCount == 0 ? "The book has no scenes" : "Every scene is composed")));
			return out;
		}

		ComposeWork work = new ComposeWork(user, book, chatConfig, force, quickStart, sdu, sdServer, dataPath);
		Submission s = submitExclusive(user, bookOid, PbRenderJobUtil.KIND_COMPOSE, work);
		out.put("submitted", Boolean.TRUE);
		out.put("reused", Boolean.valueOf(s.reused));
		out.put("jobId", s.job.getJobId());
		out.put("jobStatus", s.job.getStatus().name().toLowerCase());
		return out;
	}

	// ─────────────────────────────── submit: location pass ───────────────────────────────

	/**
	 * Submit the whole-book location pass alone as a job ({@link PbRenderJobUtil#KIND_PROPOSE_LOCATIONS};
	 * ruling 182 (9)). Same authorization contract and dedupe as {@link #submitCompose}; the job's result is
	 * {@code PbServiceFacade.locationPassDto} plus {@code bookObjectId}.
	 *
	 * @throws PictureBookException 400, 404 chat config, 409, 429, 500 as {@link #submitCompose}
	 */
	public static Map<String, Object> submitProposeLocations(BaseRecord user, BaseRecord book, String chatConfigName) {
		if(user == null || book == null) {
			throw new PictureBookException(400, "A user and a book are required");
		}
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		if(bookOid == null || bookOid.isBlank()) {
			throw new PictureBookException(400, "The book has no objectId");
		}
		BaseRecord cfg = resolveChatConfig(user, chatConfigName);
		PbLocationUtil.requireBookContext(user, book);
		ProposeWork work = new ProposeWork(user, book, cfg);
		Submission s = submitExclusive(user, bookOid, PbRenderJobUtil.KIND_PROPOSE_LOCATIONS, work);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bookObjectId", bookOid);
		out.put("kind", PbRenderJobUtil.KIND_PROPOSE_LOCATIONS);
		out.put("submitted", Boolean.TRUE);
		out.put("reused", Boolean.valueOf(s.reused));
		out.put("jobId", s.job.getJobId());
		out.put("jobStatus", s.job.getStatus().name().toLowerCase());
		return out;
	}

	// ─────────────────────────────── submit: wardrobe reference ───────────────────────────────

	/**
	 * Submit the mannequin reference render of one wardrobe entry as a job
	 * ({@link PbRenderJobUtil#KIND_APPAREL_REFERENCE}; V3 §6.2, ruling 182 (12)). The claim is keyed by the
	 * <b>apparel</b> objectId, not the book, so references of different alternates run independently of each
	 * other and of the book's compose job, while a second submit for the same apparel is answered with the
	 * caller's live job ({@code reused:true}) or a 409. {@link PbWardrobeUtil#referenceImage} runs on the
	 * worker under the backend's {@link PbRenderLanes} permit; its 4xx/409 checks (book updatable, character
	 * of the book, apparel in the wardrobe, style complete) run there too, so a refused reference is a failed
	 * job whose message is {@link PbServiceFacade#curatedMessage curated}.
	 *
	 * @return {@code submitted}, {@code jobId}, {@code jobStatus}, {@code kind}, {@code reused},
	 *         {@code bookObjectId}, {@code characterObjectId}, {@code apparelObjectId}
	 * @throws PictureBookException 400 missing input; 409 another live job on this apparel; 429 the
	 *         principal's job cap; 500 no SD backend or the principal cannot be keyed
	 */
	public static Map<String, Object> submitApparelReference(BaseRecord user, BaseRecord book, String characterObjectId,
			String apparelObjectId, SDUtil sdu, String sdServer) {
		if(user == null || book == null) {
			throw new PictureBookException(400, "A user and a book are required");
		}
		if(characterObjectId == null || characterObjectId.isBlank() || apparelObjectId == null || apparelObjectId.isBlank()) {
			throw new PictureBookException(400, "A character and an apparel are required");
		}
		if(sdu == null) {
			throw new PictureBookException(500, "SD server not configured; a wardrobe reference is rendered by the SD backend");
		}
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		if(bookOid == null || bookOid.isBlank()) {
			throw new PictureBookException(400, "The book has no objectId");
		}
		ReferenceWork work = new ReferenceWork(user, book, characterObjectId.trim(), apparelObjectId.trim(), sdu, sdServer);
		Submission s = submitExclusive(user, apparelObjectId.trim(), PbRenderJobUtil.KIND_APPAREL_REFERENCE, work);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bookObjectId", bookOid);
		out.put("characterObjectId", characterObjectId.trim());
		out.put("apparelObjectId", apparelObjectId.trim());
		out.put("kind", PbRenderJobUtil.KIND_APPAREL_REFERENCE);
		out.put("submitted", Boolean.TRUE);
		out.put("reused", Boolean.valueOf(s.reused));
		out.put("jobId", s.job.getJobId());
		out.put("jobStatus", s.job.getStatus().name().toLowerCase());
		return out;
	}

	// ─────────────────────────────── the per-book claim ───────────────────────────────

	/** True when a compose job is queued or running for the book in this JVM (any principal). */
	public static boolean isComposing(String bookObjectId) {
		Claim c = (bookObjectId != null ? INFLIGHT.get(bookObjectId) : null);
		return c != null && PbRenderJobUtil.KIND_COMPOSE.equals(c.kind);
	}

	/** 409 when {@link #isComposing}: a synchronous per-scene compose must not race the job on the same rows. */
	static void requireNotComposing(String bookObjectId) {
		if(isComposing(bookObjectId)) {
			throw new PictureBookException(409, "A compose job is running for this book; wait for it to finish or cancel it first");
		}
	}

	/** The jobId the book's live claim carries, or null. Test/diagnostic. */
	static String inflightJobId(String bookObjectId) {
		Claim c = (bookObjectId != null ? INFLIGHT.get(bookObjectId) : null);
		return (c != null ? c.jobId : null);
	}

	static final class Submission {
		AsyncJob job;
		boolean reused;
	}

	/// Dedupe, cap, claim and submit under one lock. The claim is handed to the work, which removes it in
	/// its finally and in onCancelledBeforeStart - remove(key, value) so a claim that was already replaced is
	/// never removed by the wrong job. A claim whose job the registry no longer reports live is stale and is
	/// replaced; a live claim of the caller's own, same kind, is the answer; anything else is a 409.
	static Submission submitExclusive(BaseRecord user, String bookOid, String kind, ClaimedWork work) {
		String owner = user.get(FieldNames.FIELD_OBJECT_ID);
		if(owner == null || owner.isBlank()) {
			throw new PictureBookException(500, "The principal could not be keyed for an async job");
		}
		String what = (PbRenderJobUtil.KIND_APPAREL_REFERENCE.equals(kind) ? "this wardrobe entry" : "this book");
		synchronized(SUBMIT_LOCK) {
			Claim live = INFLIGHT.get(bookOid);
			if(live != null) {
				if(owner.equals(live.ownerObjectId)) {
					AsyncJob own = (live.jobId != null ? AsyncJobRegistry.get(user, live.jobId) : null);
					if(own != null && !own.isTerminal()) {
						if(kind.equals(live.kind)) {
							Submission s = new Submission();
							s.job = own;
							s.reused = true;
							return s;
						}
						throw new PictureBookException(409, "Your " + live.kind + " job " + live.jobId
							+ " is still running for " + what + "; wait for it to finish or cancel it first");
					}
					/// The job finished (or never registered) and its claim was not removed: stale, replace it.
					INFLIGHT.remove(bookOid, live);
				}
				else {
					throw new PictureBookException(409, "A job is already running for " + what);
				}
			}
			int active = AsyncJobRegistry.activeCount(user);
			if(active >= AsyncJobRegistry.MAX_ACTIVE_JOBS_PER_PRINCIPAL) {
				throw new PictureBookException(429, "Principal already has " + active + " of "
					+ AsyncJobRegistry.MAX_ACTIVE_JOBS_PER_PRINCIPAL + " jobs queued or running");
			}
			Claim claim = new Claim(bookOid, owner, kind);
			work.setClaim(claim);
			INFLIGHT.put(bookOid, claim);
			AsyncJob job;
			try {
				job = AsyncJobRegistry.submit(user, kind, bookOid, work);
			}
			catch(AsyncJobLimitException e) {
				INFLIGHT.remove(bookOid, claim);
				throw new PictureBookException(429, e.getMessage());
			}
			catch(RuntimeException e) {
				INFLIGHT.remove(bookOid, claim);
				throw e;
			}
			if(job == null) {
				INFLIGHT.remove(bookOid, claim);
				throw new PictureBookException(500, "The job could not be submitted for this principal");
			}
			claim.jobId = job.getJobId();
			Submission s = new Submission();
			s.job = job;
			s.reused = false;
			return s;
		}
	}

	static void releaseClaim(Claim claim) {
		if(claim != null) {
			INFLIGHT.remove(claim.bookObjectId, claim);
		}
	}

	// ─────────────────────────────── the tally (submit, HTTP thread) ───────────────────────────────

	static final class Tally {
		int sceneCount;
		int toCompose;
		int current;
		int pages;
		boolean chapBook;
	}

	/// Two list queries, never one per scene: the scenes, and every SCENE-purpose composition of the book.
	static Tally tally(BaseRecord user, BaseRecord book, boolean force) {
		Tally t = new Tally();
		t.chapBook = PbNodeExecutor.isChapBook(book);
		List<BaseRecord> scenes = PbBookUtil.listScenes(user, book);
		t.sceneCount = scenes.size();
		if(t.chapBook) {
			t.pages = scenes.size();
			return t;
		}
		Map<Long, BaseRecord> bySceneId = PbComposeUtil.listSceneCompositions(user, book);
		for(BaseRecord scene : scenes) {
			Long id = scene.get(FieldNames.FIELD_ID);
			BaseRecord comp = (id != null ? bySceneId.get(id) : null);
			if(force || PbComposeUtil.needsCompose(comp)) {
				t.toCompose++;
			}
			else {
				t.current++;
			}
		}
		return t;
	}

	static BaseRecord resolveChatConfig(BaseRecord user, String chatConfigName) {
		if(chatConfigName == null || chatConfigName.isBlank()) {
			return null;
		}
		BaseRecord cfg = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatConfigName.trim(), null);
		if(cfg == null) {
			throw new PictureBookException(404, "Chat config not found: " + chatConfigName.trim());
		}
		return cfg;
	}

	// ─────────────────────────────── the worker: compose ───────────────────────────────

	/** Work that holds the book's claim and releases it however it ends. */
	static abstract class ClaimedWork implements AsyncJobWork {
		private volatile Claim claim;

		void setClaim(Claim claim) {
			this.claim = claim;
		}

		Claim getClaim() {
			return claim;
		}

		@Override
		public void onCancelledBeforeStart(AsyncJob job) {
			releaseClaim(claim);
		}
	}

	static final class ComposeWork extends ClaimedWork {
		private final BaseRecord user;
		private final BaseRecord book;
		private final BaseRecord chatConfig;
		private final boolean force;
		private final boolean quickStart;
		private final SDUtil sdu;
		private final String sdServer;
		private final String dataPath;

		ComposeWork(BaseRecord user, BaseRecord book, BaseRecord chatConfig, boolean force, boolean quickStart, SDUtil sdu, String sdServer,
				String dataPath) {
			this.user = user;
			this.book = book;
			this.chatConfig = chatConfig;
			this.force = force;
			this.quickStart = quickStart;
			this.sdu = sdu;
			this.sdServer = sdServer;
			this.dataPath = dataPath;
		}

		@Override
		public String run(AsyncJob job) {
			try {
				return JSONUtil.exportObject(composeNow(user, book, chatConfig, force, quickStart, sdu, sdServer, job.getProgress(), dataPath));
			}
			finally {
				releaseClaim(getClaim());
			}
		}
	}

	/**
	 * The compose job's body - what {@link ComposeWork#run} serializes - as a plain map, so a JUnit test can
	 * drive it on the calling thread with its own {@link SummarizeProgress} and read the outcome without a
	 * registry. Re-authorizes the book for update as {@code user} first (the HTTP thread's check was minutes
	 * ago); a 401/403/404 here fails the job.
	 *
	 * @param progress the job's token; null when driven synchronously and uncancellable
	 * @param dataPath the deployment's {@code datagen.path}, so a compose-detected outfit change can open the
	 *        world as an {@code OlioContext}; null leaves outfits to the cached context or skips them
	 */
	static Map<String, Object> composeNow(BaseRecord user, BaseRecord book, BaseRecord chatConfig, boolean force,
			boolean quickStart, SDUtil sdu, String sdServer, SummarizeProgress progress, String dataPath) {
		long started = System.currentTimeMillis();
		BaseRecord fresh = PbBookUtil.requireBookUpdate(user, book, "compose scenes");
		String slug = fresh.get(OlioFieldNames.FIELD_PB_SLUG);
		long orgId = PbGraphUtil.orgId(fresh);
		boolean chapBook = PbNodeExecutor.isChapBook(fresh);
		List<BaseRecord> scenes = PbBookUtil.listScenes(user, fresh);

		int visit = (chapBook ? 0 : scenes.size());
		if(progress != null) {
			progress.setTotal(visit + 1);
			progress.setCurrent(0);
		}

		// P4-10 (design §28 step 4): one-off migration of legacy scene fields into MIGRATED compositions, as the
		// job's user, before any scene is composed. STORY books only; ChapBook page compositions come from the
		// ChapBook path. Idempotent, so running it on every compose is safe. Failures are reported, never thrown.
		Map<String, Object> migration = null;
		int migratedCompositions = 0;
		Set<String> migrationFailedScenes = Collections.emptySet();
		if(!chapBook) {
			if(progress != null) {
				progress.setPhase("migrate compositions");
			}
			try {
				PbMigrationUtil.CompositionMigrationResult mig = PbMigrationUtil.migrateCompositions(user, fresh, scenes);
				migration = mig.toMap();
				migratedCompositions = mig.created;
				migrationFailedScenes = mig.failedSceneObjectIds;
			}
			catch(RuntimeException e) {
				logger.warn("compose " + slug + ": composition migration failed: " + e.getMessage());
				migration = new LinkedHashMap<>();
				migration.put("error", PbServiceFacade.curatedMessage(e));
				// A migration that threw before its per-scene loop migrated nothing; every scene is then in the
				// same position as a per-scene failure and must not be composed without its legacy carry-over.
				migrationFailedScenes = new HashSet<>();
				for(BaseRecord scene : scenes) {
					String soid = scene.get(FieldNames.FIELD_OBJECT_ID);
					if(soid != null) {
						migrationFailedScenes.add(soid);
					}
				}
			}
		}

		int composed = 0;
		int current = 0;
		int skippedUserEdited = 0;
		int migrated = 0;
		int hardFailures = 0;
		int pages = 0;
		int quickStartRendered = 0;
		int quickStartFailed = 0;
		int lastSceneIndex = -1;
		boolean cancelled = false;
		boolean interrupted = false;
		List<Map<String, Object>> outcomes = new ArrayList<>();

		int n = 0;
		for(BaseRecord scene : scenes) {
			if(chapBook) {
				pages++;
				continue;
			}
			n++;
			if(shuttingDown()) {
				interrupted = true;
				break;
			}
			if(isCancelled(progress)) {
				cancelled = true;
				break;
			}
			Integer idx = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
			String title = scene.get(OlioFieldNames.FIELD_PB_TITLE);
			String label = n + "/" + visit + (title != null && !title.isBlank() ? " " + title : "");
			Map<String, Object> outcome = new LinkedHashMap<>();
			outcome.put("sceneObjectId", scene.get(FieldNames.FIELD_OBJECT_ID));
			outcome.put("sceneIndex", idx);
			outcome.put("title", title);
			outcomes.add(outcome);

			// Compositions live in the book's group (where the planner, listSceneCompositions and the page path
			// look), not the scene's — chapter-group scenes would otherwise never find their composition.
			Long gid = fresh.get(FieldNames.FIELD_GROUP_ID);
			BaseRecord existing = (gid != null ? PbComposeUtil.findSceneComposition(user, scene, gid.longValue(), orgId) : null);
			if(!force && !PbComposeUtil.needsCompose(existing)) {
				current++;
				outcome.put("status", "current");
				outcome.put("compositionObjectId", existing.get(FieldNames.FIELD_OBJECT_ID));
				tick(progress);
				continue;
			}
			// A fresh LLM composition carries the scene's legacy sdPrompt/promptLocked only through the MIGRATED
			// path, so a scene whose migration failed is not composed this run: the next compose retries it.
			if(existing == null && migrationFailedScenes.contains(scene.get(FieldNames.FIELD_OBJECT_ID))) {
				hardFailures++;
				outcome.put("status", "migrationFailed");
				outcome.put("error", "composition migration failed for this scene; not composed so its legacy prompt and lock are kept");
				logger.warn("compose job " + slug + " scene " + idx + ": migration failed; skipping the compose");
				tick(progress);
				continue;
			}

			if(progress != null) {
				progress.setPhase("compose " + label);
			}
			ComposeResult r;
			try {
				r = PbComposeUtil.composeScene(user, fresh, scene, chatConfig, force, progress, dataPath);
			}
			catch(RuntimeException e) {
				String msg = PbServiceFacade.curatedMessage(e);
				if(shuttingDown()) {
					outcome.put("status", "interrupted");
					outcome.put("error", "interrupted by shutdown: " + msg);
					interrupted = true;
					break;
				}
				if(isCancelled(progress)) {
					outcome.put("status", "cancelled");
					outcome.put("error", "cancelled: " + msg);
					cancelled = true;
					break;
				}
				hardFailures++;
				outcome.put("status", "error");
				outcome.put("error", msg);
				logger.warn("compose job " + slug + " scene " + idx + " failed: " + detailOf(e));
				tick(progress);
				continue;
			}
			if(r.cancelled) {
				outcome.put("status", "cancelled");
				cancelled = true;
				break;
			}
			if(r.skippedUserEdited) {
				skippedUserEdited++;
				outcome.put("status", "skippedUserEdited");
			}
			else if(r.hardFailure) {
				hardFailures++;
				outcome.put("status", "hardFailure");
				outcome.put("error", r.failureReason);
			}
			else if(r.migratedFallback) {
				migrated++;
				outcome.put("status", "migrated");
				outcome.put("error", r.failureReason);
			}
			else {
				composed++;
				outcome.put("status", "composed");
			}
			if(r.composition != null) {
				outcome.put("compositionObjectId", r.composition.get(FieldNames.FIELD_OBJECT_ID));
			}
			outcome.put("source", r.source.name());
			outcome.put("castTier", r.castTier.name());
			outcome.put("actorCount", Integer.valueOf(r.actors.size()));
			outcome.put("elementCount", Integer.valueOf(r.elements.size()));
			outcome.put("outfitsCreated", Integer.valueOf(r.outfitsCreated));
			outcome.put("outfitsReused", Integer.valueOf(r.outfitsReused));
			outcome.put("outfitsSkipped", Integer.valueOf(r.outfitsSkipped));
			outcome.put("pinsPreserved", Integer.valueOf(r.pinsPreserved));
			outcome.put("warnings", PbServiceFacade.stripRaw(r.warnings));
			outcome.put("elapsedMs", Long.valueOf(r.elapsedMs));
			if(idx != null) {
				lastSceneIndex = idx.intValue();
			}

			if(quickStart && r.isWritten()) {
				if(progress != null) {
					progress.setPhase("quick start " + label);
				}
				boolean laneHeld = false;
				long renderStarted = System.currentTimeMillis();
				try {
					laneHeld = PbRenderLanes.acquire(sdServer, progress);
					if(!laneHeld) {
						if(shuttingDown()) {
							outcome.put("draftRenderError", "interrupted by shutdown while waiting for the render lane");
							interrupted = true;
							break;
						}
						outcome.put("draftRenderError", "cancelled while waiting for the render lane");
						cancelled = true;
						break;
					}
					Map<String, Object> ro = PbServiceFacade.executePlanScene(user, fresh, scene, sdu, QUICK_START_OPTIONS, progress);
					outcome.put("draftRender", draftRenderDto(ro));
					quickStartRendered++;
				}
				catch(PbStageCancelledException e) {
					outcome.put("draftRenderError", "cancelled: " + PbServiceFacade.curatedMessage(e));
					cancelled = true;
					break;
				}
				catch(RuntimeException e) {
					String msg = PbServiceFacade.curatedMessage(e);
					if(shuttingDown()) {
						outcome.put("draftRenderError", "interrupted by shutdown: " + msg);
						interrupted = true;
						break;
					}
					if(isCancelled(progress)) {
						outcome.put("draftRenderError", "cancelled: " + msg);
						cancelled = true;
						break;
					}
					quickStartFailed++;
					outcome.put("draftRenderError", msg);
					logger.warn("compose job " + slug + " quick-start render of scene " + idx + " failed: " + detailOf(e));
				}
				finally {
					if(laneHeld) {
						PbRenderLanes.release(sdServer);
					}
					outcome.put("draftRenderMs", Long.valueOf(System.currentTimeMillis() - renderStarted));
				}
			}
			tick(progress);
		}

		Map<String, Object> locationPass = null;
		if(!cancelled && !interrupted) {
			if(shuttingDown()) {
				interrupted = true;
			}
			else if(isCancelled(progress)) {
				cancelled = true;
			}
			else {
				if(progress != null) {
					progress.setPhase("locations");
				}
				LocationPassResult lp = PbLocationUtil.proposeLocations(user, fresh, chatConfig, progress);
				locationPass = PbServiceFacade.locationPassDto(lp);
				if(lp.cancelled) {
					cancelled = true;
				}
				tick(progress);
			}
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("bookObjectId", fresh.get(FieldNames.FIELD_OBJECT_ID));
		result.put("slug", slug);
		result.put("quickStart", Boolean.valueOf(quickStart));
		result.put("force", Boolean.valueOf(force));
		result.put("sceneCount", Integer.valueOf(scenes.size()));
		result.put("visited", Integer.valueOf(outcomes.size()));
		result.put("composed", Integer.valueOf(composed));
		result.put("current", Integer.valueOf(current));
		result.put("skippedUserEdited", Integer.valueOf(skippedUserEdited));
		result.put("migrated", Integer.valueOf(migrated));
		result.put("hardFailures", Integer.valueOf(hardFailures));
		result.put("pagesSkipped", Integer.valueOf(pages));
		result.put("quickStartRendered", Integer.valueOf(quickStartRendered));
		result.put("quickStartFailed", Integer.valueOf(quickStartFailed));
		result.put("lastSceneIndex", Integer.valueOf(lastSceneIndex));
		result.put("cancelled", Boolean.valueOf(cancelled));
		result.put("interrupted", Boolean.valueOf(interrupted));
		result.put("locationPassRan", Boolean.valueOf(locationPass != null));
		result.put("locationPass", locationPass);
		result.put("migration", migration);
		result.put("scenes", outcomes);
		if(cancelled || interrupted) {
			result.put("note", NOTE_RESUME);
		}
		result.put("durationMs", Long.valueOf(System.currentTimeMillis() - started));
		logger.info("compose job " + slug + ": " + composed + " composed, " + current + " current, " + migrated + " migrated, "
			+ hardFailures + " failed, " + pages + " page(s) skipped" + (quickStart ? ", " + quickStartRendered + " draft(s) rendered" : "")
			+ (locationPass != null ? ", location pass ran" : ", location pass skipped")
			+ ", " + migratedCompositions + " migrated composition(s) created"
			+ (cancelled ? " CANCELLED" : "") + (interrupted ? " INTERRUPTED" : ""));
		return result;
	}

	/// The subset of the executor's DTO the job result keeps per scene; the artifact itself is read through the board.
	private static Map<String, Object> draftRenderDto(Map<String, Object> ro) {
		Map<String, Object> m = new LinkedHashMap<>();
		if(ro == null) {
			return m;
		}
		for(String k : new String[] { "artifactObjectId", "nodeObjectId", "nodeStatus", "runObjectId", "reviewStatus",
				"strategy", "stagesPlanned", "stagesRendered", "degradations", PbNodeExecutor.KEY_AUTO_APPROVED,
				PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED, PbNodeExecutor.KEY_CONFIG_NOT_APPLIED }) {
			if(ro.get(k) != null) {
				m.put(k, ro.get(k));
			}
		}
		return m;
	}

	// ─────────────────────────────── the worker: location pass ───────────────────────────────

	static final class ProposeWork extends ClaimedWork {
		private final BaseRecord user;
		private final BaseRecord book;
		private final BaseRecord chatConfig;

		ProposeWork(BaseRecord user, BaseRecord book, BaseRecord chatConfig) {
			this.user = user;
			this.book = book;
			this.chatConfig = chatConfig;
		}

		@Override
		public String run(AsyncJob job) {
			try {
				return JSONUtil.exportObject(proposeNow(user, book, chatConfig, job.getProgress()));
			}
			finally {
				releaseClaim(getClaim());
			}
		}
	}

	/** The location-pass job's body: {@code bookObjectId} + {@code PbServiceFacade.locationPassDto}. */
	static Map<String, Object> proposeNow(BaseRecord user, BaseRecord book, BaseRecord chatConfig, SummarizeProgress progress) {
		long started = System.currentTimeMillis();
		if(progress != null) {
			progress.setPhase("locations");
		}
		LocationPassResult lp = PbLocationUtil.proposeLocations(user, book, chatConfig, progress);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bookObjectId", book.get(FieldNames.FIELD_OBJECT_ID));
		out.put("kind", PbRenderJobUtil.KIND_PROPOSE_LOCATIONS);
		out.putAll(PbServiceFacade.locationPassDto(lp));
		out.put("durationMs", Long.valueOf(System.currentTimeMillis() - started));
		return out;
	}

	// ─────────────────────────────── the worker: wardrobe reference ───────────────────────────────

	static final class ReferenceWork extends ClaimedWork {
		private final BaseRecord user;
		private final BaseRecord book;
		private final String characterObjectId;
		private final String apparelObjectId;
		private final SDUtil sdu;
		private final String sdServer;

		ReferenceWork(BaseRecord user, BaseRecord book, String characterObjectId, String apparelObjectId, SDUtil sdu, String sdServer) {
			this.user = user;
			this.book = book;
			this.characterObjectId = characterObjectId;
			this.apparelObjectId = apparelObjectId;
			this.sdu = sdu;
			this.sdServer = sdServer;
		}

		@Override
		public String run(AsyncJob job) {
			try {
				return JSONUtil.exportObject(referenceNow(user, book, characterObjectId, apparelObjectId, sdu, sdServer, job.getProgress()));
			}
			finally {
				releaseClaim(getClaim());
			}
		}
	}

	/**
	 * The wardrobe-reference job's body: {@link PbWardrobeUtil#referenceImage} under the backend's render
	 * lane. A cancel while waiting for the lane, or a {@link PbStageCancelledException} from the render, ends
	 * the job {@code cancelled} with nothing linked; any other failure is re-thrown with its message curated
	 * so the registry records it as the job's error and the owner never sees a raw exception text.
	 */
	static Map<String, Object> referenceNow(BaseRecord user, BaseRecord book, String characterObjectId, String apparelObjectId,
			SDUtil sdu, String sdServer, SummarizeProgress progress) {
		long started = System.currentTimeMillis();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bookObjectId", book.get(FieldNames.FIELD_OBJECT_ID));
		out.put("characterObjectId", characterObjectId);
		out.put("apparelObjectId", apparelObjectId);
		out.put("kind", PbRenderJobUtil.KIND_APPAREL_REFERENCE);
		if(progress != null) {
			progress.setTotal(1);
			progress.setCurrent(0);
			progress.setPhase("wardrobe reference");
		}
		boolean laneHeld = false;
		try {
			laneHeld = PbRenderLanes.acquire(sdServer, progress);
			if(!laneHeld) {
				out.put("cancelled", Boolean.TRUE);
				out.put("error", (shuttingDown()
					? "interrupted by shutdown while waiting for the render lane"
					: "cancelled while waiting for the render lane"));
				return out;
			}
			PbWardrobeUtil.ReferenceResult rr = PbWardrobeUtil.referenceImage(user, book, characterObjectId, apparelObjectId, sdu);
			out.put("cancelled", Boolean.FALSE);
			out.put("referenceObjectId", rr.referenceObjectId);
			List<String> ids = new ArrayList<>();
			for(BaseRecord img : rr.images) {
				ids.add(img.get(FieldNames.FIELD_OBJECT_ID));
			}
			out.put("imageObjectIds", ids);
			out.put("imageCount", Integer.valueOf(ids.size()));
			out.put("groupPath", rr.groupPath);
			out.put("renderMs", Long.valueOf(rr.elapsedMs));
			tick(progress);
			return out;
		}
		catch(PbStageCancelledException e) {
			out.put("cancelled", Boolean.TRUE);
			out.put("error", "cancelled: " + PbServiceFacade.curatedMessage(e));
			return out;
		}
		catch(PictureBookException e) {
			logger.warn("wardrobe reference " + apparelObjectId + " refused: " + detailOf(e));
			throw e;
		}
		catch(RuntimeException e) {
			logger.warn("wardrobe reference " + apparelObjectId + " failed: " + detailOf(e));
			throw new PictureBookException(500, PbServiceFacade.curatedMessage(e), e);
		}
		finally {
			if(laneHeld) {
				PbRenderLanes.release(sdServer);
			}
			out.put("durationMs", Long.valueOf(System.currentTimeMillis() - started));
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private static void tick(SummarizeProgress progress) {
		if(progress != null) {
			progress.incrementCurrent();
		}
	}

	private static boolean isCancelled(SummarizeProgress progress) {
		return progress != null && progress.isCancelled();
	}

	/// For the log only; the owner-visible text is {@link PbServiceFacade#curatedMessage}.
	private static String detailOf(RuntimeException e) {
		return e.getClass().getName() + (e.getMessage() != null && !e.getMessage().isBlank() ? ": " + e.getMessage() : "");
	}

	/** The container has announced its stop or the pool has interrupted this worker. */
	private static boolean shuttingDown() {
		return AsyncJobRegistry.isShuttingDown() || Thread.currentThread().isInterrupted();
	}
}
