/**
 * Picture Book feature — Generate illustrated picture books from story/document objects.
 * Phase 16: Viewer route + work selector.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { layout, pageLayout } from '../router.js';
import { loadPictureBook, reorderScenes, regenerateBlurb, resetPictureBook } from '../workflows/sceneExtractor.js';

// ── Work Selector View ────────────────────────────────────────────────

let works = [];
let worksLoading = false;
let worksError = null;

async function loadWorks() {
    worksLoading = true;
    worksError = null;
    try {
        let q = am7client.newQuery('data.data');
        q.range(0, 50);
        let qr = await am7client.list(q);
        if (qr && qr.results) {
            works = qr.results.filter(function (w) {
                let ct = w.contentType || '';
                return !ct || ct.startsWith('text/') || ct === 'application/pdf' ||
                    ct === 'application/msword' ||
                    ct === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
            });
        } else {
            works = [];
        }
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
let viewerLoading = false;
let viewerError = null;
let selectedScene = null;
let editingBlurb = false;
let blurbEditText = '';
let savingBlurb = false;

async function loadViewer(workObjectId) {
    viewerLoading = true;
    viewerError = null;
    viewerScenes = [];
    selectedScene = null;
    m.redraw();
    try {
        let scenes = await loadPictureBook(workObjectId);
        viewerScenes = Array.isArray(scenes) ? scenes : [];
        if (viewerScenes.length) selectedScene = viewerScenes[0];
    } catch (e) {
        viewerError = 'Failed to load picture book: ' + (e.message || '');
    }
    viewerLoading = false;
    m.redraw();
}

async function saveBlurb() {
    if (!selectedScene || !selectedScene.objectId) return;
    savingBlurb = true;
    m.redraw();
    try {
        await am7client.patch({
            schema: 'data.note',
            objectId: selectedScene.objectId,
            description: blurbEditText
        });
        selectedScene.description = blurbEditText;
        editingBlurb = false;
    } catch (e) {
        page.toast('error', 'Failed to save blurb');
    }
    savingBlurb = false;
    m.redraw();
}

async function regenBlurb() {
    if (!selectedScene || !selectedScene.objectId) return;
    savingBlurb = true;
    m.redraw();
    try {
        let result = await regenerateBlurb(selectedScene.objectId, null);
        if (result && result.blurb) {
            selectedScene.description = result.blurb;
            blurbEditText = result.blurb;
        }
    } catch (e) {
        page.toast('error', 'Blurb regeneration failed');
    }
    savingBlurb = false;
    m.redraw();
}

function renderSceneDetail() {
    if (!selectedScene) return m('div', { class: 'text-sm text-gray-500 italic p-4' }, 'Select a scene');

    let imgUrl = selectedScene.imageObjectId
        ? am7client.mediaUrl({ objectId: selectedScene.imageObjectId })
        : null;

    return m('div', { class: 'flex flex-col h-full' }, [
        imgUrl
            ? m('img', { src: imgUrl, class: 'w-full object-cover rounded mb-3', style: 'max-height:360px' })
            : m('div', {
                class: 'w-full rounded mb-3 bg-gray-100 dark:bg-gray-800 flex items-center justify-center',
                style: 'height:240px'
            }, m('span', { class: 'material-symbols-outlined text-gray-400 text-5xl' }, 'image')),

        m('div', { class: 'px-1' }, [
            m('h3', { class: 'font-semibold text-lg mb-2' }, selectedScene.title || 'Untitled'),

            editingBlurb
                ? m('div', { class: 'space-y-2' }, [
                    m('textarea', {
                        class: 'w-full text-field-full text-sm', rows: 4,
                        value: blurbEditText,
                        oninput: function (e) { blurbEditText = e.target.value; }
                    }),
                    m('div', { class: 'flex gap-2' }, [
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
                ])
                : m('div', { class: 'flex items-start gap-2' }, [
                    m('p', { class: 'text-sm text-gray-700 dark:text-gray-300 flex-1 leading-relaxed' },
                        selectedScene.description || selectedScene.summary || m('em', { class: 'text-gray-400' }, 'No blurb yet.')),
                    m('button', {
                        class: 'text-gray-400 hover:text-gray-600 text-xs shrink-0 mt-0.5',
                        title: 'Edit blurb',
                        onclick: function () {
                            blurbEditText = selectedScene.description || selectedScene.summary || '';
                            editingBlurb = true;
                        }
                    }, m('span', { class: 'material-symbols-outlined text-sm' }, 'edit'))
                ]),

            selectedScene.characters && selectedScene.characters.length > 0
                ? m('div', { class: 'mt-3 flex flex-wrap gap-1' },
                    selectedScene.characters.map(function (c) {
                        return m('span', {
                            key: c,
                            class: 'text-xs px-2 py-0.5 bg-blue-100 dark:bg-blue-900 text-blue-700 dark:text-blue-300 rounded'
                        }, c);
                    })
                )
                : null
        ])
    ]);
}

var pictureBookView = {
    oninit: function (vnode) {
        viewerWorkId = vnode.attrs.workObjectId;
        viewerWorkName = 'Loading...';
        loadViewer(viewerWorkId);
    },
    view: function () {
        return m('div', { class: 'p-4 flex flex-col h-full' }, [
            // Header
            m('div', { class: 'flex items-center gap-3 mb-4' }, [
                m('button', {
                    class: 'text-gray-500 hover:text-gray-700',
                    onclick: function () { m.route.set('/picture-book'); }
                }, m('span', { class: 'material-symbols-outlined' }, 'arrow_back')),
                m('span', { class: 'material-symbols-outlined text-xl' }, 'auto_stories'),
                m('h2', { class: 'text-xl font-semibold flex-1' }, 'Picture Book'),
                m('button', {
                    class: 'btn text-sm',
                    onclick: function () { loadViewer(viewerWorkId); }
                }, m('span', { class: 'material-symbols-outlined text-base' }, 'refresh'))
            ]),

            viewerLoading ? m('div', { class: 'text-sm text-gray-500' }, 'Loading picture book...') :
            viewerError ? m('div', { class: 'text-red-500 text-sm' }, viewerError) :
            viewerScenes.length === 0
                ? m('div', { class: 'text-sm text-gray-500 italic' }, [
                    'No picture book found for this work. ',
                    m('a', {
                        class: 'text-blue-500 underline cursor-pointer',
                        onclick: function () { m.route.set('/picture-book'); }
                    }, 'Open the document to generate one.')
                ])
                : m('div', { class: 'flex gap-4 flex-1 min-h-0' }, [
                    // Scene strip
                    m('div', { class: 'w-48 flex flex-col gap-2 overflow-y-auto shrink-0' },
                        viewerScenes.map(function (s, i) {
                            let active = selectedScene && selectedScene.objectId === s.objectId;
                            let imgUrl = s.imageObjectId ? am7client.mediaUrl({ objectId: s.imageObjectId }) : null;
                            return m('div', {
                                key: s.objectId || i,
                                class: 'cursor-pointer rounded border-2 overflow-hidden ' + (active ? 'border-blue-500' : 'border-transparent dark:border-transparent'),
                                onclick: function () { selectedScene = s; editingBlurb = false; m.redraw(); }
                            }, [
                                imgUrl
                                    ? m('img', { src: imgUrl, class: 'w-full object-cover', style: 'height:80px' })
                                    : m('div', {
                                        class: 'w-full bg-gray-100 dark:bg-gray-800 flex items-center justify-center',
                                        style: 'height:80px'
                                    }, m('span', { class: 'material-symbols-outlined text-gray-400' }, 'image')),
                                m('div', { class: 'p-1 text-xs truncate' }, s.title || 'Scene ' + (i + 1))
                            ]);
                        })
                    ),

                    // Scene detail
                    m('div', { class: 'flex-1 overflow-y-auto' }, renderSceneDetail())
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
        view: function () { return layout(pageLayout(m(pictureBookView))); }
    }
};
