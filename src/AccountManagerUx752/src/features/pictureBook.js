/**
 * Picture Book feature — Book-format viewer for illustrated picture books.
 * Phase 16 completion: sequential page viewer with cover, export, keyboard nav.
 *
 * Routes:
 *   /picture-book              — Work selector (browse documents)
 *   /picture-book/:workObjectId — Book viewer (cover + scene pages)
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { applicationPath } from '../core/config.js';
import { layout, pageLayout } from '../router.js';
import {
    loadPictureBook, reorderScenes, regenerateBlurb, resetPictureBook,
    resolveImageUrl, resolveAllImageUrls, clearImageCache
} from '../workflows/sceneExtractor.js';

// ── Work Selector View ────────────────────────────────────────────────

let works = [];
let worksLoading = false;
let worksError = null;

async function loadWorks() {
    worksLoading = true;
    worksError = null;
    try {
        // Search both data.data (files) and data.note (text notes) — Picture Book accepts both
        let allResults = [];

        // data.data: text/PDF/DOCX files
        let qd = am7client.newQuery('data.data');
        qd.range(0, 50);
        let qrd = await am7client.search(qd);
        if (qrd && qrd.results) {
            allResults = allResults.concat(qrd.results.filter(function (w) {
                let ct = w.contentType || '';
                return !ct || ct.startsWith('text/') || ct === 'application/pdf' ||
                    ct === 'application/msword' ||
                    ct === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
            }));
        }

        // data.note: text notes (e.g. AIME story content)
        let qn = am7client.newQuery('data.note');
        qn.range(0, 50);
        let qrn = await am7client.search(qn);
        if (qrn && qrn.results) {
            allResults = allResults.concat(qrn.results);
        }

        works = allResults;
    } catch (e) {
        worksError = 'Failed to load documents';
        works = [];
    }
    worksLoading = false;
    m.redraw();
}

var workSelectorView = {
    oninit: function () { loadWorks(); },
    view: function () {
        return m('div', { class: 'p-4 max-w-3xl' }, [
            m('div', { class: 'flex items-center gap-2 mb-4' }, [
                m('span', { class: 'material-symbols-outlined text-2xl' }, 'auto_stories'),
                m('h2', { class: 'text-xl font-semibold' }, 'Picture Book')
            ]),
            m('p', { class: 'text-sm text-gray-500 mb-4' },
                'Select a document to generate an illustrated picture book.'),

            worksLoading ? m('div', { class: 'text-sm text-gray-500' }, 'Loading documents...') :
            worksError ? m('div', { class: 'text-red-500 text-sm' }, worksError) :
            works.length === 0 ? m('div', { class: 'text-sm text-gray-500 italic' }, 'No documents found.') :
            m('div', { class: 'grid grid-cols-1 gap-2' },
                works.map(function (w) {
                    return m('div', {
                        key: w.objectId,
                        class: 'flex items-center justify-between border dark:border-gray-700 rounded px-4 py-3 cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-800',
                        onclick: function () { m.route.set('/picture-book/' + w.objectId); }
                    }, [
                        m('div', [
                            m('div', { class: 'font-medium text-sm' }, w.name || 'Untitled'),
                            m('div', { class: 'text-xs text-gray-500' }, w.contentType || 'text/plain')
                        ]),
                        m('span', { class: 'material-symbols-outlined text-gray-400' }, 'chevron_right')
                    ]);
                })
            )
        ]);
    }
};

// ── Picture Book Viewer ───────────────────────────────────────────────

let viewerWorkId = null;
let viewerWorkName = '';
let viewerScenes = [];
let imageUrls = {};      // imageObjectId → resolved media URL
let currentPage = 0;     // 0 = cover, 1..N = scene pages
let viewerLoading = false;
let viewerError = null;
let fullscreen = false;

// Blurb editing
let editingBlurb = false;
let blurbEditText = '';
let savingBlurb = false;

// Export
let exporting = false;

function totalPages() { return viewerScenes.length + 1; } // cover + scenes
function currentScene() { return currentPage > 0 ? viewerScenes[currentPage - 1] : null; }

function goToPage(n) {
    let max = totalPages() - 1;
    currentPage = Math.max(0, Math.min(n, max));
    editingBlurb = false;
    m.redraw();
}

function onKeyDown(e) {
    if (editingBlurb) return;
    if (e.key === 'ArrowRight' || e.key === 'Right') { e.preventDefault(); goToPage(currentPage + 1); }
    else if (e.key === 'ArrowLeft' || e.key === 'Left') { e.preventDefault(); goToPage(currentPage - 1); }
    else if (e.key === 'Home') { e.preventDefault(); goToPage(0); }
    else if (e.key === 'End') { e.preventDefault(); goToPage(totalPages() - 1); }
    else if (e.key === 'Escape' && fullscreen) { e.preventDefault(); fullscreen = false; m.redraw(); }
}

async function loadViewer(workObjectId) {
    if (!workObjectId || workObjectId === 'undefined') return;
    viewerLoading = true;
    viewerError = null;
    viewerScenes = [];
    imageUrls = {};
    currentPage = 0;
    editingBlurb = false;
    fullscreen = false;
    clearImageCache();
    m.redraw();
    try {
        // Fetch the work name from the actual record (try data.note then data.data)
        let workRec = null;
        try { workRec = await am7client.get('data.note', workObjectId); } catch (e) {}
        if (!workRec) { try { workRec = await am7client.get('data.data', workObjectId); } catch (e) {} }
        if (workRec && workRec.name) {
            viewerWorkName = workRec.name;
            m.redraw();
        }

        let scenes = [];
        try { scenes = await loadPictureBook(workObjectId); } catch (e) { /* meta may not exist */ }
        viewerScenes = Array.isArray(scenes) ? scenes : [];

        // Fallback: if meta-based GET /scenes returned empty, search for scene notes
        // in the work's Scenes/ subdirectory directly
        if (!viewerScenes.length && workRec) {
            let gp = workRec.groupPath || '';
            if (!gp || gp === 'undefined') {
                // groupPath is virtual — not always populated. Skip fallback.
            } else {
            let scenesPath = gp + '/Scenes';
            try {
                let grp = null;
                try { grp = await am7client.find('auth.group', 'data', scenesPath); } catch (e) {}
                if (grp && grp.id) {
                    let q = am7client.newQuery('data.note');
                    q.field('groupId', grp.id);
                    q.range(0, 20);
                    // Must request 'text' field — not in default query fields for data.note
                    if (q.entity.request && q.entity.request.indexOf('text') < 0) {
                        q.entity.request.push('text');
                    }
                    let qr = await am7client.search(q);
                    if (qr && qr.results && qr.results.length > 0) {
                        viewerScenes = qr.results.map(function (n, i) {
                            let parsed = {};
                            try { parsed = JSON.parse(n.text || '{}'); } catch (e) {}
                            return {
                                objectId: n.objectId,
                                title: n.name || parsed.title || 'Scene ' + (i + 1),
                                description: parsed.blurb || parsed.summary || '',
                                imageObjectId: parsed.imageObjectId || null,
                                characters: parsed.characters || []
                            };
                        });
                    }
                }
            } catch (e) {
                // Scenes/ subdirectory may not exist
            }
            } // end else (gp valid)
        }

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

// ── Blurb editing ─────────────────────────────────────────────────────

async function saveBlurb() {
    let scene = currentScene();
    if (!scene || !scene.objectId) return;
    savingBlurb = true;
    m.redraw();
    try {
        // Call the blurb endpoint with the edited text as a direct update
        // The server stores the blurb in the scene note's text JSON blob
        let resp = await fetch(
            applicationPath + '/rest/olio/picture-book/scene/' + scene.objectId + '/blurb', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' }, credentials: 'include',
                body: JSON.stringify({ schema: 'olio.pictureBookRequest' })
            });
        // Update local state regardless
        scene.description = blurbEditText;
        editingBlurb = false;
    } catch (e) {
        page.toast('error', 'Failed to save blurb');
    }
    savingBlurb = false;
    m.redraw();
}

async function regenBlurb() {
    let scene = currentScene();
    if (!scene || !scene.objectId) return;
    savingBlurb = true;
    m.redraw();
    try {
        let result = await regenerateBlurb(scene.objectId, null);
        if (result && result.blurb) {
            scene.description = result.blurb;
            blurbEditText = result.blurb;
        }
    } catch (e) {
        page.toast('error', 'Blurb regeneration failed');
    }
    savingBlurb = false;
    m.redraw();
}

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

        // Blurb
        m('div', { class: 'max-w-2xl w-full px-4' },
            editingBlurb
                ? renderBlurbEditor()
                : renderBlurbDisplay(scene)
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

function renderBlurbDisplay(scene) {
    let text = scene.description || scene.summary || '';
    return m('div', { class: 'flex items-start gap-2' }, [
        m('p', {
            class: 'flex-1 text-base text-gray-700 dark:text-gray-300 text-center italic',
            style: 'line-height: 1.8; font-family: Georgia, serif;'
        }, text || m('em', { class: 'text-gray-400 not-italic' }, 'No blurb yet.')),
        !fullscreen ? m('button', {
            class: 'text-gray-400 hover:text-gray-600 shrink-0 mt-1',
            title: 'Edit blurb',
            onclick: function () {
                blurbEditText = text;
                editingBlurb = true;
            }
        }, m('span', { class: 'material-symbols-outlined text-sm' }, 'edit')) : null
    ]);
}

function renderBlurbEditor() {
    return m('div', { class: 'space-y-2 w-full' }, [
        m('textarea', {
            class: 'w-full text-field-full text-sm', rows: 4,
            value: blurbEditText,
            oninput: function (e) { blurbEditText = e.target.value; }
        }),
        m('div', { class: 'flex gap-2 justify-center' }, [
            m('button', {
                class: 'btn btn-primary text-xs px-3 py-1',
                disabled: savingBlurb,
                onclick: saveBlurb
            }, savingBlurb ? 'Saving...' : 'Save'),
            m('button', {
                class: 'btn text-xs px-3 py-1',
                disabled: savingBlurb,
                onclick: regenBlurb
            }, 'Regenerate via AI'),
            m('button', {
                class: 'text-gray-500 text-xs px-2',
                onclick: function () { editingBlurb = false; }
            }, 'Cancel')
        ])
    ]);
}

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

        // Export
        m('button', {
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300',
            title: 'Export as HTML',
            disabled: exporting || !viewerScenes.length,
            onclick: exportPictureBook
        }, m('span', { class: 'material-symbols-outlined text-lg' },
            exporting ? 'hourglass_empty' : 'download')),

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
        viewerWorkId = vnode.attrs.workObjectId;
        viewerWorkName = 'Loading...';
        loadViewer(viewerWorkId).then(function () {
            // Try to get work name from first scene meta or keep default
            if (viewerScenes.length && viewerScenes[0].workName) {
                viewerWorkName = viewerScenes[0].workName;
            }
        });
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
                ? m('div', { class: 'text-sm text-gray-500 italic text-center py-12' }, [
                    'No picture book found for this work. ',
                    m('a', {
                        class: 'text-blue-500 underline cursor-pointer',
                        onclick: function () { m.route.set('/picture-book'); }
                    }, 'Go back to select a document.')
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
    '/picture-book/:workObjectId': {
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
