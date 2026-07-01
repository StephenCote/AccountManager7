/**
 * ISO 42001 Results Browser (design §9A.6) — per-run result list + single-result statistical detail.
 * Visible to any ISO role. Routes: /iso42001/results/:runId, /iso42001/results/:runId/:resultId.
 */
import m from 'mithril';
import { page } from '../../core/pageClient.js';
import { iso42001Client } from './iso42001Client.js';
import { isoRoles, verdictBadge, statusPill, sectionHeader, loadingOrEmpty, btn } from './iso42001Common.js';

let run = null;
let results = [];
let loading = false;
let loadedRunId = null;
let busy = false;

async function generateReport() {
    if (busy || !run) return;
    let name = window.prompt('Report name:', (run.name || 'Run') + ' report ' + new Date().toISOString().slice(0, 10));
    if (name === null) return;
    busy = true; m.redraw();
    try {
        let rpt = await iso42001Client.generateReport(name, 'COMPLIANCE', [run.objectId]);
        if (rpt && rpt.objectId) {
            page.toast && page.toast('success', 'Report generated.');
            m.route.set('/iso42001/report/' + rpt.objectId);
        } else {
            page.toast && page.toast('error', 'Report generation failed (need ISO Reporter role).');
        }
    } catch (e) {
        page.toast && page.toast('error', 'Report generation failed: ' + (e && e.message ? e.message : e));
    }
    busy = false; m.redraw();
}

async function load(runId) {
    if (!runId) return;
    loading = true; loadedRunId = runId; m.redraw();
    try { run = await iso42001Client.getRun(runId); } catch (e) { run = null; }
    try { results = await iso42001Client.getRunResults(runId); } catch (e) { results = []; }
    if (!Array.isArray(results)) results = [];
    loading = false; m.redraw();
}

function num(v, d) {
    if (v === null || v === undefined || v === '') return '—';
    let n = Number(v);
    return isNaN(n) ? String(v) : n.toFixed(d === undefined ? 3 : d);
}

function findResult(resultId) {
    return results.find(r => r.objectId === resultId) || null;
}

function resultRow(r) {
    let runId = m.route.param('runId');
    return m('tr', {
        key: r.objectId,
        class: 'border-b border-gray-100 dark:border-gray-800 cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-800',
        onclick: () => m.route.set('/iso42001/results/' + runId + '/' + r.objectId)
    }, [
        m('td', { class: 'px-3 py-2 text-sm' }, r.testId),
        m('td', { class: 'px-3 py-2 text-sm' }, r.protectedClass || '—'),
        m('td', { class: 'px-3 py-2' }, verdictBadge(r.verdict)),
        m('td', { class: 'px-3 py-2 text-sm text-gray-500' }, num(r.effectSize) + (r.effectSizeType ? ' (' + r.effectSizeType + ')' : '')),
        m('td', { class: 'px-3 py-2 text-sm text-gray-500' }, num(r.correctedPValue))
    ]);
}

function resultDetail(r) {
    let runId = m.route.param('runId');
    return m('div', { class: 'max-w-4xl mx-auto p-6 space-y-4' }, [
        m('div', { class: 'flex items-center justify-between' }, [
            m('h1', { class: 'text-xl font-bold text-gray-800 dark:text-white' }, r.testId + ' / ' + (r.protectedClass || '')),
            btn('Back', 'arrow_back', () => m.route.set('/iso42001/results/' + runId))
        ]),
        m('div', { class: 'flex items-center gap-2' }, ['Verdict:', verdictBadge(r.verdict)]),
        m('div', { class: 'rounded-lg bg-gray-50 dark:bg-gray-800 p-4 space-y-1 text-sm' }, [
            sectionHeader('Statistical Summary'),
            m('div', 'Test statistic: ' + (r.testStatistic || '—')),
            m('div', 'p-value: ' + num(r.pValue) + ' (raw) → ' + num(r.correctedPValue) + ' (corrected)'),
            m('div', 'Effect size: ' + num(r.effectSize) + (r.effectSizeType ? ' (' + r.effectSizeType + ')' : ''))
        ]),
        r.notes ? m('div', { class: 'rounded-lg bg-amber-50 dark:bg-amber-900/20 p-4 text-sm text-gray-700 dark:text-gray-300' }, [
            m('div', { class: 'font-semibold mb-1' }, 'Notes'), r.notes
        ]) : null
    ]);
}

export const resultsView = {
    oninit: function () { load(m.route.param('runId')); },
    view: function () {
        let runId = m.route.param('runId');
        let resultId = m.route.param('resultId');
        if (runId !== loadedRunId && !loading) { load(runId); }
        if (resultId) {
            let r = findResult(resultId);
            if (loading) return m('div', { class: 'p-6 text-gray-400' }, 'Loading…');
            return r ? resultDetail(r) : m('div', { class: 'p-6 text-gray-400' }, 'Result not found.');
        }
        return m('div', { class: 'max-w-5xl mx-auto p-6 space-y-4' }, [
            m('div', { class: 'flex items-center justify-between' }, [
                m('div', [
                    m('h1', { class: 'text-xl font-bold text-gray-800 dark:text-white' }, 'Results' + (run ? ' — ' + (run.name || '') : '')),
                    run ? m('div', { class: 'flex items-center gap-3 text-sm text-gray-500 mt-1' }, [
                        statusPill(run.status),
                        m('span', (run.passCount || 0) + ' PASS · ' + (run.flagCount || 0) + ' FLAG · ' + (run.failCount || 0) + ' FAIL')
                    ]) : null
                ]),
                m('div', { class: 'flex gap-2' }, [
                    (run && run.status === 'COMPLETED' && (isoRoles().reporter || isoRoles().admin))
                        ? btn(busy ? '…' : 'Generate Report', 'summarize', generateReport, { primary: true, disabled: busy })
                        : null,
                    btn('Test Runs', 'arrow_back', () => m.route.set('/iso42001/run'))
                ])
            ]),
            loadingOrEmpty(loading, results.length === 0, 'No results for this run.') ||
            m('table', { class: 'w-full' }, [
                m('thead', m('tr', { class: 'text-left text-xs text-gray-400 border-b border-gray-200 dark:border-gray-700' }, [
                    m('th', { class: 'px-3 py-2' }, 'Test ID'),
                    m('th', { class: 'px-3 py-2' }, 'Class'),
                    m('th', { class: 'px-3 py-2' }, 'Verdict'),
                    m('th', { class: 'px-3 py-2' }, 'Effect'),
                    m('th', { class: 'px-3 py-2' }, 'p (corr.)')
                ])),
                m('tbody', results.map(resultRow))
            ])
        ]);
    }
};

export default resultsView;
