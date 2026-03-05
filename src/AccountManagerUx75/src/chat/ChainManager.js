/**
 * ChainManager — Agent chain progress UI and WebSocket handling (ESM)
 * Port of Ux7 chain stream handling from chat.js.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';

// ── Chain State ─────────────────────────────────────────────────────

let _active = false;
let _steps = [];
let _currentStep = -1;
let _status = "idle"; // "idle" | "running" | "complete" | "error"
let _error = null;
let _result = null;
let _onComplete = null;

function reset() {
    _active = false;
    _steps = [];
    _currentStep = -1;
    _status = "idle";
    _error = null;
    _result = null;
}

// ── Chain Stream ────────────────────────────────────────────────────

function newChainStream(onComplete) {
    _onComplete = onComplete || null;
    _active = true;
    _steps = [];
    _currentStep = -1;
    _status = "running";
    _error = null;
    _result = null;

    let stream = {
        onchainStart: function(data) {
            _status = "running";
            _steps = [];
            _currentStep = 0;
            m.redraw();
        },
        onstepStart: function(data) {
            _currentStep = _steps.length;
            _steps.push({ status: "running", label: data.label || data.name || ("Step " + (_steps.length + 1)), data: data });
            m.redraw();
        },
        onstepComplete: function(data) {
            if (_currentStep >= 0 && _currentStep < _steps.length) {
                _steps[_currentStep].status = "complete";
                _steps[_currentStep].result = data;
            }
            m.redraw();
        },
        onstepError: function(data) {
            if (_currentStep >= 0 && _currentStep < _steps.length) {
                _steps[_currentStep].status = "error";
                _steps[_currentStep].error = data;
            }
            m.redraw();
        },
        onchainComplete: function(data) {
            _status = "complete";
            _result = data;
            if (_onComplete) _onComplete(data);
            m.redraw();
        },
        onstepsInserted: function(data) {
            // Additional steps were dynamically added
            m.redraw();
        },
        onchainMaxSteps: function(data) {
            _status = "error";
            _error = "Maximum steps reached";
            page.toast("warn", "Chain reached maximum steps");
            m.redraw();
        },
        onchainError: function(data) {
            _status = "error";
            _error = data.message || data.error || "Chain failed";
            page.toast("error", "Chain error: " + _error);
            m.redraw();
        }
    };

    page.chainStream = stream;
    return stream;
}

// ── Send ────────────────────────────────────────────────────────────

async function doChainSend(session, message, onComplete) {
    newChainStream(onComplete);

    try {
        page.wss.send("chain", JSON.stringify({
            sessionId: session.objectId,
            message: message
        }), null, "message.chatMessage");
    } catch(e) {
        _status = "error";
        _error = e.message || "Failed to send chain";
        page.chainStream = null;
        m.redraw();
    }
}

// ── Public API ──────────────────────────────────────────────────────

const ChainManager = {
    isActive: function() { return _active; },
    getStatus: function() { return _status; },
    getSteps: function() { return _steps; },
    getError: function() { return _error; },
    getResult: function() { return _result; },

    start: doChainSend,
    stop: function() {
        _status = "idle";
        _active = false;
        page.chainStream = null;
        m.redraw();
    },
    reset: reset,

    ProgressView: {
        view: function() {
            if (!_active) return null;

            let statusIcon = _status === "running" ? "progress_activity"
                : _status === "complete" ? "check_circle"
                : _status === "error" ? "error"
                : "pending";

            let statusColor = _status === "running" ? "text-blue-500"
                : _status === "complete" ? "text-green-500"
                : _status === "error" ? "text-red-500"
                : "text-gray-400";

            return m("div", { class: "px-4 py-2 border-b border-gray-200 dark:border-gray-700 bg-blue-50/50 dark:bg-blue-900/10 shrink-0" }, [
                m("div", { class: "flex items-center gap-2 mb-1" }, [
                    m("span", {
                        class: "material-symbols-outlined " + statusColor + (_status === "running" ? " animate-spin" : ""),
                        style: "font-size:16px"
                    }, statusIcon),
                    m("span", { class: "text-xs font-semibold text-gray-700 dark:text-gray-200" },
                        "Chain " + (_status === "running" ? "running" : _status) + " (" + _steps.length + " steps)"),
                    _status !== "running" ? m("button", {
                        class: "text-xs text-gray-400 hover:text-gray-600 ml-auto",
                        onclick: function() { reset(); m.redraw(); }
                    }, "dismiss") : null
                ]),
                // Step list
                _steps.length > 0 ? m("div", { class: "space-y-0.5 ml-6" }, _steps.map(function(step, idx) {
                    let stepIcon = step.status === "running" ? "play_circle"
                        : step.status === "complete" ? "check_circle"
                        : step.status === "error" ? "cancel"
                        : "radio_button_unchecked";
                    let stepColor = step.status === "running" ? "text-blue-400"
                        : step.status === "complete" ? "text-green-400"
                        : step.status === "error" ? "text-red-400"
                        : "text-gray-300";

                    return m("div", { class: "flex items-center gap-1.5 text-xs", key: idx }, [
                        m("span", { class: "material-symbols-outlined " + stepColor, style: "font-size:14px" }, stepIcon),
                        m("span", { class: "text-gray-600 dark:text-gray-300" }, step.label)
                    ]);
                })) : null,
                _error ? m("div", { class: "text-xs text-red-500 mt-1 ml-6" }, _error) : null
            ]);
        }
    }
};

export { ChainManager };
export default ChainManager;
