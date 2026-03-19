/**
 * Audio Token Library — resolves ${audio.text "..."} tokens in LLM responses (ESM)
 * Port of Ux7 am7audioTokens pattern.
 */
import m from 'mithril';
import { applicationPath } from '../core/config.js';

const TOKEN_REGEX = /\$\{audio\.text\s+"([^"]+)"\}/g;

let buttonStates = {}; // btnId -> "idle" | "loading" | "playing"
let audioElements = {}; // btnId -> HTMLAudioElement

function parse(content) {
    if (!content) return [];
    let tokens = [];
    let match;
    let regex = new RegExp(TOKEN_REGEX.source, 'g');
    while ((match = regex.exec(content)) !== null) {
        tokens.push({ text: match[1], start: match.index, end: match.index + match[0].length, full: match[0] });
    }
    return tokens;
}

function register(btnId, text, profileId, sessionId) {
    if (!buttonStates[btnId]) {
        buttonStates[btnId] = "idle";
    }
}

function state(btnId) {
    return buttonStates[btnId] || "idle";
}

async function play(btnId, text) {
    if (buttonStates[btnId] === "playing") {
        stop(btnId);
        return;
    }

    buttonStates[btnId] = "loading";
    m.redraw();

    try {
        let voiceName = btnId || 'audio-' + Date.now();
        let body = { text: text, speed: 1.2, engine: "piper", speaker: "en_GB-alba-medium" };
        let resp = await fetch(applicationPath + "/rest/voice/" + encodeURIComponent(voiceName), {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            credentials: "include",
            body: JSON.stringify(body)
        });

        if (!resp.ok) throw new Error("Synthesis failed: " + resp.status);

        let contentType = resp.headers.get('content-type') || '';
        let audio;
        if (contentType.match(/audio|octet/)) {
            // Binary audio response
            let blob = await resp.blob();
            let url = URL.createObjectURL(blob);
            audio = new Audio(url);
            audio.onended = function() {
                buttonStates[btnId] = "idle";
                URL.revokeObjectURL(url);
                delete audioElements[btnId];
                m.redraw();
            };
            audio.onerror = function() {
                buttonStates[btnId] = "idle";
                URL.revokeObjectURL(url);
                delete audioElements[btnId];
                m.redraw();
            };
        } else {
            // JSON response with audio data (base64)
            let data = await resp.json();
            if (data && data.audio) {
                let audioBlob = new Blob([Uint8Array.from(atob(data.audio), c => c.charCodeAt(0))], { type: 'audio/wav' });
                let url = URL.createObjectURL(audioBlob);
                audio = new Audio(url);
                audio.onended = function() {
                    buttonStates[btnId] = "idle";
                    URL.revokeObjectURL(url);
                    delete audioElements[btnId];
                    m.redraw();
                };
                audio.onerror = function() {
                    buttonStates[btnId] = "idle";
                    URL.revokeObjectURL(url);
                    delete audioElements[btnId];
                    m.redraw();
                };
            } else {
                throw new Error("No audio data in response");
            }
        }

        audioElements[btnId] = audio;
        buttonStates[btnId] = "playing";
        audio.play();
    } catch(e) {
        console.warn("[audioTokens] play failed:", e);
        buttonStates[btnId] = "idle";
    }

    m.redraw();
}

function stop(btnId) {
    if (audioElements[btnId]) {
        audioElements[btnId].pause();
        audioElements[btnId].currentTime = 0;
        delete audioElements[btnId];
    }
    buttonStates[btnId] = "idle";
    m.redraw();
}

/**
 * Process content string, replacing audio tokens with HTML buttons.
 * Buttons use data-audio-* attributes for click delegation.
 */
function processContent(content) {
    if (!content) return content;
    let tokens = parse(content);
    if (tokens.length === 0) return content;

    let result = content;
    for (let i = tokens.length - 1; i >= 0; i--) {
        let token = tokens[i];
        let btnId = "audio-" + i + "-" + token.text.substring(0, 10).replace(/\W/g, "");
        register(btnId, token.text);

        let st = state(btnId);
        let icon = st === "playing" ? "stop" : st === "loading" ? "hourglass_top" : "play_arrow";
        let btn = '<button class="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-300 text-xs hover:bg-purple-200 dark:hover:bg-purple-900/50" data-audio-id="' + btnId + '" data-audio-text="' + token.text.replace(/"/g, '&quot;') + '">'
            + '<span class="material-symbols-outlined" style="font-size:14px">' + icon + '</span>'
            + '<span class="truncate max-w-[120px]">' + token.text.substring(0, 30) + '</span>'
            + '</button>';

        result = result.substring(0, token.start) + btn + result.substring(token.end);
    }

    return result;
}

// Document-level click delegation for audio buttons
if (typeof document !== 'undefined') {
    document.addEventListener('click', function(e) {
        let btn = e.target.closest('[data-audio-id]');
        if (btn) {
            let btnId = btn.getAttribute('data-audio-id');
            let text = btn.getAttribute('data-audio-text');
            if (btnId && text) play(btnId, text);
        }
    });
}

const am7audioTokens = {
    parse,
    processContent,
    register,
    state,
    play,
    stop,
    clearState: function() { buttonStates = {}; audioElements = {}; }
};

export { am7audioTokens };
export default am7audioTokens;
