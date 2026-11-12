import m from 'mithril';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';
import {
    createFromScenes, createChapBookRecord, regenerateBlurb, loadPictureBook,
    startExtractScenes, startRetryFailedChunks, pollJob, cancelJob, listJobs, scenesFromResult,
    getExtractCheckpoints, discardExtractCheckpoints
} from './sceneExtractor.js';
import { initCharacterManager, renderCharacterManagerContent } from './pictureBookCharacters.js';
import { listPb2Books, listSeriesBooks, detectBoundaries, createSeries, createChapter, resolvePb2BookObjectId } from './pictureBookWorkflow.js';
import { ObjectPicker } from '../components/picker.js';
import { LLMConnector } from '../chat/LLMConnector.js';
import { resolveDefaultChatConfig } from '../components/pbChatConfig.js';

/**
 * Picture Book workflow — multi-step wizard launched from a data.data or data.note object.
 * Steps:
 *   1 — Source & Method (auto vs manual, chat config, scene count, genre)
 *   2 — Scene Preview (auto path: review/edit extracted scenes). "Continue" creates the book +
 *       real charPerson records (createFromScenes) before advancing — real characters exist by
 *       the time Step 3 renders.
 *   3 — Manage Characters (real charPerson records: portrait, statistics, apparel, "Open Full
 *       Editor" link — renders pictureBookCharacters.js inline, same UI as the standalone popup)
 * Step 3's "Continue to Images" closes the wizard and hands off to the V3 reader, where every image is
 * rendered; an existing book never re-enters the wizard (P10-1: the PB1 image steps 4–5 are gone).
 */

// ── Wizard state ──────────────────────────────────────────────────────

let step = 1;
let workObjectId = null;  // Source document objectId (for extract API)
let bookObjectId = null;  // Book group objectId (for scenes/viewer/reset APIs)
let pb2WizardBookOid = null;  // olio.pb.book objectId — PB2 viewer/workflow navigation only
let pb2WizardSlug = null;     // slug the pb2 book above was created for; a retry with the same slug reuses it
let workName = '';
function readerRoute(pb2Oid) {
    return '/picture-book/v2/' + pb2Oid;
}

/**
 * Step-3 "Continue to Images": close the wizard and open the V3 reader on the pb2 book the step-2
 * Continue created. When the wizard was resumed on a PB1 group without a cached pb2 oid, resolve it
 * through the /pb2 bridge first; no V3 record means the step-2 Continue has not run, so stay on step 3.
 */
async function handOffToReader() {
    let oid = pb2WizardBookOid;
    if (!oid && bookObjectId) {
        oid = await resolvePb2BookObjectId(bookObjectId);
        if (oid) pb2WizardBookOid = oid;
    }
    if (!oid) {
        page.toast('error', 'This book has no V3 record to open; go Back and Continue again to create it.');
        m.redraw();
        return null;
    }
    Dialog.close();
    m.route.set(readerRoute(oid));
    return readerRoute(oid);
}

// Step 1
let method = 'auto';
let bookName = '';
// Object refs (record with .name and .objectId), not just names — resolved from library on open
let chatConfigRef = null;
let genre = '';
let promptMode = 'single';  // 'single' | 'per-prompt'
let promptTemplate = null;   // single mode — applies to all (object ref)
let promptTemplates = {      // per-prompt mode (object refs)
    extractScenes: null,
    extractChunk: null,
    extractCharacter: null,
    sceneBlurb: null,
    landscapePrompt: null
};
let defaultsLoading = false;

// Default prompt template names for each slot — resolved on demand from system library
const DEFAULT_PROMPT_NAMES = {
    extractScenes: 'pictureBook.extract-scenes',
    extractChunk: 'pictureBook.extract-chunk',
    extractCharacter: 'pictureBook.extract-character',
    sceneBlurb: 'pictureBook.scene-blurb',
    landscapePrompt: 'pictureBook.landscape-prompt'
};
// "single" mode primary template — applied to all prompts if no per-slot override given
const DEFAULT_SINGLE_TEMPLATE = 'pictureBook.extract-scenes';

// Step 2
let extractedScenes = [];
let extracting = false;
let extractError = null;
// Async extraction job state. Extraction used to be a single fetch held open for the whole run,
// which is why a proxy timeout destroyed ~27 minutes of work: nothing survived the connection.
// It is now a background job we poll, so these track the run rather than the request.
let extractJobId = null;        // the in-flight job, also what Cancel targets
let reattaching = false;        // a reattach lookup is in flight (claimed before its first await)
let extractProgress = null;     // { current, total } straight from the job's progress token
let extractCancelling = false;  // cancel requested, waiting for the loop to reach a chunk boundary
let extractPartial = false;     // the scene list is incomplete (cancelled, or stopped early)
let extractFailedChunks = [];   // per-passage failure records ({context, kind, chunk, total, error, rawResponse}), surfaced not swallowed
let retryingFailed = false;     // a failed-passage retry job is in flight (extracting is also true)
let extractChaptered = false;   // the last run took the per-chapter fan-out, not the single-document path
// N-series fan-out state. When a novel splits into chapters, doExtract runs ONE bounded extraction job
// per chapter SEQUENTIALLY (one Ollama server; server caps MAX_CONCURRENT_JOBS=2), and these carry the
// "chapter i of N" context so onExtractProgress can label the shared activity indicator across chapters.
let extractChapterIndex = 0;    // 1-based index of the chapter currently extracting (0 = not fanning out)
let extractChapterCount = 0;    // total chapters in this fan-out (0 = single whole-document extraction)
let pb2SeriesObjectId = null;   // the olio.pb.series created/resolved for a multi-chapter submission
// Set when a fan-out finishes with ANY chapter missing or incomplete. The wizard then stays open on
// this summary instead of closing + navigating: a toast that disappears in a few seconds is not a
// report when the run took nine hours and half the chapters are gone.
let extractChapterSummary = null; // { saved, total, problems: [], seriesBookOid, cancelled }
// Server-side checkpoints for this document that have no book behind them yet. A single-document
// extraction persists nothing but its checkpoint until createFromScenes runs, so an interrupted run
// is invisible everywhere except here — Step 1 shows them with Resume/Discard.
let pendingCheckpoints = [];    // rows from GET /{workObjectId}/extract-checkpoints (no scene payload)
let checkpointDiscarding = false;
let blurbRegenerating = {}; // scene index → bool (U3: per-scene "Regenerate blurb" in-flight flag)

// Step 3 (Manage Characters — real charPerson records created at the Step 2→3 transition;
// pictureBookCharacters.js owns its own list/detail state once initCharacterManager() runs)
let creatingChars = false;

// Persisted-scene mirror from createFromScenes (Step 2→3); read by Step 2's blurb regenerate after a Back.
let scenes = [];

// Issue 9: role warning
let roleWarning = false;

function resetState() {
    step = 1;
    bookObjectId = null;
    pb2WizardBookOid = null;
    pb2WizardSlug = null;
    method = 'auto';
    bookName = '';
    chatConfigRef = null;
    genre = '';
    promptMode = 'single';
    promptTemplate = null;
    promptTemplates = { extractScenes: null, extractChunk: null, extractCharacter: null, sceneBlurb: null, landscapePrompt: null };
    defaultsLoading = false;
    extractedScenes = [];
    extracting = false;
    extractError = null;
    extractJobId = null;
    reattaching = false;
    extractProgress = null;
    extractCancelling = false;
    extractPartial = false;
    extractFailedChunks = [];
    retryingFailed = false;
    extractChaptered = false;
    extractChapterIndex = 0;
    extractChapterCount = 0;
    pb2SeriesObjectId = null;
    extractChapterSummary = null;
    pendingCheckpoints = [];
    checkpointDiscarding = false;
    blurbRegenerating = {};
    creatingChars = false;
    scenes = [];
    roleWarning = false;
}

// URL-safe slug from a book name — used as the PB2 olio.pb.book slug when calling createChapBookRecord.
function generateSlug(name) {
    return (name || '').toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .substring(0, 64) || 'book-' + Date.now().toString(36);
}

// ── Step helpers ──────────────────────────────────────────────────────

// Extract a lightweight object ref { name, objectId } from a full record.
// Null-safe — returns null if the record is missing name/objectId.
function toRef(rec) {
    if (!rec || typeof rec !== 'object' || !rec.name || !rec.objectId) return null;
    return { name: rec.name, objectId: rec.objectId };
}

// Look up system defaults from the shared library. Library is populated lazily
// on-demand by the backend, so we re-resolve each time the wizard opens.
// Only fills slots that are still null (user selections are preserved).
async function loadDefaults() {
    defaultsLoading = true;
    m.redraw();
    try {
        // Ensure both libraries exist (idempotent — skips if already populated)
        try { await LLMConnector.ensureLibrary(); } catch (e) { /* non-fatal */ }
        try { await LLMConnector.initPromptLibrary(); } catch (e) { /* non-fatal */ }

        let lookups = [];
        if (!chatConfigRef) {
            // The library default every PB surface shares (pbChatConfig.DEFAULT_CHAT_CONFIG_NAMES: contentAnalysis,
            // then generalChat — P10-D 4, §6.C 267 d; the wizard's own copy tried contentAnalysis only).
            lookups.push(resolveDefaultChatConfig()
                .then(r => { chatConfigRef = toRef(r); }));
        }
        if (!promptTemplate) {
            lookups.push(LLMConnector.resolveTemplate(DEFAULT_SINGLE_TEMPLATE)
                .then(r => { promptTemplate = toRef(r); }));
        }
        Object.keys(DEFAULT_PROMPT_NAMES).forEach(function (key) {
            if (promptTemplates[key]) return;
            lookups.push(LLMConnector.resolveTemplate(DEFAULT_PROMPT_NAMES[key])
                .then(r => { promptTemplates[key] = toRef(r); }));
        });
        await Promise.all(lookups);
    } catch (e) {
        console.warn('[PictureBook] loadDefaults failed:', e);
    }
    defaultsLoading = false;
    m.redraw();
}

// Prompt "slots" whose template needs completely different vars than the extraction slots
// (setting/action/mood/charNarrations, not {text}/{count}-style story vars). "single" mode's one
// user-picked template is resolved against DEFAULT_SINGLE_TEMPLATE (an extraction template) and
// was never validated against this shape — applying it here leaves the image-prompt template's
// real vars ({text}/{count}) unsubstituted server-side, which the backend now refuses to send to
// the LLM at all (PictureBookUtil.callLlmInternal's placeholder guard) rather than sending garbage,
// but that means "single" mode's override silently no-ops for these slots instead of doing what the
// user picked it for. Found + fixed 2026-07-23 (KI-31 follow-up) after "prompts are still completely
// broken" turned out to be exactly this: a scene-extraction template applied to a landscape/
// scene-image-prompt call. Per-prompt mode is unaffected — it always looks up the slot-specific
// promptTemplates[key], which was never wired to this bug.
const IMAGE_PROMPT_SLOTS = ['landscapePrompt', 'sceneImagePrompt'];

function getPromptTemplate(key) {
    if (promptMode === 'single') {
        // Never let "single" mode's one extraction-shaped template leak into an image-prompt call —
        // it doesn't define the vars those templates need, so applying it can't do anything but
        // leave the real template's placeholders unfilled. Fall through to that operation's own
        // default template (server-side) by returning null, same as if no override were requested.
        if (IMAGE_PROMPT_SLOTS.includes(key)) return null;
        return promptTemplate ? promptTemplate.name : null;
    }
    let ref = promptTemplates[key];
    return ref ? ref.name : null;
}

function chatConfigName() {
    return chatConfigRef ? chatConfigRef.name : null;
}

// One chat config drives every LLM call the wizard makes (extraction, retries, per-scene image
// prompts), so every picker in the wizard binds to the same chatConfigRef.
function openChatConfigPicker() {
    ObjectPicker.openLibrary({
        libraryType: 'chatConfig',
        title: 'Select Chat Config',
        onSelect: function (item) {
            if (item && item.name) {
                chatConfigRef = { name: item.name, objectId: item.objectId };
                m.redraw();
            }
        }
    });
}

function chatConfigLabel() {
    return chatConfigRef ? chatConfigRef.name : (defaultsLoading ? 'Loading default...' : '(click to select)');
}

/**
 * Minimal client-side character hints for createFromScenes — {name, gender, role} only. No
 * appearance/outfit/portraitPrompt: those were dead weight (createCharPerson never read them in
 * production) and omitting appearance is what makes the real per-character LLM enrichment call
 * fire and build real detail from the source text, instead of a hand-typed guess made before any
 * real charPerson (or its statistics/apparel/narrative) exists.
 *
 * @param {Array} [sceneSource] the scenes to draw the cast from. The single-document Step 2→3 path
 *        passes nothing and gets the wizard-global aggregate (`extractedScenes`); the N-series
 *        fan-out passes ONE chapter's scenes so each chapter's book is seeded from its own cast, not
 *        an aggregate the chaptered path no longer builds.
 */
function buildCharacterStubs(sceneSource) {
    let sceneList = Array.isArray(sceneSource) ? sceneSource : extractedScenes;
    let seen = {};
    let stubs = [];
    for (let s of sceneList) {
        if (!Array.isArray(s.characters)) continue;
        for (let c of s.characters) {
            let name = typeof c === 'string' ? c : (c.name || '');
            if (!name || seen[name]) continue;
            seen[name] = true;
            let obj = typeof c === 'object' ? c : {};
            stubs.push({ name: name, gender: obj.gender || '', role: obj.role || '' });
        }
    }
    return stubs;
}

/**
 * Render the job's progress into the shared background-activity indicator, every tick.
 *
 * This is also the fix for the indicator dying mid-run. LLMConnector.setBgActivity arms a 90s
 * self-clearing timer and restarts it on each call, but a chunk takes 80-110s — so on a long
 * extraction the indicator silently vanished between chunks even though the run was healthy.
 * Refreshing it from the poller keeps it alive for exactly as long as the job is, and needs
 * nothing from the WebSocket.
 */
function onExtractProgress(job) {
    extractProgress = { current: job.current || 0, total: job.total || 0 };
    // In a multi-chapter fan-out, prefix the per-chunk progress with "Chapter i/N" so the user can see
    // the run advancing across chapters, not just within the current one.
    let prefix = extractChapterCount > 0
        ? 'Chapter ' + extractChapterIndex + '/' + extractChapterCount + ' — '
        : '';
    let label = extractProgress.total > 0
        ? prefix + 'Extracting scenes ' + extractProgress.current + '/' + extractProgress.total
        : prefix + 'Extracting scenes...';
    if (extractCancelling) label = 'Cancelling extraction...';
    LLMConnector.setBgActivity('auto_awesome', label);
    m.redraw();
}

/**
 * Apply a terminal job to wizard state.
 *
 * A cancelled job is NOT an error: the chunk loop breaks at a boundary and returns the scenes it
 * already extracted, which is the whole reason cancelling is safe to offer. Those scenes are kept
 * and the list is flagged partial, rather than being thrown away.
 */
function applyExtractJob(job) {
    let sceneArray = scenesFromResult(job.result);
    extractFailedChunks = (job.result && job.result.failedExtractions) || job.failedExtractions || [];
    if (job.status === 'failed') {
        extractError = job.error || 'Extraction failed';
        return;
    }
    if (!sceneArray.length) {
        extractError = (job.status === 'cancelled')
            ? 'Extraction was cancelled before any scenes were extracted.'
            : 'No scenes returned by LLM';
        return;
    }
    extractPartial = job.status === 'cancelled'
        || (job.result && job.result.extractionComplete === false);
    extractedScenes = sceneArray;
    step = 2;
}

/**
 * Unified extract — starts a background job and polls it.
 *
 * The server auto-chunks text over 8000 chars. Both response shapes (a bare array for short text,
 * { sceneList, chunked } once chunked) are unwrapped by scenesFromResult.
 *
 * Issue 1 (novel-length manuscripts): a full novel submitted as ONE unbounded extraction job runs
 * ~4-5h, and the client poll deadline (JOB_POLL_DEADLINE_MS, 90 min) gives up long before it
 * finishes — so nothing is ever adopted. The fix is to split a chaptered manuscript into ONE bounded
 * extraction job per chapter and run them SEQUENTIALLY (one Ollama server; the server caps
 * MAX_CONCURRENT_JOBS=2). Detection is best-effort: a manuscript with fewer than two detectable
 * chapters, or any detection failure, falls back to the original single whole-document path below —
 * which is byte-for-byte unchanged, including the query string it sends.
 *
 * @param {Object} [opts]
 * @param {boolean} [opts.fresh] discard a resumable checkpoint and re-extract from the first
 *        chunk. A cancelled or interrupted run keeps its checkpoint server-side so the next
 *        attempt continues where it stopped; "Re-extract from scratch" passes this to override it.
 */
async function doExtract(opts) {
    extracting = true;
    extractError = null;
    extractProgress = null;
    extractCancelling = false;
    extractPartial = false;
    extractFailedChunks = [];
    extractChapterIndex = 0;
    extractChapterCount = 0;
    extractChapterSummary = null;
    m.redraw();
    // Hold the indicator so unrelated activity cannot clear it out from under a long run.
    let bgToken = LLMConnector.lockBgActivity();
    try {
        // Best-effort chapter detection. A failure here must NOT abort extraction — it just means we
        // treat the manuscript as one document, exactly as before this fan-out existed.
        let ranges = [];
        try {
            let detected = await detectBoundaries(workObjectId);
            if (Array.isArray(detected)) ranges = detected;
        } catch (e) {
            console.warn('[pictureBook] chapter boundary detection failed; '
                + 'extracting as a single document:', e && e.message);
        }
        extractChaptered = ranges.length >= 2;
        if (extractChaptered) {
            // Multi-chapter novel: one bounded extraction job per chapter, sequentially.
            await fanOutChaptersExtract(ranges, opts);
        } else {
            // Single whole-document extraction — the ORIGINAL path, unchanged. No offsets, no series;
            // startExtractScenes produces exactly '?async=true' [+ '&fresh=true'] as before.
            let started = await startExtractScenes(workObjectId, chatConfigName(), null,
                getPromptTemplate('extractScenes'), opts);
            extractJobId = started.jobId;
            m.redraw();
            let job = await pollJob(extractJobId, { onProgress: onExtractProgress });
            applyExtractJob(job);
        }
    } catch (e) {
        if (e && e.name === 'AbortError') {
            // Stopped watching, deliberately. The job keeps running and its result stays
            // collectable — reopening the wizard reattaches to it.
        } else {
            extractError = e.message || 'Extraction failed';
        }
    } finally {
        LLMConnector.unlockBgActivity(bgToken);
        LLMConnector.setBgActivity(null, null);
        extracting = false;
        extractCancelling = false;
        extractJobId = null;
        extractProgress = null;
        extractChapterIndex = 0;
        extractChapterCount = 0;
        m.redraw();
        // The run either consumed the checkpoint (clean finish) or left one (cancel/stop/failures);
        // the Step 1 banner must reflect which. Not awaited — it is a refresh, not part of the run.
        loadPendingCheckpoints();
    }
}

/**
 * Fan a chaptered manuscript out into ONE bounded extraction job per chapter, run SEQUENTIALLY, and
 * persist each chapter's scenes into ITS OWN chapter book in the ONE shared series world (Full
 * N-series). There is NO Step-2 aggregate review for chaptered novels — the flow finishes on the
 * series canvas.
 *
 * Sequential is a hard requirement, not a nicety: there is one Ollama server behind extraction and
 * the server caps concurrent jobs at 2, so each chapter's job is awaited to completion before the
 * next one starts. Progress is surfaced as "Chapter i/N" via extractChapterIndex/Count, which
 * onExtractProgress reads.
 *
 * Per chapter, in order:
 *   1. createChapter(...) → capture its `bookObjectId` (the chapter's olio.pb.book objectId). A
 *      create failure is FATAL for that chapter (no book ⇒ nowhere to persist), so it is recorded
 *      and reported, never silently swallowed, and the chapter is skipped.
 *   2. Bounded async extraction over the chapter's [startOffset, endOffset) span (server clamps +
 *      slices), polled to completion.
 *   3. createFromScenes(..., chapterBookObjectId) persists THAT chapter's scenes + cast into its own
 *      chapter book. Because the book's `series` FK points at the shared series world, the backend
 *      reuses that world rather than minting a duplicate. The cast is seeded from the chapter's own
 *      scenes (buildCharacterStubs(chapScenes)), not an aggregate.
 *
 * On completion, the wizard closes and navigates to the series canvas — the "series" view mode of
 * the first chapter book's workflow route (there is no route keyed on a series objectId; the series
 * overview loads from the book's `series` FK).
 *
 * @param {Array} ranges [{startOffset, endOffset, title}] from detectBoundaries — already the shape
 *        createChapter's sourceRange accepts.
 * @param {Object} [opts] the same opts doExtract received; only opts.fresh is forwarded per chapter.
 */
async function fanOutChaptersExtract(ranges, opts) {
    let title = bookName || workName || '';
    let seriesSlugBase = generateSlug(title);
    // Get-or-create the series ONCE (idempotent server-side). Every chapter book created below shares
    // this series' single world, so re-running a novel submission reuses the same series rather than
    // minting duplicates.
    let series = await createSeries(seriesSlugBase, title);
    pb2SeriesObjectId = series ? series.seriesObjectId : null;
    extractChapterCount = ranges.length;

    let firstChapterBookOid = null; // where the series canvas is reached (any chapter book resolves it)
    let persistedCount = 0;         // chapters whose scenes actually landed in a book
    let problems = [];              // per-chapter failures/warnings, surfaced not swallowed
    let chapters = [];              // per-chapter record: what was saved, what failed, how to retry it
    let anyPartial = false;
    let cancelled = false;
    let infraAbort = false;         // the server's unreachable-LLM breaker tripped; stop the fan-out

    for (let i = 0; i < ranges.length; i++) {
        let r = ranges[i];
        let chapNum = i + 1;
        extractChapterIndex = chapNum;
        m.redraw();
        // The server returns a null title only for a prologue-sized lead before the first heading.
        let chapTitle = (r.title != null && String(r.title).trim().length)
            ? String(r.title).trim()
            : (i === 0 ? 'Front Matter' : 'Chapter ' + chapNum);
        // The chapter's checkpoint is keyed on its offsets, so a later per-passage retry must carry
        // exactly these — keep them with the chapter's report rather than re-deriving them.
        let chap = { num: chapNum, title: chapTitle, bookOid: null, startOffset: r.startOffset,
            endOffset: r.endOffset, failed: [], problems: [], persisted: false };
        chapters.push(chap);

        // 1. Create this chapter's book in the series (series-first: no fromBookObjectId) and CAPTURE
        //    its objectId — createFromScenes needs it to persist the chapter's scenes into the shared
        //    series world. A create failure means there is nowhere to persist this chapter, so record
        //    the reason and skip it (validate/report, never a silent no-op).
        let chapterBookOid = null;
        let chapSlug = seriesSlugBase + '-ch' + chapNum;
        let chapOpts = {
            seriesObjectId: pb2SeriesObjectId,
            chapter: chapNum,
            sourceDataObjectId: workObjectId,
            sourceRange: { startOffset: r.startOffset, endOffset: r.endOffset, title: chapTitle }
        };
        try {
            let ch = await createChapter(null, chapSlug, chapTitle, null, null, chapOpts);
            chapterBookOid = ch ? ch.bookObjectId : null;
        } catch (e) {
            // Chapter slugs are deterministic, so a re-run of the same manuscript (e.g. after an earlier
            // run failed mid-extraction) 409s on every chapter. Same rule as the single-book path: reuse
            // the chapter book this series already has for the slug; only fork a suffixed slug when the
            // slug belongs to some other series/user.
            if (!(e && e.message && e.message.includes('409'))) {
                chap.problems.push('Chapter ' + chapNum + ' (' + chapTitle + '): could not create its book — '
                    + (e && e.message || 'unknown error'));
                continue;
            }
            let existing = null;
            try {
                let seriesBooks = pb2SeriesObjectId ? await listSeriesBooks(pb2SeriesObjectId) : [];
                existing = (seriesBooks || []).find(b => b && b.slug === chapSlug) || null;
            } catch (_) {}
            if (existing) {
                chapterBookOid = existing.objectId;
            } else {
                try {
                    let ch = await createChapter(null, chapSlug + '-' + Date.now().toString(36).slice(-4),
                        chapTitle, null, null, chapOpts);
                    chapterBookOid = ch ? ch.bookObjectId : null;
                } catch (e2) {
                    chap.problems.push('Chapter ' + chapNum + ' (' + chapTitle + '): could not create its book — '
                        + (e2 && e2.message || 'unknown error'));
                    continue;
                }
            }
        }
        if (!chapterBookOid) {
            chap.problems.push('Chapter ' + chapNum + ' (' + chapTitle + '): book creation returned no id.');
            continue;
        }
        if (!firstChapterBookOid) firstChapterBookOid = chapterBookOid;
        chap.bookOid = chapterBookOid;

        // 2. Bounded per-chapter extraction — one job at a time (awaited), carrying the chapter's
        //    explicit character span and the series so the cross-chapter roster is seeded.
        //    A thrown error (job submit rejected, poll deadline, network) is THIS chapter's
        //    problem; without the catch it unwound the whole fan-out and left every later chapter
        //    unprocessed with no record of why.
        let job;
        try {
            let started = await startExtractScenes(workObjectId, chatConfigName(), null,
                getPromptTemplate('extractScenes'), {
                    fresh: !!(opts && opts.fresh),
                    seriesObjectId: pb2SeriesObjectId,
                    startOffset: r.startOffset,
                    endOffset: r.endOffset
                });
            extractJobId = started.jobId;
            m.redraw();
            job = await pollJob(extractJobId, { onProgress: onExtractProgress });
        } catch (e) {
            if (e && e.name === 'AbortError') throw e;
            job = { status: 'failed', error: (e && e.message) || 'extraction request failed', result: null };
        } finally {
            extractJobId = null;
        }

        let chapScenes = scenesFromResult(job.result);
        let chapFailed = (job.result && job.result.failedExtractions) || job.failedExtractions || [];
        chap.failed = chapFailed;
        if (chapFailed.length) extractFailedChunks = extractFailedChunks.concat(chapFailed);
        // The server's circuit breaker trips when consecutive LLM calls cannot reach the model server
        // (host down, nothing listening, wrong model name). Every remaining chapter would hit the
        // same wall, so stop here instead of spending hours discovering it 20 more times.
        let infraReason = null;
        for (let f of chapFailed) {
            infraReason = stoppedEarlyReason(f);
            if (infraReason) break;
        }
        if (infraReason) {
            chap.problems.push('Chapter ' + chapNum + ' (' + chapTitle + '): ' + infraReason);
            infraAbort = true;
            break;
        }
        if (job.status === 'failed') {
            chap.problems.push('Chapter ' + chapNum + ' (' + chapTitle + '): extraction failed — '
                + (job.error || 'unknown error'));
        } else {
            let chapPartial = job.status === 'cancelled'
                || (job.result && job.result.extractionComplete === false);
            if (chapPartial) anyPartial = true;
            // 3. Persist THIS chapter's scenes + cast into ITS OWN chapter book. The book's series FK
            //    routes the scenes/cast into the ONE shared series world (no duplicate world minted).
            if (chapScenes.length) {
                try {
                    let meta = await createFromScenes(workObjectId, chatConfigName(), genre || null,
                        chapTitle, chapScenes, buildCharacterStubs(chapScenes), chapterBookOid);
                    persistedCount++;
                    chap.persisted = true;
                    if (meta && meta.failedCharacters && meta.failedCharacters.length) {
                        chap.problems.push('Chapter ' + chapNum + ': ' + meta.failedCharacters.length
                            + ' character(s) failed to create.');
                    } else if (meta && meta.failedExtractions && meta.failedExtractions.length) {
                        chap.problems.push('Chapter ' + chapNum + ': ' + meta.failedExtractions.length
                            + ' character(s) had LLM extraction failures.');
                    }
                } catch (e) {
                    chap.problems.push('Chapter ' + chapNum + ' (' + chapTitle + '): scenes extracted but '
                        + 'could not be saved — ' + (e && e.message || 'unknown error'));
                }
            } else if (job.status !== 'cancelled') {
                chap.noScenesProblem = 'Chapter ' + chapNum + ' (' + chapTitle + '): no scenes were extracted.';
                chap.problems.push(chap.noScenesProblem);
            }
            // A chapter that stopped before its last passage is INCOMPLETE even when its book was
            // saved — it still has to appear in the report, or "5 of 6 chapters saved" hides that
            // chapter 5 is missing a third of its scenes. (Passages the model could not read are
            // reported one line each by summaryProblems, from chap.failed.)
            if (chapPartial && job.status !== 'cancelled') {
                let done = job.result && job.result.chunksProcessed != null ? job.result.chunksProcessed : null;
                let total = job.total != null ? job.total : null;
                chap.problems.push('Chapter ' + chapNum + ' (' + chapTitle + '): extraction stopped early'
                    + (done != null && total != null ? ' after ' + done + ' of ' + total + ' passages' : '')
                    + ' — the ' + chapScenes.length + ' scene(s) read so far were saved; re-run to resume.');
            }
        }
        // A user Cancel targets the current chapter's job. When it comes back cancelled, stop the
        // fan-out at this boundary — the chapters already persisted stay in the series.
        if (job.status === 'cancelled') { cancelled = true; break; }
    }

    problems = summaryProblems(chapters);
    extractPartial = anyPartial || cancelled || infraAbort;

    if (infraAbort) {
        // Stay in the wizard and say exactly what stopped the run. Chapters already saved are in
        // the series list; re-running the same manuscript resumes from their checkpoints.
        extractError = 'Extraction stopped at chapter ' + extractChapterIndex + '/' + ranges.length
            + (persistedCount ? ' (' + persistedCount + ' chapter(s) were saved). ' : '. ')
            + problems.join(' | ');
        return;
    }

    if (firstChapterBookOid && persistedCount > 0) {
        if (problems.length || anyPartial || cancelled) {
            // Chapters are missing or incomplete. Stay in the wizard with a persistent report — the
            // previous behavior (a transient toast, then close + navigate to the series canvas) is
            // how a nine-hour run lost half its chapters with "no error" anywhere the user could see.
            extractChapterSummary = {
                saved: persistedCount,
                total: ranges.length,
                problems: problems.slice(),
                chapters: chapters,
                seriesObjectId: pb2SeriesObjectId,
                seriesBookOid: firstChapterBookOid,
                cancelled: cancelled
            };
            return;
        }
        // Clean run: chaptered novels finish on the SERIES CANVAS (N4), NOT a Step-2 aggregate
        // review. The series view is a mode inside the per-book workflow route, loaded from the
        // book's series FK — so land on the first chapter book's workflow route.
        Dialog.close();
        m.route.set('/picture-book/' + firstChapterBookOid + '/workflow');
    } else if (firstChapterBookOid && chapters.some(function (c) { return c.bookOid && Object.keys(retryablePassages(c.failed)).length; })) {
        // Every chapter's book exists in the series but none received a scene, and the passages
        // failed in a way another model may read. Keep the per-chapter report: a plain re-run is
        // served from the kept checkpoints without calling the model, so the retry button is the
        // only way forward short of "Start over".
        extractChapterSummary = {
            saved: 0,
            total: ranges.length,
            problems: problems.slice(),
            chapters: chapters,
            seriesObjectId: pb2SeriesObjectId,
            seriesBookOid: firstChapterBookOid,
            cancelled: cancelled
        };
    } else {
        // Nothing persisted — keep the wizard on step 1 and say why.
        extractError = problems.length
            ? ('No chapters were saved. ' + problems.join(' | '))
            : (cancelled ? 'Extraction was cancelled before any chapter was saved.'
                         : 'No scenes were extracted from any chapter.');
    }
}

/**
 * A failedExtractions entry is a JSON string (or object) the server wrote per failed passage. When
 * the extraction loop's circuit breaker stops a run it marks the entry with `stoppedEarly: true` —
 * that flag, not the wording, is what the client keys on. The 'failed immediately' text match is
 * kept for entries written by a server that predates the flag.
 *
 * @returns {string|null} the human-readable reason when this entry is a breaker stop, else null.
 */
function stoppedEarlyReason(entry) {
    if (entry == null) return null;
    let raw = typeof entry === 'string' ? entry : JSON.stringify(entry);
    let parsed = null;
    if (typeof entry === 'string') {
        try { parsed = JSON.parse(entry); } catch (_) { parsed = null; }
    } else {
        parsed = entry;
    }
    if (parsed && typeof parsed === 'object') {
        if (parsed.stoppedEarly === true) return parsed.error || raw;
        if (typeof parsed.error === 'string' && parsed.error.includes('failed immediately')) return parsed.error;
        return null;
    }
    return raw.includes('failed immediately') ? raw : null;
}

/** The server's `error` text from a failedExtractions entry, or the raw entry when it has none. */
function failureReason(entry) {
    if (entry == null) return '';
    let parsed = entry;
    if (typeof entry === 'string') {
        try { parsed = JSON.parse(entry); } catch (_) { return entry; }
    }
    if (parsed && typeof parsed === 'object' && typeof parsed.error === 'string') return parsed.error;
    return typeof entry === 'string' ? entry : JSON.stringify(entry);
}

// Plain-language reading of the server's failure `kind`. The server's `error` prose is free to
// change; `kind` is the contract (PictureBookUtil KIND_*), so this is keyed on it.
const FAILURE_KIND_TEXT = {
    'refusal': 'the model declined to process this passage (content policy) — retry it with a different model',
    'stalled': 'the model\'s reply stopped mid-stream and nothing usable arrived',
    'truncated': 'the model\'s reply was cut off mid-stream and the partial text could not be read',
    'timeout': 'the model did not finish within the request timeout',
    'unreachable': 'the model server could not be reached',
    'error': 'the model server returned an error',
    'empty': 'the model returned an empty reply',
    'no-json': 'the model\'s reply contained no scene list (no JSON)',
    'parse': 'the model\'s reply contained a scene list that could not be read, even after repair',
    'stopped-early': 'the run stopped before this passage was attempted'
};

/**
 * Explain one failedExtractions entry to a person.
 *
 * @returns {{chunk:number,total:number,kind:string|null,reason:string,detail:string,raw:string,retryable:boolean}}
 *   chunk/total are the 1-based passage position (0 when unknown); reason is the plain-language
 *   explanation; detail is the server's error text; raw is what the model actually replied (the
 *   refusal wording, the truncated JSON, ...); retryable mirrors the server's rule — a passage that
 *   was attempted and failed can be re-run alone, a breaker stop is resumed by a plain re-run.
 */
function describeFailedPassage(entry) {
    let p = entry;
    if (typeof entry === 'string') {
        try { p = JSON.parse(entry); } catch (_) { p = null; }
    }
    if (!p || typeof p !== 'object') {
        return { chunk: 0, total: 0, kind: null, reason: entry == null ? '' : String(entry), detail: '', raw: '', retryable: false };
    }
    let chunk = Number(p.chunk) || 0;
    let total = Number(p.total) || 0;
    if (!chunk && typeof p.context === 'string') {
        let mt = /^extract-scenes-chunk:(\d+)\/(\d+)$/.exec(p.context);
        if (mt) { chunk = Number(mt[1]); total = Number(mt[2]); }
    }
    let stopped = p.stoppedEarly === true || p.kind === 'stopped-early';
    let kind = p.kind || (stopped ? 'stopped-early' : null);
    let detail = typeof p.error === 'string' ? p.error : '';
    let reason = (kind && FAILURE_KIND_TEXT[kind]) || detail || 'the reply could not be read';
    let raw = typeof p.rawResponse === 'string' ? p.rawResponse.trim() : '';
    return { chunk, total, kind, reason, detail, raw, retryable: chunk > 0 && !stopped };
}

function passageLabel(d) {
    return d.chunk ? 'Passage ' + d.chunk + (d.total ? ' of ' + d.total : '') : 'A passage';
}

/** One sentence per failed passage: "Passage 4 of 9 — the model declined ... (server text)". */
function passageProblemText(d) {
    let text = passageLabel(d) + ' — ' + d.reason;
    if (d.detail && d.detail !== d.reason) text += ' (' + d.detail.slice(0, 160) + ')';
    return text + '.';
}

/** Distinct 1-based passage numbers that a retry would re-run, as a lookup. */
function retryablePassages(failed) {
    let out = {};
    (failed || []).forEach(function (f) {
        let d = describeFailedPassage(f);
        if (d.retryable) out[d.chunk] = true;
    });
    return out;
}

/**
 * Fold the scenes a retry recovered into the list the user is reviewing, keeping that list.
 *
 * The server returns the whole merged list, but Step 2 is an editing surface — titles get
 * changed, scenes removed or reordered — so replacing the list would discard that work to add a
 * few scenes. Only scenes whose provenance (`sourceChunk`, 0-based) is one of the passages that
 * were re-run are new; each is inserted before the first existing scene from a later passage, so
 * it lands among its neighbours. Scenes with no provenance are left where they are.
 */
function mergeRecoveredScenes(current, merged, retriedPassages) {
    let out = current.slice();
    (merged || []).forEach(function (s) {
        if (!s || typeof s.sourceChunk !== 'number' || !retriedPassages[s.sourceChunk + 1]) return;
        let at = out.findIndex(function (x) {
            return x && typeof x.sourceChunk === 'number' && x.sourceChunk > s.sourceChunk;
        });
        out.splice(at < 0 ? out.length : at, 0, s);
    });
    return out;
}

/** Apply a terminal failed-passage retry job to the Step 2 list. */
function applyRetryJob(job, retriedPassages) {
    if (job.status === 'failed') {
        extractError = job.error || 'Retrying the failed passages failed';
        return;
    }
    let result = job.result || {};
    extractedScenes = mergeRecoveredScenes(extractedScenes, scenesFromResult(result), retriedPassages);
    extractFailedChunks = result.failedExtractions || job.failedExtractions || [];
    if (result.extractionComplete === false) extractPartial = true;
    // A run in which EVERY passage failed never left Step 1; once the retry recovers scenes there
    // is a list to review, so advance exactly as a successful Extract would.
    if (step === 1 && extractedScenes.length) step = 2;
}

/**
 * Re-run ONLY the passages the last extraction could not read, with the currently selected chat
 * config, and fold any recovered scenes into the Step 2 list. The server kept the checkpoint for
 * exactly these passages, so a refusal or a stalled stream costs one passage to recover, not the
 * whole document.
 */
async function doRetryFailed() {
    if (extracting || !workObjectId) return;
    let targets = retryablePassages(extractFailedChunks);
    if (!Object.keys(targets).length) return;
    extracting = true;
    retryingFailed = true;
    extractError = null;
    extractProgress = null;
    extractCancelling = false;
    m.redraw();
    let bgToken = LLMConnector.lockBgActivity();
    try {
        let started = await startRetryFailedChunks(workObjectId, chatConfigName(),
            { seriesObjectId: pb2SeriesObjectId });
        extractJobId = started.jobId;
        m.redraw();
        let job = await pollJob(extractJobId, { onProgress: onExtractProgress });
        applyRetryJob(job, targets);
    } catch (e) {
        if (!(e && e.name === 'AbortError')) extractError = e.message || 'Retrying the failed passages failed';
    } finally {
        LLMConnector.unlockBgActivity(bgToken);
        LLMConnector.setBgActivity(null, null);
        extracting = false;
        retryingFailed = false;
        extractCancelling = false;
        extractJobId = null;
        extractProgress = null;
        m.redraw();
        loadPendingCheckpoints();
    }
}

/**
 * Chaptered counterpart of doRetryFailed: for every chapter whose extraction left retryable
 * passages, re-run just those passages against that chapter's checkpoint (same offsets its
 * extraction used — the checkpoint is keyed on them) and re-persist the merged scene list into the
 * chapter's book. createFromScenes finds-or-updates scene notes by name, so re-persisting the
 * scenes already there is idempotent and keeps their render state.
 */
async function doRetryFailedChapters() {
    let s = extractChapterSummary;
    if (!s || extracting || !workObjectId) return;
    let targets = (s.chapters || []).filter(function (c) { return c.bookOid && Object.keys(retryablePassages(c.failed)).length; });
    if (!targets.length) return;
    extracting = true;
    retryingFailed = true;
    extractError = null;
    extractProgress = null;
    extractCancelling = false;
    extractChapterCount = targets.length;
    m.redraw();
    let bgToken = LLMConnector.lockBgActivity();
    try {
        for (let i = 0; i < targets.length; i++) {
            let c = targets[i];
            extractChapterIndex = i + 1;
            m.redraw();
            let job;
            try {
                let started = await startRetryFailedChunks(workObjectId, chatConfigName(), {
                    seriesObjectId: s.seriesObjectId,
                    startOffset: c.startOffset,
                    endOffset: c.endOffset
                });
                extractJobId = started.jobId;
                m.redraw();
                job = await pollJob(extractJobId, { onProgress: onExtractProgress });
            } catch (e) {
                if (e && e.name === 'AbortError') throw e;
                job = { status: 'failed', error: (e && e.message) || 'retry request failed', result: null };
            } finally {
                extractJobId = null;
            }
            if (job.status === 'failed') {
                c.problems.push('Chapter ' + c.num + ' (' + c.title + '): retrying its failed passages failed — '
                    + (job.error || 'unknown error'));
                continue;
            }
            let result = job.result || {};
            let merged = scenesFromResult(result);
            c.failed = result.failedExtractions || job.failedExtractions || [];
            if (merged.length) {
                try {
                    await createFromScenes(workObjectId, chatConfigName(), genre || null,
                        c.title, merged, buildCharacterStubs(merged), c.bookOid);
                    if (!c.persisted) { c.persisted = true; s.saved++; }
                    if (c.noScenesProblem) {
                        c.problems = c.problems.filter(function (p) { return p !== c.noScenesProblem; });
                        c.noScenesProblem = null;
                    }
                } catch (e) {
                    c.problems.push('Chapter ' + c.num + ' (' + c.title + '): passages recovered but the scenes '
                        + 'could not be saved — ' + (e && e.message || 'unknown error'));
                }
            }
            if (job.status === 'cancelled') break;
        }
        s.problems = summaryProblems(s.chapters);
        extractFailedChunks = [].concat.apply([], s.chapters.map(function (c) { return c.failed; }));
    } catch (e) {
        if (!(e && e.name === 'AbortError')) extractError = e.message || 'Retrying the failed passages failed';
    } finally {
        LLMConnector.unlockBgActivity(bgToken);
        LLMConnector.setBgActivity(null, null);
        extracting = false;
        retryingFailed = false;
        extractCancelling = false;
        extractJobId = null;
        extractProgress = null;
        extractChapterIndex = 0;
        extractChapterCount = 0;
        m.redraw();
    }
}

/**
 * Flatten the per-chapter report into the display list: a chapter's own problems, then one line per
 * passage the model could not read. Breaker stops are skipped here — the chapter already carries
 * the breaker's own line.
 */
function summaryProblems(chapters) {
    let out = [];
    (chapters || []).forEach(function (c) {
        out = out.concat(c.problems);
        (c.failed || []).forEach(function (f) {
            if (stoppedEarlyReason(f)) return;
            out.push('Chapter ' + c.num + ' (' + c.title + '): ' + passageProblemText(describeFailedPassage(f)));
        });
    });
    return out;
}

/**
 * Cancel the running extraction.
 *
 * There was no way to do this before: the wizard's only "Cancel" was Dialog.close(), which left
 * the fetch running and `extracting` stuck true. Cancellation is cooperative, so the loop stops at
 * the next chunk boundary and still returns what it extracted — the poller then applies that
 * partial list.
 */
async function cancelExtract() {
    if (!extractJobId || extractCancelling) return;
    extractCancelling = true;
    m.redraw();
    try {
        await cancelJob(extractJobId);
    } catch (e) {
        console.error('[pictureBook] cancel failed:', e);
        extractCancelling = false;
        m.redraw();
    }
}

/**
 * Reattach to an extraction already running for this document.
 *
 * Reopening the wizard (or reloading the page) used to start a SECOND run against the same
 * document while the first carried on invisibly — two jobs competing for one GPU-backed LLM. The
 * job list is ownership-scoped server-side, so this only ever sees the caller's own jobs.
 *
 * @returns {Promise<boolean>} true when a run was found and is now being polled
 */
async function reattachExtractJob() {
    if (extracting || reattaching || !workObjectId) return false;
    // Claim the attempt SYNCHRONOUSLY, before the listJobs() await. The `extracting` guard alone
    // is evaluated before that round-trip, so a user who hits Extract while the listing is in
    // flight could end up with two pollers on one job — and the first to finish nulls
    // extractJobId, leaving Cancel a no-op.
    reattaching = true;
    try {
        let jobs = await listJobs();
        let mine = jobs.find(function (j) {
            return j.kind === 'pb.extractScenes' && j.key === workObjectId && !j.terminal;
        });
        if (!mine) {
            reattaching = false;
            return false;
        }
        extracting = true;
        reattaching = false;
        extractJobId = mine.jobId;
        extractError = null;
        onExtractProgress(mine);
        let bgToken = LLMConnector.lockBgActivity();
        try {
            let job = await pollJob(extractJobId, { onProgress: onExtractProgress });
            applyExtractJob(job);
        } finally {
            LLMConnector.unlockBgActivity(bgToken);
            LLMConnector.setBgActivity(null, null);
            extracting = false;
            extractCancelling = false;
            extractJobId = null;
            extractProgress = null;
            m.redraw();
        }
        return true;
    } catch (e) {
        console.error('[pictureBook] reattach failed:', e);
        extracting = false;
        extractJobId = null;
        m.redraw();
        return false;
    } finally {
        reattaching = false;
    }
}

function addManualScene() {
    extractedScenes.push({
        index: extractedScenes.length,
        title: 'New Scene',
        blurb: '',
        setting: '',
        action: '',
        mood: '',
        characters: [],
        diffusionPrompt: '',
        userEdited: true
    });
    m.redraw();
}

function removeScene(idx) {
    extractedScenes.splice(idx, 1);
    extractedScenes.forEach(function (s, i) { s.index = i; });
    m.redraw();
}

function moveScene(idx, dir) {
    let newIdx = idx + dir;
    if (newIdx < 0 || newIdx >= extractedScenes.length) return;
    let tmp = extractedScenes[idx];
    extractedScenes[idx] = extractedScenes[newIdx];
    extractedScenes[newIdx] = tmp;
    extractedScenes.forEach(function (s, i) { s.index = i; });
    m.redraw();
}

/**
 * U3 — regenerate a scene's blurb via the LLM (POST /scene/{id}/blurb) and write the result back
 * into the Step 2 editor. Only callable for scenes that already exist server-side (a persisted
 * data.note objectId): raw auto-extracted scenes aren't persisted until Step 2→3's
 * createFromScenes, so `oid` is resolved by the caller from the scene itself or its persisted
 * mirror in `scenes`.
 */
async function doRegenerateBlurb(idx, oid) {
    if (!oid) return;
    blurbRegenerating[idx] = true;
    m.redraw();
    try {
        let result = await regenerateBlurb(oid, chatConfigName());
        let blurb = result && result.blurb ? result.blurb : '';
        if (blurb) {
            extractedScenes[idx].blurb = blurb;
            extractedScenes[idx].summary = blurb;
            extractedScenes[idx].userEdited = true;
            // Keep the persisted-scene mirror (read by this control after a Back) in sync when present.
            if (scenes[idx]) { scenes[idx].blurb = blurb; scenes[idx].summary = blurb; }
        } else {
            page.toast('error', 'Blurb regeneration returned no text');
        }
    } catch (e) {
        page.toast('error', 'Blurb regeneration failed: ' + (e.message || ''));
    }
    blurbRegenerating[idx] = false;
    m.redraw();
}

// ── Render functions ──────────────────────────────────────────────────

/**
 * Live extraction progress with a Cancel.
 *
 * Replaces a bare "Extracting scenes..." spinner. A chunked run over a real document is minutes
 * long and the server has always known exactly where it is (the job's current/total), so showing
 * an indeterminate spinner was throwing away information the user needed — and there was no way
 * to stop a run at all: the wizard's only Cancel called Dialog.close(), which abandoned the fetch
 * and left `extracting` stuck true.
 */
// Step 1 status blocks render below the Prompt Templates box, past the fold of the scrollable dialog body.
function revealOnCreate(vnode) {
    if (vnode.dom && typeof vnode.dom.scrollIntoView === 'function') {
        vnode.dom.scrollIntoView({ block: 'nearest' });
    }
}

function renderExtractProgress() {
    let cur = extractProgress ? extractProgress.current : 0;
    let total = extractProgress ? extractProgress.total : 0;
    let pct = total > 0 ? Math.round((cur / total) * 100) : 0;
    return m('div', { class: 'px-4 py-3 text-sm bg-blue-50 dark:bg-blue-900/20 rounded space-y-2', oncreate: revealOnCreate }, [
        m('div', { class: 'flex items-center gap-2 text-blue-600 dark:text-blue-400' }, [
            m('span', { class: 'material-symbols-outlined text-base animate-spin' }, 'progress_activity'),
            m('span', { class: 'flex-1' },
                extractCancelling ? 'Cancelling — finishing the current chunk...'
                    : retryingFailed
                        ? (extractChapterCount > 1 ? 'Retrying failed passages — chapter ' + extractChapterIndex + ' of ' + extractChapterCount
                            : 'Retrying failed passages' + (total > 0 ? ' — ' + cur + ' of ' + total : '...'))
                    : (total > 0 ? 'Extracting scenes — chunk ' + cur + ' of ' + total
                        : 'Extracting scenes...')),
            extractJobId ? m('button', {
                class: 'btn text-xs',
                disabled: extractCancelling,
                onclick: function () { cancelExtract(); }
            }, [
                m('span', { class: 'material-symbols-outlined text-xs mr-1' }, 'stop'),
                extractCancelling ? 'Cancelling...' : 'Cancel'
            ]) : null
        ]),
        // Only render a determinate bar once the server has told us how many chunks there are;
        // faking 0/0 as 0% would imply progress information we do not have yet.
        total > 0 ? m('div', { class: 'h-1.5 w-full bg-blue-100 dark:bg-blue-900/40 rounded overflow-hidden' }, [
            m('div', { class: 'h-full bg-blue-500 transition-all', style: { width: pct + '%' } })
        ]) : null,
        total > 0 ? m('div', { class: 'text-xs text-gray-500 dark:text-gray-400' },
            'Extraction continues on the server if you close this dialog — reopen it to reattach.')
            : null
    ]);
}

/**
 * Banners for a scene list that is NOT a complete extraction.
 *
 * Both conditions were previously invisible: a cancelled run's partial list looked identical to a
 * finished one, and per-chunk parse failures were dropped by the service before they reached the
 * client at all. Proceeding to build a book from a partial list is legitimate — the user just has
 * to know that is what they are doing.
 */
/** Refresh the Step 1 checkpoint banner for the open document. Never throws; a failed read hides the banner. */
async function loadPendingCheckpoints() {
    let wid = workObjectId;
    if (!wid) return;
    let rows = [];
    try {
        rows = await getExtractCheckpoints(wid);
    } catch (e) {
        console.warn('[pictureBook] checkpoint lookup failed:', e && e.message);
    }
    // The dialog may have moved on to another document while this was in flight.
    if (wid !== workObjectId) return;
    pendingCheckpoints = Array.isArray(rows) ? rows : [];
    m.redraw();
}

/** One-line description of a checkpoint row for the banner and the list. */
function describeCheckpointRow(row) {
    let parts = [];
    let processed = Number(row.chunksProcessed) || 0;
    let total = Number(row.totalChunks) || 0;
    if (row.startOffset != null && row.endOffset != null) {
        parts.push('chars ' + row.startOffset + '–' + row.endOffset);
    }
    parts.push(total ? processed + ' of ' + total + ' passage' + (total === 1 ? '' : 's') : processed + ' passage' + (processed === 1 ? '' : 's'));
    let sc = Number(row.sceneCount) || 0;
    parts.push(sc + ' scene' + (sc === 1 ? '' : 's') + ' saved');
    let fc = Number(row.failedCount) || 0;
    if (fc) parts.push(fc + ' failed passage' + (fc === 1 ? '' : 's'));
    return parts.join(' · ');
}

/**
 * Remove a checkpoint without starting an extraction. This is the ONLY discard path that does not
 * cost an LLM run: "Start over" (?fresh=true) also discards, but then re-extracts the document.
 */
async function discardPendingCheckpoint(row) {
    if (!workObjectId || checkpointDiscarding || extracting) return;
    let ok = await Dialog.confirm({
        title: 'Discard unfinished extraction',
        message: 'Discard the saved progress for "' + workName + '" (' + describeCheckpointRow(row) + ')?'
            + ' The scenes extracted so far will be lost and the next extraction starts from the beginning.',
        confirmLabel: 'Discard', confirmIcon: 'delete', destructive: true
    });
    if (!ok) return;
    checkpointDiscarding = true;
    m.redraw();
    try {
        let deleted = await discardExtractCheckpoints(workObjectId, { startOffset: row.startOffset, endOffset: row.endOffset });
        page.toast(deleted ? 'success' : 'info', deleted ? 'Unfinished extraction discarded' : 'Already removed');
    } catch (e) {
        page.toast('error', (e && e.message) || 'Failed to discard the unfinished extraction');
    } finally {
        checkpointDiscarding = false;
        await loadPendingCheckpoints();
    }
}

/**
 * Step 1 banner: the server holds progress for this document from a run that never reached
 * createFromScenes. Resume continues from the saved passage (a complete-with-failures checkpoint
 * resumes instantly — no LLM calls — straight into the Step 2 review with its failed-passage banner);
 * Discard removes the checkpoint outright. Hidden while a run is in flight, since that run is the resume.
 */
function renderCheckpointBanner() {
    if (extracting || !pendingCheckpoints.length) return null;
    return m('div', {
        'data-pb-checkpoint-banner': '1',
        class: 'p-2 rounded bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-300 dark:border-yellow-700 text-xs text-yellow-800 dark:text-yellow-200 space-y-2'
    }, [
        m('div', { class: 'flex items-center gap-2 font-medium' }, [
            m('span', { class: 'material-symbols-outlined text-sm' }, 'history'),
            m('span', pendingCheckpoints.length === 1
                ? 'An earlier extraction of this document was interrupted before a book was created. Its progress is saved.'
                : pendingCheckpoints.length + ' earlier extractions of this document were interrupted before a book was created. Their progress is saved.')
        ]),
        m('ul', { class: 'space-y-1' }, pendingCheckpoints.map(function (row, i) {
            let complete = !!row.complete;
            return m('li', { key: row.noteObjectId || i, 'data-pb-checkpoint-row': '1', class: 'flex items-center gap-2' }, [
                m('span', { class: 'flex-1' }, describeCheckpointRow(row)),
                m('button', {
                    class: 'btn text-xs btn-primary',
                    'data-pb-checkpoint-resume': '1',
                    disabled: extracting || checkpointDiscarding,
                    title: complete
                        ? 'Every passage was processed; open the saved scenes for review (no extraction runs)'
                        : 'Continue extracting from the saved passage',
                    onclick: function () { doExtract(); }
                }, [m('span', { class: 'material-symbols-outlined text-xs mr-1' }, 'resume'),
                    complete ? 'Review saved scenes' : 'Resume']),
                m('button', {
                    class: 'btn text-xs',
                    'data-pb-checkpoint-discard': '1',
                    disabled: extracting || checkpointDiscarding,
                    title: 'Delete the saved progress without extracting anything',
                    onclick: function () { discardPendingCheckpoint(row); }
                }, [m('span', { class: 'material-symbols-outlined text-xs mr-1' }, 'delete'), 'Discard'])
            ]);
        }))
    ]);
}

function renderExtractWarnings() {
    let out = [];
    if (extractPartial) {
        out.push(m('div', { class: 'p-2 rounded bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-300 dark:border-yellow-700 text-xs text-yellow-800 dark:text-yellow-200 flex items-center gap-2' }, [
            m('span', { class: 'material-symbols-outlined text-sm' }, 'warning'),
            m('span', { class: 'flex-1' },
                'This scene list is incomplete — the extraction stopped before the end of the'
                + ' document, usually because the model server stopped responding. The '
                + extractedScenes.length + ' scene(s) below are saved. Resume to carry on from'
                + ' where it stopped.'),
            // RESUME is the primary action and must come first. The server keeps a checkpoint for
            // exactly this case, so resuming costs only the remaining chunks — whereas the
            // previous version of this banner offered ONLY "Start over", which discards every
            // scene already extracted and re-pays for the chunks that already succeeded. On a
            // 17-chunk document that is most of an hour thrown away to recover from a blip.
            m('button', {
                class: 'btn text-xs btn-primary',
                disabled: extracting,
                onclick: function () { doExtract(); }
            }, [m('span', { class: 'material-symbols-outlined text-xs mr-1' }, 'resume'), 'Resume']),
            m('button', {
                class: 'btn text-xs',
                disabled: extracting,
                title: 'Discard the scenes above and re-extract the whole document from the start',
                onclick: function () { doExtract({ fresh: true }); }
            }, 'Start over')
        ]));
    }
    if (extractFailedChunks && extractFailedChunks.length) {
        out.push(renderFailedPassages());
    }
    if (step === 2 && extractError) {
        out.push(m('div', { class: 'text-red-500 text-xs', 'data-pb-extract-error': '1' }, extractError));
    }
    return out.length ? m('div', { class: 'space-y-2' }, out) : null;
}

/**
 * The passages the model could not read, one line each with WHY — and the way to recover them.
 *
 * The previous banner said only "N passages could not be read", which left the user guessing
 * between a context-window problem, a too-strict prompt, a stalled stream and a content-policy
 * refusal (all of which happen, and each has a different remedy). The server now records a typed
 * `kind` plus the model's own reply per passage; this shows them, lets the user switch the chat
 * config, and re-runs ONLY the failed passages against the server's checkpoint — the whole
 * document no longer has to be resubmitted to recover one refused passage.
 */
function renderFailedPassages() {
    let described = extractFailedChunks.map(describeFailedPassage);
    let retryCount = Object.keys(retryablePassages(extractFailedChunks)).length;
    let anyChaptered = !!extractChapterSummary;
    return m('div', {
        'data-pb-failed-passages': '1',
        class: 'p-2 rounded bg-orange-50 dark:bg-orange-900/20 border border-orange-300 dark:border-orange-700 text-xs text-orange-800 dark:text-orange-200 space-y-2'
    }, [
        m('div', { class: 'flex items-center gap-2 font-medium' }, [
            m('span', { class: 'material-symbols-outlined text-sm' }, 'error_outline'),
            m('span', described.length + ' passage' + (described.length === 1 ? '' : 's')
                + ' could not be read by the model, so any scenes in '
                + (described.length === 1 ? 'it' : 'them') + ' are missing.')
        ]),
        m('ul', { class: 'list-disc pl-5 space-y-1 max-h-40 overflow-y-auto' },
            described.map(function (d, i) {
                return m('li', { key: i, 'data-pb-failed-passage': '1', 'data-pb-failed-kind': d.kind || '' }, [
                    m('span', { class: 'font-medium' }, passageLabel(d) + ' — '),
                    m('span', d.reason + '.'),
                    d.detail && d.detail !== d.reason
                        ? m('div', { class: 'text-orange-700 dark:text-orange-300' }, d.detail)
                        : null,
                    d.raw ? m('div', {
                        class: 'font-mono text-[11px] text-gray-600 dark:text-gray-400 truncate',
                        title: d.raw.slice(0, 2000)
                    }, 'Model replied: ' + d.raw.slice(0, 200)) : null
                ]);
            })),
        anyChaptered ? null : m('div', { class: 'flex flex-wrap items-center gap-2 pt-1' }, [
            m('button', {
                class: 'btn text-xs btn-primary',
                'data-pb-retry-failed': '1',
                disabled: extracting || !retryCount,
                title: retryCount
                    ? 'Re-run only these passages with the chat config shown; the scenes already listed are kept'
                    : 'These passages were never attempted — use Resume to continue the run',
                onclick: function () { doRetryFailed(); }
            }, [
                m('span', { class: 'material-symbols-outlined text-xs mr-1' }, 'replay'),
                extracting && retryingFailed ? 'Retrying...' : 'Retry ' + retryCount + ' failed passage' + (retryCount === 1 ? '' : 's')
            ]),
            m('span', { class: 'text-gray-600 dark:text-gray-300' }, 'using'),
            m('button', {
                class: 'btn text-xs',
                'data-pb-retry-chatconfig': '1',
                disabled: extracting,
                title: 'Change the chat config (model) the retry will use — a refused passage usually needs a different model',
                onclick: openChatConfigPicker
            }, [
                chatConfigRef ? chatConfigRef.name : '(default chat config)',
                m('span', { class: 'material-symbols-outlined text-xs ml-1' }, 'search')
            ])
        ])
    ]);
}

/**
 * Persistent end-of-fan-out report for a chaptered manuscript whose run did not finish cleanly.
 * Stays on screen until the user acts on it: open the series as it is, or re-run to resume.
 */
/**
 * The report's state, computed from the summary as it is NOW — a retry that recovers every failed
 * passage mutates `saved`/`problems` in place, and the headline must follow. Pure; exported for tests.
 * @returns {{clean: boolean, headline: string}}
 */
function chapterSummaryState(s) {
    let missing = Math.max(0, s.total - s.saved);
    let problems = (s.problems || []).length;
    let clean = !s.cancelled && !missing && !problems;
    let headline = (s.cancelled
        ? 'Extraction was cancelled: '
        : (clean ? 'Extraction finished: ' : 'Extraction finished with problems: '))
        + s.saved + ' of ' + s.total + ' chapters saved'
        + (missing ? ', ' + missing + ' missing' : '')
        + (problems ? ', ' + problems + ' issue' + (problems === 1 ? '' : 's') : '')
        + '.';
    return { clean: clean, headline: headline };
}

function renderChapterSummary() {
    let s = extractChapterSummary;
    if (!s) return null;
    let retryable = (s.chapters || []).reduce(function (n, c) {
        return n + (c.bookOid ? Object.keys(retryablePassages(c.failed)).length : 0);
    }, 0);
    let state = chapterSummaryState(s);
    return m('div', {
        'data-pb-chapter-summary': '1',
        'data-pb-chapter-summary-clean': state.clean ? 'true' : 'false',
        class: 'p-3 rounded border text-xs space-y-2 ' + (state.clean
            ? 'bg-green-50 dark:bg-green-900/20 border-green-300 dark:border-green-700 text-green-900 dark:text-green-100'
            : 'bg-orange-50 dark:bg-orange-900/20 border-orange-300 dark:border-orange-700 text-orange-900 dark:text-orange-100'),
        oncreate: revealOnCreate
    }, [
        m('div', { class: 'flex items-center gap-2 font-medium' }, [
            m('span', { class: 'material-symbols-outlined text-sm' }, state.clean ? 'check_circle' : 'report'),
            m('span', { 'data-pb-chapter-summary-headline': '1' }, state.headline)
        ]),
        s.problems.length
            ? m('ul', { class: 'list-disc pl-5 space-y-1 max-h-48 overflow-y-auto' },
                s.problems.map((p, i) => m('li', { key: i, 'data-pb-chapter-problem': '1' }, p)))
            : null,
        m('div', { class: 'flex flex-wrap items-center gap-2 pt-1' }, [
            m('button', {
                class: 'btn text-xs btn-primary',
                'data-pb-open-series': '1',
                disabled: extracting,
                onclick: function () {
                    let oid = s.seriesBookOid;
                    Dialog.close();
                    m.route.set('/picture-book/' + oid + '/workflow');
                }
            }, 'Open series'),
            m('button', {
                class: 'btn text-xs',
                'data-pb-rerun-resume': '1',
                disabled: extracting,
                onclick: function () { doExtract(); }
            }, 'Re-run to resume'),
            retryable ? m('button', {
                class: 'btn text-xs',
                'data-pb-retry-failed-chapters': '1',
                disabled: extracting,
                title: 'Re-run only the ' + retryable + ' passage(s) the model could not read, with the chat config selected above, and add any recovered scenes to their chapter books',
                onclick: function () { doRetryFailedChapters(); }
            }, [
                m('span', { class: 'material-symbols-outlined text-xs mr-1' }, 'replay'),
                'Retry ' + retryable + ' failed passage' + (retryable === 1 ? '' : 's')
            ]) : null,
            m('span', { class: 'text-gray-600 dark:text-gray-300' },
                'Re-running reuses the chapter books already in the series; an incomplete chapter resumes from its checkpoint.'
                + (retryable ? ' Retrying re-reads only the failed passages — change the chat config above first if the model declined them.' : ''))
        ])
    ]);
}

function renderStep1() {
    return m('div', { class: 'p-4 space-y-4' }, [
        m('div', { class: 'text-sm text-gray-600 dark:text-gray-400 mb-2' }, 'Source: ' + workName),

        renderCheckpointBanner(),

        // Picture book name
        m('div', [
            m('label', { class: 'field-label' }, 'Picture Book Name'),
            m('input', {
                class: 'text-field-full text-sm',
                placeholder: workName || 'My Picture Book',
                value: bookName,
                oninput: function (e) { bookName = e.target.value; }
            })
        ]),

        // Method toggle
        m('div', { class: 'flex gap-4 mb-3' }, [
            m('label', { class: 'flex items-center gap-2 cursor-pointer' }, [
                m('input', {
                    type: 'radio', name: 'method', value: 'auto',
                    checked: method === 'auto',
                    onchange: function () { method = 'auto'; }
                }),
                m('span', { class: 'text-sm' }, 'Auto-extract from text')
            ]),
            m('label', { class: 'flex items-center gap-2 cursor-pointer' }, [
                m('input', {
                    type: 'radio', name: 'method', value: 'manual',
                    checked: method === 'manual',
                    onchange: function () { method = 'manual'; }
                }),
                m('span', { class: 'text-sm' }, 'Enter scenes manually')
            ])
        ]),

        method === 'auto' ? m('div', { class: 'space-y-3' }, [
            m('div', [
                m('label', { class: 'field-label' }, 'Chat Config'),
                m('div', {
                    class: 'text-field-full text-sm cursor-pointer flex items-center justify-between',
                    onclick: openChatConfigPicker
                }, [
                    m('span', { class: chatConfigRef ? '' : 'text-gray-400' }, chatConfigLabel()),
                    m('span', { class: 'material-symbols-outlined text-gray-400 text-sm' }, 'search')
                ])
            ]),
            m('div', [
                m('label', { class: 'field-label' }, 'Genre Hint'),
                m('select', {
                    class: 'text-field-compact',
                    value: genre,
                    onchange: function (e) { genre = e.target.value; }
                }, [
                    m('option', { value: '' }, 'None'),
                    m('option', { value: 'fantasy' }, 'Fantasy'),
                    m('option', { value: 'sci-fi' }, 'Sci-Fi'),
                    m('option', { value: 'contemporary' }, 'Contemporary'),
                    m('option', { value: 'historical' }, 'Historical')
                ])
            ]),
            // Prompt template config
            m('div', { class: 'border dark:border-gray-700 rounded p-3 space-y-2' }, [
                m('div', { class: 'text-xs font-medium text-gray-500 uppercase tracking-wide mb-1' }, 'Prompt Templates'),
                m('div', { class: 'flex gap-4 mb-2' }, [
                    m('label', { class: 'flex items-center gap-1 text-xs cursor-pointer' }, [
                        m('input', {
                            type: 'radio', name: 'promptMode', value: 'single',
                            checked: promptMode === 'single',
                            onchange: function () { promptMode = 'single'; }
                        }),
                        'Use one for all'
                    ]),
                    m('label', { class: 'flex items-center gap-1 text-xs cursor-pointer' }, [
                        m('input', {
                            type: 'radio', name: 'promptMode', value: 'per-prompt',
                            checked: promptMode === 'per-prompt',
                            onchange: function () { promptMode = 'per-prompt'; }
                        }),
                        'Select per prompt'
                    ])
                ]),
                promptMode === 'single'
                    ? m('div', {
                        class: 'text-field-full text-xs cursor-pointer flex items-center justify-between',
                        onclick: function () {
                            ObjectPicker.openLibrary({
                                libraryType: 'promptTemplate',
                                title: 'Select Prompt Template',
                                onSelect: function (item) {
                                    if (item && item.name) {
                                        promptTemplate = { name: item.name, objectId: item.objectId };
                                        m.redraw();
                                    }
                                }
                            });
                        }
                    }, [
                        m('span', { class: promptTemplate ? '' : 'text-gray-400' },
                            promptTemplate ? promptTemplate.name : (defaultsLoading ? 'Loading default...' : '(default)')),
                        m('span', { class: 'material-symbols-outlined text-gray-400 text-sm' }, 'search')
                    ])
                    : null,
                promptMode === 'single'
                    ? m('div', { class: 'text-[10px] text-gray-400' },
                        'Applies to scene/chunk/character extraction and blurb prompts only — '
                        + 'landscape and scene-image prompts need different template vars and always '
                        + 'use their own defaults; switch to "Select per prompt" to customize those.')
                    : m('div', { class: 'space-y-1' },
                        [
                            { key: 'extractScenes', label: 'Scene Extraction' },
                            { key: 'extractChunk', label: 'Chunk Extraction' },
                            { key: 'extractCharacter', label: 'Character Details' },
                            { key: 'sceneBlurb', label: 'Scene Blurb' },
                            { key: 'landscapePrompt', label: 'Landscape Prompt' }
                        ].map(function (p) {
                            let ref = promptTemplates[p.key];
                            return m('div', { key: p.key, class: 'flex items-center gap-2' }, [
                                m('span', { class: 'text-xs text-gray-500 w-28 shrink-0' }, p.label),
                                m('div', {
                                    class: 'text-field-full text-xs cursor-pointer flex-1 flex items-center justify-between',
                                    onclick: function () {
                                        ObjectPicker.openLibrary({
                                            libraryType: 'promptTemplate',
                                            title: 'Select ' + p.label + ' Template',
                                            onSelect: function (item) {
                                                if (item && item.name) {
                                                    promptTemplates[p.key] = { name: item.name, objectId: item.objectId };
                                                    m.redraw();
                                                }
                                            }
                                        });
                                    }
                                }, [
                                    m('span', { class: ref ? '' : 'text-gray-400' },
                                        ref ? ref.name : (defaultsLoading ? 'Loading...' : '(default)')),
                                    m('span', { class: 'material-symbols-outlined text-gray-400 text-xs' }, 'search')
                                ])
                            ]);
                        })
                    )
            ]),

            extracting ? renderExtractProgress() : null,

            extractError ? m('div', { class: 'text-red-500 text-sm', 'data-pb-extract-error': '1', oncreate: revealOnCreate }, extractError) : null,

            // A single-document run in which EVERY passage failed has no Step 2 to show the per-passage
            // reasons on — "No scenes returned by LLM" alone is exactly the unexplained dead end this
            // banner exists to replace, so it renders here too, retry included.
            !extractChaptered && extractFailedChunks.length ? renderFailedPassages() : null,

            extractChapterSummary ? renderChapterSummary() : null
        ]) : m('div', { class: 'text-sm text-gray-500 italic' }, 'Manual scene entry — proceed to add scenes.')
    ]);
}

function renderStep2() {
    return m('div', { class: 'p-4 space-y-3' }, [
        m('div', { class: 'flex justify-between items-center mb-2' }, [
            m('h3', { class: 'font-medium' }, 'Scene List (' + extractedScenes.length + ')'),
            m('div', { class: 'flex gap-2' }, [
                m('button', {
                    class: 'btn text-xs',
                    onclick: function () { addManualScene(); }
                }, [m('span', { class: 'material-symbols-outlined text-xs mr-1' }, 'add'), 'Add Scene']),
                m('button', {
                    class: 'btn text-xs',
                    disabled: extracting,
                    onclick: function () { doExtract(); }
                }, extracting ? 'Extracting...' : 'Re-extract')
            ])
        ]),
        renderExtractWarnings(),
        extracting ? renderExtractProgress() :
        m('div', { class: 'space-y-2 max-h-[28rem] overflow-y-auto' },
            extractedScenes.map(function (s, i) {
                return m('div', { key: 'scene-' + i, class: 'border dark:border-gray-700 rounded p-3 text-sm space-y-2' }, [
                    // Header: number + title + reorder/remove buttons
                    m('div', { class: 'flex gap-2 items-center' }, [
                        m('span', { class: 'text-gray-400 text-xs w-5 shrink-0' }, String(i + 1) + '.'),
                        m('input', {
                            class: 'text-field-full text-sm font-medium flex-1',
                            value: s.title || '',
                            placeholder: 'Scene title',
                            oninput: function (e) { extractedScenes[i].title = e.target.value; s.userEdited = true; }
                        }),
                        m('button', {
                            class: 'text-gray-400 hover:text-gray-600 p-0.5',
                            disabled: i === 0,
                            onclick: function () { moveScene(i, -1); }
                        }, m('span', { class: 'material-symbols-outlined text-sm' }, 'arrow_upward')),
                        m('button', {
                            class: 'text-gray-400 hover:text-gray-600 p-0.5',
                            disabled: i === extractedScenes.length - 1,
                            onclick: function () { moveScene(i, 1); }
                        }, m('span', { class: 'material-symbols-outlined text-sm' }, 'arrow_downward')),
                        m('button', {
                            class: 'text-red-400 hover:text-red-600 p-0.5',
                            onclick: function () { removeScene(i); }
                        }, m('span', { class: 'material-symbols-outlined text-sm' }, 'close'))
                    ]),
                    // Blurb + regenerate control (U3). The LLM regen needs a persisted scene
                    // (data.note objectId); raw auto-extracted scenes only gain one at Step 2→3, so
                    // the control appears once the book exists (objectId resolvable via the scene or
                    // its persisted mirror in `scenes`).
                    (function () {
                        let blurbOid = s.objectId || (scenes[i] && scenes[i].objectId) || null;
                        let regenerating = !!blurbRegenerating[i];
                        return m('div', { class: 'space-y-1' }, [
                            m('textarea', {
                                class: 'w-full text-field-full text-xs', rows: 2,
                                value: s.blurb || s.summary || s.description || '',
                                placeholder: 'Scene description/blurb',
                                oninput: function (e) {
                                    extractedScenes[i].blurb = e.target.value;
                                    extractedScenes[i].summary = e.target.value;
                                    s.userEdited = true;
                                }
                            }),
                            blurbOid ? m('button', {
                                class: 'btn text-xs text-gray-500',
                                disabled: regenerating,
                                onclick: function () { doRegenerateBlurb(i, blurbOid); }
                            }, [
                                m('span', { class: 'material-symbols-outlined text-xs mr-0.5' + (regenerating ? ' animate-spin' : '') },
                                    regenerating ? 'progress_activity' : 'auto_awesome'),
                                regenerating ? 'Regenerating...' : 'Regenerate blurb'
                            ]) : null
                        ]);
                    })(),
                    // Diffusion prompt (collapsible)
                    m('details', { class: 'text-xs' }, [
                        m('summary', { class: 'cursor-pointer text-gray-500 hover:text-gray-700' }, 'Diffusion Prompt'),
                        m('textarea', {
                            class: 'w-full text-field-full text-xs mt-1', rows: 2,
                            value: s.diffusionPrompt || '',
                            placeholder: 'Stable Diffusion prompt for illustration',
                            oninput: function (e) { extractedScenes[i].diffusionPrompt = e.target.value; s.userEdited = true; }
                        })
                    ]),
                    // Characters
                    m('div', { class: 'text-gray-500 text-xs' }, 'Characters: ' +
                        (Array.isArray(s.characters) ? s.characters.map(function (c) { return typeof c === 'string' ? c : c.name; }).join(', ') : '—'))
                ]);
            })
        )
    ]);
}

function renderStep3() {
    // Step 3 IS the Manage Characters screen now — real charPerson records already exist by the
    // time this renders (created at the Step 2→3 transition), so this just renders
    // pictureBookCharacters.js's list/detail UI inline instead of a disconnected pre-creation
    // stub editor. See pictureBookCharacters.js for the actual list/statistics/apparel/portrait
    // panels and the "Open Full Editor →" link to the real charPerson record.
    return renderCharacterManagerContent();
}

function renderBgActivity() {
    let bg = LLMConnector.bgActivity;
    if (!bg || !bg.label) return null;
    return m('div', { class: 'flex items-center gap-2 px-4 py-2 text-sm text-blue-600 dark:text-blue-400 bg-blue-50 dark:bg-blue-900/20 rounded mx-4 mb-2' }, [
        m('span', { class: 'material-symbols-outlined text-base animate-spin' }, bg.icon || 'progress_activity'),
        m('span', bg.label)
    ]);
}

function renderStepContent() {
    if (step === 1) return renderStep1();
    if (step === 2) return renderStep2();
    if (step === 3) return renderStep3();
    return null;
}

function renderProgressBar() {
    // The wizard ends at Characters — images are rendered in the V3 reader.
    let labels = ['Source', 'Scenes', 'Characters'];
    return m('div', { class: 'flex items-center gap-1 px-4 pt-3 pb-1' },
        labels.map(function (label, i) {
            let n = i + 1;
            let active = step === n;
            let done = step > n;
            return m('div', { key: n, class: 'flex items-center gap-1' }, [
                m('div', {
                    class: 'w-6 h-6 rounded-full text-xs flex items-center justify-center font-medium ' + (
                        done ? 'bg-green-500 text-white' :
                        active ? 'bg-blue-500 text-white' :
                        'bg-gray-200 dark:bg-gray-700 text-gray-500'
                    )
                }, done ? m('span', { class: 'material-symbols-outlined text-sm' }, 'check') : String(n)),
                m('span', { class: 'text-xs ' + (active ? 'text-blue-500 font-medium' : 'text-gray-400') }, label),
                n < labels.length ? m('span', { class: 'text-gray-300 dark:text-gray-600 mx-1' }, '›') : null
            ]);
        })
    );
}

// ── Action builders ───────────────────────────────────────────────────

function buildActions() {
    let actions = [];

    // Back
    if (step > 1) {
        actions.push({
            label: 'Back', icon: 'arrow_back',
            onclick: function () { step--; m.redraw(); }
        });
    }

    // Cancel. While an extraction is running this must STOP THE RUN, not just close the dialog —
    // closing used to abandon the fetch with the server still working and `extracting` stuck true,
    // so the wizard could not be used again without a reload.
    if (extracting && extractJobId) {
        actions.push({
            label: extractCancelling ? 'Cancelling...' : 'Stop extraction',
            icon: 'stop', destructive: true,
            disabled: extractCancelling,
            onclick: function () { cancelExtract(); }
        });
        actions.push({
            label: 'Close', icon: 'close',
            // Leaves the job running on purpose: its result is retained server-side and reopening
            // the wizard reattaches to it. That is the difference between "stop this" and
            // "stop watching this".
            onclick: function () { Dialog.close(); }
        });
    } else {
        actions.push({
            label: 'Cancel', icon: 'cancel',
            onclick: function () { Dialog.close(); }
        });
    }

    // Step-specific primary actions
    if (step === 1) {
        if (method === 'auto') {
            actions.push({
                label: extracting ? 'Extracting...' : 'Extract',
                icon: 'auto_awesome',
                primary: true,
                // Issue 9: disable when the AccountUsers role is missing
                disabled: extracting || roleWarning,
                onclick: doExtract
            });
        } else {
            // Manual mode — go to Step 2 (scene editor) to add scenes
            actions.push({
                label: 'Continue', icon: 'arrow_forward', primary: true,
                // Issue 9: disable when the AccountUsers role is missing
                disabled: roleWarning,
                onclick: function () { step = 2; m.redraw(); }
            });
        }
    } else if (step === 2) {
        actions.push({
            label: creatingChars ? 'Creating characters...' : 'Continue',
            icon: 'arrow_forward', primary: true,
            disabled: creatingChars,
            onclick: async function () {
                // Characters (and the book/scenes) already exist if the user went Back from
                // Step 3 then Continue again — createCharPerson's by-name dedup only protects
                // against duplicates *within* one createFromScenes call, not across repeated
                // calls, so never re-run it once bookObjectId is set.
                if (bookObjectId) {
                    step = 3;
                    m.redraw();
                    return;
                }
                creatingChars = true;
                m.redraw();
                try {
                    // Create PB2 book first so universe/world exist before scenes are linked
                    let slug = generateSlug(bookName || workName);
                    const baseSlug = slug;
                    let pb2BookObjectId = null;
                    if (pb2WizardBookOid && pb2WizardSlug === baseSlug) {
                        // An earlier Continue in this wizard already created the book+world for this
                        // slug and failed afterwards (extraction/characters). Re-POSTing would 409
                        // against our own row, so reuse it.
                        pb2BookObjectId = pb2WizardBookOid;
                    } else {
                        let pb2 = null;
                        try {
                            pb2 = await createChapBookRecord(slug, bookName || workName);
                        } catch (slugErr) {
                            // Only handle 409 slug conflicts — any other error propagates.
                            if (!slugErr.message || !slugErr.message.includes('409')) throw slugErr;
                            // Before creating a second world with a new slug, check whether the
                            // user already owns a book with this slug (e.g. a previous partial run
                            // that created the book+world but failed on character creation). If so,
                            // reuse it — creating a new slug would litter an additional world.
                            let existingBooks = [];
                            try { existingBooks = await listPb2Books(); } catch (_) {}
                            let existing = existingBooks.find(b => b.slug === slug);
                            if (existing) {
                                pb2 = existing;
                            } else {
                                // Slug belongs to another user — pick a unique suffix.
                                slug = generateSlug(bookName || workName) + '-' + Date.now().toString(36).slice(-4);
                                pb2 = await createChapBookRecord(slug, bookName || workName);
                            }
                        }
                        // createChapBookRecord returns { bookObjectId }; listPb2Books returns { objectId }
                        pb2BookObjectId = pb2 ? (pb2.bookObjectId || pb2.objectId) : null;
                        // Remember it now, before createFromScenes, so a failure there is retryable
                        // (keyed on the un-suffixed slug so a forked slug is also reused).
                        pb2WizardBookOid = pb2BookObjectId;
                        pb2WizardSlug = baseSlug;
                    }

                    let meta = await createFromScenes(
                        workObjectId, chatConfigName(), genre || null,
                        bookName || workName, extractedScenes, buildCharacterStubs(), pb2BookObjectId
                    );
                    // bookObjectId MUST be the data.group objectId — /characters, /scenes, reset all need it.
                    // pb2WizardBookOid carries the olio.pb.book objectId for viewer/workflow navigation.
                    bookObjectId = meta.bookObjectId || null;
                    pb2WizardBookOid = meta.pb2BookObjectId || pb2BookObjectId;
                    scenes = meta.scenes || [];
                    if (meta.failedCharacters && meta.failedCharacters.length) {
                        page.toast('warn', meta.failedCharacters.length + ' character(s) failed to create: ' + meta.failedCharacters.join(', '));
                    } else if (meta.failedExtractions && meta.failedExtractions.length) {
                        page.toast('warn', meta.failedExtractions.length + ' character(s) had LLM extraction failures and may be incomplete.');
                    }
                    step = 3;
                } catch (e) {
                    const msg = e?.message || (typeof e === 'string' ? e : null) || 'Unknown error';
                    console.error('[PictureBook] book creation failed:', e);
                    // Fatal error: open a dismissible dialog so the full message is visible
                    // rather than a transient toast that may auto-dismiss before the user reads it.
                    Dialog.open({
                        title: 'Book Creation Failed',
                        size: 'sm',
                        content: m('p', { class: 'text-red-600 dark:text-red-400' }, 'Failed to create book: ' + msg),
                        closable: true,
                        actions: [{ label: 'Close', icon: 'close', primary: true, onclick: function() { Dialog.close(); } }]
                    });
                }
                // Non-fatal: initialize character list after the main try so a
                // fetch failure doesn't mask a successful book creation.
                if (bookObjectId) {
                    try { await initCharacterManager(bookObjectId); } catch (_) {}
                }
                creatingChars = false;
                m.redraw();
            }
        });
    } else if (step === 3) {
        actions.push({
            label: 'Continue to Images', icon: 'arrow_forward', primary: true,
            onclick: function () { handOffToReader(); }
        });
    }

    return actions;
}

// ── Entry point ───────────────────────────────────────────────────────

/**
 * Resume detection for an existing book. The wizard never resumes onto a rendered book (P10-1: the PB1
 * image steps are gone): a book that already exists opens in the V3 reader (the id is a pb2 book, or a
 * PB1 group bridged to one via /pb2); a pure PB1 book that has scenes opens in the read-only PB1 viewer
 * (its "replaced by V3 — import" notice is P7-4's entry). Anything else is a fresh source document and
 * the wizard opens at step 1. Returns the route to hand off to, or null to open the wizard.
 */
async function tryResumeExistingBook(id) {
    let pb2 = await resolvePb2BookObjectId(id);
    if (pb2) return { route: readerRoute(pb2) };
    let legacyScenes = [];
    try { legacyScenes = await loadPictureBook(id); } catch (e) { legacyScenes = []; }
    if (legacyScenes && legacyScenes.length) return { route: '/picture-book/' + id };
    return null;
}

async function pictureBook(entity, inst, onClose) {
    if (!inst) {
        page.toast('error', 'No instance provided');
        return;
    }

    resetState();
    workObjectId = inst.api.objectId ? inst.api.objectId() : (entity ? entity.objectId : null);
    workName = inst.api.name ? inst.api.name() : (entity ? entity.name : 'Untitled');
    bookName = workName; // default to source name, user can edit

    if (!workObjectId) {
        page.toast('error', 'Cannot open Picture Book: no objectId');
        return;
    }

    // Resume detection first: an existing book never opens the wizard — it hands off to the V3 reader
    // (or the read-only PB1 viewer) before any defaults are loaded. Only a fresh source document
    // continues into steps 1–3 (defaults load async — the UI renders "Loading default..." meanwhile).
    let handoff = await tryResumeExistingBook(workObjectId);
    if (handoff && handoff.route) {
        m.route.set(handoff.route);
        return;
    }
    loadDefaults();

    // Issue 9: check for AccountUsers role — same pattern as ChapBook.
    let pbRoles = page.context && page.context() && page.context().roles;
    roleWarning = !(pbRoles && pbRoles.user);

    // A fresh source document (not an existing book) may carry progress from an interrupted run
    // that never produced a book. Not awaited — the banner fills in on its own redraw.
    if (!bookObjectId) loadPendingCheckpoints();

    // Reattach to an extraction already running for this document, rather than offering to start a
    // second one. Deliberately NOT awaited: it resolves only when the run finishes, and the dialog
    // has to open now. The dialog therefore renders once WITHOUT progress and then updates — the
    // GET /rest/job round-trip has to complete before `extracting` can be known, and
    // onExtractProgress redraws as soon as it is.
    reattachExtractJob();

    Dialog.open({
        title: 'Picture Book — ' + workName,
        size: 'xl',
        closable: false,
        onClose: typeof onClose === 'function' ? onClose : undefined,
        content: {
            view: function () {
                return m('div', [
                    // Issue 9: role warning banner
                    roleWarning ? m('div', { class: 'mx-4 mt-3 p-3 rounded bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-300 dark:border-yellow-700 text-sm text-yellow-800 dark:text-yellow-200 flex items-center gap-2' }, [
                        m('span', { class: 'material-symbols-outlined text-yellow-500' }, 'warning'),
                        'You need the AccountUsers role to use PictureBook features.'
                    ]) : null,
                    renderProgressBar(),
                    renderBgActivity(),
                    renderStepContent()
                ]);
            }
        },
        actions: { view: function () { return buildActions(); } }
    });
}

/**
 * Simplified entry point — opens the wizard with just an objectId and name.
 * Used by the viewer empty state when no inst/entity is available.
 * `onClose`, when given, runs whenever the wizard dialog closes, so a caller showing a list
 * the wizard may have changed (a discarded checkpoint, a new book) can refresh it.
 */
async function pictureBookFromId(objectId, name, onClose) {
    console.log('[PictureBook] pictureBookFromId called: objectId=' + objectId + ' name=' + name);
    if (!objectId) {
        page.toast('error', 'No document selected');
        return;
    }
    let fakeInst = {
        api: {
            objectId: function () { return objectId; },
            name: function () { return name || 'Untitled'; }
        }
    };
    await pictureBook(null, fakeInst, onClose);
}

export { pictureBook, pictureBookFromId };
export default pictureBook;

// Test-only seam: getPromptTemplate() is pure w.r.t. this module's own mutable state
// (promptMode/promptTemplate/promptTemplates), which real usage only ever changes via UI
// interaction. Exporting it plus a minimal setter lets KI-31-follow-up's regression test drive the
// actual function directly, rather than re-implementing its logic in the test or mounting the
// entire multi-step wizard component just to reach one internal branch.
export function __setPromptStateForTest(mode, single, perPrompt) {
    promptMode = mode;
    promptTemplate = single;
    if (perPrompt) Object.assign(promptTemplates, perPrompt);
}
export { getPromptTemplate };

// Test-only seam: the async-extraction state machine. Same rationale as the two seams above —
// doExtract/applyExtractJob/reattachExtractJob are module-private and mounting the whole multi-step
// wizard to reach their branches would test the dialog, not the logic.
//
// These branches specifically: a cancelled run whose partial scenes must be KEPT and flagged
// partial, a failed run whose error must surface, and a reattach that must not start a second run
// against a document already being extracted. The equivalent server-side branches shipped two real
// defects before they had tests; this is the client half of that lesson.
export { doExtract, applyExtractJob, reattachExtractJob, cancelExtract, stoppedEarlyReason,
    describeFailedPassage, mergeRecoveredScenes, doRetryFailed, doRetryFailedChapters,
    loadPendingCheckpoints, discardPendingCheckpoint, describeCheckpointRow, renderCheckpointBanner,
    chapterSummaryState, renderChapterSummary };

// Test-only seam: the reader hand-off. buildActions' step-3 branch, the progress bar, the resume
// decision and handOffToReader are the whole of "the wizard ends at step 3"; they are module-private
// and reach m.route / Dialog directly, so the test drives them here and stubs those.
export { buildActions, renderProgressBar, tryResumeExistingBook, handOffToReader };
export { loadDefaults as __loadDefaultsForTest };
export function __extractStateForTest() {
    return {
        step, workObjectId, extracting, retryingFailed, extractChaptered, extractError, extractJobId, extractProgress,
        extractPartial, extractFailedChunks, extractedScenes, reattaching,
        extractChapterIndex, extractChapterCount, pb2SeriesObjectId, extractChapterSummary, chatConfigRef,
        pendingCheckpoints, checkpointDiscarding, bookObjectId, pb2WizardBookOid
    };
}
export function __setExtractStateForTest(patch) {
    if ('step' in patch) step = patch.step;
    if ('extractedScenes' in patch) extractedScenes = patch.extractedScenes;
    if ('extractFailedChunks' in patch) extractFailedChunks = patch.extractFailedChunks;
    if ('extractPartial' in patch) extractPartial = patch.extractPartial;
    if ('extractChapterSummary' in patch) extractChapterSummary = patch.extractChapterSummary;
    if ('pb2SeriesObjectId' in patch) pb2SeriesObjectId = patch.pb2SeriesObjectId;
    if ('chatConfigRef' in patch) chatConfigRef = patch.chatConfigRef;
    if ('pendingCheckpoints' in patch) pendingCheckpoints = patch.pendingCheckpoints;
    if ('extracting' in patch) extracting = patch.extracting;
    if ('workName' in patch) workName = patch.workName;
    if ('bookObjectId' in patch) bookObjectId = patch.bookObjectId;
    if ('pb2WizardBookOid' in patch) pb2WizardBookOid = patch.pb2WizardBookOid;
}
export function __resetExtractStateForTest(work) {
    step = 1;
    workObjectId = work || null;
    bookObjectId = null;
    pb2WizardBookOid = null;
    pendingCheckpoints = [];
    checkpointDiscarding = false;
    extracting = false;
    retryingFailed = false;
    extractChaptered = false;
    extractError = null;
    extractJobId = null;
    extractProgress = null;
    extractCancelling = false;
    extractPartial = false;
    extractFailedChunks = [];
    extractedScenes = [];
    reattaching = false;
    extractChapterIndex = 0;
    extractChapterCount = 0;
    pb2SeriesObjectId = null;
    extractChapterSummary = null;
    chatConfigRef = null;
    promptMode = 'single';
    promptTemplate = null;
    bookName = '';
    workName = '';
}
