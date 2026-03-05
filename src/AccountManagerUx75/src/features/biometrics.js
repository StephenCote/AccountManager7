/**
 * Biometrics feature — Biometric feedback visualization (ESM port)
 * Particle canvas + emotion-based theming + camera face analysis.
 * Route: /hyp
 */
import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { applicationPath } from '../core/config.js';
import { layout } from '../router.js';
import { camera } from '../components/camera.js';

// ── Emotion / demographic color maps ─────────────────────────────────

const EMOTION_THEMES = {
    neutral:  { bg: "bg-gray-800",   text: "text-blue-200",   shadow: "rgba(180, 210, 255, 0.4)" },
    happy:    { bg: "bg-yellow-900",  text: "text-yellow-100", shadow: "rgba(255, 230, 150, 0.5)" },
    sad:      { bg: "bg-indigo-900",  text: "text-indigo-200", shadow: "rgba(190, 200, 255, 0.4)" },
    angry:    { bg: "bg-red-900",     text: "text-red-200",    shadow: "rgba(255, 180, 180, 0.5)" },
    fear:     { bg: "bg-purple-900",  text: "text-purple-200", shadow: "rgba(220, 180, 255, 0.4)" },
    surprise: { bg: "bg-pink-900",    text: "text-pink-200",   shadow: "rgba(255, 180, 220, 0.5)" },
    disgust:  { bg: "bg-green-900",   text: "text-green-200",  shadow: "rgba(180, 255, 180, 0.4)" }
};

const RACE_COLORS = {
    white: [255, 220, 180], black: [100, 75, 50], asian: [255, 235, 160],
    'East Asian': [255, 235, 160], 'Southeast Asian': [255, 235, 160],
    indian: [200, 160, 120], 'middle eastern': [220, 190, 150],
    'latino hispanic': [180, 130, 90]
};

const GENDER_COLORS = {
    Man: [180, 200, 255], Woman: [255, 200, 220]
};

// ── Particle system ──────────────────────────────────────────────────

const PARTICLE_COUNT = 100;
let _canvas = null;
let _ctx = null;
let _particles = [];
let _particleColor = { r: 150, g: 180, b: 255 };
let _animFrameId = null;

function resetParticle(p) {
    let w = _canvas ? _canvas.width : 800;
    let h = _canvas ? _canvas.height : 600;
    p.x = p.x !== undefined ? p.x : Math.random() * w;
    p.y = p.y !== undefined ? p.y : Math.random() * h;
    p.vx = (Math.random() - 0.5) * 0.5;
    p.vy = (Math.random() - 0.5) * 0.5;
    p.radius = Math.random() * 3 + 1;
    p.alpha = Math.random() * 0.5 + 0.1;
    return p;
}

function initParticles(canvasEl) {
    _canvas = canvasEl;
    _ctx = canvasEl.getContext('2d');
    canvasEl.width = window.innerWidth;
    canvasEl.height = window.innerHeight;
    _particles = [];
    for (let i = 0; i < PARTICLE_COUNT; i++) {
        _particles.push(resetParticle({}));
    }
    animate();
}

function animate() {
    if (!_ctx) return;
    _ctx.clearRect(0, 0, _canvas.width, _canvas.height);
    let c = _particleColor;
    for (let p of _particles) {
        p.x += p.vx;
        p.y += p.vy;
        if (p.x < 0 || p.x > _canvas.width) p.vx *= -1;
        if (p.y < 0 || p.y > _canvas.height) p.vy *= -1;
        p.alpha -= 0.001;
        if (p.alpha <= 0) resetParticle(p);
        _ctx.beginPath();
        _ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
        _ctx.fillStyle = "rgba(" + c.r + "," + c.g + "," + c.b + "," + p.alpha + ")";
        _ctx.fill();
    }
    _animFrameId = requestAnimationFrame(animate);
}

function stopParticles() {
    if (_animFrameId) cancelAnimationFrame(_animFrameId);
    _animFrameId = null;
    _canvas = null;
    _ctx = null;
    _particles = [];
}

// ── Biometric data processing ────────────────────────────────────────

function blendColors(scores, colorMap) {
    let total = 0;
    for (let key in scores) {
        if (scores[key] > 0 && colorMap[key]) total += scores[key];
    }
    if (total <= 0) return null;
    let r = 0, g = 0, b = 0;
    for (let key in scores) {
        if (scores[key] > 0 && colorMap[key]) {
            let w = scores[key] / total;
            r += colorMap[key][0] * w;
            g += colorMap[key][1] * w;
            b += colorMap[key][2] * w;
        }
    }
    return { r: Math.round(r), g: Math.round(g), b: Math.round(b) };
}

function processBiometricData(data, state) {
    state.biometricData = data;
    let clues = [];

    let emotion = data.dominant_emotion || "neutral";
    state.theme = EMOTION_THEMES[emotion] || EMOTION_THEMES.neutral;
    clues.push("A state of " + emotion + ".");

    let raceBlend = blendColors(data.race_scores || {}, RACE_COLORS);
    if (raceBlend) clues.push("A tapestry of heritage.");

    let genderBlend = blendColors(data.gender_scores || {}, GENDER_COLORS);

    if (raceBlend && genderBlend) {
        _particleColor = {
            r: Math.round((raceBlend.r + genderBlend.r) / 2),
            g: Math.round((raceBlend.g + genderBlend.g) / 2),
            b: Math.round((raceBlend.b + genderBlend.b) / 2)
        };
    } else if (raceBlend) {
        _particleColor = raceBlend;
    } else if (genderBlend) {
        _particleColor = genderBlend;
    }

    if (data.age) clues.push(data.age + " years of experience.");
    if (data.dominant_gender) clues.push("An expression of " + data.dominant_gender.toLowerCase() + " energy.");

    state.clues = clues;
    m.redraw();
}

// ── Session state ────────────────────────────────────────────────────

const DISTRACTIONS = [
    "Listen to the space between sounds...", "The feeling of now is the memory of later...",
    "What if right was left?", "Remember to forget...", "A thought is just a visitor...",
    "Up is down if you allow it...", "Focus on everything and nothing...",
    "The future was yesterday...", "Notice your breath without changing it...", "See the silence..."
];
const CORE_MESSAGES = ["peace of mind", "purity of essence", "peace and harmony"];

function newSessionState() {
    return {
        started: false,
        theme: EMOTION_THEMES.neutral,
        distractionText: "...",
        coreText: "",
        imageUrl: "",
        biometricData: null,
        clues: [],
        contentInterval: null,
        imageUrls: []
    };
}

let _state = newSessionState();

function cycleContent() {
    _state.distractionText = DISTRACTIONS[Math.floor(Math.random() * DISTRACTIONS.length)];
    _state.coreText = CORE_MESSAGES[Math.floor(Math.random() * CORE_MESSAGES.length)];
    _state.imageUrl = _state.imageUrls.length ? _state.imageUrls[Math.floor(Math.random() * _state.imageUrls.length)] : "";
    m.redraw();
}

function onFaceCapture(imageData) {
    if (imageData && imageData.results && imageData.results.length) {
        processBiometricData(imageData.results[0], _state);
    }
}

function loadBackgroundImages() {
    let groupIds = [218, 220, 130, 172, 173];
    let q = am7client.newQuery("data.data");
    let qg = q.field(null, null);
    qg.comparator = "group_or";
    qg.fields = groupIds.map(a => ({ name: "groupId", comparator: "equals", value: a }));
    q.range(0, 0);
    am7client.search(q, function(res) {
        if (res && res.results) {
            _state.imageUrls = res.results.map(r =>
                applicationPath + "/media/Public/data.data" + r.groupPath + "/" + r.name
            );
            cycleContent();
        }
    });
}

function startExperience() {
    if (_state.started) return;
    _state.started = true;

    if (!camera.devices().length) {
        camera.initializeAndFindDevices(onFaceCapture);
    } else {
        camera.startCapture(onFaceCapture);
    }

    if (!_state.imageUrls.length) loadBackgroundImages();
    m.redraw();
}

// ── Views ────────────────────────────────────────────────────────────

function startScreenView() {
    return m("div", { class: "h-screen w-screen flex items-center justify-center bg-gray-900" },
        m("div", { class: "text-center p-8 bg-gray-800 rounded-lg shadow-2xl" }, [
            m("h1", { class: "text-3xl font-bold mb-4 text-blue-200" }, "Biometric Feedback Session"),
            m("p", { class: "text-lg mb-6 text-gray-300" }, "This experience adapts to you. Please use headphones for the best effect."),
            m("button", {
                class: "px-8 py-3 bg-blue-600 text-white font-bold rounded-full hover:bg-blue-500 transition-colors duration-300 shadow-lg",
                onclick: startExperience
            }, "Begin")
        ])
    );
}

function experienceView() {
    let theme = _state.theme;
    return [
        m("div", { class: "relative h-screen w-screen transition-colors duration-1000 " + theme.bg }, [
            _state.imageUrl ? m("img", {
                class: "absolute top-0 left-0 w-full h-full object-cover z-0 opacity-50",
                src: _state.imageUrl,
                alt: ""
            }) : "",
            m("canvas", {
                id: "visual-canvas",
                class: "absolute top-0 left-0 z-[5]",
                oncreate: function(vnode) { initParticles(vnode.dom); }
            }),
            m("div", { class: "absolute top-0 left-0 z-10 h-full w-full p-4 bg-black/40" }, [
                m("div", { class: "absolute top-1/4 left-1/2 -translate-x-1/2 text-center" },
                    m("p", { class: "text-xl text-gray-300 opacity-75", style: "text-shadow: 0 0 5px rgba(0,0,0,0.7)" }, _state.distractionText)
                ),
                m("div", { class: "absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-center" },
                    m("h1", {
                        class: "text-5xl font-bold transition-colors duration-1000 opacity-75 " + theme.text,
                        style: { textShadow: "0 0 8px rgba(255,255,255,0.3), 0 0 20px " + theme.shadow }
                    }, _state.coreText.toUpperCase())
                ),
                _state.clues.length ? m("div", { class: "absolute bottom-8 left-1/2 -translate-x-1/2 text-center text-sm text-gray-400 opacity-60 p-2 rounded-lg bg-black/20" },
                    _state.clues.map((clue, i) => m("p", { key: i }, clue))
                ) : ""
            ])
        ]),
        camera.videoView()
    ];
}

// ── Route export ────────────────────────────────────────────────────

export const routes = {
    "/hyp": {
        oncreate: function() {
            _state.contentInterval = setInterval(cycleContent, 5000);
            cycleContent();
        },
        onremove: function() {
            camera.stopCapture();
            stopParticles();
            if (_state.contentInterval) clearInterval(_state.contentInterval);
            _state = newSessionState();
        },
        view: function() {
            return layout(_state.started ? experienceView() : startScreenView());
        }
    }
};
