/**
 * Audio Token Library — resolves ${audio.text "..."} tokens in LLM responses (ESM)
 * Port of Ux7 am7audioTokens pattern.
 */
import m from 'mithril';
import { applicationPath } from '../core/config.js';

// Backend produces ${audio.TEXT} — arbitrary text after "audio." until closing }
const TOKEN_REGEX = /\$\{audio\.([^}]+)\}/g;

let buttonStates = {}; // btnId -> "idle" | "loading" | "playing"
let audioElements = {}; // btnId -> HTMLAudioElement
let buttonProfiles = {}; // btnId -> { profileId, sessionId }
let voiceCache = {}; // profileId -> resolved voice settings

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
    if (profileId) {
        buttonProfiles[btnId] = { profileId, sessionId };
    }
}

async function resolveVoice(profileId) {
    if (!profileId) return null;
    if (voiceCache[profileId]) return voiceCache[profileId];
    try {
        let resp = await fetch(applicationPath + "/rest/model/identity.profile/" + encodeURIComponent(profileId) + "/full", { credentials: "include" });
        if (!resp.ok) return null;
        let profile = await resp.json();
        if (profile && profile.voice) {
            let v = profile.voice;
            let resolved = { engine: v.engine || "piper", speaker: v.speaker || "en_GB-alba-medium", speakerId: v.speakerId, speed: v.speed || 1.2 };
            voiceCache[profileId] = resolved;
            return resolved;
        }
    } catch(e) { /* ignore */ }
    return null;
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

    // Pre-create Audio element in user gesture context (before any async)
    // This preserves the click gesture for autoplay policy
    let audio = new Audio();
    audioElements[btnId] = audio;

    function cleanup(url) {
        buttonStates[btnId] = "idle";
        if (url) URL.revokeObjectURL(url);
        delete audioElements[btnId];
        m.redraw();
    }

    try {
        let voiceName = btnId || 'audio-' + Date.now();
        // Resolve character voice profile if available, otherwise use defaults
        let voiceSettings = null;
        let bp = buttonProfiles[btnId];
        if (bp && bp.profileId) {
            voiceSettings = await resolveVoice(bp.profileId);
        }
        let body = {
            text: text,
            speed: voiceSettings ? voiceSettings.speed : 1.2,
            engine: voiceSettings ? voiceSettings.engine : "piper",
            speaker: voiceSettings ? voiceSettings.speaker : "en_GB-alba-medium"
        };
        if (voiceSettings && voiceSettings.speakerId != null && voiceSettings.speakerId >= 0) {
            body.speaker_id = voiceSettings.speakerId;
        }
        let resp = await fetch(applicationPath + "/rest/voice/" + encodeURIComponent(voiceName), {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            credentials: "include",
            body: JSON.stringify(body)
        });

        if (!resp.ok) throw new Error("Synthesis failed: " + resp.status);

        let contentType = resp.headers.get('content-type') || '';
        let url;
        if (contentType.match(/audio|octet/)) {
            let blob = await resp.blob();
            url = URL.createObjectURL(blob);
        } else {
            let data = await resp.json();
            let audioB64 = data ? (data.dataBytesStore || data.audio) : null;
            if (!audioB64) throw new Error("No audio data in response (keys: " + (data ? Object.keys(data).join(",") : "null") + ")");
            let audioBlob = new Blob([Uint8Array.from(atob(audioB64), c => c.charCodeAt(0))], { type: data.contentType || 'audio/wav' });
            url = URL.createObjectURL(audioBlob);
        }

        audio.src = url;
        audio.onended = function() { cleanup(url); };
        audio.onerror = function() { cleanup(url); };
        buttonStates[btnId] = "playing";
        audio.play().catch(function(e) {
            console.warn("[audioTokens] autoplay blocked:", e);
            cleanup(url);
        });
    } catch(e) {
        console.warn("[audioTokens] play failed:", e);
        buttonStates[btnId] = "idle";
        delete audioElements[btnId];
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
        // Include text hash for uniqueness across different content
        let textHash = 0;
        for (let ci = 0; ci < token.text.length; ci++) { textHash = ((textHash << 5) - textHash + token.text.charCodeAt(ci)) | 0; }
        let btnId = "audio-" + i + "-" + Math.abs(textHash) + "-" + token.text.substring(0, 10).replace(/\W/g, "");
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
