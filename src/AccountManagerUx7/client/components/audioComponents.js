(function () {
    /**
     * Simple hash function for generating stable identifiers from content
     */
    function simpleHash(str) {
        let hash = 0;
        if (!str || str.length === 0) return hash.toString(36);
        for (let i = 0; i < str.length; i++) {
            let char = str.charCodeAt(i);
            hash = ((hash << 5) - hash) + char;
            hash = hash & hash; // Convert to 32bit integer
        }
        return Math.abs(hash).toString(36);
    }

    /**
     * AudioSource Component - Manages audio playback with Web Audio API
     *
     * Features:
     * - Handles audio synthesis and buffering
     * - Manages playback state (play/pause/stop)
     * - Recreates buffer source nodes for replay (since they're one-shot)
     * - Provides analyzer node for visualization
     * - Auto-stops other audio sources when playing (optional)
     */

    function createAudioSource(attrs) {
        let state = {
            id: attrs.id || page.uid(),
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
                console.log("Synthesizing audio for:", state.name);

                // Clean content (remove emojis, quotes, etc.)
                let cleanContent = state.content
                    .replace(/([\u2700-\u27BF]|[\uE000-\uF8FF]|\uD83C[\uDC00-\uDFFF]|\uD83D[\uDC00-\uDFFF]|[\u2011-\u26FF]|\uD83E[\uDD10-\uDDFF])/g, "")
                    .replace(/["\*]+/g, "");

                // Prepare voice synthesis request
                let vprops = {
                    text: cleanContent,
                    speed: 1.2,
                    voiceProfileId: state.profileId
                };

                if (!vprops.voiceProfileId) {
                    vprops.engine = "piper";
                    vprops.speaker = "en_GB-alba-medium";
                }

                console.log("Requesting synthesis with props:", vprops);

                // Request audio synthesis
                let response = await m.request({
                    method: 'POST',
                    url: g_application_path + "/rest/voice/" + state.name,
                    withCredentials: true,
                    body: vprops
                });

                console.log("Synthesis response received for:", state.name);

                // Create audio context and decode
                state.context = new AudioContext();
                state.analyzerNode = state.context.createAnalyser();
                state.analyzerNode.fftSize = 2048;
                state.analyzerNode.smoothingTimeConstant = 0.8;

                // Decode audio data
                let arrayBuffer = base64ToArrayBuffer(response.dataBytesStore);
                state.buffer = await state.context.decodeAudioData(arrayBuffer);

                console.log("Audio decoded successfully for:", state.name);

                state.isLoading = false;
                m.redraw();
                return true;

            } catch (err) {
                console.error("Error synthesizing audio for", state.name, ":", err);
                state.error = err.message || "Failed to synthesize audio";
                state.isLoading = false;
                m.redraw();
                return false;
            }
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

        function createSourceNode() {
            if (!state.context || !state.buffer) {
                console.warn("Cannot create source node: context or buffer missing");
                return null;
            }

            // Create new buffer source (required for replay)
            let source = state.context.createBufferSource();
            source.buffer = state.buffer;

            // Connect: source -> analyzer -> destination
            source.connect(state.analyzerNode);
            state.analyzerNode.connect(state.context.destination);

            // Handle playback end
            source.onended = () => {
                state.isPlaying = false;
                state.sourceNode = null;
                state.onPlayStateChange(false);
                m.redraw();
            };

            return source;
        }

        async function play() {
            // Auto-stop other audio sources (both old and new systems)
            if (state.autoStopOthers) {
                if (page.components.audio) {
                    // Stop old system audio sources
                    page.components.audio.stopAudioSources();
                    // Stop new system audio source controllers
                    page.components.audio.stopAllAudioSourceControllers(api);
                }
            }

            // Load audio if not already loaded
            if (!state.buffer && !state.isLoading) {
                let success = await synthesizeAudio();
                if (!success) {
                    console.error("Failed to synthesize audio for:", state.name);
                    return;
                }
            }

            // Wait for loading to complete
            if (state.isLoading) {
                console.warn("Audio is still loading:", state.name);
                return;
            }

            // If already playing, don't start again
            if (state.isPlaying) {
                console.warn("Audio is already playing:", state.name);
                return;
            }

            // Ensure we have a context
            if (!state.context) {
                console.error("No audio context available");
                return;
            }

            // Resume context if suspended
            if (state.context.state === "suspended") {
                await state.context.resume();
            }

            // Always create a new source node (Web Audio API requirement)
            state.sourceNode = createSourceNode();
            if (!state.sourceNode) {
                console.error("Failed to create source node");
                return;
            }

            // Start playback
            try {
                state.sourceNode.start(0);
                state.isPlaying = true;
                state.onPlayStateChange(true);
                m.redraw();
            } catch (err) {
                console.error("Error starting audio:", err, state.name);
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
                try {
                    state.sourceNode.stop();
                } catch (e) {
                    // May already be stopped
                }
                state.sourceNode = null;
            }
            if (state.context && state.context.state !== "closed") {
                state.context.suspend();
            }
            state.isPlaying = false;
            state.onPlayStateChange(false);
            m.redraw();
        }

        function togglePlayPause() {
            console.log("togglePlayPause called on audio source:", state.name, "isPlaying:", state.isPlaying);
            if (state.isPlaying) {
                pause();
            } else if (state.context && state.context.state === "suspended" && state.sourceNode) {
                resume();
            } else {
                play();
            }
        }

        function destroy() {
            stop();
            if (state.context && state.context.state !== "closed") {
                state.context.close();
            }
            // Unregister from audio system
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
            play,
            pause,
            resume,
            stop,
            togglePlayPause,
            destroy,
            getAnalyzerNode: () => state.analyzerNode,
            getContext: () => state.context,
            isPlaying: () => state.isPlaying,
            isLoading: () => state.isLoading,
            hasError: () => state.error !== null
        };

        // Register with audio system
        if (page.components.audio && page.components.audio.registerAudioSource) {
            page.components.audio.registerAudioSource(state.id, api);
            console.log("Registered audio source:", state.id, "name:", state.name);
        }

        return api;
    }


    /**
     * AudioVisualizer Component - Mithril component for audio visualization
     *
     * Features:
     * - Uses AudioMotionAnalyzer for visualization
     * - Click to play/pause
     * - Configurable appearance
     * - Auto-cleanup on remove
     */

    function AudioVisualizer() {
        function tryCreateVisualizer(vnode, attrs) {
            if (vnode.state.visualizer) return; // Already created

            // Use the current audioSource from attrs
            let currentAudioSource = attrs.audioSource || vnode.state.audioSourceController;
            if (!currentAudioSource) {
                return;
            }

            let analyzerNode = currentAudioSource.getAnalyzerNode();
            if (!analyzerNode) {
                // Analyzer not ready yet, will retry
                return;
            }

            // Use calculated height from DOM or provided height
            let height = vnode.state.calculatedHeight || attrs.height || 60;

            // Default visualizer properties
            let props = {
                source: analyzerNode,
                height: height,
                overlay: true,
                bgAlpha: attrs.bgAlpha !== undefined ? attrs.bgAlpha : 0,
                gradient: attrs.gradient || 'prism',
                showBgColor: attrs.showBgColor !== undefined ? attrs.showBgColor : true,
                showSource: false,
                showScaleY: false,
                showScaleX: false,
                ...(attrs.visualizerProps || {})
            };

            try {
                vnode.state.visualizer = new AudioMotionAnalyzer(vnode.dom, props);
                console.log("AudioMotionAnalyzer created successfully for audio source:", currentAudioSource.state.name, "height:", height);

                // Clear the check interval once created
                if (vnode.state.checkInterval) {
                    clearInterval(vnode.state.checkInterval);
                    vnode.state.checkInterval = null;
                }
            } catch (err) {
                console.error("Error creating AudioMotionAnalyzer:", err);
            }
        }

        return {
            oncreate: function(vnode) {
                let attrs = vnode.attrs;

                // Store state on vnode.state to prevent sharing between component instances
                vnode.state.visualizer = null;
                vnode.state.checkInterval = null;
                vnode.state.audioSourceController = attrs.audioSource;

                if (!vnode.state.audioSourceController) {
                    console.warn("AudioVisualizer: No audio source provided");
                    return;
                }

                // Calculate actual height from DOM element if not specified
                let actualHeight = attrs.height;
                if (!actualHeight && vnode.dom) {
                    actualHeight = vnode.dom.clientHeight;
                }

                // Store calculated height for visualizer creation
                vnode.state.calculatedHeight = actualHeight || 60;

                // Try to create visualizer immediately
                tryCreateVisualizer(vnode, attrs);

                // If not ready, check periodically until analyzer is available
                if (!vnode.state.visualizer) {
                    vnode.state.checkInterval = setInterval(() => {
                        tryCreateVisualizer(vnode, attrs);
                    }, 100);
                }
            },

            onremove: function(vnode) {
                // Clear check interval if still running
                if (vnode.state.checkInterval) {
                    clearInterval(vnode.state.checkInterval);
                    vnode.state.checkInterval = null;
                }

                // Destroy visualizer if created
                if (vnode.state.visualizer) {
                    try {
                        vnode.state.visualizer.stop();
                        vnode.state.visualizer.destroy();
                    } catch (err) {
                        console.warn("Error destroying visualizer:", err);
                    }
                    vnode.state.visualizer = null;
                }
            },

            view: function(vnode) {
                let attrs = vnode.attrs;
                let cssClass = "audio-visualizer " + (attrs.class || "");

                return m("div", {
                    class: cssClass,
                    style: attrs.style || "",
                    onclick: (e) => {
                        e.stopPropagation();
                        // Always use the current audioSource from attrs, not the cached one
                        let currentAudioSource = attrs.audioSource;
                        if (currentAudioSource) {
                            console.log("AudioVisualizer clicked, audio source ID:", currentAudioSource.state.id, "name:", currentAudioSource.state.name);
                            console.log("About to call togglePlayPause on:", currentAudioSource.state.name);
                            currentAudioSource.togglePlayPause();
                        } else {
                            console.warn("AudioVisualizer clicked but no audio source available");
                        }
                    }
                });
            }
        };
    }


    /**
     * SimpleAudioPlayer Component - Complete audio player with visualizer
     *
     * Combines AudioSource controller and AudioVisualizer component
     * for easy integration with play/pause/delete controls
     */

    function SimpleAudioPlayer() {
        return {
            oninit: function(vnode) {
                let attrs = vnode.attrs;

                console.log("SimpleAudioPlayer oninit - ID:", attrs.id, "Name:", attrs.name, "Key:", attrs.key);

                // Store state on vnode.state to prevent sharing between component instances
                vnode.state.visualizer = null;
                vnode.state.checkInterval = null;
                vnode.state.autoPlayInitiated = false;

                // Create audio source controller and store on vnode.state
                vnode.state.audioSource = createAudioSource({
                    id: attrs.id,
                    name: attrs.name,
                    profileId: attrs.profileId,
                    content: attrs.content,
                    autoStopOthers: attrs.autoStopOthers,
                    onPlayStateChange: (isPlaying) => {
                        if (attrs.onPlayStateChange) {
                            attrs.onPlayStateChange(isPlaying);
                        }
                        m.redraw();
                    }
                });

                console.log("SimpleAudioPlayer created audio source:", vnode.state.audioSource.state.name);
            },

            oncreate: function(vnode) {
                let attrs = vnode.attrs;
                console.log("SimpleAudioPlayer oncreate - ID:", attrs.id, "Looking for element:", attrs.id + "-viz");

                // Auto-load audio (synthesize without playing) if requested
                if ((attrs.autoLoad || attrs.autoPlay) && !vnode.state.audioSource.state.buffer && !vnode.state.audioSource.state.isLoading) {
                    console.log("Auto-loading audio for:", vnode.state.audioSource.state.name);
                    // Trigger synthesis by calling the internal synthesizeAudio
                    // We need to access the internal state, so let's just trigger a play and immediately pause
                    setTimeout(async () => {
                        if (vnode.state.audioSource && !vnode.state.audioSource.state.buffer) {
                            // Manually call synthesize
                            let state = vnode.state.audioSource.state;
                            if (!state.content || state.content.length === 0) {
                                return;
                            }

                            state.isLoading = true;
                            state.error = null;
                            m.redraw();

                            try {
                                console.log("Synthesizing audio for:", state.name);
                                let cleanContent = state.content
                                    .replace(/([\u2700-\u27BF]|[\uE000-\uF8FF]|\uD83C[\uDC00-\uDFFF]|\uD83D[\uDC00-\uDFFF]|[\u2011-\u26FF]|\uD83E[\uDD10-\uDDFF])/g, "")
                                    .replace(/["\*]+/g, "");

                                let vprops = {
                                    text: cleanContent,
                                    speed: 1.2,
                                    voiceProfileId: state.profileId
                                };

                                if (!vprops.voiceProfileId) {
                                    vprops.engine = "piper";
                                    vprops.speaker = "en_GB-alba-medium";
                                }

                                let response = await m.request({
                                    method: 'POST',
                                    url: g_application_path + "/rest/voice/" + state.name,
                                    withCredentials: true,
                                    body: vprops
                                });

                                state.context = new AudioContext();
                                state.analyzerNode = state.context.createAnalyser();
                                state.analyzerNode.fftSize = 2048;
                                state.analyzerNode.smoothingTimeConstant = 0.8;

                                let arrayBuffer = base64ToArrayBuffer(response.dataBytesStore);
                                state.buffer = await state.context.decodeAudioData(arrayBuffer);

                                console.log("Audio decoded successfully for:", state.name);
                                state.isLoading = false;
                                m.redraw();
                            } catch (err) {
                                console.error("Error synthesizing audio for", state.name, ":", err);
                                state.error = err.message || "Failed to synthesize audio";
                                state.isLoading = false;
                                m.redraw();
                            }
                        }
                    }, 50);
                }

                // Auto-play if requested (only once on first oncreate)
                if (attrs.autoPlay && !vnode.state.autoPlayInitiated) {
                    vnode.state.autoPlayInitiated = true;
                    console.log("Initiating auto-play for:", vnode.state.audioSource.state.name);
                    setTimeout(() => {
                        if (vnode.state.audioSource) {
                            vnode.state.audioSource.play();
                        }
                    }, attrs.autoPlayDelay || 100);
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

                // Create visualizer for this specific audio source
                let tryCreateVisualizer = () => {
                    if (vnode.state.visualizer) {
                        console.log("Visualizer already exists for:", vnode.state.audioSource.state.name);
                        return;
                    }

                    let analyzerNode = vnode.state.audioSource.getAnalyzerNode();
                    if (!analyzerNode) {
                        console.log("No analyzer node yet for:", vnode.state.audioSource.state.name);
                        return; // Not ready yet
                    }

                    let vizElement = document.getElementById(attrs.id + "-viz");
                    if (!vizElement) {
                        console.warn("No viz element found for ID:", attrs.id + "-viz");
                        return;
                    }

                    console.log("Creating AudioMotionAnalyzer for:", vnode.state.audioSource.state.name, "on element:", attrs.id + "-viz");

                    let props = {
                        source: analyzerNode,
                        height: attrs.height || 60,
                        overlay: true,
                        bgAlpha: attrs.bgAlpha !== undefined ? attrs.bgAlpha : 0,
                        gradient: attrs.gradient || 'prism',
                        showBgColor: attrs.showBgColor !== undefined ? attrs.showBgColor : true,
                        showSource: false,
                        showScaleY: false,
                        showScaleX: false,
                        ...(attrs.visualizerProps || {})
                    };

                    try {
                        vnode.state.visualizer = new AudioMotionAnalyzer(vizElement, props);
                        console.log("AudioMotionAnalyzer successfully created for:", vnode.state.audioSource.state.name, "Element ID:", attrs.id + "-viz");

                        if (vnode.state.checkInterval) {
                            clearInterval(vnode.state.checkInterval);
                            vnode.state.checkInterval = null;
                        }
                    } catch (err) {
                        console.error("Error creating AudioMotionAnalyzer:", err);
                    }
                };

                // Try immediately
                tryCreateVisualizer();

                // If not ready, check periodically
                if (!vnode.state.visualizer) {
                    vnode.state.checkInterval = setInterval(tryCreateVisualizer, 100);
                }
            },

            onremove: function(vnode) {
                // Clean up interval if still running
                if (vnode.state.checkInterval) {
                    clearInterval(vnode.state.checkInterval);
                    vnode.state.checkInterval = null;
                }

                // Clean up visualizer
                if (vnode.state.visualizer) {
                    try {
                        vnode.state.visualizer.destroy();
                    } catch (e) {
                        console.error("Error destroying visualizer:", e);
                    }
                    vnode.state.visualizer = null;
                }

                // Clean up audio source
                if (vnode.state.audioSource) {
                    vnode.state.audioSource.destroy();
                    vnode.state.audioSource = null;
                }
            },

            view: function(vnode) {
                let attrs = vnode.attrs;
                let audioSource = vnode.state.audioSource;

                if (!audioSource) {
                    return m("div", { class: "audio-player-error min-h-[60px] flex items-center justify-center" },
                        "Audio player not initialized"
                    );
                }

                if (audioSource.hasError()) {
                    return m("div", {
                        class: "audio-player-error min-h-[60px] flex items-center justify-center gap-2 text-red-500"
                    }, [
                        m("span", { class: "material-icons text-sm" }, "error"),
                        m("span", { class: "text-xs" }, "Failed to load audio")
                    ]);
                }

                if (audioSource.isLoading()) {
                    return m("div", {
                        class: "audio-player-loading min-h-[60px] flex items-center justify-center gap-2"
                    }, [
                        m("span", { class: "material-icons text-sm animate-spin" }, "refresh"),
                        m("span", { class: "text-xs" }, "Loading...")
                    ]);
                }

                let isPlaying = audioSource.isPlaying();

                return m("div", {
                    class: (attrs.visualizerClass || "") + " audio-player-container relative block rounded-lg overflow-hidden",
                    style: `height: ${attrs.height || 60}px; min-width: 80%;`,
                    key: attrs.id + "-container"
                }, [
                    // Visualizer div - AudioMotionAnalyzer will be created directly on this element
                    m("div", {
                        key: attrs.id + "-viz",
                        id: attrs.id + "-viz",
                        class: "w-full h-full block cursor-pointer",
                        style: attrs.visualizerStyle || "",
                        onclick: (e) => {
                            e.stopPropagation();
                            console.log("Visualizer div clicked - Element ID:", attrs.id + "-viz");
                            if (vnode.state.audioSource) {
                                console.log("  Audio source name:", vnode.state.audioSource.state.name);
                                console.log("  Audio source ID:", vnode.state.audioSource.state.id);
                                console.log("  Has visualizer:", !!vnode.state.visualizer);
                                vnode.state.audioSource.togglePlayPause();
                            } else {
                                console.error("  No audio source in vnode.state!");
                            }
                        }
                    }),

                    // Small controls overlay in bottom-right corner
                    m("div", {
                        key: attrs.id + "-controls",
                        class: "absolute bottom-2 right-2 flex items-center gap-2 z-10"
                    }, [
                        // Play/Pause indicator (small icon, semi-transparent)
                        m("div", {
                            class: "p-1 rounded-full bg-black/30 dark:bg-white/30",
                            title: isPlaying ? "Playing" : "Paused"
                        }, m("span", { class: "material-icons text-base text-white" }, isPlaying ? "volume_up" : "volume_off")),

                        // Delete button (if onDelete provided)
                        attrs.onDelete && m("button", {
                            class: "audio-control-btn p-1 rounded-full bg-red-500/70 hover:bg-red-600 transition-colors",
                            onclick: (e) => {
                                e.stopPropagation();
                                if (audioSource) {
                                    audioSource.stop();
                                }
                                attrs.onDelete();
                            },
                            title: "Delete audio"
                        }, m("span", { class: "material-icons text-base text-white" }, "close"))
                    ])
                ]);
            }
        };
    }


    /**
     * Magic8Ball Component - Dual audio visualizer in a circular design
     *
     * Shows two audio sources (typically assistant and user) in a split-circle
     * visualization with profile images as background
     */

    function Magic8Ball() {
        let audioSource1 = null;  // Top/Assistant
        let audioSource2 = null;  // Bottom/User
        let lastContentHash = null;
        let backgroundImages = [];
        let imageBaseGroupsLoaded = false;

        function getContentHash(messages) {
            if (!messages || !messages.length) return null;
            let lastTwo = messages.slice(-2);
            return lastTwo.map(m => m?.content || "").join("|");
        }

        return {
            oninit: function(vnode) {
                // Initialize state on vnode.state
                vnode.state.bgImageIndex = 0;
                vnode.state.bgAnimationInterval = null;
                vnode.state.imageA_src = null;
                vnode.state.imageB_src = null;
                vnode.state.isA_onTop = false;
                vnode.state.isTransitioning = false;
            },

            oncreate: function(vnode) {
                let attrs = vnode.attrs;
                let messages = attrs.messages || [];
                let contentHash = getContentHash(messages);

                if (!messages.length || messages.length < 2) {
                    return;
                }

                lastContentHash = contentHash;

                // Load background images from group IDs if not in profile mode
                let useProfile = attrs.useProfile !== false;  // Default to true
                let sysUrl = attrs.systemProfileImageUrl;
                let usrUrl = attrs.userProfileImageUrl;

                if (!useProfile && !sysUrl && !usrUrl && attrs.imageBaseGroups && attrs.imageBaseGroups.length > 0 && !imageBaseGroupsLoaded) {
                    imageBaseGroupsLoaded = true;
                    console.log("Loading Magic8Ball background images from groups:", attrs.imageBaseGroups);

                    let q = am7client.newQuery("data.data");
                    let qg = q.field(null, null);
                    qg.comparator = "group_or";
                    qg.fields = attrs.imageBaseGroups.map((groupId) => {
                        return {
                            name: "groupId",
                            comparator: "equals",
                            value: groupId
                        };
                    });
                    q.range(0, 0);

                    page.search(q).then((res) => {
                        if (res && res.results) {
                            backgroundImages = res.results.map((r) => {
                                return g_application_path + "/media/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + r.groupPath + "/" + r.name;
                            });

                            console.log("Loaded", backgroundImages.length, "background images for Magic8Ball");

                            if (backgroundImages.length > 0) {
                                vnode.state.imageA_src = backgroundImages[Math.floor(Math.random() * backgroundImages.length)];
                                vnode.state.imageB_src = backgroundImages[Math.floor(Math.random() * backgroundImages.length)];
                                m.redraw();
                            }
                        }
                    }).catch((err) => {
                        console.error("Error loading Magic8Ball background images:", err);
                    });
                }

                // Get last two messages
                let messagesToProcess = messages.slice(-2);
                let sysProfileId = attrs.systemProfileId;
                let usrProfileId = attrs.userProfileId;
                let prune = attrs.pruneContent || ((c) => c);

                // Create audio source controllers
                messagesToProcess.forEach((m, i) => {
                    if (!m) return;

                    let content = prune(m.content);
                    if (!content || content.length === 0) return;

                    // Generate stable name based on chat objectId, role, and content hash
                    let contentHash = simpleHash(content);
                    let name = attrs.instanceId + "-" + m.role + "-" + contentHash;
                    let profId = (m.role === "assistant") ? sysProfileId : usrProfileId;

                    let audioSource = createAudioSource({
                        id: name,
                        name: name,
                        profileId: profId,
                        content: content,
                        autoStopOthers: true,
                        onPlayStateChange: () => {
                            // Trigger Mithril redraw using global m object
                            if (window.m && window.m.redraw) {
                                window.m.redraw();
                            }
                        }
                    });

                    if (m.role === "assistant") {
                        audioSource1 = audioSource;
                    } else {
                        audioSource2 = audioSource;
                    }
                });

                // Auto-play the most recent message
                setTimeout(() => {
                    if (messagesToProcess.length > 0) {
                        let lastMsg = messagesToProcess[messagesToProcess.length - 1];
                        if (lastMsg.role === "assistant" && audioSource1) {
                            audioSource1.play();
                        } else if (lastMsg.role === "user" && audioSource2) {
                            audioSource2.play();
                        }
                    }
                }, 100);
            },

            onupdate: function(vnode) {
                let attrs = vnode.attrs;
                let messages = attrs.messages || [];
                let contentHash = getContentHash(messages);

                // Check if content has changed
                if (contentHash !== lastContentHash) {
                    // Clean up old audio sources
                    if (audioSource1) {
                        audioSource1.destroy();
                        audioSource1 = null;
                    }
                    if (audioSource2) {
                        audioSource2.destroy();
                        audioSource2 = null;
                    }

                    // Recreate in next frame
                    lastContentHash = contentHash;
                    setTimeout(() => {
                        this.oncreate(vnode);
                    }, 0);
                }
            },

            onremove: function() {
                // Clean up audio sources
                if (audioSource1) {
                    audioSource1.destroy();
                    audioSource1 = null;
                }
                if (audioSource2) {
                    audioSource2.destroy();
                    audioSource2 = null;
                }
            },

            view: function(vnode) {
                let attrs = vnode.attrs;
                let sysUrl = attrs.systemProfileImageUrl;
                let usrUrl = attrs.userProfileImageUrl;

                console.log("Magic8Ball view - sysUrl:", sysUrl, "usrUrl:", usrUrl, "bgImages:", backgroundImages.length);

                // Image transition handler (called on image load)
                let imageTransition = () => {
                    if (vnode.state.isTransitioning || backgroundImages.length === 0) return;
                    vnode.state.isTransitioning = true;

                    setTimeout(() => {
                        if (vnode.state.isA_onTop) {
                            vnode.state.imageA_src = backgroundImages[Math.floor(Math.random() * backgroundImages.length)];
                        } else {
                            vnode.state.imageB_src = backgroundImages[Math.floor(Math.random() * backgroundImages.length)];
                        }
                        vnode.state.isA_onTop = !vnode.state.isA_onTop;
                        vnode.state.isTransitioning = false;
                        m.redraw();
                    }, 3000);
                };

                let imageClasses = 'absolute top-0 left-0 w-full h-full rounded-full object-cover transition-all ease-in-out duration-300';

                return m("div", {
                    key: "magic8-container",
                    class: `
                        relative aspect-square w-[90vw] max-w-[800px] max-h-[800px] mx-auto
                        rounded-full overflow-hidden
                        ring-2 ring-white/20
                    `
                }, [
                    // Background images with crossfade if no profile images
                    !sysUrl && !usrUrl && backgroundImages.length > 0 && m("div", {
                        class: "absolute top-0 left-0 w-full h-full rounded-full overflow-hidden",
                        style: "z-index: 0;"
                    }, [
                        // Image A
                        vnode.state.imageA_src && m("img", {
                            key: "bg-A",
                            src: vnode.state.imageA_src,
                            class: `${imageClasses} ${vnode.state.isA_onTop && vnode.state.isTransitioning ? 'opacity-0 blur-md' : 'opacity-50 blur-0'}`,
                            loading: "eager",
                            onload: !vnode.state.isA_onTop ? imageTransition : null,
                            onerror: () => {
                                console.error("Failed to load image:", vnode.state.imageA_src);
                                imageTransition();
                            }
                        }),
                        // Image B
                        vnode.state.imageB_src && m("img", {
                            key: "bg-B",
                            src: vnode.state.imageB_src,
                            class: `${imageClasses} ${!vnode.state.isA_onTop && vnode.state.isTransitioning ? 'opacity-0 blur-md' : 'opacity-50 blur-0'}`,
                            loading: "eager",
                            onload: vnode.state.isA_onTop ? imageTransition : null,
                            onerror: () => {
                                console.error("Failed to load image:", vnode.state.imageB_src);
                                imageTransition();
                            }
                        })
                    ]),

                    // System (assistant) profile image - upper hemisphere
                    sysUrl && m("img", {
                        class: `
                            absolute top-0 left-0 w-full h-1/2 z-0
                            pointer-events-none opacity-60 blur-sm object-cover
                        `,
                        src: sysUrl,
                        loading: "eager",
                        style: "object-position: top center;"
                    }),

                    // User profile image - lower hemisphere
                    usrUrl && m("img", {
                        class: `
                            absolute bottom-0 left-0 w-full h-1/2 z-0
                            pointer-events-none opacity-60 blur-sm object-cover
                        `,
                        src: usrUrl,
                        loading: "eager",
                        style: "object-position: top center;"
                    }),

                    // Top visualizer (assistant)
                    audioSource1 && m(AudioVisualizer, {
                        audioSource: audioSource1,
                        class: "absolute top-0 left-0 w-full h-1/2 z-10",
                        visualizerProps: {
                            overlay: true,
                            bgAlpha: 0,
                            gradient: 'prism',
                            showBgColor: true,
                            showSource: false,
                            showScaleY: false,
                            showScaleX: false
                        }
                    }),

                    // Bottom visualizer (user, inverted)
                    audioSource2 && m(AudioVisualizer, {
                        audioSource: audioSource2,
                        class: "absolute bottom-0 left-0 w-full h-1/2 z-10 transform scale-y-[-1]",
                        visualizerProps: {
                            overlay: true,
                            bgAlpha: 0,
                            gradient: 'prism',
                            showBgColor: true,
                            showSource: false,
                            showScaleY: false,
                            showScaleX: false
                        }
                    }),

                    // Overlay effects
                    m("div", {
                        class: "absolute inset-0 rounded-full pointer-events-none"
                    })
                ]);
            }
        };
    }


    // Export to page.components
    if (!page.components.audioComponents) {
        page.components.audioComponents = {};
    }

    page.components.audioComponents = {
        createAudioSource,
        AudioVisualizer,
        SimpleAudioPlayer,
        Magic8Ball,
        simpleHash
    };

}());
