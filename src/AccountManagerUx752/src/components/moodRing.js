/**
 * MoodRing — Toolbar mood indicator with biometric LLM integration (ESM port)
 * Uses camera face analysis + dedicated chat session for mood observations.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { camera } from './camera.js';
import { am7chat } from '../chat/chatUtil.js';

let enabled = false;
let emotion = 'neutral';
let gender = null;
let moodColor = null;
let chatSession = null;
let chatConfig = null;
let promptConfig = null;
let tickInterval = null;
let faceProfile = null;
let initializing = false;
let lastTickTime = 0;
let TICK_INTERVAL_MS = 60000;

let displayedEmotion = 'neutral';
let displayedGender = null;
let emojiOpacity = 1;
let fadeTimeout = null;
let llmSuggested = false;

const emotionEmojis = {
    neutral: '\u{1F610}', happy: '\u{1F60A}', sad: '\u{1F622}',
    angry: '\u{1F621}', fear: '\u{1F628}', surprise: '\u{1F632}',
    disgust: '\u{1F922}'
};

const genderEmojis = {
    Man: '\u{1F468}', Woman: '\u{1F469}'
};

const emotionColors = {
    neutral: [128, 128, 160],
    happy: [255, 215, 0],
    sad: [65, 105, 225],
    angry: [220, 50, 50],
    fear: [138, 43, 226],
    surprise: [255, 165, 0],
    disgust: [50, 150, 50]
};

const MOOD_RING_SYSTEM_PROMPT = [
    "You are a mood ring observer. You receive periodic biometric facial analysis data and provide brief, empathetic mood observations.",
    "Respond ONLY with a JSON object (no markdown, no code fences):",
    '{ "emotion": "happy|sad|angry|fear|surprise|disgust|neutral", "gender": "Man|Woman"|null, "commentary": "brief empathetic observation" }',
    "Guidelines:",
    "- Keep commentary under 20 words. Be warm and observational, not clinical.",
    "- Focus on the emotional state and provide gentle, supportive feedback.",
    "- Valid emotions: neutral, happy, sad, angry, fear, surprise, disgust.",
    "- Only include gender if the biometric data suggests it, otherwise set null.",
    "- Track emotional transitions across messages. Note shifts in mood.",
    "- Be encouraging and positive. If someone looks stressed, suggest they take a breath."
];

const VALID_EMOTIONS = ['neutral', 'happy', 'sad', 'angry', 'fear', 'surprise', 'disgust'];

function crossfadeTo(newEmotion, newGender) {
    if (newEmotion === displayedEmotion && newGender === displayedGender) return;
    if (emojiOpacity < 1) return;
    emojiOpacity = 0;
    m.redraw();
    if (fadeTimeout) clearTimeout(fadeTimeout);
    fadeTimeout = setTimeout(function() {
        displayedEmotion = newEmotion;
        displayedGender = newGender;
        emojiOpacity = 1;
        m.redraw();
    }, 300);
}

async function initialize() {
    if (initializing || chatSession) return;
    initializing = true;
    try {
        chatConfig = await am7chat.makeChat("MoodRing", null, null, null);
        promptConfig = await am7chat.makePrompt("MoodRingObserver", MOOD_RING_SYSTEM_PROMPT);
        if (chatConfig && promptConfig) {
            chatSession = await am7chat.getChatRequest("MoodRingSession", chatConfig, promptConfig);
        }
    } catch (e) {
        console.error("MoodRing: Failed to initialize chat session", e);
    } finally {
        initializing = false;
    }
}

function handleCapture(imageData) {
    if (imageData?.results?.length) {
        let result = imageData.results[0];
        faceProfile = {
            emotion: result.dominant_emotion,
            emotions: result.emotion_scores || {},
            gender: result.dominant_gender,
            race: result.dominant_race
        };
        let rawEmotion = (faceProfile.emotion || 'neutral').toLowerCase();
        if (VALID_EMOTIONS.indexOf(rawEmotion) > -1) {
            emotion = rawEmotion;
        }
        if (faceProfile.gender) {
            let g = faceProfile.gender.toLowerCase();
            if (g === 'man' || g === 'male') gender = 'Man';
            else if (g === 'woman' || g === 'female') gender = 'Woman';
        }
        moodColor = emotionColors[emotion] || emotionColors.neutral;
        llmSuggested = false;
        crossfadeTo(emotion, gender);
        m.redraw();
    }
}

async function tick() {
    if (!enabled || !chatSession || !faceProfile) return;
    let now = Date.now();
    if (now - lastTickTime < TICK_INTERVAL_MS) return;
    lastTickTime = now;

    try {
        let msg = JSON.stringify({
            biometrics: {
                emotion: faceProfile.emotion,
                emotionScores: faceProfile.emotions,
                race: faceProfile.race
            },
            currentMood: emotion,
            timestamp: new Date().toISOString()
        });

        let resp = await am7chat.chat(chatSession, msg);
        if (resp?.messages?.length) {
            let last = resp.messages[resp.messages.length - 1];
            if (last.role === 'assistant') {
                try {
                    let content = last.content.trim();
                    if (content.startsWith('```')) {
                        content = content.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '');
                    }
                    let parsed = JSON.parse(content);
                    if (parsed.emotion && VALID_EMOTIONS.indexOf(parsed.emotion) > -1) {
                        emotion = parsed.emotion;
                        moodColor = emotionColors[emotion] || emotionColors.neutral;
                    }
                    if (parsed.gender === 'Man' || parsed.gender === 'Woman') {
                        gender = parsed.gender;
                    }
                    llmSuggested = true;
                    crossfadeTo(emotion, gender);
                } catch (e) { /* non-JSON response */ }
            }
        }
        m.redraw();
    } catch (e) {
        console.error("MoodRing: tick error", e);
    }
}

function ensureVideoElement() {
    if (!document.querySelector('#facecam')) {
        let video = document.createElement('video');
        video.id = 'facecam';
        video.autoplay = true;
        video.muted = true;
        video.setAttribute('playsinline', '');
        video.style.cssText = 'position: absolute; top: 0; left: 0; opacity: 0; width: 1px; height: 1px;';
        document.body.appendChild(video);
    }
}

function removeVideoElement() {
    let el = document.querySelector('#facecam');
    if (el && el.parentNode === document.body) {
        el.parentNode.removeChild(el);
    }
}

async function toggle() {
    enabled = !enabled;
    if (enabled) {
        ensureVideoElement();
        await initialize();
        camera.initializeAndFindDevices(handleCapture);
        tickInterval = setInterval(tick, 5000);
    } else {
        if (tickInterval) { clearInterval(tickInterval); tickInterval = null; }
        camera.stopCapture();
        removeVideoElement();
        if (fadeTimeout) { clearTimeout(fadeTimeout); fadeTimeout = null; }
        emotion = 'neutral';
        gender = null;
        moodColor = null;
        faceProfile = null;
        lastTickTime = 0;
        displayedEmotion = 'neutral';
        displayedGender = null;
        emojiOpacity = 1;
        llmSuggested = false;
    }
    m.redraw();
}

const moodRing = {
    enabled: () => enabled,
    emotion: () => emotion,
    gender: () => gender,
    moodColor: () => moodColor,
    toggle,
    component: {
        view: function() {
            let emojiChar = emotionEmojis[displayedEmotion] || emotionEmojis.neutral;
            let genderChar = displayedGender ? (genderEmojis[displayedGender] || '') : '';
            let bgStyle = "transition: background-color 1s ease-in-out, border-color 0.3s ease;";
            let btnClass = "p-1.5 rounded";

            if (enabled && moodColor) {
                bgStyle = "background-color: rgba(" + moodColor[0] + ", " + moodColor[1] + ", " + moodColor[2] + ", 0.35); border: 2px solid rgba(" + moodColor[0] + ", " + moodColor[1] + ", " + moodColor[2] + ", 0.7); transition: background-color 1s ease-in-out, border-color 1s ease-in-out;";
            } else if (enabled) {
                bgStyle = "border: 2px solid rgba(139, 92, 246, 0.7); transition: background-color 1s ease-in-out, border-color 0.3s ease;";
            }

            let glow = llmSuggested ? " filter: drop-shadow(0 0 6px rgba(255,255,255,0.7));" : "";
            let emojiStyle = "font-size: 20px; line-height: 1; opacity: " + emojiOpacity + "; transition: opacity 300ms ease-in-out, filter 0.5s ease;" + glow;

            return m("button", {
                class: btnClass,
                style: bgStyle,
                onclick: toggle,
                title: enabled ? "Mood Ring: " + emotion + " (click to disable)" : "Enable Mood Ring"
            }, [
                m("span", { style: emojiStyle }, emojiChar),
                genderChar ? m("span", { style: emojiStyle }, genderChar) : null
            ]);
        }
    }
};

export { moodRing };
export default moodRing;
