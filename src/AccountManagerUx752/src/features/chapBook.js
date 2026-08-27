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
import { ObjectPicker } from '../components/picker.js';
import { bookPages } from '../workflows/pictureBookWorkflow.js';

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

async function renderChapBook(bookObjectId) {
    let resp = await fetch(cbBase() + '/render/' + bookObjectId, {
        method: 'POST',
        credentials: 'include'
    });
    if (!resp.ok) throw new Error('Render failed: ' + resp.status);
    return resp.json();
}

async function importPoemsFromSources(sources) {
    let body = { sources: sources };
    let resp = await fetch(cbBase() + '/poems', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Import failed: ' + resp.status);
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
    // Images are served by MediaServlet at /media/{orgDotPath}/data.data{groupPath}/{name}
    // (the canonical path-based route — there is no objectId-based /rest/resource route).
    // bookPageView supplies imageGroupPath + imageName for exactly this.
    let imageUrl = scene.imageUrl
        || ((scene.imageGroupPath && scene.imageName)
            ? applicationPath + '/media/' + am7client.dotPath(am7client.currentOrganization)
                + '/data.data' + scene.imageGroupPath + '/' + scene.imageName
            : null);
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

// Add Poems from Notes — multi-select + order before import
let addingPoem = false;
let pendingNotes = [];       // [{objectId, name}] — notes selected, awaiting order confirmation
let showNoteOrderDialog = false;

// Add Poem (direct text entry) dialog state
let showAddPoemDialog = false;
let addPoemTitle = '';
let addPoemAuthor = '';
let addPoemText = '';

// Render state
let renderingBook = false;
let lastRenderResult = null;
let lastCreatedBook = null;

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

function openSourcePicker(sourceType) {
    let isData = sourceType === 'data.data';
    ObjectPicker.open({
        type: sourceType,
        title: 'Select ' + (isData ? 'data objects' : 'notes') + ' to import as poems (use checkboxes, then ✓)',
        multiSelect: true,
        onSelect: function (items) {
            if (!items || !items.length) return;
            pendingNotes = items.map(function (it) {
                return { objectId: it.objectId, name: it.name || 'Untitled', sourceType: sourceType };
            });
            showNoteOrderDialog = true;
            m.redraw();
        }
    });
}

function moveNoteUp(idx) {
    if (idx <= 0) return;
    let tmp = pendingNotes[idx - 1];
    pendingNotes[idx - 1] = pendingNotes[idx];
    pendingNotes[idx] = tmp;
    m.redraw();
}

function moveNoteDown(idx) {
    if (idx >= pendingNotes.length - 1) return;
    let tmp = pendingNotes[idx + 1];
    pendingNotes[idx + 1] = pendingNotes[idx];
    pendingNotes[idx] = tmp;
    m.redraw();
}

function removeNote(idx) {
    pendingNotes.splice(idx, 1);
    if (!pendingNotes.length) showNoteOrderDialog = false;
    m.redraw();
}

async function doImportNotes() {
    if (!pendingNotes.length) return;
    addingPoem = true;
    showNoteOrderDialog = false;
    m.redraw();
    let sources = pendingNotes.map(function (n) {
        return { type: n.sourceType, objectId: n.objectId, title: n.name };
    });
    let toImport = pendingNotes.length;
    pendingNotes = [];
    try {
        let result = await importPoemsFromSources(sources);
        let imported = (result.poems || []).length;
        if (imported) {
            page.toast('success', 'Imported ' + imported + ' of ' + toImport + ' poem(s)');
            await loadPoems();
            // Auto-select the newly imported poems so "Create ChapBook" button appears immediately.
            let newIds = (result.poems || []).map(function (p) { return p.objectId; }).filter(Boolean);
            newIds.forEach(function (id) { selectedIds.add(id); });
        } else if (!result.errors || !result.errors.length) {
            page.toast('warn', 'No poems were imported');
        }
        if (result.errors && result.errors.length) {
            page.toast('error', result.errors.join('; '));
        }
    } catch (e) {
        page.toast('error', 'Import failed: ' + (e.message || ''));
    }
    addingPoem = false;
    m.redraw();
}

async function doAddPoem() {
    if (!addPoemTitle.trim()) {
        page.toast('warn', 'Title is required');
        return;
    }
    if (!addPoemText.trim()) {
        page.toast('warn', 'Poem text is required');
        return;
    }
    addingPoem = true;
    m.redraw();
    try {
        await createPoem(addPoemTitle.trim(), addPoemAuthor.trim() || null, addPoemText.trim());
        page.toast('success', 'Poem added: ' + addPoemTitle.trim());
        showAddPoemDialog = false;
        addPoemTitle = '';
        addPoemAuthor = '';
        addPoemText = '';
        await loadPoems();
    } catch (e) {
        page.toast('error', 'Failed to add poem: ' + (e.message || ''));
    }
    addingPoem = false;
    m.redraw();
}

async function renderBook(bookObjectId) {
    renderingBook = true;
    lastRenderResult = null;
    m.redraw();
    try {
        let result = await renderChapBook(bookObjectId);
        lastRenderResult = result;
        page.toast('success', 'Render complete: ' + (result.rendered || 0) + ' scene(s) generated');
    } catch (e) {
        page.toast('error', 'Render failed: ' + (e.message || ''));
    }
    renderingBook = false;
    m.redraw();
}

function renderNoteOrderDialog() {
    if (!showNoteOrderDialog || !pendingNotes.length) return null;
    return m('div', {
        class: 'fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50',
        onclick: function (e) { if (e.target === e.currentTarget) { showNoteOrderDialog = false; m.redraw(); } }
    },
        m('div', { class: 'bg-white dark:bg-gray-900 rounded-lg shadow-xl p-6 w-full max-w-lg mx-4' }, [
            m('div', { class: 'flex items-center justify-between mb-4' }, [
                m('h3', { class: 'text-lg font-semibold dark:text-white' }, 'Set poem order (' + pendingNotes.length + ' notes)'),
                m('button', {
                    class: 'text-gray-400 hover:text-gray-600 dark:hover:text-gray-200',
                    onclick: function () { showNoteOrderDialog = false; pendingNotes = []; m.redraw(); }
                }, m('span', { class: 'material-symbols-outlined' }, 'close'))
            ]),
            m('p', { class: 'text-xs text-gray-500 dark:text-gray-400 mb-3' },
                'Use ↑ / ↓ to reorder. Poems will be imported in this sequence.'),
            m('ol', { class: 'space-y-1 mb-4 max-h-72 overflow-y-auto' },
                pendingNotes.map(function (note, idx) {
                    return m('li', { key: note.objectId, class: 'flex items-center gap-2 px-2 py-1 rounded bg-gray-50 dark:bg-gray-800' }, [
                        m('span', { class: 'text-xs text-gray-400 w-6 text-right flex-shrink-0' }, (idx + 1) + '.'),
                        m('span', { class: 'flex-1 text-sm dark:text-white truncate', title: note.name }, note.name),
                        m('button', {
                            class: 'text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 disabled:opacity-30',
                            title: 'Move up',
                            disabled: idx === 0,
                            onclick: function (e) { e.stopPropagation(); moveNoteUp(idx); }
                        }, m('span', { class: 'material-symbols-outlined', style: 'font-size:18px' }, 'arrow_upward')),
                        m('button', {
                            class: 'text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 disabled:opacity-30',
                            title: 'Move down',
                            disabled: idx === pendingNotes.length - 1,
                            onclick: function (e) { e.stopPropagation(); moveNoteDown(idx); }
                        }, m('span', { class: 'material-symbols-outlined', style: 'font-size:18px' }, 'arrow_downward')),
                        m('button', {
                            class: 'text-red-400 hover:text-red-600 ml-1',
                            title: 'Remove',
                            onclick: function (e) { e.stopPropagation(); removeNote(idx); }
                        }, m('span', { class: 'material-symbols-outlined', style: 'font-size:18px' }, 'close'))
                    ]);
                })
            ),
            m('div', { class: 'flex justify-end gap-2' }, [
                m('button', {
                    class: 'px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 text-sm dark:text-white hover:bg-gray-50 dark:hover:bg-gray-800',
                    onclick: function () { showNoteOrderDialog = false; pendingNotes = []; m.redraw(); }
                }, 'Cancel'),
                m('button', {
                    class: 'px-4 py-1.5 rounded bg-green-600 text-white text-sm hover:bg-green-700',
                    onclick: doImportNotes
                }, 'Import ' + pendingNotes.length + ' poem(s) in this order')
            ])
        ])
    );
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
        lastCreatedBook = result;
        page.toast('success', 'ChapBook created: ' + (result.slug || createSlug));
        // Carry the source poem objectIds forward: analyze is per-poem and the book does not
        // retain poem references, so the reader's Analyze button needs the ids from this step.
        readerPoemIds = ids.slice();
        selectedIds = new Set();
        showCreateDialog = false;
        let navObjectId = result.objectId || result.bookObjectId;
        if (navObjectId) {
            m.route.set('/chap-book/read/' + navObjectId);
        }
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
        addingPoem = false;
        pendingNotes = [];
        showNoteOrderDialog = false;
        showCreateDialog = false;
        showAddPoemDialog = false;
        addPoemTitle = '';
        addPoemAuthor = '';
        addPoemText = '';
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
                    class: 'px-3 py-1 rounded bg-green-600 text-white text-sm hover:bg-green-700 flex items-center gap-1 disabled:opacity-50',
                    onclick: function () { openSourcePicker('data.note'); },
                    disabled: addingPoem
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, addingPoem ? 'hourglass_empty' : 'note_add'),
                    addingPoem ? ' Importing...' : ' Add from Note'
                ]),
                m('button', {
                    class: 'px-3 py-1 rounded bg-teal-600 text-white text-sm hover:bg-teal-700 flex items-center gap-1 disabled:opacity-50',
                    onclick: function () { openSourcePicker('data.data'); },
                    disabled: addingPoem
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, 'description'),
                    ' Add from Data'
                ]),
                m('button', {
                    class: 'px-3 py-1 rounded bg-indigo-600 text-white text-sm hover:bg-indigo-700 flex items-center gap-1',
                    onclick: function () { showAddPoemDialog = true; addPoemTitle = ''; addPoemAuthor = ''; addPoemText = ''; m.redraw(); }
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, 'add'),
                    ' New Poem'
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

            // Note order dialog — shown after multi-select pick, before import.
            renderNoteOrderDialog(),

            // ObjectPicker renders itself as a portal — no inline dialog needed here.

            // Last created book — render button available when a book was just created
            lastCreatedBook ? m('div', { class: 'mt-4 p-3 rounded bg-purple-50 dark:bg-purple-900/20 border border-purple-200 dark:border-purple-800 flex items-center gap-3' }, [
                m('span', { class: 'material-symbols-outlined text-purple-500' }, 'auto_stories'),
                m('span', { class: 'flex-1 text-sm dark:text-white' }, 'ChapBook created: ' + (lastCreatedBook.slug || lastCreatedBook.objectId || '')),
                m('button', {
                    class: 'px-3 py-1 rounded bg-orange-600 text-white text-sm hover:bg-orange-700 flex items-center gap-1 disabled:opacity-50',
                    disabled: renderingBook,
                    onclick: function () { renderBook(lastCreatedBook.objectId || lastCreatedBook.bookObjectId); }
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, renderingBook ? 'hourglass_empty' : 'image'),
                    renderingBook ? ' Rendering...' : ' Render'
                ])
            ]) : null,

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
            ) : null,

            // Add Poem (direct text entry) dialog
            showAddPoemDialog ? m('div', {
                class: 'fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50',
                onclick: function (e) { if (e.target === e.currentTarget) { showAddPoemDialog = false; m.redraw(); } }
            },
                m('div', { class: 'bg-white dark:bg-gray-900 rounded-lg shadow-xl p-6 w-full max-w-lg mx-4' }, [
                    m('div', { class: 'flex items-center justify-between mb-4' }, [
                        m('h3', { class: 'text-lg font-semibold dark:text-white' }, 'New Poem'),
                        m('button', { class: 'text-gray-400 hover:text-gray-600 dark:hover:text-gray-200', onclick: function () { showAddPoemDialog = false; m.redraw(); } },
                            m('span', { class: 'material-symbols-outlined' }, 'close'))
                    ]),
                    m('div', { class: 'space-y-3' }, [
                        m('div', [
                            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' }, 'Title'),
                            m('input', {
                                type: 'text',
                                class: 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white',
                                value: addPoemTitle,
                                oninput: function (e) { addPoemTitle = e.target.value; }
                            })
                        ]),
                        m('div', [
                            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' }, 'Author (optional)'),
                            m('input', {
                                type: 'text',
                                class: 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white',
                                value: addPoemAuthor,
                                oninput: function (e) { addPoemAuthor = e.target.value; }
                            })
                        ]),
                        m('div', [
                            m('label', { class: 'block text-xs font-medium text-gray-500 dark:text-gray-400 mb-0.5' }, 'Poem text'),
                            m('textarea', {
                                rows: 10,
                                class: 'w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm dark:text-white font-mono',
                                value: addPoemText,
                                oninput: function (e) { addPoemText = e.target.value; }
                            })
                        ])
                    ]),
                    m('div', { class: 'flex justify-end gap-2 mt-4' }, [
                        m('button', {
                            class: 'px-3 py-1.5 rounded border border-gray-300 dark:border-gray-600 text-sm dark:text-white hover:bg-gray-50 dark:hover:bg-gray-800',
                            onclick: function () { showAddPoemDialog = false; m.redraw(); }
                        }, 'Cancel'),
                        m('button', {
                            class: 'px-4 py-1.5 rounded bg-indigo-600 text-white text-sm hover:bg-indigo-700 disabled:opacity-50',
                            disabled: addingPoem || !addPoemTitle.trim() || !addPoemText.trim(),
                            onclick: doAddPoem
                        }, addingPoem ? 'Adding...' : 'Add Poem')
                    ])
                ])
            ) : null
        ]);
    }
};

// ── ChapBook reader state ─────────────────────────────────────────────

let readerBookObjectId = null;
let readerBook = null;
let readerPages = [];
let readerLoading = false;
let readerError = null;
let readerAnalyzing = false;
let readerRendering = false;
// Poem objectIds carried from the create flow. Analyze is a PER-POEM endpoint and the
// olio.pb.book does not retain references to its source poems (they become olio.pb.scene
// stanza chunks), so the only way to iterate "the book's poems" is to remember them here.
let readerPoemIds = [];

async function loadReaderBook(bookObjectId) {
    readerLoading = true;
    readerError = null;
    m.redraw();
    try {
        readerBook = await am7client.getFull('olio.pb.book', bookObjectId) || null;
        // bookPages returns [{objectId, sceneIndex, title, blurb, summary, poemStanza, dataObjectId}, ...]
        // — poemStanza is visible immediately (6D) and dataObjectId is the render fallback image.
        let pages = await bookPages(bookObjectId);
        readerPages = Array.isArray(pages) ? pages : [];
    } catch (e) {
        readerError = e.message || 'Failed to load book';
        readerPages = [];
    }
    readerLoading = false;
    m.redraw();
}

async function analyzeReaderPoems() {
    if (!readerPoemIds.length) {
        page.toast('warn', 'No poems are associated with this session — analyze poems from the Poem Library, then create the ChapBook.');
        return;
    }
    readerAnalyzing = true;
    m.redraw();
    let ok = 0, fail = 0;
    for (let pid of readerPoemIds) {
        try {
            await analyzePoem(pid);
            ok++;
        } catch (e) {
            fail++;
        }
    }
    readerAnalyzing = false;
    if (fail) page.toast('warn', 'Analyzed ' + ok + ' poem(s); ' + fail + ' failed');
    else page.toast('success', 'Analyzed ' + ok + ' poem(s)');
    m.redraw();
}

async function renderReaderBook() {
    if (!readerBookObjectId) return;
    readerRendering = true;
    m.redraw();
    try {
        let result = await renderChapBook(readerBookObjectId);
        page.toast('success', 'Render complete: ' + (result.rendered || 0) + ' scene(s) generated');
        // Reload so the freshly-generated images (dataObjectId) appear (6C).
        await loadReaderBook(readerBookObjectId);
    } catch (e) {
        page.toast('error', 'Render failed: ' + (e.message || ''));
    }
    readerRendering = false;
    m.redraw();
}

// ── ChapBookReader component — dedicated poem-book reader (6B/6C/6D) ──

const ChapBookReader = {
    oninit: function (vnode) {
        readerBookObjectId = vnode.attrs.bookObjectId || null;
        readerBook = null;
        readerPages = [];
        readerError = null;
        readerAnalyzing = false;
        readerRendering = false;
        if (readerBookObjectId) loadReaderBook(readerBookObjectId);
    },
    view: function () {
        let title = (readerBook && (readerBook.name || readerBook.slug)) || 'ChapBook';
        let busy = readerAnalyzing || readerRendering;
        return m('div', { class: 'p-4 max-w-4xl mx-auto' }, [
            // Header — back, title, Analyze + Render controls
            m('div', { class: 'flex flex-wrap items-center gap-3 mb-6' }, [
                m('button', {
                    class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300',
                    title: 'Back to Poem Library',
                    onclick: function () { m.route.set('/chap-book'); }
                }, m('span', { class: 'material-symbols-outlined' }, 'arrow_back')),
                m('span', { class: 'material-symbols-outlined text-2xl text-purple-500' }, 'menu_book'),
                m('h2', { class: 'flex-1 text-xl font-semibold dark:text-white truncate', title: title }, title),
                m('button', {
                    class: 'px-3 py-1.5 rounded bg-blue-600 text-white text-sm hover:bg-blue-700 flex items-center gap-1 disabled:opacity-50',
                    disabled: busy,
                    onclick: analyzeReaderPoems
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, readerAnalyzing ? 'hourglass_empty' : 'psychology'),
                    readerAnalyzing ? ' Analyzing...' : ' Analyze'
                ]),
                m('button', {
                    class: 'px-3 py-1.5 rounded bg-orange-600 text-white text-sm hover:bg-orange-700 flex items-center gap-1 disabled:opacity-50',
                    disabled: busy,
                    onclick: renderReaderBook
                }, [
                    m('span', { class: 'material-symbols-outlined', style: 'font-size:16px;vertical-align:middle' }, readerRendering ? 'hourglass_empty' : 'image'),
                    readerRendering ? ' Rendering...' : ' Render'
                ])
            ]),

            // Body — status or pages
            readerLoading ? m('div', { class: 'text-sm text-gray-500 dark:text-gray-400 py-12 text-center' }, 'Loading book...') :
            readerError ? m('div', { class: 'text-sm text-red-500 py-12 text-center' }, 'Error: ' + readerError) :
            readerPages.length === 0 ? m('div', { class: 'text-center py-12' }, [
                m('span', { class: 'material-symbols-outlined text-5xl text-gray-300 mb-4' }, 'auto_stories'),
                m('div', { class: 'text-sm text-gray-500 dark:text-gray-400' }, 'No pages in this book yet.')
            ]) :

            m('div', { class: 'space-y-6' },
                readerPages.map(function (pg, idx) {
                    return m('div', { key: pg.objectId || idx, class: 'rounded-lg overflow-hidden border border-gray-200 dark:border-gray-700' }, [
                        renderChapBookPage(pg),
                        m('div', { class: 'px-3 py-1.5 text-xs text-gray-400 text-center bg-gray-50 dark:bg-gray-800/50' },
                            'Page ' + (idx + 1) + ' of ' + readerPages.length)
                    ]);
                })
            )
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
    },
    '/chap-book/read/:bookObjectId': {
        view: function (vnode) {
            return layout(pageLayout(m(ChapBookReader, { bookObjectId: vnode.attrs.bookObjectId })));
        }
    }
};

export { renderChapBookPage, ChapBookFeature, ChapBookReader, PoemLibrary };
export default ChapBookFeature;
