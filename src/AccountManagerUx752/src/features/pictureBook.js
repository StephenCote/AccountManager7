/**
 * Picture Book feature — Book-format viewer for illustrated picture books.
 * Phase 16 completion: sequential page viewer with cover, export, keyboard nav.
 *
 * Routes:
 *   /picture-book                — Work selector (browse existing books + create new)
 *   /picture-book/:bookObjectId  — Book viewer (cover + scene pages, bookObjectId = book group objectId)
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { applicationPath } from '../core/config.js';
import { layout, pageLayout } from '../router.js';
import { ObjectPicker } from '../components/picker.js';
import {
    loadPictureBook, reorderScenes, resetPictureBook,
    resolveImageUrl, resolveAllImageUrls, clearImageCache
} from '../workflows/sceneExtractor.js';
import { pictureBookFromId } from '../workflows/pictureBook.js';

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

async function loadExistingBooks() {
    existingLoading = true;
    m.redraw();
    try {
        // Search for .pictureBookMeta notes — each one represents an extracted picture book
        // With decoupled identity, meta lives under ~/PictureBooks/{bookName}/
        let q = am7client.newQuery('data.note');
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

var workSelectorView = {
    oninit: function () { loadExistingBooks(); },
    view: function () {
        return m('div', { class: 'p-4 max-w-3xl' }, [
            m('div', { class: 'flex items-center gap-2 mb-4' }, [
                m('span', { class: 'material-symbols-outlined text-2xl' }, 'auto_stories'),
                m('h2', { class: 'text-xl font-semibold' }, 'Picture Book')
            ]),

            // Existing picture books
            existingBooks.length > 0 ? m('div', { class: 'mb-6' }, [
                m('div', { class: 'text-xs font-medium text-gray-500 uppercase tracking-wide mb-2' }, 'Existing Picture Books'),
                m('div', { class: 'grid grid-cols-1 gap-2' },
                    existingBooks.map(function (b) {
                        return m('div', {
                            key: b.bookObjectId,
                            class: 'flex items-center justify-between border dark:border-gray-700 rounded px-4 py-3 cursor-pointer hover:bg-blue-50 dark:hover:bg-blue-900/20',
                            onclick: function () { m.route.set('/picture-book/' + b.bookObjectId); }
                        }, [
                            m('div', { class: 'flex items-center gap-3' }, [
                                m('span', { class: 'material-symbols-outlined text-amber-500' }, 'auto_stories'),
                                m('div', [
                                    m('div', { class: 'font-medium text-sm' }, b.workName),
                                    m('div', { class: 'text-xs text-gray-500' }, b.sceneCount + ' scene' + (b.sceneCount !== 1 ? 's' : ''))
                                ])
                            ]),
                            m('span', { class: 'material-symbols-outlined text-gray-400' }, 'chevron_right')
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
let currentPage = 0;     // 0 = cover, 1..N = scene pages
let viewerLoading = false;
let viewerError = null;
let fullscreen = false;

// Blurb editing removed — viewer is read-only; editing done via wizard

// Export
let exporting = false;

function totalPages() { return viewerScenes.length + 1; } // cover + scenes
function currentScene() { return currentPage > 0 ? viewerScenes[currentPage - 1] : null; }

function goToPage(n) {
    let max = totalPages() - 1;
    currentPage = Math.max(0, Math.min(n, max));
    m.redraw();
}

function onKeyDown(e) {
    if (e.key === 'ArrowRight' || e.key === 'Right') { e.preventDefault(); goToPage(currentPage + 1); }
    else if (e.key === 'ArrowLeft' || e.key === 'Left') { e.preventDefault(); goToPage(currentPage - 1); }
    else if (e.key === 'Home') { e.preventDefault(); goToPage(0); }
    else if (e.key === 'End') { e.preventDefault(); goToPage(totalPages() - 1); }
    else if (e.key === 'Escape' && fullscreen) { e.preventDefault(); fullscreen = false; m.redraw(); }
}

async function loadViewer(bookObjectId) {
    if (!bookObjectId || bookObjectId === 'undefined') return;
    viewerLoading = true;
    viewerError = null;
    viewerScenes = [];
    imageUrls = {};
    currentPage = 0;
    fullscreen = false;
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

// ── Export as self-contained HTML ──────────────────────────────────────

async function fetchImageAsBase64(url) {
    try {
        let resp = await fetch(url, {
            credentials: 'include'
        });
        if (!resp.ok) return null;
        let blob = await resp.blob();
        return new Promise(function (resolve) {
            let reader = new FileReader();
            reader.onloadend = function () { resolve(reader.result); };
            reader.readAsDataURL(blob);
        });
    } catch (e) {
        return null;
    }
}

async function exportPictureBook() {
    if (exporting || !viewerScenes.length) return;
    exporting = true;
    m.redraw();

    let coverUrl = getCoverImageUrl();
    let coverB64 = coverUrl ? await fetchImageAsBase64(coverUrl) : null;

    let sceneSections = '';
    for (let i = 0; i < viewerScenes.length; i++) {
        let s = viewerScenes[i];
        let imgUrl = getImageUrl(s.imageObjectId);
        let imgB64 = imgUrl ? await fetchImageAsBase64(imgUrl) : null;
        let chars = Array.isArray(s.characters) ? s.characters.join(', ') : '';
        sceneSections += '\n    <div class="scene">\n';
        if (imgB64) {
            sceneSections += '      <img src="' + imgB64 + '" alt="' + escHtml(s.title || '') + '" />\n';
        }
        sceneSections += '      <h2>' + escHtml(s.title || 'Scene ' + (i + 1)) + '</h2>\n';
        sceneSections += '      <p class="blurb">' + escHtml(s.description || s.summary || '') + '</p>\n';
        if (chars) {
            sceneSections += '      <div class="characters">' + escHtml(chars) + '</div>\n';
        }
        sceneSections += '      <div class="page-num">Page ' + (i + 1) + ' of ' + viewerScenes.length + '</div>\n';
        sceneSections += '    </div>\n';
    }

    let html = '<!DOCTYPE html>\n<html lang="en">\n<head>\n<meta charset="UTF-8">\n'
        + '<meta name="viewport" content="width=device-width, initial-scale=1.0">\n'
        + '<title>' + escHtml(viewerWorkName) + ' — Picture Book</title>\n'
        + '<style>\n' + exportCss() + '\n</style>\n</head>\n<body>\n'
        + '  <div class="book">\n'
        + '    <div class="cover">\n'
        + (coverB64 ? '      <img src="' + coverB64 + '" alt="Cover" />\n' : '')
        + '      <div class="cover-overlay">\n'
        + '        <h1>' + escHtml(viewerWorkName) + '</h1>\n'
        + '        <p>' + viewerScenes.length + ' Scene' + (viewerScenes.length !== 1 ? 's' : '') + '</p>\n'
        + '      </div>\n'
        + '    </div>\n'
        + sceneSections
        + '  </div>\n</body>\n</html>';

    let blob = new Blob([html], { type: 'text/html' });
    let url = URL.createObjectURL(blob);
    let a = document.createElement('a');
    a.href = url;
    a.download = (viewerWorkName || 'picturebook').replace(/[^a-zA-Z0-9_-]/g, '_') + '-picturebook.html';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);

    exporting = false;
    m.redraw();
    page.toast('success', 'Picture book exported');
}

function escHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function exportCss() {
    return `
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: Georgia, 'Times New Roman', serif; background: #1a1a2e; color: #e0e0e0; }
.book { max-width: 900px; margin: 0 auto; }
.cover { position: relative; min-height: 80vh; display: flex; align-items: flex-end; justify-content: center;
         background: linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%); overflow: hidden; }
.cover img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; opacity: 0.6; }
.cover-overlay { position: relative; z-index: 1; text-align: center; padding: 3rem 2rem;
                  background: linear-gradient(transparent, rgba(0,0,0,0.8)); width: 100%; }
.cover h1 { font-size: 3rem; font-weight: 700; text-shadow: 0 2px 8px rgba(0,0,0,0.7); margin-bottom: 0.5rem; }
.cover p { font-size: 1.1rem; opacity: 0.7; }
.scene { padding: 3rem 2rem; border-bottom: 1px solid #2a2a3e; }
.scene img { width: 100%; max-height: 60vh; object-fit: contain; border-radius: 4px; margin-bottom: 1.5rem; display: block; }
.scene h2 { font-size: 1.6rem; margin-bottom: 0.75rem; color: #e8d5b7; }
.scene .blurb { font-size: 1.1rem; line-height: 1.8; max-width: 700px; color: #c8c8d0; }
.scene .characters { margin-top: 1rem; font-size: 0.85rem; color: #8888aa; }
.scene .page-num { margin-top: 1.5rem; font-size: 0.75rem; color: #555; text-align: center; }
@media print { .cover { min-height: auto; page-break-after: always; }
               .scene { page-break-inside: avoid; } }
`;
}

// ── Render: Cover Page ────────────────────────────────────────────────

function renderCover() {
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
                onclick: function () { goToPage(1); }
            }, [
                m('span', { class: 'material-symbols-outlined align-middle mr-1 text-base' }, 'arrow_forward'),
                'Begin'
            ])
        ])
    ]);
}

// ── Render: Scene Page ────────────────────────────────────────────────

function renderScenePage() {
    let scene = currentScene();
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
            'Page ' + currentPage + ' of ' + viewerScenes.length)
    ]);
}

// renderBlurbDisplay and renderBlurbEditor removed — viewer is read-only

// ── Render: Navigation ────────────────────────────────────────────────

function renderHeader() {
    let total = totalPages();
    let pageLabel = currentPage === 0
        ? 'Cover'
        : 'Page ' + currentPage + ' of ' + viewerScenes.length;

    return m('div', { class: 'flex items-center gap-3 mb-4' }, [
        // Back to selector
        !fullscreen ? m('button', {
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300',
            title: 'Back to documents',
            onclick: function () { m.route.set('/picture-book'); }
        }, m('span', { class: 'material-symbols-outlined' }, 'arrow_back')) : null,

        // Prev arrow
        m('button', {
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300 disabled:opacity-30',
            disabled: currentPage === 0,
            onclick: function () { goToPage(currentPage - 1); }
        }, m('span', { class: 'material-symbols-outlined' }, 'chevron_left')),

        // Title + page label
        m('div', { class: 'flex-1 text-center' }, [
            m('span', { class: 'font-semibold text-sm' }, viewerWorkName),
            m('span', { class: 'text-gray-400 text-xs ml-2' }, pageLabel)
        ]),

        // Next arrow
        m('button', {
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300 disabled:opacity-30',
            disabled: currentPage >= total - 1,
            onclick: function () { goToPage(currentPage + 1); }
        }, m('span', { class: 'material-symbols-outlined' }, 'chevron_right')),

        // Edit Book — reopen wizard
        !fullscreen && viewerScenes.length ? m('button', {
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300',
            title: 'Edit Book',
            onclick: function () { pictureBookFromId(viewerBookId, viewerWorkName); }
        }, m('span', { class: 'material-symbols-outlined text-lg' }, 'edit')) : null,

        // Export
        m('button', {
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300',
            title: 'Export as HTML',
            disabled: exporting || !viewerScenes.length,
            onclick: exportPictureBook
        }, m('span', { class: 'material-symbols-outlined text-lg' },
            exporting ? 'hourglass_empty' : 'download')),

        // Delete picture book
        !fullscreen && viewerScenes.length ? m('button', {
            class: 'text-red-400 hover:text-red-600',
            title: 'Delete picture book',
            onclick: function () {
                if (!confirm('Delete this picture book? Scenes, characters, and images will be removed.')) return;
                resetPictureBook(viewerBookId).then(function () {
                    page.toast('success', 'Picture book deleted');
                    viewerScenes = [];
                    imageUrls = {};
                    currentPage = 0;
                    am7client.clearCache('data.note', true);
                    am7client.clearCache('auth.group', true);
                    m.route.set('/picture-book');
                }).catch(function () {
                    page.toast('error', 'Failed to delete');
                });
            }
        }, m('span', { class: 'material-symbols-outlined text-lg' }, 'delete')) : null,

        // Fullscreen toggle
        m('button', {
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300',
            title: fullscreen ? 'Exit fullscreen' : 'Fullscreen',
            onclick: function () { fullscreen = !fullscreen; m.redraw(); }
        }, m('span', { class: 'material-symbols-outlined text-lg' },
            fullscreen ? 'fullscreen_exit' : 'fullscreen'))
    ]);
}

function renderPageDots() {
    let total = totalPages();
    if (total <= 1) return null;
    return m('div', { class: 'flex justify-center gap-2 mt-4 py-2' },
        Array.from({ length: total }, function (_, i) {
            let active = i === currentPage;
            return m('button', {
                key: i,
                class: 'w-2.5 h-2.5 rounded-full transition-colors ' +
                    (active
                        ? 'bg-blue-500'
                        : 'bg-gray-300 dark:bg-gray-600 hover:bg-gray-400 dark:hover:bg-gray-500'),
                title: i === 0 ? 'Cover' : 'Page ' + i,
                onclick: function () { goToPage(i); }
            });
        })
    );
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
    oncreate: function () {
        document.addEventListener('keydown', onKeyDown);
    },
    onremove: function () {
        document.removeEventListener('keydown', onKeyDown);
    },
    view: function () {
        let containerClass = fullscreen
            ? 'fixed inset-0 z-50 bg-gray-900 text-white overflow-y-auto p-6'
            : 'p-4 flex flex-col h-full';

        return m('div', { class: containerClass }, [
            renderHeader(),

            viewerLoading ? m('div', { class: 'text-sm text-gray-500 text-center py-12' }, 'Loading picture book...') :
            viewerError ? m('div', { class: 'text-red-500 text-sm text-center py-12' }, viewerError) :
            viewerScenes.length === 0
                ? m('div', { class: 'text-center py-12' }, [
                    m('span', { class: 'material-symbols-outlined text-5xl text-gray-300 mb-4' }, 'auto_stories'),
                    m('div', { class: 'text-sm text-gray-500 mb-6' },
                        'No picture book has been generated for this document yet.'),
                    m('button', {
                        class: 'btn btn-primary px-6 py-2',
                        onclick: function () {
                            pictureBookFromId(viewerBookId, viewerWorkName);
                        }
                    }, [
                        m('span', { class: 'material-symbols-outlined align-middle mr-1 text-base' }, 'auto_awesome'),
                        'Generate Picture Book'
                    ]),
                    m('div', { class: 'mt-4' }, [
                        m('a', {
                            class: 'text-blue-500 underline cursor-pointer text-xs',
                            onclick: function () { m.route.set('/picture-book'); }
                        }, 'or select a different document')
                    ])
                ])
                : m('div', { class: 'flex-1 overflow-y-auto max-w-3xl mx-auto w-full' }, [
                    currentPage === 0 ? renderCover() : renderScenePage(),
                    renderPageDots()
                ])
        ]);
    }
};

// ── Routes ────────────────────────────────────────────────────────────

export const routes = {
    '/picture-book': {
        oninit: function () { workSelectorView.oninit(); },
        view: function () { return layout(pageLayout(workSelectorView.view())); }
    },
    '/picture-book/:bookObjectId': {
        oninit: function (vnode) { pictureBookView.oninit(vnode); },
        oncreate: function () { pictureBookView.oncreate(); },
        onremove: function () { pictureBookView.onremove(); },
        view: function () {
            if (fullscreen) {
                return m(pictureBookView);
            }
            return layout(pageLayout(m(pictureBookView)));
        }
    }
};
