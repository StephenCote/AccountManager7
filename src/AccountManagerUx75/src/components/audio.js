/**
 * audio.js — Core audio engine (ESM)
 * Selective port of Ux7 client/components/audio.js
 *
 * Provides: TTS synthesis, playback, source management, recording,
 * speech recognition, visualizer integration.
 * Skips: Magic 8 dual-channel, binaural sweep (deferred to Phase 18).
 */
import m from 'mithril';
import { am7model } from '../core/model.js';

function getClient() { return am7model._client; }
function getPage() { return am7model._page; }

let audioMap = {};
let audioSource = {};
let visualizers = {};
let recorder;
let upNext = [];
let recording = false;
let recognition;

// ── Audio Source Controllers (new system from audioComponents.js) ──
let audioSourceControllers = {};

function registerAudioSource(id, controller) {
    audioSourceControllers[id] = controller;
}

function unregisterAudioSource(id) {
    delete audioSourceControllers[id];
}

function stopAllAudioSourceControllers(except) {
    Object.values(audioSourceControllers).forEach(controller => {
        if (controller !== except && controller.isPlaying && controller.isPlaying()) {
            controller.stop();
        }
    });
}

// ── Base64 ────────────────────────────────────────────────────────

function base64ToArrayBuffer(base64) {
    let cleanedBase64 = base64.replace(/^data:audio\/\w+;base64,/, '');
    let binaryString = atob(cleanedBase64);
    let len = binaryString.length;
    let bytes = new Uint8Array(len);
    for (let i = 0; i < len; i++) {
        bytes[i] = binaryString.charCodeAt(i);
    }
    return bytes.buffer;
}

// ── Audio Source Creation (legacy system) ─────────────────────────

async function createAudioSourceLegacy(name, voiceProps, content) {
    let am7client = getClient();
    let page = getPage();

    if (content) {
        content = content.replace(/([\u2700-\u27BF]|[\uE000-\uF8FF]|\uD83C[\uDC00-\uDFFF]|\uD83D[\uDC00-\uDFFF]|[\u2011-\u26FF]|\uD83E[\uDD10-\uDDFF])/g, "");
        content = content.replace(/["\*]+/g, "");
    }
    if (!content || content.length == 0) return;

    let tmpAud;
    if (!audioMap[name]) {
        tmpAud = audioMap[name] = { name, voiceProps, content, pending: false };
    }
    if (!audioMap[name].data && !audioMap[name].pending) {
        audioMap[name].pending = true;

        let vprops;
        if (voiceProps && typeof voiceProps === "object") {
            vprops = { text: content, speed: voiceProps.speed || 1.2, engine: voiceProps.engine, speaker: voiceProps.speaker, speaker_id: voiceProps.speakerId };
            if (voiceProps.voiceSample) vprops.voice_sample = voiceProps.voiceSample;
        } else if (voiceProps) {
            vprops = { text: content, speed: 1.2, voiceProfileId: voiceProps };
        } else {
            vprops = { text: content, speed: 1.2, engine: "piper", speaker: "en_GB-alba-medium" };
        }

        let d;
        try {
            d = await m.request({ method: 'POST', url: am7client.base() + "/voice/" + name, withCredentials: true, body: vprops });
        } catch (e) {
            console.error("[audio] Error synthesizing:", e);
        }

        if (!audioMap[name]) audioMap[name] = tmpAud;
        if (d) {
            audioMap[name].data = d;
            audioMap[name].pending = false;
        } else {
            page.toast("error", "Failed to synthesize audio - is the audio service running?");
            audioMap[name].error = true;
            audioMap[name].pending = false;
        }
    }

    if (audioMap[name] && audioMap[name].data && !audioMap[name].error && !audioMap[name].pending) {
        let o = audioMap[name].data;
        if (!audioSource[name]) {
            let audioContext = new AudioContext();
            let audioBuffer = await audioContext.decodeAudioData(base64ToArrayBuffer(o.dataBytesStore));
            let sourceNode = audioContext.createBufferSource();
            sourceNode.buffer = audioBuffer;
            audioSource[name] = { id: (getPage().uid ? getPage().uid() : Math.random().toString(36)), context: audioContext, buffer: audioBuffer, started: false, source: sourceNode };
        }
    }
    return audioSource[name];
}

// ── Playback Controls ─────────────────────────────────────────────

function getRunningAudioSources() {
    return Object.values(audioSource).filter(aud => aud.started && aud.context.state == "running");
}

function stopAudioSources(aud) {
    let running = getRunningAudioSources();
    running.forEach(r => {
        if (!aud || r.id != aud.id) {
            togglePlayAudioSource(r, false, true);
        }
    });
}

function togglePlayAudio(id) {
    let aud = document.getElementById(id);
    if (!aud) return;
    aud.paused ? aud.play() : aud.pause();
}

function togglePlayAudioSource(aud, autoStop, noStart) {
    if (autoStop) upNext = [];

    if (typeof aud == "string") {
        if (visualizers[aud] && visualizers[aud].lateLoad) {
            stopAudioSources();
            configureVisualizer(document.querySelectorAll("div[id*='" + aud + "']")[0], true);
            return;
        }
        let am = getAudioMapForContainer(aud);
        if (am && audioSource[am.name]) aud = audioSource[am.name];
    }
    if (!aud) return;
    if (autoStop) stopAudioSources(aud);
    if (noStart) return;

    if (!aud.started) {
        if (aud.context) {
            aud.source = aud.context.createBufferSource();
            aud.source.buffer = aud.buffer;
            aud.source.connect(aud.context.destination);
            if (aud.context.state === "suspended") {
                aud.context.resume().then(() => { aud.source.start(0); aud.started = true; });
            } else {
                aud.source.start(0);
                aud.started = true;
            }
        }
    } else if (aud.context.state == "suspended") {
        aud.context.resume();
    } else if (aud.context.state != "closed") {
        aud.context.suspend();
    }

    if (aud.source) {
        aud.source.onended = function() {
            aud.started = false;
            if (upNext.length > 0) togglePlayAudioSource(upNext.shift());
        };
    }
}

// ── Visualizer Integration ────────────────────────────────────────

function getAudioMapForContainer(containerId) {
    let aM = Object.values(audioMap).filter(k => k.containerId == containerId);
    return aM.length ? aM[0] : undefined;
}

function createAudioVisualizer(name, idx, profileId, autoPlay, content) {
    let page = getPage();
    let contId = "chatAudioContainer-" + idx;
    let aud = m("div", { class: "block w-full h-[60px]", id: contId, onclick: function() { togglePlayAudioSource(contId, true); } }, "");
    if (!audioMap[name]) {
        audioMap[name] = { id: (page.uid ? page.uid() : Math.random().toString(36)), index: idx, name, profileId, content, autoPlay, containerId: contId, pending: false };
    }
    return aud;
}

function configureVisualizer(aud, autoPlay) {
    if (!aud) return;
    let props = { height: 60, overlay: true, bgAlpha: 0, showBgColor: true, showSource: false, gradient: "prism", showScaleY: false, showScaleX: false };

    if (visualizers[aud.id] && !visualizers[aud.id].lateLoad) return;
    visualizers[aud.id] = { pending: true };

    let oM = getAudioMapForContainer(aud.id);
    if (!oM) return;

    createAudioSourceLegacy(oM.name, oM.profileId, oM.content).then(o => {
        if (!o) return;
        if (typeof AudioMotionAnalyzer !== 'undefined') {
            let props1 = Object.assign({ source: o.source, onclick: function() { togglePlayAudioSource(o); } }, props);
            let audioMotion = new AudioMotionAnalyzer(aud, props1);
            visualizers[aud.id] = audioMotion;
        }
        if (autoPlay || oM.autoPlay) {
            if (getRunningAudioSources().length > 0) {
                upNext.push(o);
            } else {
                togglePlayAudioSource(o);
            }
        }
    });
}

function configureAudio(enabled) {
    if (!enabled) return;
    let aa = document.querySelectorAll("div[id*='chatAudioContainer-']");
    for (let i = 0; i < aa.length; i++) {
        let aud = aa[i];
        if (visualizers[aud.id]) continue;
        if (i < (aa.length - 2)) {
            visualizers[aud.id] = { lateLoad: true };
        } else {
            configureVisualizer(aud);
        }
    }
}

function unconfigureAudio(enabled) {
    if (enabled) return;
    upNext = [];
    clearAudioSource();
    for (let id in visualizers) {
        if (visualizers[id] && !visualizers[id].pending && !visualizers[id].lateLoad) {
            visualizers[id].stop();
            visualizers[id].destroy();
        }
        delete visualizers[id];
    }
}

function clearAudioSource() {
    for (let id in audioSource) {
        let aud = audioSource[id];
        if (aud && aud.started && aud.context.state != "closed") {
            aud.context.close();
        }
    }
    audioSource = {};
    audioMap = {};
}

// ── Recording ─────────────────────────────────────────────────────

function newAudioStream(h) {
    return {
        onaudioupdate: () => {},
        onaudiosttupdate: (msg) => { if (h) h(msg); },
        onaudiouerror: () => {}
    };
}

function stopRecording() {
    if (recorder) {
        if (recorder.recorder) recorder.recorder.stop();
        if (recorder.stream) recorder.stream.getTracks().forEach(track => track.stop());
        if (recorder.motionAnalyzer) recorder.motionAnalyzer.destroy();
        recorder = undefined;
    }
}

function recordWithVisualizer(stream, handler, saveHandler, options) {
    let page = getPage();
    if (recorder) return recorder.view;

    let config = {
        maxSilenceSeconds: (options && options.maxSilenceSeconds) || null,
        silenceThreshold: (options && options.silenceThreshold) || 3,
        chunkInterval: (options && options.chunkInterval) || 2000,
        autoRestart: options ? options.autoRestart !== false : true,
        maxSilenceBeforeRestart: (options && options.maxSilenceBeforeRestart) || 5
    };

    recorder = { uid: page.uid ? page.uid() : Math.random().toString(36) };

    recorder.view = m("div", {
        id: "recorder-visualizer",
        class: "w-[80%]",
        oncreate: function(vnode) {
            if (visualizers.recorder) {
                try { visualizers.recorder.destroy(); } catch (e) {}
                delete visualizers.recorder;
            }

            navigator.mediaDevices.getUserMedia({
                audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
            }).then(mediaStream => {
                let audioCtx = new AudioContext();
                let analyzer = audioCtx.createAnalyser();
                analyzer.fftSize = 2048;
                analyzer.smoothingTimeConstant = 0.8;

                recorder.analyzer = analyzer;
                recorder.context = audioCtx;
                recorder.stream = mediaStream;

                let source = audioCtx.createMediaStreamSource(mediaStream);
                source.connect(analyzer);

                if (typeof AudioMotionAnalyzer !== 'undefined') {
                    let props = { source: analyzer, height: 40, overlay: true, bgAlpha: 0, gradient: 'prism', showBgColor: true, showSource: false, showScaleY: false, showScaleX: false, volume: 0 };
                    try {
                        recorder.motionAnalyzer = new AudioMotionAnalyzer(vnode.dom, props);
                        recorder.motionAnalyzer.volume = 0;
                        visualizers.recorder = recorder.motionAnalyzer;
                    } catch (e) {
                        console.error("[audio] Error creating visualizer:", e);
                    }
                }

                let chunks = [];
                let mediaRecorder;
                try {
                    mediaRecorder = new MediaRecorder(mediaStream, { mimeType: 'audio/webm;codecs=opus' });
                } catch (e) {
                    try { mediaRecorder = new MediaRecorder(mediaStream); } catch (e2) { return; }
                }
                recorder.recorder = mediaRecorder;

                function ab_b64(buf) {
                    return btoa(buf.reduce((data, val) => data + String.fromCharCode(val), ""));
                }
                async function getAudioBase64(blob) {
                    let buff = await blob.arrayBuffer();
                    return ab_b64(new Uint8Array(buff));
                }
                async function sendChunk(chunk, close) {
                    let b64 = await getAudioBase64(chunk);
                    if (b64 && page.wss) await page.wss.send("audio", b64, undefined, undefined);
                    if (close) page.audioStream = undefined;
                }

                mediaRecorder.ondataavailable = event => {
                    if (event.data && event.data.size > 0) {
                        chunks.push(event.data);
                        sendChunk(event.data);
                    }
                };
                mediaRecorder.onstop = () => {
                    if (chunks.length) {
                        let audioBlob = new Blob(chunks, { type: mediaRecorder.mimeType || 'audio/webm' });
                        if (saveHandler) {
                            getAudioBase64(audioBlob).then(b64 => { if (b64) saveHandler(mediaRecorder.mimeType || "audio/webm", b64); });
                        }
                        chunks = [];
                    }
                };

                if (handler) page.audioStream = newAudioStream(handler);
                mediaRecorder.start(config.chunkInterval);
            }).catch(err => {
                console.error('[audio] Microphone access error:', err);
                page.toast("error", "Could not access microphone");
            });
        },
        onremove: function() { stopRecording(); }
    });

    return recorder.view;
}

function toggleRecord() {
    recording = !recording;
}

function recordButton() {
    return m("button", { class: "button", onclick: toggleRecord },
        m("span", { class: "material-symbols-outlined" }, "adaptive_audio_mic" + (recording ? "" : "_off")));
}

function recordField(ctl) {
    if (!recognition) {
        let SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
        if (SpeechRecognition) {
            recognition = new SpeechRecognition();
            recognition.continuous = true;
            recognition.lang = 'en-US';
            recognition.interimResults = true;
        } else {
            return m("span", "");
        }
    }

    let finalTranscript = '';
    return m('button', {
        class: "p-1 rounded " + (recording ? 'bg-red-100 dark:bg-red-900 animate-pulse' : ''),
        onclick: function() {
            if (recording) { recognition.stop(); return; }
            let baseText = ctl(null);
            if (baseText && baseText.length > 0) baseText += " ";
            recording = true;
            finalTranscript = '';

            recognition.onresult = (event) => {
                let interimTranscript = '';
                for (let i = event.resultIndex; i < event.results.length; ++i) {
                    let transcript = event.results[i][0].transcript;
                    if (event.results[i].isFinal) {
                        finalTranscript += transcript.trim() + ' ';
                        baseText += transcript.trim() + ' ';
                    } else {
                        interimTranscript += transcript;
                    }
                }
                ctl(baseText + interimTranscript);
                m.redraw();
            };
            recognition.onend = () => { recording = false; m.redraw(); };
            recognition.start();
        }
    }, m('span', { class: 'material-symbols-outlined text-xl' }, recording ? 'mic' : 'mic_off'));
}

// ── Public API ────────────────────────────────────────────────────

const audio = {
    configureAudio,
    unconfigureAudio,
    togglePlayAudio,
    createAudioVisualizer,
    createAudioSource: createAudioSourceLegacy,
    clearAudioSource,
    hasAudioMap: (name) => !!audioMap[name],
    recordButton,
    recordField,
    recordWithVisualizer,
    stopRecording,
    stopAudioSources,
    registerAudioSource,
    unregisterAudioSource,
    stopAllAudioSourceControllers,
    recording: () => recording,
    base64ToArrayBuffer
};

export { audio };
export default audio;
