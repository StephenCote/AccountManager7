/**
 * audioComponents.js — Audio source controller + visualizer components (ESM)
 * Selective port of Ux7 client/components/audioComponents.js
 *
 * Provides: AudioSourceController, AudioVisualizer, SimpleAudioPlayer
 */
import m from 'mithril';
import { am7model } from '../core/model.js';

function getClient() { return am7model._client; }
function getPage() { return am7model._page; }

function simpleHash(str) {
    let hash = 0;
    if (!str || str.length === 0) return hash.toString(36);
    for (let i = 0; i < str.length; i++) {
        let c = str.charCodeAt(i);
        hash = ((hash << 5) - hash) + c;
        hash = hash & hash;
    }
    return Math.abs(hash).toString(36);
}

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

// ── AudioSourceController ─────────────────────────────────────────

function createAudioSourceController(attrs) {
    let page = getPage();
    let am7client = getClient();

    let state = {
        id: attrs.id || (page.uid ? page.uid() : Math.random().toString(36)),
        name: attrs.name,
        profileId: attrs.profileId,
        content: attrs.content,
        context: null,
        buffer: null,
        sourceNode: null,
        analyzerNode: null,
        isPlaying: false,
        isLoading: false,
        error: null,
        onPlayStateChange: attrs.onPlayStateChange || (() => {}),
        autoStopOthers: attrs.autoStopOthers !== false
    };

    async function synthesizeAudio() {
        if (!state.content || state.content.length === 0) {
            state.error = "No content provided";
            return false;
        }

        state.isLoading = true;
        state.error = null;
        m.redraw();

        try {
            let cleanContent = state.content
                .replace(/([\u2700-\u27BF]|[\uE000-\uF8FF]|\uD83C[\uDC00-\uDFFF]|\uD83D[\uDC00-\uDFFF]|[\u2011-\u26FF]|\uD83E[\uDD10-\uDDFF])/g, "")
                .replace(/["\*]+/g, "");

            let vprops = { text: cleanContent, speed: 1.2, voiceProfileId: state.profileId };
            if (!vprops.voiceProfileId) {
                vprops.engine = "piper";
                vprops.speaker = "en_GB-alba-medium";
            }

            let response = await m.request({
                method: 'POST',
                url: am7client.base() + "/voice/" + state.name,
                withCredentials: true,
                body: vprops
            });

            state.context = new AudioContext();
            state.analyzerNode = state.context.createAnalyser();
            state.analyzerNode.fftSize = 2048;
            state.analyzerNode.smoothingTimeConstant = 0.8;

            let arrayBuffer = base64ToArrayBuffer(response.dataBytesStore);
            state.buffer = await state.context.decodeAudioData(arrayBuffer);

            state.isLoading = false;
            m.redraw();
            return true;
        } catch (err) {
            console.error("[audioComponents] Synthesis error:", state.name, err);
            state.error = err.message || "Failed to synthesize audio";
            state.isLoading = false;
            m.redraw();
            return false;
        }
    }

    function createSourceNode() {
        if (!state.context || !state.buffer) return null;
        let source = state.context.createBufferSource();
        source.buffer = state.buffer;
        source.connect(state.analyzerNode);
        state.analyzerNode.connect(state.context.destination);
        source.onended = () => {
            state.isPlaying = false;
            state.sourceNode = null;
            state.onPlayStateChange(false);
            m.redraw();
        };
        return source;
    }

    async function play() {
        if (state.autoStopOthers && page.components.audio) {
            page.components.audio.stopAudioSources();
            page.components.audio.stopAllAudioSourceControllers(api);
        }

        if (!state.buffer && !state.isLoading) {
            let success = await synthesizeAudio();
            if (!success) return;
        }
        if (state.isLoading || state.isPlaying) return;
        if (!state.context) return;

        if (state.context.state === "suspended") await state.context.resume();

        state.sourceNode = createSourceNode();
        if (!state.sourceNode) return;

        try {
            state.sourceNode.start(0);
            state.isPlaying = true;
            state.onPlayStateChange(true);
            m.redraw();
        } catch (err) {
            console.error("[audioComponents] Play error:", err);
            state.isPlaying = false;
            state.sourceNode = null;
        }
    }

    function pause() {
        if (state.context && state.context.state === "running") {
            state.context.suspend();
            state.isPlaying = false;
            state.onPlayStateChange(false);
            m.redraw();
        }
    }

    function resume() {
        if (state.context && state.context.state === "suspended") {
            state.context.resume();
            state.isPlaying = true;
            state.onPlayStateChange(true);
            m.redraw();
        }
    }

    function stop() {
        if (state.sourceNode) {
            try { state.sourceNode.stop(); } catch (e) {}
            state.sourceNode = null;
        }
        if (state.context && state.context.state !== "closed") state.context.suspend();
        state.isPlaying = false;
        state.onPlayStateChange(false);
        m.redraw();
    }

    function togglePlayPause() {
        if (state.isPlaying) pause();
        else if (state.context && state.context.state === "suspended" && state.sourceNode) resume();
        else play();
    }

    function destroy() {
        stop();
        if (state.context && state.context.state !== "closed") state.context.close();
        if (page.components.audio && page.components.audio.unregisterAudioSource) {
            page.components.audio.unregisterAudioSource(state.id);
        }
        state.context = null;
        state.buffer = null;
        state.sourceNode = null;
        state.analyzerNode = null;
    }

    let api = {
        state,
        play, pause, resume, stop, togglePlayPause, destroy,
        synthesizeAudio,
        getAnalyzerNode: () => state.analyzerNode,
        getContext: () => state.context,
        isPlaying: () => state.isPlaying,
        isLoading: () => state.isLoading,
        hasError: () => state.error !== null
    };

    if (page.components.audio && page.components.audio.registerAudioSource) {
        page.components.audio.registerAudioSource(state.id, api);
    }

    return api;
}

// ── AudioVisualizer Component ─────────────────────────────────────

function AudioVisualizer() {
    function tryCreateVisualizer(vnode, attrs) {
        if (vnode.state.visualizer) return;
        let currentAudioSource = attrs.audioSource || vnode.state.audioSourceController;
        if (!currentAudioSource) return;

        let analyzerNode = currentAudioSource.getAnalyzerNode();
        if (!analyzerNode) return;

        let height = vnode.state.calculatedHeight || attrs.height || 60;

        if (typeof AudioMotionAnalyzer === 'undefined') return;

        let props = {
            source: analyzerNode,
            height: height,
            overlay: true,
            bgAlpha: attrs.bgAlpha !== undefined ? attrs.bgAlpha : 0,
            gradient: attrs.gradient || 'prism',
            showBgColor: attrs.showBgColor !== undefined ? attrs.showBgColor : true,
            showSource: false, showScaleY: false, showScaleX: false,
            ...(attrs.visualizerProps || {})
        };

        try {
            vnode.state.visualizer = new AudioMotionAnalyzer(vnode.dom, props);
            if (vnode.state.checkInterval) {
                clearInterval(vnode.state.checkInterval);
                vnode.state.checkInterval = null;
            }
        } catch (err) {
            console.error("[audioComponents] Visualizer error:", err);
        }
    }

    return {
        oncreate: function(vnode) {
            vnode.state.visualizer = null;
            vnode.state.checkInterval = null;
            vnode.state.audioSourceController = vnode.attrs.audioSource;
            vnode.state.calculatedHeight = vnode.attrs.height || (vnode.dom ? vnode.dom.clientHeight : 60);
            tryCreateVisualizer(vnode, vnode.attrs);
            if (!vnode.state.visualizer) {
                vnode.state.checkInterval = setInterval(() => tryCreateVisualizer(vnode, vnode.attrs), 100);
            }
        },
        onremove: function(vnode) {
            if (vnode.state.checkInterval) clearInterval(vnode.state.checkInterval);
            if (vnode.state.visualizer) {
                try { vnode.state.visualizer.stop(); vnode.state.visualizer.destroy(); } catch (e) {}
                vnode.state.visualizer = null;
            }
        },
        view: function(vnode) {
            return m("div", {
                class: "audio-visualizer " + (vnode.attrs.class || ""),
                style: vnode.attrs.style || "",
                onclick: (e) => {
                    e.stopPropagation();
                    let src = vnode.attrs.audioSource;
                    if (src) src.togglePlayPause();
                }
            });
        }
    };
}

// ── SimpleAudioPlayer Component ───────────────────────────────────

function SimpleAudioPlayer() {
    return {
        oninit: function(vnode) {
            let attrs = vnode.attrs;
            vnode.state.visualizer = null;
            vnode.state.checkInterval = null;
            vnode.state.autoPlayInitiated = false;

            vnode.state.audioSource = createAudioSourceController({
                id: attrs.id,
                name: attrs.name,
                profileId: attrs.profileId,
                content: attrs.content,
                autoStopOthers: attrs.autoStopOthers,
                onPlayStateChange: (isPlaying) => {
                    if (attrs.onPlayStateChange) attrs.onPlayStateChange(isPlaying);
                    m.redraw();
                }
            });
        },
        oncreate: function(vnode) {
            let attrs = vnode.attrs;

            if ((attrs.autoLoad || attrs.autoPlay) && !vnode.state.audioSource.state.buffer && !vnode.state.audioSource.state.isLoading) {
                setTimeout(async () => {
                    if (vnode.state.audioSource) {
                        await vnode.state.audioSource.synthesizeAudio();
                    }
                }, 50);
            }

            if (attrs.autoPlay && !vnode.state.autoPlayInitiated) {
                vnode.state.autoPlayInitiated = true;
                setTimeout(() => {
                    if (vnode.state.audioSource) vnode.state.audioSource.play();
                }, attrs.autoPlayDelay || 100);
            }
        },
        onremove: function(vnode) {
            if (vnode.state.checkInterval) clearInterval(vnode.state.checkInterval);
            if (vnode.state.visualizer) {
                try { vnode.state.visualizer.stop(); vnode.state.visualizer.destroy(); } catch (e) {}
            }
            if (vnode.state.audioSource) vnode.state.audioSource.destroy();
        },
        view: function(vnode) {
            let attrs = vnode.attrs;
            let audioSrc = vnode.state.audioSource;
            if (!audioSrc) return m("div");

            let isPlaying = audioSrc.isPlaying();
            let isLoading = audioSrc.isLoading();
            let hasError = audioSrc.hasError();

            return m("div", { class: "flex items-center gap-2 p-2 rounded bg-gray-50 dark:bg-gray-800 " + (attrs.class || "") }, [
                // Play/pause button
                m("button", {
                    class: "p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700 " + (isPlaying ? "text-blue-500" : "text-gray-600 dark:text-gray-400"),
                    disabled: isLoading,
                    onclick: () => audioSrc.togglePlayPause()
                }, m("span", { class: "material-symbols-outlined text-xl" },
                    isLoading ? "sync" : isPlaying ? "pause" : "play_arrow")),

                // Visualizer area
                m("div", {
                    id: (attrs.id || "") + "-viz",
                    class: "flex-1 h-[40px] cursor-pointer",
                    onclick: () => audioSrc.togglePlayPause()
                }),

                // Status
                hasError ? m("span", { class: "text-xs text-red-500" }, "Error") : null,

                // Delete button
                attrs.onDelete ? m("button", {
                    class: "p-1 rounded hover:bg-red-100 dark:hover:bg-red-900 text-gray-400 hover:text-red-500",
                    onclick: () => { audioSrc.destroy(); if (attrs.onDelete) attrs.onDelete(); }
                }, m("span", { class: "material-symbols-outlined text-lg" }, "delete")) : null
            ]);
        }
    };
}

// ── Export ─────────────────────────────────────────────────────────

const audioComponents = {
    createAudioSourceController,
    AudioVisualizer,
    SimpleAudioPlayer,
    simpleHash,
    base64ToArrayBuffer
};

export { audioComponents, createAudioSourceController, AudioVisualizer, SimpleAudioPlayer };
export default audioComponents;
