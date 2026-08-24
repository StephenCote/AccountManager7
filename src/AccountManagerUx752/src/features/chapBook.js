/**
 * ChapBook feature — Poem library, analysis, and landscape-illustrated poetry-book creation.
 *
 * Routes:
 *   /chap-book   — Poem library + ChapBook creation
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { applicationPath } from '../core/config.js';
import { layout, pageLayout } from '../router.js';
import { Dialog } from '../components/dialogCore.js';

// ── REST base ─────────────────────────────────────────────────────────

function cbBase() {
    return applicationPath + '/rest/olio/chap-book';
}

// ── API helpers ───────────────────────────────────────────────────────

async function fetchPoems(themeFilter) {
    let url = cbBase() + '/poems';
    if (themeFilter) url += '?theme=' + encodeURIComponent(themeFilter);
    let resp = await fetch(url, { credentials: 'include' });
    if (!resp.ok) throw new Error('Failed to load poems: ' + resp.status);
    return resp.json();
}

async function analyzePoem(poemObjectId) {
    let resp = await fetch(cbBase() + '/analyze/' + poemObjectId, {
        method: 'POST',
        credentials: 'include'
    });
    if (!resp.ok) throw new Error('Analysis failed: ' + resp.status);
    return resp.json();
}

async function createChapBook(slug, title, poemObjectIds, maxLinesPerPage) {
    let body = { slug: slug, title: title, poemObjectIds: poemObjectIds, maxLinesPerPage: maxLinesPerPage || 8 };
    let resp = await fetch(cbBase() + '/create', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Create ChapBook failed: ' + resp.status);
    return resp.json();
}

// TODO(ChapBook Phase 2): set membership for ChapBook creation — select poems by set rather than individual checkbox.
// Set creation and listing are in place (GET /sets, POST /set); add/remove poem endpoints and set-selection UI are deferred.
async function fetchSets() {
    let resp = await fetch(cbBase() + '/sets', { credentials: 'include' });
    if (!resp.ok) throw new Error('Failed to load sets: ' + resp.status);
    return resp.json();
}

async function createPoem(title, author, text) {
    let body = { title, author, text };
    let resp = await fetch(cbBase() + '/poem', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Create poem failed: ' + resp.status);
    return resp.json();
}

// ── renderChapBookPage — landscape page with text overlay ─────────────

/**
 * Render a single ChapBook page: full-bleed landscape image with a poem stanza
 * overlaid on a semi-transparent panel. Follows the same pattern as the PB viewer
 * but tuned for portrait-prose / stanza text.
 *
 * @param {object} scene  — {imageUrl, poemStanza, blurb, title}
 * @param {number} overlayOpacity — 0–1, default 0.4 (background image dimming)
 */
function renderChapBookPage(scene, overlayOpacity) {
    let imageUrl = scene.imageUrl
        || (scene.dataObjectId ? applicationPath + '/rest/resource/data.data/' + scene.dataObjectId : null);
    let stanzaText = scene.poemStanza || scene.blurb || '';
    let poemTitle = scene.title || '';
    let opacity = overlayOpacity != null ? overlayOpacity : 0.4;
    return m('div.relative.overflow-hidden', { style: 'min-height: 70vh' }, [
        imageUrl ? m('img.absolute.inset-0.w-full.h-full.object-cover', {
            src: imageUrl,
            style: 'opacity: ' + opacity
        }) : null,
        m('div.relative.z-10.flex.items-center.justify-center', { style: 'min-height: 70vh' },
            m('div.bg-black.bg-opacity-40.rounded.p-6.max-w-xl.text-center', [
                m('p', { style: 'font-family: Georgia, serif; line-height: 1.9; color: white; white-space: pre-wrap;' }, stanzaText),
                poemTitle ? m('p.text-xs.text-gray-300.mt-4', poemTitle) : null
            ])
        )
    ]);
}

// ── Poem library state ────────────────────────────────────────────────

let poems = [];
let loading = false;
let loadError = null;
let selectedIds = new Set();
let themeFilter = '';
let sortField = 'title';
let sortAsc = true;
let analyzingIds = new Set();

// Create ChapBook dialog state
let showCreateDialog = false;
let createSlug = '';
let createTitle = '';
let createMaxLines = 8;
let creating = false;

// Add Poem form state
let showAddPoemDialog = false;
let addPoemTitle = '';
let addPoemAuthor = '';
let addPoemText = '';
let addingPoem = false;
let addPoemError = null;

// ── Helpers ───────────────────────────────────────────────────────────

function slugify(name) {
    return (name || '').toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .substring(0, 64) || 'chapbook-' + Date.now().toString(36);
}

async function loadPoems() {
    loading = true;
    loadError = null;
    m.redraw();
    try {
        let result = await fetchPoems(themeFilter || null);
        poems = Array.isArray(result) ? result : [];
    } catch (e) {
        loadError = e.message || 'Failed to load';
        poems = [];
    }
    loading = false;
    m.redraw();
}

function sortedPoems() {
    let list = poems.slice();
    list.sort(function (a, b) {
        let av = (a[sortField] || '').toString().toLowerCase();
        let bv = (b[sortField] || '').toString().toLowerCase();
        if (av < bv) return sortAsc ? -1 : 1;
        if (av > bv) return sortAsc ? 1 : -1;
        return 0;
    });
    return list;
}

function filteredPoems() {
    let list = sortedPoems();
    if (themeFilter) {
        let f = themeFilter.toLowerCase();
        list = list.filter(function (p) {
            return (p.theme || '').toLowerCase().indexOf(f) >= 0 ||
                   (p.title || '').toLowerCase().indexOf(f) >= 0;
        });
    }
    return list;
}

function thSort(field) {
    if (sortField === field) {
        sortAsc = !sortAsc;
    } else {
        sortField = field;
        sortAsc = true;
    }
}

function thClass(field) {
    let base = 'px-3 py-2 text-left text-xs font-medium text-gray-500 dark:text-gray-400 uppercase tracking-wide cursor-pointer select-none hover:text-gray-800 dark:hover:text-gray-200';
    if (sortField === field) base += ' text-blue-600 dark:text-blue-400';
    return base;
}

function sortIndicator(field) {
    if (sortField !== field) return null;
    return m('span', { class: 'ml-1 material-symbols-outlined', style: 'font-size:12px;vertical-align:middle' },
        sortAsc ? 'arrow_upward' : 'arrow_downward');
}

// ── Create dialog ─────────────────────────────────────────────────────

function openCreateDialog() {
    // Pre-fill title from first selected poem
    let first = poems.find(function (p) { return selectedIds.has(p.objectId); });
    createTitle = first ? (first.title || '') : '';
    createSlug = slugify(createTitle);
    createMaxLines = 8;
    showCreateDialog = true;
    m.redraw();
}

function closeCreateDialog() {
    showCreateDialog = false;
    m.redraw();
}

function openAddPoemDialog() {
    addPoemTitle = '';
    addPoemAuthor = '';
    addPoemText = '';
    addPoemError = null;
    showAddPoemDialog = true;
    m.redraw();
}

function closeAddPoemDialog() {
    showAddPoemDialog = false;
    m.redraw();
}

async function doAddPoem() {
    if (!addPoemTitle.trim()) {
        addPoemError = 'Title is required';
        m.redraw();
        return;
    }
    if (!addPoemText.trim()) {
        addPoemError = 'Poem text is required';
        m.redraw();
        return;
    }
    addingPoem = true;
    addPoemError = null;
    m.redraw();
    try {
        await createPoem(addPoemTitle.trim(), addPoemAuthor.trim(), addPoemText.trim());
        page.toast('success', 'Poem added: ' + addPoemTitle);
        showAddPoemDialog = false;
        await loadPoems();
    } catch (e) {
        addPoemError = e.message || 'Failed to add poem';
    }
    addingPoem = false;
    m.redraw();
}

async function doCreateChapBook() {
    if (!createSlug || !createTitle) {
        page.toast('warn', 'Slug and title are required');
        return;
    }
    let ids = Array.from(selectedIds);
    if (!ids.length) {
        page.toast('warn', 'Select at least one poem');
        return;
    }
    creating = true;
    m.redraw();
    try {
        let result = await createChapBook(createSlug, createTitle, ids, createMaxLines);
        page.toast('success', 'ChapBook created: ' + (result.slug || createSlug));
        selectedIds = new Set();
        showCreateDialog = false;
    } catch (e) {
        page.toast('error', 'Failed to create: ' + (e.message || ''));
    }
    creating = false;
    m.redraw();
}

// ── PoemLibrary component ─────────────────────────────────────────────

const PoemLibrary = {
    oninit: function () {
        poems = [];
        selectedIds = new Set();
        loading = false;
        loadError = null;
        showCreateDialog = false;
        showAddPoemDialog = false;
        addPoemTitle = '';
        addPoemAuthor = '';
        addPoemText = '';
        addingPoem = false;
        addPoemError = null;
        loadPoems();
    },
    view: function () {
        let list = filteredPoems();
        let allSelected = list.length > 0 && list.every(function (p) { return selectedIds.has(p.objectId); });

        return m('div', { class: 'p-4 max-w-5xl' }, [
            // Header
            m('div', { class: 'flex items-center gap-2 mb-4' }, [
                m('span', { class: 'material-symbols-outlined text-2xl text-purple-500' }, 'menu_book'),
                m('h2', { class: 'text-xl font-semibold dark:text-white' }, 'ChapBook — Poem Library')
            ]),

            // Filter row
            m('div', { class: 'flex flex-wrap items-center gap-3 mb-4' }, [
                m('input', {
                    type: 'text',
                    placeholder: 'Filter by theme or title...',
                    class: 'px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white w-56',
                    value: themeFilter,
                    oninput: function (e) { themeFilter = e.target.value; m.redraw(); }
                }),
                m('button', {
                    class: 'px-3 py-1 rounded bg-gray-100 dark:bg-gray-700 text-sm dark:text-white hover:bg-gray-200 dark:hover:bg-gray-600',
                    onclick: loadPoems
                }, [m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, 'refresh'), ' Refresh']),
                m('button', {
                    class: 'px-3 py-1 rounded bg-green-600 text-white text-sm hover:bg-green-700 flex items-center gap-1',
                    onclick: openAddPoemDialog
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, 'add'),
                    ' Add Poem'
                ]),
                selectedIds.size > 0 ? m('button', {
                    class: 'px-3 py-1 rounded bg-purple-600 text-white text-sm hover:bg-purple-700',
                    onclick: openCreateDialog
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, 'auto_stories'),
                    ' Create ChapBook (' + selectedIds.size + ')'
                ]) : null
            ]),

            // Status
            loading ? m('div', { class: 'text-sm text-gray-500 dark:text-gray-400 py-4' }, 'Loading poems...') :
            loadError ? m('div', { class: 'text-sm text-red-500 py-4' }, 'Error: ' + loadError) :
            poems.length === 0 ? m('div', { class: 'text-sm text-gray-500 dark:text-gray-400 py-4' }, 'No poems found.') :

            // Table
            m('div', { class: 'overflow-x-auto' },
                m('table', { class: 'w-full text-sm' }, [
                    m('thead', m('tr', { class: 'border-b border-gray-200 dark:border-gray-700' }, [
                        m('th', { class: 'px-3 py-2 w-8' },
                            m('input', {
                                type: 'checkbox',
                                checked: allSelected,
                                onchange: function (e) {
                                    if (e.target.checked) {
                                        list.forEach(function (p) { selectedIds.add(p.objectId); });
                                    } else {
                                        selectedIds = new Set();
                                    }
                                    m.redraw();
                                }
                            })
                        ),
                        m('th', { class: thClass('title'), onclick: function () { thSort('title'); m.redraw(); } },
                            ['Title', sortIndicator('title')]),
                        m('th', { class: thClass('author'), onclick: function () { thSort('author'); m.redraw(); } },
                            ['Author', sortIndicator('author')]),
                        m('th', { class: thClass('theme'), onclick: function () { thSort('theme'); m.redraw(); } },
                            ['Theme', sortIndicator('theme')]),
                        m('th', { class: thClass('mood'), onclick: function () { thSort('mood'); m.redraw(); } },
                            ['Mood', sortIndicator('mood')]),
                        m('th', { class: 'px-3 py-2 text-left text-xs font-medium text-gray-500 dark:text-gray-400 uppercase tracking-wide' }, 'Actions')
                    ])),
                    m('tbody',
                        list.map(function (p) {
                            let sel = selectedIds.has(p.objectId);
                            let analyzing = analyzingIds.has(p.objectId);
                            return m('tr', {
                                key: p.objectId,
                                class: 'border-b border-gray-100 dark:border-gray-800 hover:bg-gray-50 dark:hover:bg-gray-800/50 cursor-pointer ' + (sel ? 'bg-purple-50 dark:bg-purple-900/20' : ''),
                                onclick: function () {
                                    if (sel) selectedIds.delete(p.objectId);
                                    else selectedIds.add(p.objectId);
                                    m.redraw();
                                }
                            }, [
                                m('td', { class: 'px-3 py-2', onclick: function (e) { e.stopPropagation(); } },
                                    m('input', {
                                        type: 'checkbox',
                                        checked: sel,
                                        onchange: function () {
                                            if (sel) selectedIds.delete(p.objectId);
                                            else selectedIds.add(p.objectId);
                                            m.redraw();
                                        }
                                    })
                                ),
                                m('td', { class: 'px-3 py-2 font-medium dark:text-white' }, p.title || '—'),
                                m('td', { class: 'px-3 py-2 text-gray-600 dark:text-gray-400' }, p.author || '—'),
                                m('td', { class: 'px-3 py-2 text-gray-600 dark:text-gray-400' }, p.theme || '—'),
                                m('td', { class: 'px-3 py-2 text-gray-600 dark:text-gray-400' }, p.mood || '—'),
                                m('td', { class: 'px-3 py-2', onclick: function (e) { e.stopPropagation(); } },
                                    m('button', {
                                        class: 'px-2 py-1 rounded text-xs bg-gray-100 dark:bg-gray-700 dark:text-white hover:bg-gray-200 dark:hover:bg-gray-600 disabled:opacity-50',
                                        disabled: analyzing,
                                        onclick: async function () {
                                            analyzingIds.add(p.objectId);
                                            m.redraw();
                                            try {
                                                await analyzePoem(p.objectId);
                                                page.toast('success', 'Analysis complete: ' + (p.title || p.objectId));
                                                await loadPoems();
                                            } catch (e) {
                                                page.toast('error', 'Analyze failed: ' + (e.message || ''));
                                            }
                                            analyzingIds.delete(p.objectId);
                                            m.redraw();
                                        }
                                    }, analyzing ? 'Analyzing...' : 'Analyze')
                                )
                            ]);
                        })
                    )
                ])
            ),

            // Add Poem dialog — inline overlay
            showAddPoemDialog ? m('div', {
                class: 'fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50',
                onclick: function (e) { if (e.target === e.currentTarget) closeAddPoemDialog(); }
            },
                m('div', { class: 'bg-white dark:bg-gray-900 rounded-lg shadow-xl p-6 w-full max-w-lg mx-4' }, [
                    m('div', { class: 'flex items-center justify-between mb-4' }, [
                        m('h3', { class: 'text-lg font-semibold dark:text-white' }, 'Add Poem'),
                        m('button', { class: 'text-gray-400 hover:text-gray-600 dark:hover:text-gray-200', onclick: closeAddPoemDialog },
                            m('span', { class: 'material-symbols-outlined' }, 'close'))
                    ]),
                    m('div', { class: 'space-y-3' }, [
                        m('div', [
                            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' }, 'Title *'),
                            m('input', {
                                type: 'text',
                                class: 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white',
                                value: addPoemTitle,
                                oninput: function (e) { addPoemTitle = e.target.value; }
                            })
                        ]),
                        m('div', [
                            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' }, 'Author'),
                            m('input', {
                                type: 'text',
                                class: 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white',
                                value: addPoemAuthor,
                                oninput: function (e) { addPoemAuthor = e.target.value; }
                            })
                        ]),
                        m('div', [
                            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' }, 'Poem Text *'),
                            m('textarea', {
                                rows: 10,
                                class: 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white font-mono resize-y',
                                value: addPoemText,
                                oninput: function (e) { addPoemText = e.target.value; }
                            })
                        ]),
                        addPoemError ? m('div', { class: 'text-sm text-red-500' }, addPoemError) : null
                    ]),
                    m('div', { class: 'flex justify-end gap-2 mt-4' }, [
                        m('button', {
                            class: 'px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 text-sm dark:text-white hover:bg-gray-50 dark:hover:bg-gray-800',
                            onclick: closeAddPoemDialog
                        }, 'Cancel'),
                        m('button', {
                            class: 'px-4 py-1.5 rounded bg-green-600 text-white text-sm hover:bg-green-700 disabled:opacity-50',
                            disabled: addingPoem,
                            onclick: doAddPoem
                        }, addingPoem ? 'Adding...' : 'Add Poem')
                    ])
                ])
            ) : null,

            // Create ChapBook dialog — inline overlay following the Dialog pattern
            showCreateDialog ? m('div', {
                class: 'fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50',
                onclick: function (e) { if (e.target === e.currentTarget) closeCreateDialog(); }
            },
                m('div', { class: 'bg-white dark:bg-gray-900 rounded-lg shadow-xl p-6 w-full max-w-md mx-4' }, [
                    m('div', { class: 'flex items-center justify-between mb-4' }, [
                        m('h3', { class: 'text-lg font-semibold dark:text-white' }, 'Create ChapBook'),
                        m('button', { class: 'text-gray-400 hover:text-gray-600 dark:hover:text-gray-200', onclick: closeCreateDialog },
                            m('span', { class: 'material-symbols-outlined' }, 'close'))
                    ]),
                    m('div', { class: 'space-y-3' }, [
                        m('div', [
                            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' }, 'Title'),
                            m('input', {
                                type: 'text',
                                class: 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white',
                                value: createTitle,
                                oninput: function (e) {
                                    createTitle = e.target.value;
                                    createSlug = slugify(createTitle);
                                }
                            })
                        ]),
                        m('div', [
                            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' }, 'Slug (URL-safe ID)'),
                            m('input', {
                                type: 'text',
                                class: 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white',
                                value: createSlug,
                                oninput: function (e) { createSlug = e.target.value; }
                            })
                        ]),
                        m('div', [
                            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' },
                                'Max Lines per Page'),
                            m('input', {
                                type: 'number',
                                min: 1, max: 32,
                                class: 'w-24 px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white',
                                value: createMaxLines,
                                oninput: function (e) { createMaxLines = parseInt(e.target.value) || 8; }
                            })
                        ]),
                        m('div', { class: 'text-xs text-gray-500 dark:text-gray-400' },
                            selectedIds.size + ' poem(s) selected')
                    ]),
                    m('div', { class: 'flex justify-end gap-2 mt-4' }, [
                        m('button', {
                            class: 'px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 text-sm dark:text-white hover:bg-gray-50 dark:hover:bg-gray-800',
                            onclick: closeCreateDialog
                        }, 'Cancel'),
                        m('button', {
                            class: 'px-4 py-1.5 rounded bg-purple-600 text-white text-sm hover:bg-purple-700 disabled:opacity-50',
                            disabled: creating || !createSlug || !createTitle,
                            onclick: doCreateChapBook
                        }, creating ? 'Creating...' : 'Create')
                    ])
                ])
            ) : null
        ]);
    }
};

// ── ChapBookFeature — top-level route component ───────────────────────

const ChapBookFeature = {
    view: function () {
        return m(PoemLibrary);
    }
};

// ── Routes ────────────────────────────────────────────────────────────

export const routes = {
    '/chap-book': {
        view: function () {
            return layout(pageLayout(m(ChapBookFeature)));
        }
    }
};

export { renderChapBookPage, ChapBookFeature, PoemLibrary };
export default ChapBookFeature;
