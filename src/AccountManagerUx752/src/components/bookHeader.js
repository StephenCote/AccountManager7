/**
 * bookHeader — the one header for a PictureBook / ChapBook in either mode
 * (aiDocs/PictureBookUxConsistencyDesign.md §4.1).
 *
 *   ← library · [series ·] Book name [chapter ▾] [Editing]   [ Read | Edit ]   ● activity pill   … actions
 *
 *   bookHeader({
 *     kind: 'pb' | 'cb', bookObjectId, mode: 'read' | 'edit',
 *     title, series, chapterSelect: vnode, pageLabel,
 *     onMode(mode)          // optional; default routes (guarded) to readRoute / editRoute
 *     actions: [vnode],     // mode actions (Read: export, fullscreen)
 *     hideBack: bool,       // fullscreen hides back, as ReaderShell does
 *     leftNav: [vnode]      // Read: prev / next page controls around the title
 *     rightNav: [vnode]
 *   })
 *
 * Edit mode gets three cues: an accent bottom border, an "Editing" chip, and (from bookWorkspace) a shaded body.
 */
import m from 'mithril';
import { iconBtn } from './buttons.js';
import { segmented } from './bookUi.js';
import { guardedRoute } from './dirtyGuard.js';
import { summaryFor } from '../workflows/bookActivity.js';
import { libraryRoute, readRoute, editRoute } from '../workflows/bookWorkspaceNav.js';

function activityPill(o) {
    let s = summaryFor(o.bookObjectId);
    if (!s) return null;
    let icon = s.running ? 'progress_activity' : (s.failed ? 'error' : 'check_circle');
    let tone = s.running ? 'text-blue-700 dark:text-blue-300 border-blue-300 dark:border-blue-700'
        : s.failed ? 'text-red-700 dark:text-red-300 border-red-300 dark:border-red-700'
            : 'text-green-700 dark:text-green-300 border-green-300 dark:border-green-700';
    return m('button', {
        type: 'button',
        'data-testid': 'book-activity',
        'data-running': s.running ? '1' : '0',
        'data-failed': s.failed ? '1' : '0',
        class: 'flex items-center gap-1 text-xs px-2 py-1 rounded-full border whitespace-nowrap max-w-xs ' + tone,
        title: 'Open the activity in Edit › Render',
        onclick: function () {
            if (o.onActivity) return o.onActivity(s);
            guardedRoute(editRoute(o.kind, o.bookObjectId, s.section || 'render', null, { dock: 1 }));
        }
    }, [
        m('span', { class: 'material-symbols-outlined' + (s.running ? ' animate-spin' : ''), style: 'font-size:14px', 'aria-hidden': 'true' }, icon),
        m('span', { class: 'truncate', 'data-testid': 'book-activity-label' }, s.label),
        s.counter ? m('span', { class: 'text-gray-500 dark:text-gray-400', 'data-testid': 'book-activity-counter' }, s.counter) : null
    ]);
}

export function bookHeader(o) {
    o = o || {};
    let edit = o.mode === 'edit';
    function setMode(mode) {
        if (mode === o.mode) return;
        if (o.onMode) return o.onMode(mode);
        guardedRoute(mode === 'edit' ? editRoute(o.kind, o.bookObjectId) : readRoute(o.kind, o.bookObjectId));
    }
    let libLabel = o.kind === 'cb' ? 'Back to ChapBooks' : 'Back to Picture Books';
    return m('div', {
        'data-testid': 'book-header',
        'data-mode': o.mode,
        'data-book-kind': o.kind,
        class: 'flex flex-wrap items-center gap-3 px-4 py-2 flex-shrink-0 '
            + (edit ? 'border-b-[3px] border-purple-500 bg-white dark:bg-gray-800' : 'border-b border-gray-200 dark:border-gray-700')
    }, [
        o.hideBack ? null : iconBtn('arrow_back', libLabel, function () { guardedRoute(libraryRoute(o.kind)); }, { 'data-testid': 'book-back' }),
        (o.leftNav || []),
        m('div', { class: 'flex items-center gap-2 min-w-0 flex-1' }, [
            m('div', { class: 'min-w-0 flex flex-col' }, [
                m('div', { class: 'flex items-center gap-2 min-w-0' }, [
                    o.series ? m('span', { class: 'text-gray-500 text-xs whitespace-nowrap', 'data-testid': 'book-series' }, o.series + ' ·') : null,
                    m('span', { 'data-testid': 'book-title', 'data-pb2-book-name': true, class: 'font-semibold text-sm truncate' }, o.title || ''),
                    o.pageLabel ? m('span', { class: 'text-gray-400 text-xs whitespace-nowrap', 'data-testid': 'book-page-label' }, o.pageLabel) : null,
                    edit ? m('span', {
                        'data-testid': 'book-editing',
                        class: 'text-[10px] uppercase tracking-wide px-2 py-0.5 rounded-full bg-purple-100 text-purple-800 dark:bg-purple-900/40 dark:text-purple-200'
                    }, 'Editing') : null
                ]),
                o.chapterSelect || null
            ])
        ]),
        (o.rightNav || []),
        o.bookObjectId ? segmented({
            testid: 'book-mode',
            value: o.mode,
            onchange: setMode,
            options: [
                { value: 'read', label: 'Read', icon: 'auto_stories', testid: 'book-mode-read', title: 'Read the book' },
                { value: 'edit', label: 'Edit', icon: 'edit', testid: 'book-mode-edit', title: 'Edit the book' }
            ]
        }) : null,
        activityPill(o),
        (o.actions || [])
    ]);
}

export default bookHeader;
