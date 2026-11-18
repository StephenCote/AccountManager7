/**
 * @vitest-environment jsdom
 *
 * components/jobRunPanel.js — the shared run panel (PB2 reader run, Locations runs, Compose render /
 * compose-book runs) and the shared background-activity banner (PB wizard + ChapBook). Real Mithril
 * render; the PB2 reader panel is rendered through its own state hook to prove it uses the component.
 */
import { describe, it, expect } from 'vitest';
import m from 'mithril';
import { jobRunPanel, stageIcon, bgActivityBanner } from '../components/jobRunPanel.js';

function render(vnode) {
    let div = document.createElement('div');
    m.render(div, vnode);
    return div.firstChild;
}

const STAGES = [
    { handle: 'a', state: 'done' },
    { handle: 'b', state: 'running' },
    { handle: 'c', state: 'failed', error: 'boom' },
    { handle: 'd', state: 'pending', autoApproved: true }
];

describe('jobRunPanel', () => {
    it('running: spinner, counter, cancel (not dismiss), stage rows with state attrs and icons', () => {
        let cancels = 0;
        let el = render(jobRunPanel({
            testid: 'run', terminal: false, label: 'Render scenes', counter: '2 of 4', counterTestid: 'ctr',
            onCancel: function () { cancels++; }, cancelTestid: 'cancel', onDismiss: function () {}, dismissTestid: 'dismiss',
            stages: STAGES, stageTestid: 'stage'
        }));
        expect(el.getAttribute('data-run-terminal')).toBe('0');
        expect(el.querySelector('.animate-spin')).not.toBeNull();
        expect(el.querySelector('[data-testid="ctr"]').textContent).toBe('2 of 4');
        expect(el.querySelector('[data-testid="dismiss"]')).toBeNull();
        el.querySelector('[data-testid="cancel"]').click();
        expect(cancels).toBe(1);
        let rows = el.querySelectorAll('[data-testid="stage"]');
        expect(Array.from(rows).map(function (r) { return r.getAttribute('data-stage-state'); })).toEqual(['done', 'running', 'failed', 'pending']);
        expect(rows[2].textContent).toContain('— boom');
        expect(rows[3].textContent).toContain('auto-approved');
        expect(rows[0].querySelector('.material-symbols-outlined').textContent).toBe('check_circle');
    });

    it('terminal: no cancel, dismiss shown, bool attr form, failed icon, empty-stage text', () => {
        let dismissed = 0;
        let el = render(jobRunPanel({
            testid: 'run', terminalAttr: 'bool', terminal: true, failed: true, label: 'L',
            onCancel: function () {}, cancelTestid: 'cancel', onDismiss: function () { dismissed++; }, dismissTestid: 'dismiss',
            stages: [], emptyText: 'Waiting for the first poll…'
        }));
        expect(el.getAttribute('data-run-terminal')).toBe('true');
        expect(el.querySelector('[data-testid="cancel"]')).toBeNull();
        expect(el.textContent).toContain('Waiting for the first poll…');
        expect(el.querySelector('.material-symbols-outlined').textContent).toBe('error');
        el.querySelector('[data-testid="dismiss"]').click();
        expect(dismissed).toBe(1);
    });

    it('dismissWhileRunning shows dismiss before terminal; error line renders', () => {
        let el = render(jobRunPanel({ terminal: false, label: 'L', error: 'nope', onDismiss: function () {}, dismissWhileRunning: true, dismissTitle: 'Stop following', dismissTestid: 'd' }));
        expect(el.querySelector('[data-testid="d"]').getAttribute('title')).toBe('Stop following');
        expect(el.textContent).toContain('nope');
    });

    it('stageIcon covers every deriveStageList state', () => {
        expect(['running', 'done', 'failed', 'skipped', 'unrun', 'pending'].map(stageIcon))
            .toEqual(['progress_activity', 'check_circle', 'error', 'push_pin', 'remove_circle_outline', 'radio_button_unchecked']);
    });
});

describe('bgActivityBanner', () => {
    it('renders nothing without a label; label + icon + optional cancel otherwise', () => {
        expect(bgActivityBanner({ bg: null })).toBeNull();
        expect(bgActivityBanner({ bg: { label: '' } })).toBeNull();
        let cancels = 0;
        let el = render(bgActivityBanner({ bg: { label: 'Memorizing', icon: 'psychology' }, onCancel: function () { cancels++; } }));
        expect(el.textContent).toContain('Memorizing');
        expect(el.querySelector('.material-symbols-outlined').textContent).toBe('psychology');
        el.querySelector('button').click();
        expect(cancels).toBe(1);
        let c = render(bgActivityBanner({ bg: { label: 'x' }, onCancel: function () {}, cancelling: true }));
        expect(c.querySelector('button').disabled).toBe(true);
        expect(c.querySelector('button').textContent).toContain('Cancelling...');
        expect(render(bgActivityBanner({ bg: { label: 'x' } })).querySelector('button')).toBeNull();
    });
});

describe('PB2 reader run panel uses jobRunPanel', () => {
    it('renderPb2RunProgress renders the shared panel with its testids, chat-config chip and cancel', async () => {
        const pb = await import('../features/pictureBook.js');
        pb.__setPb2V3StateForTest({ pb2Run: {
            label: 'Render 2 scenes', runObjectId: 'run-1', planned: ['n1', 'n2'],
            chatConfig: { name: 'contentAnalysis' },
            job: { terminal: false, current: 1, total: 2, phase: 'rendering' }
        } });
        let el = render(pb.renderPb2RunProgress()).firstChild;
        expect(el.getAttribute('data-job-run')).toBe('1');
        expect(el.getAttribute('data-testid')).toBe('pb2-run-progress');
        expect(el.querySelector('[data-testid="pb2-run-counter"]').textContent).toBe('1 of 2 · rendering');
        expect(el.querySelector('[data-testid="pb2-run-chat-config"]').textContent).toBe('contentAnalysis');
        expect(el.querySelector('[data-testid="pb2-run-cancel"]')).not.toBeNull();
        expect(el.querySelector('[data-testid="pb2-run-dismiss"]')).toBeNull();
        expect(el.querySelectorAll('[data-testid="pb2-run-stage"]').length).toBe(2);
        pb.__setPb2V3StateForTest({ pb2Run: null });
    });
});
