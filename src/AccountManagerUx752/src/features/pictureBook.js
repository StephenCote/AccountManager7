/**
 * Picture Book feature — Book-format viewer for illustrated picture books.
 * Phase 16 completion: sequential page viewer with cover, export, keyboard nav.
 * Phase 5b: PB2 native book list + scene page reader.
 *
 * Routes:
 *   /picture-book                        — Work selector (PB1 books + PB2 books + create new)
 *   /picture-book/:bookObjectId          — PB1 book viewer (bookObjectId = PB1 book group objectId)
 *   /picture-book/v2/:pb2BookObjectId    — PB2 scene page reader (pb2BookObjectId = olio.pb.book objectId)
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { applicationPath } from '../core/config.js';
import { layout, pageLayout } from '../router.js';
import { ObjectPicker } from '../components/picker.js';
import { Dialog } from '../components/dialogCore.js';
import {
    loadPictureBook, reorderScenes, resetPictureBook,
    resolveImageUrl, resolveAllImageUrls, clearImageCache
} from '../workflows/sceneExtractor.js';
import { pictureBookFromId } from '../workflows/pictureBook.js';
import { routes as wfRoutes } from './pictureBookWorkflow.js';
import { listPb2Books, bookPages } from '../workflows/pictureBookWorkflow.js';
import { am7olio } from '../components/olio.js';
import { ReaderShell } from '../components/readerShell.js';

// ── Work Selector View ────────────────────────────────────────────────

/**
 * Open a document picker for selecting a source document, then launch the
 * wizard to create a new picture book from it.
 */
function openDocumentPicker(type) {
    ObjectPicker.open({
        type: type,
        title: 'Select ' + (type === 'data.note' ? 'Note' : 'Document'),
        onSelect: function (item) {
            if (item && item.objectId) {
                // Open the wizard dialog for this source document
                pictureBookFromId(item.objectId, item.name || 'Untitled');
            }
        }
    });
}

// ── Existing picture books ───────────────────────────────────────────

let existingBooks = [];
let existingLoading = false;

// Issue 9: role check warning flag
let pbRoleWarning = false;

// PB2 native books
let pb2Books = [];
let pb2Loading = false;

async function loadPb2Books() {
    pb2Loading = true;
    m.redraw();
    try {
        let result = await listPb2Books();
        pb2Books = Array.isArray(result) ? result : [];
    } catch (e) {
        pb2Books = [];
    }
    pb2Loading = false;
    m.redraw();
}

async function loadExistingBooks() {
    existingLoading = true;
    m.redraw();
    try {
        // Search for .pictureBookMeta notes — each one represents an extracted picture book
        // With decoupled identity, meta lives under ~/PictureBooks/{bookName}/
        let q = am7client.newQuery('data.note');
        q.cache(false);
        q.field('name', '.pictureBookMeta');
        q.range(0, 20);
        if (q.entity.request.indexOf('text') < 0) q.entity.request.push('text');
        if (q.entity.request.indexOf('groupPath') < 0) q.entity.request.push('groupPath');
        let qr = await am7client.search(q);
        existingBooks = [];
        if (qr && qr.results) {
            for (let meta of qr.results) {
                let parsed = {};
                try { parsed = JSON.parse(meta.text || '{}'); } catch (e) {}
                // Use bookObjectId if available, fall back to workObjectId for legacy books
                let bookId = parsed.bookObjectId || parsed.workObjectId;
                if (bookId) {
                    existingBooks.push({
                        bookObjectId: bookId,
                        workName: parsed.workName || 'Untitled',
                        sceneCount: parsed.sceneCount || 0,
                        extractedAt: parsed.extractedAt || ''
                    });
                }
            }
        }
    } catch (e) {
        existingBooks = [];
    }
    existingLoading = false;
    m.redraw();
}

// Issue 1 (idempotent delete): a book can be gone server-side while a stale row still shows in the
// list — a lingering .pictureBookMeta note outlives a deleted book group, or a double-click races
// the list refresh. Deleting an already-gone book is idempotent server-side (HTTP 404 with body
// {"error":"Book not found"}), so treat "already gone" as a benign success, ALWAYS reload the
// list(s) afterward (even on a hard error) so a stale row can never persist, and only raise a real
// red error for a genuine failure (403 auth denial, 500 with a concrete reason).
// resetPictureBook returns {reset, reason, status}; be defensive and also tolerate a throw-on-404
// shape, hence both branches feed pbDeleteIsGone().
function pbDeleteIsGone(resultOrError) {
    if (!resultOrError) return false;
    if (resultOrError.status === 404) return true;
    let text = ((resultOrError.reason || resultOrError.message) || '') + '';
    return /not\s*found/i.test(text) || /\b404\b/.test(text);
}

/**
 * Perform an idempotent picture-book delete against every delete surface.
 * @param {string} objectId  book group objectId OR olio.pb.book objectId (backend reset() accepts either)
 * @param {Function|null} reloadFn  optional async reload; ALWAYS invoked after the attempt
 * @returns {Promise<boolean>} true when the book is gone (deleted now, or already absent)
 */
async function performPbDelete(objectId, reloadFn) {
    let outcome = null;         // 'deleted' | 'gone'
    let hardError = null;
    try {
        let result = await resetPictureBook(objectId);
        if (result && result.reset) outcome = 'deleted';
        else if (pbDeleteIsGone(result)) outcome = 'gone';
        else hardError = (result && result.reason) || 'Failed to delete book';
    } catch (e) {
        if (pbDeleteIsGone(e)) outcome = 'gone';
        else hardError = (e && e.message) || 'Failed to delete book';
    }
    if (outcome === 'deleted') page.toast('success', 'Picture book deleted');
    else if (outcome === 'gone') page.toast('info', 'Already removed');
    else page.toast('error', hardError);
    // Always clear the server search cache and reload — even on a hard error — so a stale row for a
    // book that is already gone can never survive the attempt.
    am7client.clearCache(0, true);
    if (reloadFn) { try { await reloadFn(); } catch (_) {} }
    return !!outcome;
}

// Reload BOTH selector lists — a stale entry may be in either (PB1 meta notes or PB2 books).
async function reloadSelectorLists() {
    await loadPb2Books();
    await loadExistingBooks();
}

async function deleteBookFromList(book) {
    if (!book || !book.bookObjectId) return;
    let ok = await Dialog.confirm({ title: 'Delete Picture Book', message: 'Delete "' + book.workName + '"? Scenes, characters, and images will be removed.', confirmLabel: 'Delete', confirmIcon: 'delete', destructive: true });
    if (!ok) return;
    await performPbDelete(book.bookObjectId, reloadSelectorLists);
}

async function deletePb2BookFromList(b) {
    if (!b || !b.objectId) return;
    let ok = await Dialog.confirm({ title: 'Delete Picture Book', message: 'Delete "' + (b.name || 'this book') + '"? Scenes, characters, and images will be removed.', confirmLabel: 'Delete', confirmIcon: 'delete', destructive: true });
    if (!ok) return;
    // Backend reset() accepts either data.group objectId or olio.pb.book objectId.
    await performPbDelete(b.objectId, reloadSelectorLists);
}

var workSelectorView = {
    oninit: function () {
        // Issue 9: check for AccountUsers role
        let roles = page.context && page.context() && page.context().roles;
        pbRoleWarning = !(roles && roles.user);
        loadExistingBooks();
        loadPb2Books();
    },
    view: function () {
        return m('div', { class: 'p-4 max-w-3xl' }, [
            // Issue 9: role warning banner
            pbRoleWarning ? m('div', { class: 'mb-4 p-3 rounded bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-300 dark:border-yellow-700 text-sm text-yellow-800 dark:text-yellow-200 flex items-center gap-2' }, [
                m('span', { class: 'material-symbols-outlined text-yellow-500' }, 'warning'),
                'You need the AccountUsers role to use Picture Book features.'
            ]) : null,

            m('div', { class: 'flex items-center gap-2 mb-4' }, [
                m('span', { class: 'material-symbols-outlined text-2xl' }, 'auto_stories'),
                m('h2', { class: 'text-xl font-semibold' }, 'Picture Book')
            ]),

            // PB2 native books
            pb2Books.length > 0 ? m('div', { class: 'mb-6' }, [
                m('div', { class: 'text-xs font-medium text-gray-500 uppercase tracking-wide mb-2' }, 'Workflow Books (PB2)'),
                m('div', { class: 'grid grid-cols-1 gap-2' },
                    pb2Books.map(function (b) {
                        let status = (b.bookStatus || '').toLowerCase();
                        let statusLabel = status === 'active' ? 'Active' : status === 'extracted' ? 'Migrated' : status || '';
                        return m('div', {
                            key: b.objectId,
                            class: 'flex items-center justify-between border dark:border-gray-700 rounded px-4 py-3 cursor-pointer hover:bg-purple-50 dark:hover:bg-purple-900/20',
                            onclick: function () { m.route.set('/picture-book/v2/' + b.objectId); }
                        }, [
                            m('div', { class: 'flex items-center gap-3' }, [
                                m('span', { class: 'material-symbols-outlined text-purple-500' }, 'schema'),
                                m('div', [
                                    m('div', { class: 'font-medium text-sm' }, b.name),
                                    m('div', { class: 'text-xs text-gray-500' },
                                        b.slug + (statusLabel ? ' · ' + statusLabel : ''))
                                ])
                            ]),
                            m('div', { class: 'flex items-center gap-1' }, [
                                m('button', {
                                    class: 'text-red-400 hover:text-red-600 p-1',
                                    title: 'Delete picture book',
                                    onclick: function (e) {
                                        e.stopPropagation();
                                        deletePb2BookFromList(b);
                                    }
                                }, m('span', { class: 'material-symbols-outlined text-lg' }, 'delete')),
                                m('span', { class: 'material-symbols-outlined text-gray-400' }, 'chevron_right')
                            ])
                        ]);
                    })
                )
            ]) : pb2Loading ? m('div', { class: 'text-sm text-gray-500 mb-6' }, 'Loading PB2 books...') : null,

            // Existing PB1 picture books
            existingBooks.length > 0 ? m('div', { class: 'mb-6' }, [
                m('div', { class: 'text-xs font-medium text-gray-500 uppercase tracking-wide mb-2' }, 'Legacy Books (PB1)'),
                m('div', { class: 'grid grid-cols-1 gap-2' },
                    existingBooks.map(function (b) {
                        let incomplete = !b.sceneCount;
                        return m('div', {
                            key: b.bookObjectId,
                            class: 'flex items-center justify-between border dark:border-gray-700 rounded px-4 py-3 cursor-pointer hover:bg-blue-50 dark:hover:bg-blue-900/20',
                            onclick: function () { m.route.set('/picture-book/' + b.bookObjectId); }
                        }, [
                            m('div', { class: 'flex items-center gap-3' }, [
                                m('span', { class: 'material-symbols-outlined ' + (incomplete ? 'text-gray-400' : 'text-amber-500') }, 'auto_stories'),
                                m('div', [
                                    m('div', { class: 'font-medium text-sm' }, b.workName),
                                    m('div', { class: 'text-xs text-gray-500' },
                                        incomplete
                                            ? 'Incomplete — no scenes'
                                            : b.sceneCount + ' scene' + (b.sceneCount !== 1 ? 's' : ''))
                                ])
                            ]),
                            m('div', { class: 'flex items-center gap-1' }, [
                                m('button', {
                                    class: 'text-red-400 hover:text-red-600 p-1',
                                    title: 'Delete picture book',
                                    onclick: function (e) {
                                        e.stopPropagation();
                                        deleteBookFromList(b);
                                    }
                                }, m('span', { class: 'material-symbols-outlined text-lg' }, 'delete')),
                                m('span', { class: 'material-symbols-outlined text-gray-400' }, 'chevron_right')
                            ])
                        ]);
                    })
                )
            ]) : existingLoading ? m('div', { class: 'text-sm text-gray-500 mb-6' }, 'Loading...') : null,

            // New picture book
            m('div', { class: 'text-xs font-medium text-gray-500 uppercase tracking-wide mb-2' },
                existingBooks.length > 0 ? 'Create New' : 'Select a document'),
            m('div', { class: 'flex flex-col gap-3' }, [
                m('button', {
                    class: 'flex items-center gap-3 border dark:border-gray-700 rounded px-4 py-3 cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-800 text-left',
                    onclick: function () { openDocumentPicker('data.note'); }
                }, [
                    m('span', { class: 'material-symbols-outlined text-blue-500' }, 'note'),
                    m('div', [
                        m('div', { class: 'font-medium text-sm' }, 'Browse Notes'),
                        m('div', { class: 'text-xs text-gray-500' }, 'Text notes with story content')
                    ]),
                    m('span', { class: 'material-symbols-outlined text-gray-400 ml-auto' }, 'chevron_right')
                ]),
                m('button', {
                    class: 'flex items-center gap-3 border dark:border-gray-700 rounded px-4 py-3 cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-800 text-left',
                    onclick: function () { openDocumentPicker('data.data'); }
                }, [
                    m('span', { class: 'material-symbols-outlined text-green-500' }, 'description'),
                    m('div', [
                        m('div', { class: 'font-medium text-sm' }, 'Browse Documents'),
                        m('div', { class: 'text-xs text-gray-500' }, 'PDF, DOCX, and text files')
                    ]),
                    m('span', { class: 'material-symbols-outlined text-gray-400 ml-auto' }, 'chevron_right')
                ])
            ])
        ]);
    }
};

// ── Picture Book Viewer ───────────────────────────────────────────────

let viewerBookId = null;
let viewerWorkName = '';
let viewerScenes = [];
let imageUrls = {};      // imageObjectId → resolved media URL
let viewerLoading = false;
let viewerError = null;

// Reader nav state (currentPage: 0 = cover, 1..N = scene pages; fullscreen). Held in one object owned
// here and passed to the shared ReaderShell (components/readerShell.js): the shell mutates it, and the
// route wrapper below reads `pbReader.fullscreen` to decide whether to render layout chrome. Keeping
// nav + keyboard + page-dots + export in the shell is what gives ChapBook the same reader for free.
let pbReader = { currentPage: 0, fullscreen: false };

// Blurb editing removed — viewer is read-only; editing done via wizard

async function loadViewer(bookObjectId) {
    if (!bookObjectId || bookObjectId === 'undefined') return;
    viewerLoading = true;
    viewerError = null;
    viewerScenes = [];
    imageUrls = {};
    pbReader.currentPage = 0;
    pbReader.fullscreen = false;
    clearImageCache();
    m.redraw();
    try {
        // Resolve book group name for the title
        try {
            let q = am7client.newQuery('auth.group');
            q.field('objectId', bookObjectId);
            q.range(0, 1);
            let qr = await am7client.search(q);
            if (qr && qr.results && qr.results.length > 0) {
                viewerWorkName = qr.results[0].name || '';
                m.redraw();
            }
        } catch (e) {}

        let scenes = [];
        try { scenes = await loadPictureBook(bookObjectId); } catch (e) { /* meta may not exist */ }
        viewerScenes = Array.isArray(scenes) ? scenes : [];

        if (viewerScenes.length) {
            imageUrls = await resolveAllImageUrls(viewerScenes);
        }
    } catch (e) {
        viewerError = 'Failed to load picture book: ' + (e.message || '');
    }
    viewerLoading = false;
    m.redraw();
}

function getImageUrl(imageObjectId) {
    return imageObjectId ? (imageUrls[imageObjectId] || null) : null;
}

// Cover image = first scene's image (cover created last, uses first scene)
function getCoverImageUrl() {
    if (!viewerScenes.length) return null;
    return getImageUrl(viewerScenes[0].imageObjectId);
}

// Blurb editing removed — viewer is read-only; use "Edit Book" to reopen wizard

// Export as self-contained HTML is handled by ReaderShell (see components/readerShell.js).
// PictureBook passes its export params (title suffix, scene noun, etc.) as attrs so the exported
// file is byte-for-byte identical to the legacy exportPictureBook() implementation.

// ── Render: Cover Page (ReaderShell renderCover slot) ─────────────────

function renderCover(nav) {
    let coverImg = getCoverImageUrl();
    return m('div', {
        class: 'flex flex-col items-center justify-center min-h-[60vh] relative overflow-hidden rounded-lg',
        style: 'background: linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%);'
    }, [
        coverImg ? m('img', {
            src: coverImg,
            class: 'absolute inset-0 w-full h-full object-cover opacity-50'
        }) : null,
        m('div', { class: 'relative z-10 text-center p-8' }, [
            m('h1', {
                class: 'text-4xl font-bold text-white mb-3',
                style: 'text-shadow: 0 2px 8px rgba(0,0,0,0.7); font-family: Georgia, serif;'
            }, viewerWorkName || 'Untitled'),
            m('p', { class: 'text-lg text-gray-300 opacity-70' },
                viewerScenes.length + ' Scene' + (viewerScenes.length !== 1 ? 's' : '')),
            m('button', {
                class: 'mt-8 px-6 py-2 bg-white/20 hover:bg-white/30 text-white rounded-full backdrop-blur-sm transition-colors',
                onclick: function () { nav.goToPage(1); }
            }, [
                m('span', { class: 'material-symbols-outlined align-middle mr-1 text-base' }, 'arrow_forward'),
                'Begin'
            ])
        ])
    ]);
}

// ── Render: Scene Page (ReaderShell renderPage slot) ──────────────────

function renderScenePage(scene, pageNumber, nav) {
    if (!scene) return m('div', { class: 'text-sm text-gray-500 italic p-4' }, 'No scene data.');

    let imgUrl = getImageUrl(scene.imageObjectId);

    return m('div', { class: 'flex flex-col items-center' }, [
        // Hero image
        imgUrl
            ? m('img', {
                src: imgUrl,
                class: 'w-full rounded-lg mb-4',
                style: 'max-height: 55vh; object-fit: contain;'
            })
            : m('div', {
                class: 'w-full rounded-lg mb-4 bg-gray-100 dark:bg-gray-800 flex items-center justify-center',
                style: 'height: 240px;'
            }, m('span', { class: 'material-symbols-outlined text-gray-400 text-5xl' }, 'image')),

        // Title
        m('h2', {
            class: 'text-2xl font-semibold mb-3 text-center',
            style: 'font-family: Georgia, serif;'
        }, scene.title || 'Untitled Scene'),

        // Blurb (read-only)
        m('div', { class: 'max-w-2xl w-full px-4' },
            m('p', {
                class: 'text-base text-gray-700 dark:text-gray-300 text-center italic',
                style: 'line-height: 1.8; font-family: Georgia, serif;'
            }, (scene.description || scene.summary || '') || m('em', { class: 'text-gray-400 not-italic' }, 'No blurb yet.'))
        ),

        // Character badges
        scene.characters && scene.characters.length > 0
            ? m('div', { class: 'flex flex-wrap gap-1.5 mt-4 justify-center' },
                scene.characters.map(function (c) {
                    let name = typeof c === 'string' ? c : (c.name || c);
                    return m('span', {
                        key: name,
                        class: 'text-xs px-2.5 py-1 bg-blue-100 dark:bg-blue-900/50 text-blue-700 dark:text-blue-300 rounded-full'
                    }, name);
                })
            )
            : null,

        // Page number
        m('div', { class: 'mt-6 text-xs text-gray-400 text-center' },
            'Page ' + pageNumber + ' of ' + viewerScenes.length)
    ]);
}

// renderBlurbDisplay and renderBlurbEditor removed — viewer is read-only

// ── Reader body slots (loading / error / empty) ───────────────────────

function renderViewerLoading() {
    return m('div', { class: 'text-sm text-gray-500 text-center py-12' }, 'Loading picture book...');
}

function renderViewerError() {
    return m('div', { class: 'text-center py-12' }, [
        m('div', { class: 'text-red-500 text-sm mb-6' }, viewerError),
        m('button', {
            class: 'btn px-6 py-2 text-red-500 border border-red-300 hover:bg-red-50 dark:hover:bg-red-900/20',
            onclick: async function () {
                let ok = await Dialog.confirm({ title: 'Delete Picture Book', message: 'Delete this picture book?', confirmLabel: 'Delete', confirmIcon: 'delete', destructive: true });
                if (!ok) return;
                let gone = await performPbDelete(viewerBookId, null);
                if (gone) m.route.set('/picture-book');
            }
        }, [
            m('span', { class: 'material-symbols-outlined align-middle mr-1 text-base' }, 'delete'),
            'Delete Book'
        ])
    ]);
}

function renderViewerEmpty() {
    return m('div', { class: 'text-center py-12' }, [
        m('span', { class: 'material-symbols-outlined text-5xl text-gray-300 mb-4' }, 'auto_stories'),
        m('div', { class: 'text-sm text-gray-500 mb-6' },
            'No picture book has been generated for this document yet.'),
        m('div', { class: 'flex gap-2 justify-center' }, [
            m('button', {
                class: 'btn btn-primary px-6 py-2',
                onclick: function () {
                    pictureBookFromId(viewerBookId, viewerWorkName);
                }
            }, [
                m('span', { class: 'material-symbols-outlined align-middle mr-1 text-base' }, 'auto_awesome'),
                'Generate Picture Book'
            ]),
            m('button', {
                class: 'btn px-6 py-2 text-red-500 border border-red-300 hover:bg-red-50 dark:hover:bg-red-900/20',
                title: 'Delete this incomplete book',
                onclick: async function () {
                    let ok = await Dialog.confirm({ title: 'Delete Picture Book', message: 'Delete this incomplete picture book?', confirmLabel: 'Delete', confirmIcon: 'delete', destructive: true });
                    if (!ok) return;
                    let gone = await performPbDelete(viewerBookId, null);
                    if (gone) m.route.set('/picture-book');
                }
            }, [
                m('span', { class: 'material-symbols-outlined align-middle mr-1 text-base' }, 'delete'),
                'Delete'
            ])
        ]),
        m('div', { class: 'mt-4' }, [
            m('a', {
                class: 'text-blue-500 underline cursor-pointer text-xs',
                onclick: function () { m.route.set('/picture-book'); }
            }, 'or select a different document')
        ])
    ]);
}

// ── Header action slots (between chevrons/export/fullscreen) ───────────

function renderViewerActionsLeft(nav) {
    // Edit Book — reopen wizard
    return (!nav.fullscreen && viewerScenes.length) ? m('button', {
        class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300',
        title: 'Edit Book',
        onclick: function () { pictureBookFromId(viewerBookId, viewerWorkName); }
    }, m('span', { class: 'material-symbols-outlined text-lg' }, 'edit')) : null;
}

function renderViewerActionsRight(nav) {
    return [
        // Workflow graph
        (!nav.fullscreen && viewerBookId) ? m('button', {
            class: 'text-gray-500 hover:text-blue-600',
            title: 'View Workflow Graph',
            onclick: function () { m.route.set('/picture-book/' + viewerBookId + '/workflow'); }
        }, m('span', { class: 'material-symbols-outlined text-lg' }, 'account_tree')) : null,

        // Delete picture book
        (!nav.fullscreen && viewerScenes.length) ? m('button', {
            class: 'text-red-400 hover:text-red-600',
            title: 'Delete picture book',
            onclick: async function () {
                let ok = await Dialog.confirm({ title: 'Delete Picture Book', message: 'Delete this picture book? Scenes, characters, and images will be removed.', confirmLabel: 'Delete', confirmIcon: 'delete', destructive: true });
                if (!ok) return;
                let gone = await performPbDelete(viewerBookId, null);
                if (gone) {
                    viewerScenes = [];
                    imageUrls = {};
                    pbReader.currentPage = 0;
                    m.route.set('/picture-book');
                }
            }
        }, m('span', { class: 'material-symbols-outlined text-lg' }, 'delete')) : null
    ];
}

// ── Main Viewer Component ─────────────────────────────────────────────

var pictureBookView = {
    oninit: function (vnode) {
        // Only init on first call (route oninit) — skip when re-rendered as m(component)
        if (vnode.attrs.bookObjectId) {
            viewerBookId = vnode.attrs.bookObjectId;
            viewerWorkName = 'Loading...';
            loadViewer(viewerBookId).then(function () {
                if (viewerScenes.length && viewerScenes[0].workName) {
                    viewerWorkName = viewerScenes[0].workName;
                }
            });
        }
    },
    // Keyboard nav + fullscreen state are owned by ReaderShell (pbReader is the caller-owned state).
    view: function () {
        return m(ReaderShell, {
            state: pbReader,
            pages: viewerScenes,
            title: viewerWorkName,
            pageNoun: 'Page',
            loading: viewerLoading,
            error: viewerError,
            renderLoading: renderViewerLoading,
            renderError: renderViewerError,
            renderEmpty: renderViewerEmpty,
            renderCover: renderCover,
            renderPage: renderScenePage,
            imageUrlFor: function (s) { return getImageUrl(s.imageObjectId); },
            onBack: function () { m.route.set('/picture-book'); },
            backTitle: 'Back to documents',
            actionsLeft: renderViewerActionsLeft,
            actionsRight: renderViewerActionsRight,
            // Export params — reproduce the legacy exportPictureBook() output byte-for-byte.
            coverImageUrl: getCoverImageUrl,
            exportCountNoun: 'Scene',
            exportTitleSuffix: 'Picture Book',
            exportNameFallback: 'picturebook',
            exportNameSuffix: '-picturebook.html',
            exportToast: 'Picture book exported'
        });
    }
};

// ── PB2 Page Reader ───────────────────────────────────────────────────

let pb2Pages = [];
let pb2PageLoading = false;
let pb2PageError = null;
let pb2CurrentPage = 0;
let pb2BookName = '';
let pb2BookObjectId = null;

function pb2TotalPages() { return pb2Pages.length + 1; } // cover + scenes
function pb2CurrentScene() { return pb2CurrentPage > 0 ? pb2Pages[pb2CurrentPage - 1] : null; }

function pb2GoToPage(n) {
    let max = pb2TotalPages() - 1;
    pb2CurrentPage = Math.max(0, Math.min(n, max));
    m.redraw();
}

function pb2OnKeyDown(e) {
    if (e.key === 'ArrowRight' || e.key === 'Right') { e.preventDefault(); pb2GoToPage(pb2CurrentPage + 1); }
    else if (e.key === 'ArrowLeft' || e.key === 'Left') { e.preventDefault(); pb2GoToPage(pb2CurrentPage - 1); }
    else if (e.key === 'Home') { e.preventDefault(); pb2GoToPage(0); }
    else if (e.key === 'End') { e.preventDefault(); pb2GoToPage(pb2TotalPages() - 1); }
}

function pb2ImageUrl(page) {
    // Images are served by MediaServlet at /media/{orgDotPath}/data.data{groupPath}/{name}
    // (the canonical path-based route used across the UI — see components/decorator.js).
    // There is no objectId-based /rest/resource route, so dataObjectId alone cannot fetch bytes;
    // bookPageView supplies imageGroupPath + imageName for exactly this.
    if (!page || !page.imageGroupPath || !page.imageName) return null;
    return applicationPath + '/media/' + am7client.dotPath(am7client.currentOrganization)
        + '/data.data' + page.imageGroupPath + '/' + page.imageName;
}

async function loadPb2Pages(pb2ObjId) {
    if (!pb2ObjId || pb2ObjId === 'undefined') return;
    pb2PageLoading = true;
    pb2PageError = null;
    pb2Pages = [];
    pb2CurrentPage = 0;
    pb2BookName = 'Loading...';
    m.redraw();
    try {
        // Phase 1b: fetch the full book record so world.objectId and world.basis.objectId
        // are available for threading into game REST calls.
        let bookFull = await am7client.getFull('olio.pb.book', pb2ObjId);
        am7olio.setCurrentBook(bookFull || null);
        if (bookFull) {
            pb2BookName = bookFull.name || 'Untitled';
        } else {
            // Fallback: look for this book in the already-loaded pb2Books list
            let known = pb2Books.find(function (b) { return b.objectId === pb2ObjId; });
            if (known) pb2BookName = known.name || 'Untitled';
            else pb2BookName = 'Untitled';
        }

        let pages = await bookPages(pb2ObjId);
        pb2Pages = Array.isArray(pages) ? pages : [];
        if (pb2Pages.length > 0) {
            pb2BookName = pb2Pages[0].title || pb2BookName;
        }
    } catch (e) {
        pb2PageError = 'Failed to load pages: ' + (e.message || '');
    }
    pb2PageLoading = false;
    m.redraw();
}

function renderPb2Cover() {
    let firstPage = pb2Pages.length > 0 ? pb2Pages[0] : null;
    let coverImgUrl = firstPage ? pb2ImageUrl(firstPage) : null;
    return m('div', {
        class: 'flex flex-col items-center justify-center min-h-[60vh] relative overflow-hidden rounded-lg',
        style: 'background: linear-gradient(135deg, #2d1b69 0%, #1a1a4e 50%, #0f0f3d 100%);'
    }, [
        coverImgUrl ? m('img', {
            src: coverImgUrl,
            class: 'absolute inset-0 w-full h-full object-cover opacity-40'
        }) : null,
        m('div', { class: 'relative z-10 text-center p-8' }, [
            m('div', { class: 'text-purple-300 text-sm mb-2 uppercase tracking-widest' }, 'PB2 · Workflow Book'),
            m('h1', {
                class: 'text-4xl font-bold text-white mb-3',
                style: 'text-shadow: 0 2px 8px rgba(0,0,0,0.7); font-family: Georgia, serif;'
            }, pb2BookName || 'Untitled'),
            m('p', { class: 'text-lg text-gray-300 opacity-70' },
                pb2Pages.length + ' scene' + (pb2Pages.length !== 1 ? 's' : '')),
            pb2Pages.length > 0 ? m('button', {
                class: 'mt-8 px-6 py-2 bg-purple-500/30 hover:bg-purple-500/50 text-white rounded-full backdrop-blur-sm transition-colors',
                onclick: function () { pb2GoToPage(1); }
            }, [
                m('span', { class: 'material-symbols-outlined align-middle mr-1 text-base' }, 'arrow_forward'),
                'Begin'
            ]) : null
        ])
    ]);
}

function renderPb2ScenePage() {
    let scene = pb2CurrentScene();
    if (!scene) return m('div', { class: 'text-sm text-gray-500 italic p-4' }, 'No scene data.');
    let imgUrl = pb2ImageUrl(scene);
    let text = scene.poemStanza || scene.blurb || scene.summary || '';
    return m('div', { class: 'flex flex-col items-center' }, [
        imgUrl
            ? m('img', {
                src: imgUrl,
                class: 'w-full rounded-lg mb-4',
                style: 'max-height: 55vh; object-fit: contain;'
            })
            : m('div', {
                class: 'w-full rounded-lg mb-4 bg-gray-100 dark:bg-gray-800 flex items-center justify-center',
                style: 'height: 240px;'
            }, m('span', { class: 'material-symbols-outlined text-gray-400 text-5xl' }, 'image')),

        m('h2', {
            class: 'text-2xl font-semibold mb-3 text-center',
            style: 'font-family: Georgia, serif;'
        }, scene.title || 'Scene ' + (pb2CurrentPage)),

        m('div', { class: 'max-w-2xl w-full px-4' },
            m('p', {
                class: 'text-base text-gray-700 dark:text-gray-300 text-center italic',
                style: 'line-height: 1.8; font-family: Georgia, serif;'
            }, text || m('em', { class: 'text-gray-400 not-italic' }, 'No text yet.'))
        ),

        m('div', { class: 'mt-6 text-xs text-gray-400 text-center' },
            'Page ' + pb2CurrentPage + ' of ' + pb2Pages.length)
    ]);
}

function renderPb2Header() {
    let total = pb2TotalPages();
    let pageLabel = pb2CurrentPage === 0
        ? 'Cover'
        : 'Page ' + pb2CurrentPage + ' of ' + pb2Pages.length;
    return m('div', { class: 'flex items-center gap-3 mb-4' }, [
        m('button', {
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300',
            title: 'Back to book list',
            onclick: function () { m.route.set('/picture-book'); }
        }, m('span', { class: 'material-symbols-outlined' }, 'arrow_back')),

        m('button', {
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300 disabled:opacity-30',
            disabled: pb2CurrentPage === 0,
            onclick: function () { pb2GoToPage(pb2CurrentPage - 1); }
        }, m('span', { class: 'material-symbols-outlined' }, 'chevron_left')),

        m('div', { class: 'flex-1 text-center' }, [
            m('span', { class: 'font-semibold text-sm' }, pb2BookName && pb2BookName !== 'Loading...' ? pb2BookName : 'Picture Book'),
            m('span', { class: 'text-gray-400 text-xs ml-2' }, pageLabel)
        ]),

        m('button', {
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300 disabled:opacity-30',
            disabled: pb2CurrentPage >= total - 1,
            onclick: function () { pb2GoToPage(pb2CurrentPage + 1); }
        }, m('span', { class: 'material-symbols-outlined' }, 'chevron_right')),

        // Open workflow canvas — always visible for PB2 books
        pb2BookObjectId ? m('button', {
            class: 'text-gray-500 hover:text-purple-600',
            title: 'Open Workflow Canvas',
            onclick: function () { m.route.set('/picture-book/' + pb2BookObjectId + '/workflow'); }
        }, m('span', { class: 'material-symbols-outlined text-lg' }, 'account_tree')) : null,

        // Delete button — always available
        pb2BookObjectId ? m('button', {
            class: 'text-red-400 hover:text-red-600',
            title: 'Delete picture book',
            onclick: async function () {
                let ok = await Dialog.confirm({ title: 'Delete Picture Book', message: 'Delete this picture book? Scenes, characters, and images will be removed.', confirmLabel: 'Delete', confirmIcon: 'delete', destructive: true });
                if (!ok) return;
                let gone = await performPbDelete(pb2BookObjectId, null);
                if (gone) m.route.set('/picture-book');
            }
        }, m('span', { class: 'material-symbols-outlined text-lg' }, 'delete')) : null
    ]);
}

function renderPb2PageDots() {
    let total = pb2TotalPages();
    if (total <= 1) return null;
    return m('div', { class: 'flex justify-center gap-2 mt-4 py-2' },
        Array.from({ length: total }, function (_, i) {
            let active = i === pb2CurrentPage;
            return m('button', {
                key: i,
                class: 'w-2.5 h-2.5 rounded-full transition-colors ' +
                    (active ? 'bg-purple-500' : 'bg-gray-300 dark:bg-gray-600 hover:bg-gray-400 dark:hover:bg-gray-500'),
                title: i === 0 ? 'Cover' : 'Page ' + i,
                onclick: function () { pb2GoToPage(i); }
            });
        })
    );
}

var pb2PageReaderView = {
    oninit: function (vnode) {
        if (vnode.attrs.pb2BookObjectId) {
            pb2BookObjectId = vnode.attrs.pb2BookObjectId;
            loadPb2Pages(pb2BookObjectId);
        }
    },
    oncreate: function () { document.addEventListener('keydown', pb2OnKeyDown); },
    onremove: function () {
        document.removeEventListener('keydown', pb2OnKeyDown);
        am7olio.setCurrentBook(null);
    },
    view: function () {
        return m('div', { class: 'p-4 flex flex-col h-full' }, [
            renderPb2Header(),
            pb2PageLoading
                ? m('div', { class: 'text-sm text-gray-500 text-center py-12' }, 'Loading scenes...')
                : pb2PageError
                    ? m('div', { class: 'text-red-500 text-sm text-center py-12' }, pb2PageError)
                    : pb2Pages.length === 0
                        ? m('div', { class: 'text-center py-12' }, [
                            m('span', { class: 'material-symbols-outlined text-5xl text-gray-300 block mb-4' }, 'auto_stories'),
                            m('div', { class: 'text-sm text-gray-500 mb-4' }, 'No scenes in this book yet.'),
                            pb2BookObjectId ? m('button', {
                                class: 'px-4 py-2 bg-purple-600 hover:bg-purple-700 text-white rounded text-sm',
                                onclick: function () { m.route.set('/picture-book/' + pb2BookObjectId + '/workflow'); }
                            }, [
                                m('span', { class: 'material-symbols-outlined align-middle mr-1 text-sm' }, 'account_tree'),
                                'Open Workflow Canvas'
                            ]) : null
                        ])
                        : m('div', { class: 'flex-1 overflow-y-auto max-w-3xl mx-auto w-full' }, [
                            pb2CurrentPage === 0 ? renderPb2Cover() : renderPb2ScenePage(),
                            renderPb2PageDots()
                        ])
        ]);
    }
};

// ── Routes ────────────────────────────────────────────────────────────

export const routes = {
    ...wfRoutes,
    '/picture-book': {
        oninit: function () { workSelectorView.oninit(); },
        view: function () { return layout(pageLayout(workSelectorView.view())); }
    },
    '/picture-book/v2/:pb2BookObjectId': {
        oninit: function (vnode) { pb2PageReaderView.oninit(vnode); },
        oncreate: function () { pb2PageReaderView.oncreate(); },
        onremove: function () { pb2PageReaderView.onremove(); },
        view: function () { return layout(pageLayout(m(pb2PageReaderView))); }
    },
    '/picture-book/:bookObjectId': {
        oninit: function (vnode) { pictureBookView.oninit(vnode); },
        view: function () {
            // ReaderShell renders its own fullscreen overlay; when active, skip the layout chrome
            // so the overlay covers it (reads the shell-mutated pbReader.fullscreen).
            if (pbReader.fullscreen) {
                return m(pictureBookView);
            }
            return layout(pageLayout(m(pictureBookView)));
        }
    }
};
