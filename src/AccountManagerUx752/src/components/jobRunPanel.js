/**
 * jobRunPanel — the one "a background job is running / finished" panel for PictureBook surfaces
 * (PB2 reader run, Locations runs, Compose render + compose-book runs). Replaces four hand-rolled
 * copies that differed only in accent colour (purple / teal / default) and spacing.
 *
 *   jobRunPanel({
 *     testid, terminal, failed,          // failed → red terminal icon
 *     label, counter, counterTestid,
 *     chips:   [vnode],                   // e.g. the chat config the run was opened with
 *     error,                              // run-level error line
 *     onCancel, cancelTestid,             // shown while running
 *     onDismiss, dismissTestid, dismissTitle, dismissWhileRunning,
 *     stages, stageTestid, emptyText,     // deriveStageList() rows: {handle, state, error, autoApproved}
 *     body:    [vnode],                   // extra content below (e.g. per-scene results)
 *     compact: false,                     // smaller padding / text (inline under a card)
 *     terminalAttr: 'digit' | 'bool'      // data-run-terminal as '1'/'0' (default) or 'true'/'false'
 *   })
 */
import m from 'mithril';
import { btn, iconBtn } from './buttons.js';

/** Material icon for a deriveStageList state. */
function stageIcon(state) {
    switch (state) {
        case 'running': return 'progress_activity';
        case 'done': return 'check_circle';
        case 'failed': return 'error';
        case 'skipped': return 'push_pin';
        case 'unrun': return 'remove_circle_outline';
        default: return 'radio_button_unchecked';
    }
}

function stageClass(state) {
    if (state === 'failed') return 'text-red-600 dark:text-red-400';
    if (state === 'running') return 'text-blue-600 dark:text-blue-400';
    if (state === 'done') return 'text-green-700 dark:text-green-400';
    return 'text-gray-500 dark:text-gray-400';
}

function statusIcon(terminal, failed) {
    if (!terminal) return m('span', { class: 'material-symbols-outlined text-base text-blue-500 animate-spin', 'aria-hidden': 'true' }, 'progress_activity');
    return m('span', { class: 'material-symbols-outlined text-base ' + (failed ? 'text-red-600' : 'text-green-600'), 'aria-hidden': 'true' }, failed ? 'error' : 'task_alt');
}

function jobRunPanel(o) {
    o = o || {};
    let terminal = !!o.terminal;
    let attrVal = o.terminalAttr === 'bool' ? (terminal ? 'true' : 'false') : (terminal ? '1' : '0');
    let showDismiss = o.onDismiss && (terminal || o.dismissWhileRunning || o.error);
    return m('div', {
        'data-testid': o.testid,
        'data-run-terminal': attrVal,
        'data-job-run': '1',
        role: 'status',
        class: (o.compact ? 'mt-2 p-2 text-xs' : 'mb-3 p-3 text-sm') + ' rounded border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900'
    }, [
        m('div', { class: 'flex items-center gap-2 flex-wrap' + (o.stages || o.error || o.body ? ' mb-1' : '') }, [
            statusIcon(terminal, !!o.failed),
            m('span', { class: 'font-semibold' }, o.label),
            o.counter != null ? m('span', { 'data-testid': o.counterTestid, class: 'text-xs text-gray-500 dark:text-gray-400' }, o.counter) : null,
            (o.chips || []),
            m('span', { class: 'flex-1' }),
            !terminal && o.onCancel ? btn({ label: 'Cancel', size: 'sm', onclick: o.onCancel, attrs: { 'data-testid': o.cancelTestid } }) : null,
            showDismiss ? iconBtn('close', o.dismissTitle || 'Dismiss', o.onDismiss, { 'data-testid': o.dismissTestid }) : null
        ]),
        o.error ? m('div', { class: 'text-xs text-red-600 dark:text-red-400 mb-1' }, o.error) : null,
        o.stages ? (o.stages.length ? m('ul', { class: 'text-xs space-y-0.5' }, o.stages.map(function (s) {
            return m('li', {
                key: s.handle,
                'data-testid': o.stageTestid,
                'data-stage-state': s.state,
                class: 'flex items-center gap-1 ' + stageClass(s.state)
            }, [
                m('span', { class: 'material-symbols-outlined text-sm' + (s.state === 'running' ? ' animate-spin' : ''), 'aria-hidden': 'true' }, stageIcon(s.state)),
                m('span', { class: 'font-mono' }, s.handle),
                s.error ? m('span', { class: 'ml-1 truncate' }, '— ' + s.error) : null,
                s.autoApproved ? m('span', { class: 'ml-1 text-gray-400' }, 'auto-approved') : null
            ]);
        })) : m('div', { class: 'text-xs text-gray-500' }, o.emptyText || 'Nothing planned.')) : null,
        (o.body || [])
    ]);
}

/**
 * The shared background-activity strip for LLMConnector.bgActivity ({label, icon}) — used by the
 * PictureBook wizard and ChapBook (which had byte-for-byte copies). o = { bg, onCancel, cancelling, class }.
 */
function bgActivityBanner(o) {
    o = o || {};
    let bg = o.bg;
    if (!bg || !bg.label) return null;
    return m('div', {
        'data-bg-activity': '1', role: 'status',
        class: 'flex items-center gap-2 px-4 py-2 text-sm text-blue-600 dark:text-blue-400 bg-blue-50 dark:bg-blue-900/20 rounded mb-2' + (o.class ? ' ' + o.class : '')
    }, [
        m('span', { class: 'material-symbols-outlined text-base animate-spin', 'aria-hidden': 'true' }, bg.icon || 'progress_activity'),
        m('span', { class: 'flex-1' }, bg.label),
        o.onCancel ? btn({ label: o.cancelling ? 'Cancelling...' : 'Cancel', size: 'sm', disabled: !!o.cancelling, onclick: o.onCancel }) : null
    ]);
}

export { jobRunPanel, stageIcon, bgActivityBanner };
export default jobRunPanel;
