/**
 * PbPlanInspector — read-only view of a V3 illustration plan (design §26, P2-9).
 *
 * Renders exactly what the server returned from POST /{book}/scene/{s}/plan or what a renderPlan
 * artifact carries in artifactText: strategy, preset, canvas, per-task model resolution, compiled
 * prompts per stage, degradations, configNotApplied codes, the estimate and the content hash. Nothing
 * here is derived client-side beyond formatting; codes and reasons are shown as received.
 *
 * Usage: m(PbPlanInspector, { plan: normalizePlan(body) })
 */
import m from 'mithril';
import { normalizePlan } from '../workflows/pictureBookWorkflow.js';

/** "N scenes · S stages · V variants · K steps" plus the time when measured, else the estimate source verbatim. Pure. */
export function estimateText(estimate) {
    let e = estimate && typeof estimate === 'object' ? estimate : {};
    let parts = [];
    if (e.scenes != null) parts.push(e.scenes + (e.scenes === 1 ? ' scene' : ' scenes'));
    if (e.stages != null) parts.push(e.stages + (e.stages === 1 ? ' stage' : ' stages'));
    if (e.variants != null) parts.push(e.variants + (e.variants === 1 ? ' variant' : ' variants'));
    if (e.steps != null) parts.push(e.steps + ' steps');
    let head = parts.join(' · ');
    if (e.measured && e.seconds != null) {
        let secs = Number(e.seconds);
        let time = secs >= 90 ? (Math.round(secs / 6) / 10) + ' min' : Math.round(secs) + ' s';
        let per = e.secondsPerStep != null ? ' (' + Number(e.secondsPerStep).toFixed(2) + ' s/step)' : '';
        return (head ? head + ' · ' : '') + '~' + time + per;
    }
    let src = e.source != null ? String(e.source) : '';
    return head && src ? head + ' · ' + src : (head || src || 'No estimate');
}

/** Rows for the per-task model table, keyed by the plan's own task names. Pure. */
export function modelRows(models) {
    let mm = models && typeof models === 'object' ? models : {};
    return Object.keys(mm).map(function (task) {
        let r = mm[task] && typeof mm[task] === 'object' ? mm[task] : {};
        return {
            task: r.task || task,
            model: r.model || null,
            reason: r.reason != null ? String(r.reason) : '',
            degraded: !!r.degraded,
            degradationText: r.degradationText || null
        };
    });
}

function label(text) {
    return m('div', { class: 'text-[11px] font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400 mt-3 mb-1' }, text);
}

function kv(k, v) {
    return m('div', { class: 'flex gap-2 text-xs' }, [
        m('span', { class: 'text-gray-500 dark:text-gray-400 w-28 shrink-0' }, k),
        m('span', { class: 'text-gray-800 dark:text-gray-200 break-all' }, v == null || v === '' ? '—' : String(v))
    ]);
}

const PbPlanInspector = {
    view: function (vnode) {
        let plan = normalizePlan(vnode.attrs.plan);
        let strategy = plan.strategy;
        let preset = plan.preset;
        let canvas = plan.canvas;
        let rows = modelRows(plan.models);
        let stages = Object.keys(plan.compiled);
        return m('div', { 'data-testid': 'pb2-plan-inspector', class: 'text-sm' }, [
            m('div', { class: 'flex flex-wrap items-center gap-2' }, [
                m('span', { 'data-testid': 'pb2-plan-strategy', class: 'px-2 py-0.5 rounded-full text-xs bg-purple-100 text-purple-800 dark:bg-purple-900/40 dark:text-purple-200' },
                    strategy.name || 'strategy ?'),
                strategy.stages.length ? m('span', { class: 'text-xs text-gray-500' }, strategy.stages.join(' → ')) : null,
                plan.planVersion ? m('span', { class: 'text-xs text-gray-400' }, plan.planVersion) : null,
                plan.purpose ? m('span', { class: 'text-xs text-gray-400' }, plan.purpose) : null
            ]),
            strategy.reason ? m('div', { class: 'text-xs text-gray-600 dark:text-gray-300 mt-1' }, strategy.reason) : null,

            label('Estimate'),
            m('div', { 'data-testid': 'pb2-plan-estimate', class: 'text-xs text-gray-800 dark:text-gray-200' }, estimateText(plan.estimate)),

            label('Preset & canvas'),
            kv('Preset', preset.preset),
            kv('Steps', preset.steps),
            kv('Variants', preset.variants),
            kv('Upscale', preset.upscale == null ? null : (preset.upscale ? 'yes' : 'no')),
            kv('QA', preset.qa),
            kv('Canvas', canvas.width != null && canvas.height != null ? canvas.width + ' × ' + canvas.height : null),
            kv('Model (config)', plan.config.model),
            kv('Sampler', plan.config.sampler != null ? plan.config.sampler + (plan.config.scheduler ? ' / ' + plan.config.scheduler : '') : null),
            kv('CFG', plan.config.cfg),
            kv('Seed', plan.config.seed),

            label('Models by task'),
            rows.length
                ? m('table', { 'data-testid': 'pb2-plan-models', class: 'w-full text-xs' }, [
                    m('thead', m('tr', { class: 'text-left text-gray-500' }, [m('th', 'Task'), m('th', 'Model'), m('th', 'Reason'), m('th', '')])),
                    m('tbody', rows.map(function (r) {
                        return m('tr', { 'data-task': r.task, 'data-degraded': r.degraded ? '1' : '0', class: 'border-t border-gray-100 dark:border-gray-700 align-top' }, [
                            m('td', { class: 'py-1 pr-2 font-medium' }, r.task),
                            m('td', { class: 'py-1 pr-2 break-all' }, r.model || m('span', { class: 'text-amber-700 dark:text-amber-400' }, 'unresolved')),
                            m('td', { class: 'py-1 pr-2 text-gray-600 dark:text-gray-300' }, r.reason || '—'),
                            m('td', { class: 'py-1' }, r.degraded ? m('span', { class: 'px-1.5 rounded bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200' }, 'degraded') : null)
                        ]);
                    }))
                ])
                : m('div', { class: 'text-xs text-gray-400 italic' }, 'No model resolution in this plan.'),

            label('Compiled prompts'),
            stages.length
                ? stages.map(function (stage) {
                    let c = plan.compiled[stage] && typeof plan.compiled[stage] === 'object' ? plan.compiled[stage] : {};
                    return m('div', { 'data-testid': 'pb2-plan-stage', 'data-stage': stage, class: 'mb-2' }, [
                        m('div', { class: 'flex items-center gap-2 text-xs' }, [
                            m('span', { class: 'font-semibold' }, stage),
                            c.estimatedTokens != null ? m('span', { class: 'text-gray-400' }, '~' + c.estimatedTokens + ' tokens') : null
                        ]),
                        m('pre', { class: 'whitespace-pre-wrap break-words text-xs bg-gray-50 dark:bg-gray-800 rounded p-2 mt-1' }, c.prompt || '—'),
                        c.negative ? m('pre', { class: 'whitespace-pre-wrap break-words text-xs bg-red-50 dark:bg-red-900/20 rounded p-2 mt-1' }, 'negative: ' + c.negative) : null
                    ]);
                })
                : m('div', { class: 'text-xs text-gray-400 italic' }, 'No compiled prompts.'),

            label('Degradations'),
            plan.degradations.length
                ? m('ul', { 'data-testid': 'pb2-plan-degradations', class: 'text-xs space-y-1' }, plan.degradations.map(function (d) {
                    let dd = d && typeof d === 'object' ? d : { message: String(d) };
                    return m('li', { 'data-code': dd.code || '', class: 'flex gap-2' }, [
                        m('span', { class: 'px-1.5 rounded bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200 shrink-0' }, dd.code || 'DEGRADED'),
                        m('span', { class: 'break-words' }, (dd.stage ? '[' + dd.stage + '] ' : '') + (dd.message || ''))
                    ]);
                }))
                : m('div', { class: 'text-xs text-gray-400 italic' }, 'None.'),

            plan.configNotApplied.length
                ? [label('Config not applied'), m('div', { 'data-testid': 'pb2-plan-config-not-applied', class: 'text-xs text-gray-700 dark:text-gray-300' },
                    plan.configNotApplied.map(function (c) { return typeof c === 'string' ? c : JSON.stringify(c); }).join(', '))]
                : null,

            label('Style & location'),
            kv('Style', plan.style.styleObjectId),
            kv('Revision hash', plan.style.revisionHash),
            kv('Photoreal', plan.style.photoreal == null ? null : (plan.style.photoreal ? 'yes' : 'no')),
            kv('Location', plan.location && plan.location.description),
            kv('Raw intent', plan.intent && plan.intent.raw != null ? (plan.intent.raw ? 'yes' : 'no') : null),
            kv('Content hash', plan.contentHash)
        ]);
    }
};

export { PbPlanInspector };
export default PbPlanInspector;
