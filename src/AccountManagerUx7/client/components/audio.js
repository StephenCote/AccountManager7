(function () {
    // Audio registry for component-based management
    const audioRegistry = {
        players: new Map(),      // messageId -> AudioPlayer instance
        visualizers: new Map(),  // containerId -> AudioVisualizer instance
        activePlayer: null,      // Currently playing messageId
        lastPlayedMessageId: null, // Track last played for view transitions
        queue: []                // Queued players
    };

    // Legacy maps (to be gradually migrated)
    let audioMap = {};
    let audioSource = {};
    let visualizers = {};
    let recorder;
    let upNext = [];

    // Utility: Hash content for message identification
    async function hashContent(str) {
        const encoder = new TextEncoder();
        const data = encoder.encode(str);
        const hashBuffer = await crypto.subtle.digest('SHA-256', data);
        const hashArray = Array.from(new Uint8Array(hashBuffer));
        const hashHex = hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
        return hashHex.substring(0, 16); // Use first 16 chars for brevity
    }

    // Utility: Generate unique message ID
    async function getMessageId(chatObjectId, role, content) {
        if (!content || !role) {
            console.warn("Invalid message parameters for ID generation");
            return null;
        }
        const hash = await hashContent(content.trim());
        return `${chatObjectId}-${role}-${hash}`;
    }

    // Utility: Synchronous hash for backward compatibility (less secure, but works)
    function hashContentSync(str) {
        let hash = 0;
        for (let i = 0; i < str.length; i++) {
            const char = str.charCodeAt(i);
            hash = ((hash << 5) - hash) + char;
            hash = hash & hash; // Convert to 32bit integer
        }
        return Math.abs(hash).toString(16).padStart(8, '0');
    }

    // Utility: Synchronous message ID generation
    function getMessageIdSync(chatObjectId, role, content) {
        if (!content || !role) {
            console.warn("Invalid message parameters for ID generation");
            return null;
        }
        const hash = hashContentSync(content.trim());
        return `${chatObjectId}-${role}-${hash}`;
    }

    // AudioPlayer Component - Manages audio playback for a single message
    const AudioPlayer = {
        oninit: function (vnode) {
            // Initialize component state
            vnode.state.messageId = vnode.attrs.messageId;
            vnode.state.profileId = vnode.attrs.profileId;
            vnode.state.content = vnode.attrs.content;
            vnode.state.autoPlay = vnode.attrs.autoPlay || false;
            vnode.state.error = false;
        },

        oncreate: function (vnode) {
            const { messageId, profileId, content } = vnode.state;

            // Check if player already exists in registry
            if (audioRegistry.players.has(messageId)) {
                vnode.state.player = audioRegistry.players.get(messageId);
                console.log("AudioPlayer: Reusing existing player for", messageId);
                return;
            }

            // Create new audio source
            console.log("AudioPlayer: Creating new player for", messageId);
            createAudioSource(messageId, profileId, content).then(audioData => {
                if (!audioData) {
                    console.error("AudioPlayer: Failed to create audio source");
                    vnode.state.error = true;
                    m.redraw();
                    return;
                }

                vnode.state.player = audioData;
                audioRegistry.players.set(messageId, audioData);

                if (vnode.state.autoPlay) {
                    togglePlayAudioSource(audioData, true);
                }

                m.redraw();
            }).catch(err => {
                console.error("AudioPlayer: Error creating audio source:", err);
                vnode.state.error = true;
                m.redraw();
            });
        },

        onupdate: function (vnode) {
            // Check if message content changed
            const newMessageId = vnode.attrs.messageId;
            const newContent = vnode.attrs.content;

            if (newMessageId !== vnode.state.messageId || newContent !== vnode.state.content) {
                console.log("AudioPlayer: Content changed, recreating player");

                // Stop old player if exists
                if (vnode.state.player) {
                    stopAudioSources(vnode.state.player);
                }

                // Update state
                vnode.state.messageId = newMessageId;
                vnode.state.content = newContent;
                vnode.state.profileId = vnode.attrs.profileId;
                vnode.state.player = null;
                vnode.state.error = false;

                // Recreate player (trigger oncreate logic)
                if (audioRegistry.players.has(newMessageId)) {
                    vnode.state.player = audioRegistry.players.get(newMessageId);
                } else {
                    createAudioSource(newMessageId, vnode.attrs.profileId, newContent).then(audioData => {
                        if (audioData) {
                            vnode.state.player = audioData;
                            audioRegistry.players.set(newMessageId, audioData);
                            m.redraw();
                        }
                    });
                }
            }
        },

        onremove: function (vnode) {
            // Don't destroy the audio context - just suspend it for potential reuse
            if (vnode.state.player && vnode.state.player.context) {
                if (vnode.state.player.context.state === "running") {
                    vnode.state.player.context.suspend();
                    console.log("AudioPlayer: Suspended context for", vnode.state.messageId);
                }
            }
            // Keep player in registry for reuse
        },

        view: function (vnode) {
            // Minimal UI - just return empty div for now
            // Actual controls and visualization handled by AudioVisualizer
            return m("div", {
                "data-player-id": vnode.state.messageId,
                style: "display: none;"
            });
        }
    };

    // Helper: Get or create audio player
    function getOrCreatePlayer(messageId, profileId, content) {
        if (audioRegistry.players.has(messageId)) {
            return Promise.resolve(audioRegistry.players.get(messageId));
        }

        return createAudioSource(messageId, profileId, content).then(audioData => {
            if (audioData) {
                audioRegistry.players.set(messageId, audioData);
            }
            return audioData;
        });
    }

    // Helper: Check if player exists (synchronous)
    function hasPlayer(messageId) {
        return audioRegistry.players.has(messageId);
    }

    // Helper: Pause all players without destroying
    function pauseAllPlayers(saveState = true) {
        let pausedAny = false;
        audioRegistry.players.forEach((player, messageId) => {
            if (player.context && player.context.state === "running") {
                player.context.suspend();
                console.log("Paused player:", messageId);
                if (saveState) {
                    audioRegistry.lastPlayedMessageId = messageId;
                }
                pausedAny = true;
            }
        });
        if (!saveState) {
            audioRegistry.activePlayer = null;
        }
        return pausedAny;
    }

    // Helper: Resume specific player
    function resumePlayer(messageId) {
        if (audioRegistry.players.has(messageId)) {
            const player = audioRegistry.players.get(messageId);
            if (player.context && player.context.state === "suspended") {
                player.context.resume();
                audioRegistry.activePlayer = messageId;
                audioRegistry.lastPlayedMessageId = messageId;
                console.log("Resumed player:", messageId);
                return true;
            }
        }
        return false;
    }

    // Helper: Get currently playing or last played message ID
    function getActiveOrLastMessageId() {
        // Check if any player is currently running
        for (let [messageId, player] of audioRegistry.players) {
            if (player.context && player.context.state === "running") {
                return messageId;
            }
        }
        // Return last played if available
        return audioRegistry.lastPlayedMessageId;
    }

    // Helper: Start playing a specific message
    function startPlayingMessage(messageId, autoStop = true) {
        if (!audioRegistry.players.has(messageId)) {
            console.warn("Cannot play - player not found:", messageId);
            return false;
        }

        const player = audioRegistry.players.get(messageId);

        if (autoStop) {
            // Stop all other players first
            audioRegistry.players.forEach((p, id) => {
                if (id !== messageId && p.context && p.context.state === "running") {
                    p.context.suspend();
                }
            });
        }

        togglePlayAudioSource(player, autoStop);
        audioRegistry.activePlayer = messageId;
        audioRegistry.lastPlayedMessageId = messageId;
        return true;
    }

    // AudioVisualizer Component - Renders visualization for audio
    const AudioVisualizer = {
        oninit: function (vnode) {
            vnode.state.messageId = vnode.attrs.messageId;
            vnode.state.mode = vnode.attrs.mode || 'inline'; // 'inline', 'magic8-top', 'magic8-bottom'
            vnode.state.containerId = vnode.attrs.containerId || `visualizer-${vnode.state.messageId}`;
            vnode.state.height = vnode.attrs.height || (vnode.state.mode === 'inline' ? 60 : undefined);
            vnode.state.onClick = vnode.attrs.onClick;
            vnode.state.error = false;
        },

        oncreate: function (vnode) {
            const { messageId, mode, containerId } = vnode.state;

            // Check if visualizer already exists for this container
            if (audioRegistry.visualizers.has(containerId)) {
                console.log("AudioVisualizer: Reusing existing visualizer for", containerId);
                vnode.state.analyzer = audioRegistry.visualizers.get(containerId);
                return;
            }

            // Wait for player to be ready
            let retryCount = 0;
            const maxRetries = 50; // 5 seconds max wait time

            const checkPlayer = () => {
                if (!audioRegistry.players.has(messageId)) {
                    if (retryCount++ < maxRetries) {
                        console.log("AudioVisualizer: Waiting for player", messageId);
                        setTimeout(checkPlayer, 100);
                    } else {
                        console.error("AudioVisualizer: Timeout waiting for player", messageId);
                        vnode.state.error = true;
                        m.redraw();
                    }
                    return;
                }

                const player = audioRegistry.players.get(messageId);
                if (!player) {
                    console.warn("AudioVisualizer: Player is null", messageId);
                    vnode.state.error = true;
                    m.redraw();
                    return;
                }

                // Check if source exists, if not wait a bit more
                if (!player.source) {
                    if (retryCount++ < maxRetries) {
                        console.log("AudioVisualizer: Waiting for player source", messageId, retryCount);
                        setTimeout(checkPlayer, 100);
                    } else {
                        console.error("AudioVisualizer: Timeout waiting for source", messageId);
                        vnode.state.error = true;
                        m.redraw();
                    }
                    return;
                }

                // Create visualizer
                createVisualizer(vnode, player);
            };

            checkPlayer();
        },

        onupdate: function (vnode) {
            const newMessageId = vnode.attrs.messageId;
            const { messageId } = vnode.state;

            // If message ID changed, recreate visualizer
            if (newMessageId !== messageId) {
                console.log("AudioVisualizer: Message changed, updating visualizer");

                // Destroy old visualizer
                if (vnode.state.analyzer) {
                    try {
                        vnode.state.analyzer.stop();
                        vnode.state.analyzer.destroy();
                        audioRegistry.visualizers.delete(vnode.state.containerId);
                    } catch (e) {
                        console.warn("Error destroying visualizer:", e);
                    }
                }

                // Update state
                vnode.state.messageId = newMessageId;
                vnode.state.containerId = vnode.attrs.containerId || `visualizer-${newMessageId}`;
                vnode.state.analyzer = null;
                vnode.state.error = false;

                // Recreate visualizer
                if (audioRegistry.players.has(newMessageId)) {
                    const player = audioRegistry.players.get(newMessageId);
                    createVisualizer(vnode, player);
                }
            }
            // If we had an error and now the player exists with a source, retry
            else if (vnode.state.error && audioRegistry.players.has(messageId)) {
                const player = audioRegistry.players.get(messageId);
                if (player && player.source) {
                    console.log("AudioVisualizer: Player now available after error, retrying", messageId);
                    vnode.state.error = false;
                    vnode.state.analyzer = null;
                    createVisualizer(vnode, player);
                }
            }
            // If we don't have an analyzer yet but player with source exists, create it
            else if (!vnode.state.analyzer && !vnode.state.error && audioRegistry.players.has(messageId)) {
                const player = audioRegistry.players.get(messageId);
                if (player && player.source && !audioRegistry.visualizers.has(vnode.state.containerId)) {
                    console.log("AudioVisualizer: Player available, creating visualizer", messageId);
                    createVisualizer(vnode, player);
                }
            }
        },

        onremove: function (vnode) {
            // Destroy visualizer when view changes
            if (vnode.state.analyzer) {
                try {
                    vnode.state.analyzer.stop();
                    vnode.state.analyzer.destroy();
                    audioRegistry.visualizers.delete(vnode.state.containerId);
                    console.log("AudioVisualizer: Destroyed visualizer for", vnode.state.containerId);
                } catch (e) {
                    console.warn("Error destroying visualizer:", e);
                }
            }
        },

        view: function (vnode) {
            const { containerId, mode, height, error, messageId } = vnode.state;

            if (error) {
                return m("div", {
                    id: containerId,
                    class: "audio-visualizer-error",
                    style: height ? `height: ${height}px` : ""
                }, "Audio visualization unavailable");
            }

            let classNames = "audio-visualizer";
            let styles = {};

            if (mode === 'inline') {
                classNames = "audio-container block w-full relative";
                if (height) styles.height = `${height}px`;
            } else if (mode === 'magic8-top') {
                classNames = "absolute top-0 left-0 w-full h-1/2 z-10";
            } else if (mode === 'magic8-bottom') {
                classNames = "absolute bottom-0 left-0 w-full h-1/2 z-10 transform scale-y-[-1]";
            }

            // Check if audio is playing for inline mode
            let isPlaying = false;
            if (audioRegistry.players.has(messageId)) {
                const player = audioRegistry.players.get(messageId);
                isPlaying = player.context && player.context.state === "running";
            }

            // Build the visualizer container
            const container = m("div", {
                id: containerId,
                class: classNames,
                style: styles,
                onclick: vnode.state.onClick || (() => {
                    if (audioRegistry.players.has(vnode.state.messageId)) {
                        togglePlayAudioSource(audioRegistry.players.get(vnode.state.messageId), true);
                    }
                })
            });

            // For inline mode, add play/pause overlay
            if (mode === 'inline') {
                return m("div", {
                    class: "relative",
                    style: styles
                }, [
                    container,
                    // Play/Pause overlay
                    m("div", {
                        class: "absolute inset-0 flex items-center justify-center pointer-events-none z-20",
                        style: {
                            opacity: isPlaying ? "0" : "0.7",
                            transition: "opacity 0.3s ease"
                        }
                    }, m("div", {
                        class: "bg-gray-800/80 rounded-full p-3"
                    }, m("span", {
                        class: "material-symbols-outlined text-white text-4xl"
                    }, isPlaying ? "pause" : "play_arrow")))
                ]);
            }

            return container;
        }
    };

    // Helper: Create visualizer for a player
    function createVisualizer(vnode, player) {
        const { containerId, mode, height } = vnode.state;
        const element = document.getElementById(containerId);

        if (!element) {
            console.warn("AudioVisualizer: Container element not found", containerId);
            setTimeout(() => createVisualizer(vnode, player), 100);
            return;
        }

        const props = {
            overlay: true,
            bgAlpha: 0,
            gradient: 'prism',
            showBgColor: true,
            showSource: false,
            showScaleY: false,
            showScaleX: false
        };

        if (mode === 'inline') {
            props.height = height || 60;
        } else if (mode.startsWith('magic8-')) {
            props.height = element.offsetHeight;
        }

        if (player.source) {
            props.source = player.source;
        }

        try {
            const analyzer = new AudioMotionAnalyzer(element, props);
            vnode.state.analyzer = analyzer;
            audioRegistry.visualizers.set(containerId, analyzer);
            console.log("AudioVisualizer: Created visualizer for", containerId);
            m.redraw();
        } catch (e) {
            console.error("AudioVisualizer: Error creating analyzer:", e);
            vnode.state.error = true;
            m.redraw();
        }
    }

    // Helper: Get or create visualizer
    function getOrCreateVisualizer(containerId, messageId, mode, height) {
        if (audioRegistry.visualizers.has(containerId)) {
            return audioRegistry.visualizers.get(containerId);
        }

        // Visualizer will be created via component lifecycle
        return null;
    }

    // Helper: Connect visualizer to player
    function connectVisualizerToPlayer(visualizer, player) {
        if (visualizer && player && player.source) {
            try {
                visualizer.connectInput(player.source);
                console.log("Connected visualizer to player");
            } catch (e) {
                console.warn("Error connecting visualizer to player:", e);
            }
        }
    }

    function newMagic8() {
        return {
            id: page.uid(),
            audioMotionTop: undefined,
            audioMotionBottom: undefined,
            audio1: undefined,
            audio1Content: undefined,
            audio2: undefined,
            audio2Content: undefined,
            lastAudio: 0,
            configuring: false,
            lastContent: undefined // Track content changes
        };
    }

    let magic8 = newMagic8();

    function clearMagic8(audioMagic8) {

        let preserveContent = magic8.lastContent;
        upNext = [];

        if (magic8.audio1) {
            stopAudioSources(magic8.audio1);
        }
        if (magic8.audio2) {
            stopAudioSources(magic8.audio2);
        }

        if (!audioMagic8) {
            clearAudioSource();
        }

        if (magic8.audioMotionTop) {
            magic8.audioMotionTop.stop();
            magic8.audioMotionTop.destroy();
        }
        if (magic8.audioMotionBottom) {
            magic8.audioMotionBottom.stop();
            magic8.audioMotionBottom.destroy();
        }

        let canvasTop = document.getElementById("waveform-top");
        let canvasBottom = document.getElementById("waveform-bottom");
        if (canvasTop) canvasTop.innerHTML = "";
        if (canvasBottom) canvasBottom.innerHTML = "";

        if (magic8.audio1 && magic8.audio1.name) {
            delete audioSource[magic8.audio1.name];
        }
        if (magic8.audio2 && magic8.audio2.name) {
            delete audioSource[magic8.audio2.name];
        }

        magic8 = newMagic8();

        if (audioMagic8) {
            magic8.lastContent = preserveContent;
        }
    }

    // NEW: Component-based Magic8 configuration
    function configureMagic8(inst, chatCfg, audioMagic8, prune) {
        if (!audioMagic8) {
            return;
        }

        let aMsg = chatCfg?.history?.messages;
        if (!aMsg || !aMsg.length) {
            return;
        }

        // Get last 2 messages for Magic8 display
        let messagesToProcess = aMsg.slice(-2);
        let currentContent = messagesToProcess.map(m => m?.content || "").join("|");

        // Check if content changed
        let contentChanged = magic8.lastContent !== currentContent;

        // Store message IDs and metadata for Magic8 view
        magic8.messages = [];
        const chatObjectId = inst.api.objectId();

        // Get profile IDs
        let sysProfileId = chatCfg?.system?.profile?.objectId;
        let usrProfileId = chatCfg?.user?.profile?.objectId;

        let mostRecentMessageId = null;

        messagesToProcess.forEach((msg, i) => {
            if (!msg) return;

            let profId = (msg.role === "assistant") ? sysProfileId : usrProfileId;
            let cnt = prune ? prune(msg.content) : msg.content;

            if (cnt && cnt.trim().length > 0) {
                // Generate hash-based message ID
                const messageId = getMessageIdSync(chatObjectId, msg.role, cnt);

                magic8.messages.push({
                    messageId,
                    role: msg.role,
                    content: cnt,
                    profileId: profId,
                    autoPlay: false // Don't auto-play, we'll handle it manually
                });

                if (i === messagesToProcess.length - 1) {
                    mostRecentMessageId = messageId;
                }

                // Create audio player in registry if doesn't exist
                if (!audioRegistry.players.has(messageId)) {
                    createAudioSource(messageId, profId, cnt).then((aud) => {
                        if (aud) {
                            audioRegistry.players.set(messageId, aud);

                            // Auto-play the most recent message if:
                            // 1. Content changed (new message)
                            // 2. This is the most recent message
                            // 3. We're in Magic8 mode
                            if (contentChanged && messageId === mostRecentMessageId && audioMagic8) {
                                console.log("Magic8: Auto-playing new message", messageId);
                                setTimeout(() => {
                                    startPlayingMessage(messageId, true);
                                }, 200);
                            }
                            // Resume playing if we were playing this message before
                            else if (!contentChanged && messageId === audioRegistry.lastPlayedMessageId) {
                                console.log("Magic8: Resuming playback", messageId);
                                setTimeout(() => {
                                    resumePlayer(messageId);
                                }, 200);
                            }

                            m.redraw(); // Trigger visualizer creation
                        }
                    }).catch(err => {
                        console.error("Error creating audio source for Magic8:", err);
                    });
                }
                // Player already exists - check if we should resume it
                else if (!contentChanged && messageId === audioRegistry.lastPlayedMessageId) {
                    console.log("Magic8: Resuming existing player", messageId);
                    setTimeout(() => {
                        resumePlayer(messageId);
                    }, 200);
                }
            }
        });

        magic8.lastContent = currentContent;
        magic8.configured = true;
    }

    // LEGACY: Old configureMagic8 (kept for backward compatibility, will be removed)
    function configureMagic8Legacy(inst, chatCfg, audioMagic8, prune) {
        if (!audioMagic8) {
            return;
        }

        if (magic8.configuring) {
            return;
        }

        let aMsg = chatCfg?.history?.messages;
        if (!aMsg || !aMsg.length) {
            return;
        }

        let messagesToProcess = aMsg.slice(-2);
        let currentContent = messagesToProcess.map(m => m?.content || "").join("|");

        if (magic8.lastContent === currentContent && magic8.audioMotionTop && magic8.audioMotionBottom) {
            return;
        }

        let canvasTop = document.getElementById("waveform-top");
        let canvasBottom = document.getElementById("waveform-bottom");
        if (!canvasTop || !canvasBottom) {
            return;
        }

        let contentChanged = magic8.lastContent !== currentContent;

        if (contentChanged) {
            clearMagic8(false);
            magic8.lastContent = currentContent;
        } else if (!magic8.audioMotionTop || !magic8.audioMotionBottom) {
        } else {
            return;
        }

        magic8.configuring = true;
        let aP = [];

        let sysProfileId = chatCfg?.system?.profile?.objectId;
        let usrProfileId = chatCfg?.user?.profile?.objectId;
        if (!sysProfileId || !usrProfileId) {
            console.warn("No system or user profile for chat");
            magic8.configuring = false;
            return;
        }

        if (contentChanged || !magic8.audio1 || !magic8.audio2) {
            messagesToProcess.forEach((m, i) => {
                if (!m) return;
                let actualIndex = aMsg.length - 1 + i;
                let name = inst.api.objectId() + " - " + actualIndex;
                let profId = (m.role == "assistant") ? sysProfileId : usrProfileId;

                let cnt = prune ? prune(m.content) : m.content;
                if (cnt.length) {
                    aP.push(createAudioSource(name, profId, cnt).then((aud) => {
                        if (!aud) return;

                        aud.name = name;

                        if (m.role == "assistant") {
                            console.log("Configure Magic8 audio 1 (assistant)");
                            magic8.audio1 = aud;
                            magic8.audio1Content = cnt;
                            magic8.lastAudio = 1;
                        } else {
                            console.log("Configure Magic8 audio 2 (user)");
                            magic8.audio2 = aud;
                            magic8.audio2Content = cnt;
                            magic8.lastAudio = 2;
                        }
                    }).catch(err => {
                        console.error("Error creating audio source for Magic8:", err);
                    }));
                }
            });
        }

        Promise.all(aP).then(() => {
            canvasTop = document.getElementById("waveform-top");
            canvasBottom = document.getElementById("waveform-bottom");
            if (!canvasTop || !canvasBottom) {
                magic8.configuring = false;
                return;
            }

            if (!magic8.audioMotionTop || !magic8.audioMotionBottom) {
                let props = {
                    overlay: true,
                    bgAlpha: 0,
                    gradient: 'prism',
                    showBgColor: true,
                    showSource: false,
                    showScaleY: false,
                    showScaleX: false
                };

                let props1 = Object.assign({ height: canvasTop.offsetHeight, source: magic8.audio1?.source }, props);
                let props2 = Object.assign({ height: canvasBottom.offsetHeight, source: magic8.audio2?.source }, props);

                magic8.audioMotionTop = new AudioMotionAnalyzer(canvasTop, props1);
                magic8.audioMotionBottom = new AudioMotionAnalyzer(canvasBottom, props2);

                canvasTop.onclick = function (e) {
                    togglePlayMagic8(magic8.audio1, magic8.audio2);
                };
                canvasBottom.onclick = function (e) {
                    togglePlayMagic8(magic8.audio2, magic8.audio1);
                };
            }

            upNext = [];

            if (contentChanged && magic8.lastAudio && magic8["audio" + magic8.lastAudio]) {
                let currentAudio = magic8["audio" + magic8.lastAudio];
                setTimeout(() => {
                    togglePlayAudioSource(currentAudio, true);
                }, 100);
            }

            magic8.configuring = false;
        }).catch(err => {
            console.error("Error configuring Magic8:", err);
            magic8.configuring = false;
        });
    }
    let bgAudio = false;
    let bgAnim = false;
    let bgImg = true;
    let images = [];
    /// At the moment, this is just a group id 
    let imgBase = [132, 1546, 1545, 1547];
    // let imgBase = [3261];
    let imgUrl;
    const imgCfg = {
        isA_onTop: false,
        isTransitioning: false,
        imageA_src: undefined,
        imageB_src: undefined
    };

    // The onload handler that triggers the visual transition
    const imageTransition = () => {
        // Prevent re-triggering during a transition
        if (imgCfg.isTransitioning) return;
        imgCfg.isTransitioning = true;
        setTimeout(() => {
            if (imgCfg.isA_onTop) {
                imgCfg.imageA_src = images[Math.floor(Math.random() * images.length)];
            }
            else {
                imgCfg.imageB_src = images[Math.floor(Math.random() * images.length)];
            }
            imgCfg.isA_onTop = !imgCfg.isA_onTop;
            imgCfg.isTransitioning = false;
            m.redraw();
        }, 3000);
    };

    function getMagic8View(chatCfg, profile) {

        // Get profile image URLs if available
        let sysUrl, usrUrl;
        if (profile) {
            if (chatCfg.system?.profile?.portrait) {
                let pp = chatCfg.system.profile.portrait;
                sysUrl = g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + pp.groupPath + "/" + pp.name + "/256x256";
            }
            if (chatCfg.user?.profile?.portrait) {
                let pp = chatCfg.user.profile.portrait;
                usrUrl = g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + pp.groupPath + "/" + pp.name + "/256x256";
            }
        }

        let gimg = "";
        if (bgImg && !profile && imgBase.length && images.length == 0) {
            // console.log("Loading magic8 images from base", imgBase);
            let q = am7client.newQuery("data.data");
            //q.field("groupId", imgBase);
            let qg = q.field(null, null);
            qg.comparator = "group_or";
            qg.fields = imgBase.map((a) => {
                return {
                    name: "groupId",
                    comparator: "equals",
                    value: a
                }
            });
            q.range(0, 0);
            imgBase = [];
            page.search(q).then((res) => {

                if (res && res.results) {
                    images = res.results.map((r) => {
                        return g_application_path + "/media/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + r.groupPath + "/" + r.name;
                    });
                    imgCfg.imageA_src = images[Math.floor(Math.random() * images.length)];
                    imgCfg.imageB_src = images[Math.floor(Math.random() * images.length)];

                    m.redraw();
                }
            });
        }
        if (bgImg && images.length > 0) {
            const imageClasses = 'absolute top-0 left-0 w-full h-full rounded-full object-cover transition-all ease-in-out duration-300';

            gimg = m("div", { class: 'relative w-full h-full' }, [
                // Image A
                m("img", {
                    key: 'A',
                    objectFit: "contain",
                    objectPosition: "center",
                    src: imgCfg.imageA_src,
                    class: `${imageClasses} ${imgCfg.isA_onTop && imgCfg.isTransitioning ? 'opacity-0 blur-md' : 'opacity-50 dark:opacity-50 blur-0'}`,
                    onload: !imgCfg.isA_onTop ? imageTransition : null,
                    onerror: () => { page.toast("error", "Failed to load image: " + imgCfg.imageA_src); imageTransition(); }
                }),
                // Image B
                m("img", {
                    key: 'B',
                    objectFit: "contain",
                    objectPosition: "center",
                    src: imgCfg.imageB_src,
                    class: `${imageClasses} ${!imgCfg.isA_onTop && imgCfg.isTransitioning ? 'opacity-0 blur-md' : 'opacity-50 dark:opacity-50 blur-0'}`,
                    onload: imgCfg.isA_onTop ? imageTransition : null,
                    onerror: () => { page.toast("error", "Failed to load image: " + imgCfg.imageB_src); imageTransition(); }
                })
            ]);

        }

        // bg-gradient-to-br from-slate-100 via-slate-200 to-slate-300 shadow-[inset_0_10px_20px_rgba(255,255,255,0.1),inset_0_-10px_20px_rgba(0,0,0,0.2)]
        return m("div", {
            key: "magic8-container", // Use a stable key instead of magic8.id
            class: `
      relative aspect-square w-[90vw] max-w-[800px] max-h-[800px] mx-auto
      rounded-full overflow-hidden
      
      ring-2 ring-white/20
    `
        }, [
            !sysUrl && !usrUrl && gimg,
            // System (assistant) profile image - upper hemisphere
            sysUrl && m("div", {
                class: `
        absolute top-0 left-0 w-full h-1/2 z-0
        pointer-events-none
        opacity-60 blur-sm
        bg-cover
      `,
                style: {
                    backgroundImage: `url('${sysUrl}')`,
                    backgroundPosition: "top center"
                }
            }),

            // User profile image - lower hemisphere
            usrUrl && m("div", {
                class: `
        absolute bottom-0 left-0 w-full h-1/2 z-0
        pointer-events-none
        opacity-60 blur-sm
        bg-cover
      `,
                style: {
                    backgroundImage: `url('${usrUrl}')`,
                    backgroundPosition: "top center"
                }
            }),

            // NEW: Component-based visualizers
            magic8.messages && magic8.messages.length > 0 && magic8.messages.map((msg, idx) => {
                const mode = msg.role === "assistant" ? "magic8-top" : "magic8-bottom";
                const containerId = `magic8-${msg.role}-${idx}`;

                return m(AudioVisualizer, {
                    key: msg.messageId,
                    messageId: msg.messageId,
                    containerId: containerId,
                    mode: mode,
                    onClick: () => {
                        if (audioRegistry.players.has(msg.messageId)) {
                            togglePlayAudioSource(audioRegistry.players.get(msg.messageId), true);
                        }
                    }
                });
            }),

            // LEGACY: Top waveform canvas (for backward compatibility)
            !magic8.messages && m("div", {
                id: "waveform-top",
                class: `
        absolute top-0 left-0 w-full h-1/2 z-10
      `
            }),

            // LEGACY: Bottom waveform canvas (inverted)
            !magic8.messages && m("div", {
                id: "waveform-bottom",
                class: `
        absolute bottom-0 left-0 w-full h-1/2 z-10
        transform scale-y-[-1]
      `
            }),
            /// bg-[radial-gradient(circle_at_50%_50%,rgba(255,255,255,0.15)_0%,transparent_70%)]
            // ...rest of your overlays and effects...
            m("div", {
                class: `
        absolute inset-0 rounded-full pointer-events-none
        
      `
            }),
            // bg-[radial-gradient(circle_at_30%_30%,rgba(255,255,255,0.3)_0%,transparent_40%)]
            m("div", {
                class: `
        absolute top-0 left-0 w-full h-full pointer-events-none rounded-full
        
        mix-blend-screen
      `
            }),
            // bg-gradient-to-t from-black/20 to-transparent
            m("div", {
                class: `
        absolute bottom-0 left-0 w-full h-1/2
        
        pointer-events-none
      `
            }),
            bgAnim && m("canvas", {
                id: "spiral-overlay",
                class: `
                    absolute z-20 pointer-events-none
                    opacity-20 w-full h-full top-0 left-0
                `,
                oncreate: ({ dom }) => {
                    drawSpiral(dom);
                    //drawMandala(dom);
                }
            })
        ]);
    }

    function drawMandala(canvas) {
        const ctx = canvas.getContext("2d");
        const width = canvas.width = canvas.offsetWidth;
        const height = canvas.height = canvas.offsetHeight;
        const centerX = width / 2;
        const centerY = height / 2;

        let frame = 0;

        function animate() {
            ctx.clearRect(0, 0, width, height);
            ctx.save();

            const pulse = 1 + 0.08 * Math.sin(frame * 0.02); // gentle scale
            const rotation = frame * 0.002;
            const hue = (frame * 0.6) % 360;
            const petals = 12;
            const layers = 4;

            ctx.translate(centerX, centerY);
            ctx.scale(pulse, pulse);
            ctx.rotate(rotation);

            for (let layer = 0; layer < layers; layer++) {
                const radius = 60 + layer * 30;
                const petalLength = 30 + layer * 10;
                const petalWidth = 12;

                for (let i = 0; i < petals; i++) {
                    const angle = (i * 2 * Math.PI) / petals;

                    const x = radius * Math.cos(angle);
                    const y = radius * Math.sin(angle);

                    ctx.save();
                    ctx.translate(x, y);
                    ctx.rotate(angle + rotation);

                    ctx.beginPath();
                    ctx.ellipse(0, 0, petalWidth, petalLength, 0, 0, 2 * Math.PI);
                    ctx.strokeStyle = `hsl(${(hue + i * 30) % 360}, 100%, 75%)`;
                    ctx.shadowColor = ctx.strokeStyle;
                    ctx.shadowBlur = 8;
                    ctx.lineWidth = 2;
                    ctx.stroke();

                    ctx.restore();
                }
            }

            ctx.restore();
            frame++;
            requestAnimationFrame(animate);
        }

        animate();
    }

    function drawSpiral(canvas) {
        const ctx = canvas.getContext("2d");
        const width = canvas.width = canvas.offsetWidth;
        const height = canvas.height = canvas.offsetHeight;
        const centerX = width / 2;
        const centerY = height / 2;

        let frame = 0;
        let spiralSpeed = 0.01;
        let pulseSpeed = 0.005;
        let hue = 0;

        function animate() {
            ctx.clearRect(0, 0, width, height);

            const pulse = 1 + 0.1 * Math.sin(frame * pulseSpeed); // gentle scale

            // Color cycling: hue shift over time
            hue = (frame * 0.5) % 360;
            const strokeColor = `hsl(${hue}, 100%, 80%)`;

            ctx.beginPath();
            ctx.strokeStyle = strokeColor;
            ctx.shadowColor = strokeColor;
            ctx.shadowBlur = 10;
            ctx.lineWidth = 2;

            let angle = 0;
            let radius = 0;
            ctx.moveTo(centerX, centerY);

            for (let i = 0; i < 2000; i++) {
                radius = i * 0.1 * pulse;
                angle += 0.02;
                const x = centerX + radius * Math.cos(angle + frame * spiralSpeed);
                const y = centerY + radius * Math.sin(angle + frame * spiralSpeed);
                ctx.lineTo(x, y);
            }

            ctx.stroke();
            frame++;
            requestAnimationFrame(animate);
        }

        animate();
    }

    function newRecorder() {
        return {
            uid: page.uid
        }
    }

    function togglePlayAudio(id) {
        let aud = document.getElementById(id);
        if (!aud) return;
        aud.paused ? aud.play() : aud.pause();
    }

    function isStreamSilent() {
        if (!recorder || !recorder.analyzer) {
            console.warn("Analyzer not available", recorder?.analyzer);
            return;
        }
        let threshold = 3;
        let bufferLength = recorder.analyzer.fftSize;
        let dataArray = new Uint8Array(bufferLength);

        // Get the current waveform data
        recorder.analyzer.getByteTimeDomainData(dataArray);

        // Find the maximum amplitude in the buffer
        let maxAmplitude = 0;
        for (let i = 0; i < bufferLength; i++) {
            // The values are 0-255, with 128 being the center (silence)
            let amplitude = Math.abs(dataArray[i] - 128);
            if (amplitude > maxAmplitude) {
                maxAmplitude = amplitude;
            }
        }
        console.log(maxAmplitude + " < " + threshold);
        return maxAmplitude <= threshold;
    }

    /// currently only setup for live streaming to extract text, not to actually record the audio
    ///
    function recordWithVisualizer(stream, handler, saveHandler, options = {}) {
        if (recorder) {
            return recorder.view;
        }

        // Default options
        let config = {
            maxSilenceSeconds: options.maxSilenceSeconds || null, // null = no auto-stop
            silenceThreshold: options.silenceThreshold || 3,
            chunkInterval: options.chunkInterval || 2000,
            autoRestart: options.autoRestart !== false, // default true
            maxSilenceBeforeRestart: options.maxSilenceBeforeRestart || 5
        };

        recorder = newRecorder();

        recorder.view = m("div", {
            id: "recorder-visualizer",
            class: "w-[80%]",
            oncreate: function (vnode) {
                // Check if a visualizer is already running
                if (visualizers.recorder) {
                    try {
                        visualizers.recorder.destroy();
                    } catch (e) {
                        console.warn("Error destroying existing visualizer:", e);
                    }
                    delete visualizers.recorder;
                }

                navigator.mediaDevices.getUserMedia({
                    audio: {
                        echoCancellation: true,
                        noiseSuppression: true,
                        autoGainControl: true
                    }
                })
                    .then(mediaStream => {
                        let audioCtx = new (window.AudioContext || window.webkitAudioContext)();
                        let analyzer = audioCtx.createAnalyser();
                        analyzer.fftSize = 2048;
                        analyzer.smoothingTimeConstant = 0.8;

                        recorder.analyzer = analyzer;
                        recorder.context = audioCtx;
                        recorder.stream = mediaStream;

                        let source = audioCtx.createMediaStreamSource(mediaStream);
                        source.connect(analyzer);

                        let props = {
                            source: analyzer,
                            height: 40,
                            overlay: true,
                            bgAlpha: 0,
                            gradient: 'prism',
                            showBgColor: true,
                            showSource: false,
                            showScaleY: false,
                            showScaleX: false,
                            volume: 0
                        };

                        try {
                            recorder.motionAnalyzer = new AudioMotionAnalyzer(vnode.dom, props);
                            recorder.motionAnalyzer.volume = 0;
                            visualizers.recorder = recorder.motionAnalyzer;
                        } catch (e) {
                            console.error("Error creating AudioMotionAnalyzer:", e);
                            vnode.dom.textContent = "Error: Could not create audio visualizer.";
                            return;
                        }

                        let chunks = [];
                        let mediaRecorder;

                        try {
                            mediaRecorder = new MediaRecorder(mediaStream, {
                                mimeType: 'audio/webm;codecs=opus'
                            });
                        } catch (e) {
                            try {
                                mediaRecorder = new MediaRecorder(mediaStream);
                            } catch (e2) {
                                console.error("MediaRecorder not supported:", e2);
                                vnode.dom.textContent = "Error: Recording not supported in this browser.";
                                return;
                            }
                        }

                        recorder.recorder = mediaRecorder;

                        let silenceCount = 0;
                        let totalSilenceTime = 0;
                        let silenceStartTime = null;
                        let isCurrentlySilent = false;

                        function ab_b64(buf) {
                            return btoa(buf.reduce(
                                function (data, val) {
                                    return data + String.fromCharCode(val);
                                },
                                ""
                            ));
                        }

                        async function getAudioBase64(blob) {
                            try {
                                let buff = await blob.arrayBuffer();
                                return ab_b64(new Uint8Array(buff));
                            } catch (e) {
                                console.error("Error converting audio to base64:", e);
                                return null;
                            }
                        }

                        async function sendChunk(chunk, close = false) {
                            try {
                                window.dbgBlob = chunk;
                                // console.log("Sending chunk...", chunk.size, "bytes");
                                let b64 = await getAudioBase64(chunk);

                                if (b64 && page.wss) {
                                    await page.wss.send("audio", b64, undefined, undefined);
                                }

                                if (close) {
                                    page.audioStream = undefined;
                                }
                            } catch (e) {
                                console.error("Error sending audio chunk:", e);
                            }
                        }

                        function checkSilence() {
                            let isSilent = isStreamSilent();
                            let currentTime = Date.now();

                            if (isSilent && !isCurrentlySilent) {
                                // Just became silent
                                isCurrentlySilent = true;
                                silenceStartTime = currentTime;
                            } else if (!isSilent && isCurrentlySilent) {
                                // Just stopped being silent
                                if (silenceStartTime) {
                                    let silenceDuration = (currentTime - silenceStartTime) / 1000;
                                    totalSilenceTime += silenceDuration;
                                }
                                isCurrentlySilent = false;
                                silenceStartTime = null;
                                silenceCount = 0; // Reset restart counter on speech
                            }

                            return isSilent;
                        }

                        mediaRecorder.ondataavailable = event => {
                            try {
                                if (event.data && event.data.size > 0) {
                                    let isSilent = checkSilence();

                                    if (!isSilent) {
                                        chunks.push(event.data);
                                        if (mediaStream && !mediaStream.getTracks().some(t => !t.enabled)) {
                                            sendChunk(event.data);
                                        }
                                    } else {
                                        silenceCount++;

                                        // Check for auto-restart based on silence chunks
                                        if (config.autoRestart && silenceCount >= config.maxSilenceBeforeRestart) {
                                            console.log("Restarting recorder due to prolonged silence...");
                                            silenceCount = 0;

                                            try {
                                                mediaRecorder.stop();
                                                setTimeout(() => {
                                                    if (recorder && recorder.recorder && recorder.recorder.state === 'inactive') {
                                                        mediaRecorder.start(config.chunkInterval);
                                                    }
                                                }, 100);
                                            } catch (e) {
                                                console.error("Error restarting recorder:", e);
                                            }
                                        }

                                        // Check for auto-stop based on total silence time
                                        if (config.maxSilenceSeconds) {
                                            let currentSilenceTime = isCurrentlySilent && silenceStartTime ?
                                                (Date.now() - silenceStartTime) / 1000 : 0;
                                            let totalCurrentSilence = totalSilenceTime + currentSilenceTime;

                                            if (totalCurrentSilence >= config.maxSilenceSeconds) {
                                                console.log(`Auto-stopping recording after ${totalCurrentSilence}s of silence`);
                                                toggleRecord(); // Stop recording
                                                return;
                                            }
                                        }
                                    }
                                } else {
                                    console.warn("No audio data in chunk");
                                }
                            } catch (e) {
                                console.error("Error processing audio data:", e);
                            }
                        };

                        mediaRecorder.onstop = () => {
                            try {
                                console.log("MediaRecorder stopped, processing", chunks.length, "chunks");

                                if (chunks.length === 0) {
                                    console.warn("No audio chunks to process");
                                    return;
                                }

                                let audioBlob = new Blob(chunks, {
                                    type: mediaRecorder.mimeType || 'audio/webm'
                                });

                                console.log("Created audio blob:", audioBlob.size, "bytes");

                                // Send final chunk if not streaming
                                if (!mediaStream || mediaStream.getTracks().some(t => !t.enabled)) {
                                    sendChunk(audioBlob, true);
                                }

                                // Save if handler provided
                                if (saveHandler) {
                                    getAudioBase64(audioBlob).then((b64) => {
                                        if (b64) {
                                            saveHandler(mediaRecorder.mimeType || "audio/webm", b64);
                                        }
                                    }).catch(e => {
                                        console.error("Error saving audio:", e);
                                    });
                                }

                                chunks = []; // Clear chunks
                            } catch (e) {
                                console.error("Error in onstop handler:", e);
                            }
                        };

                        mediaRecorder.onerror = (event) => {
                            console.error("MediaRecorder error:", event.error);
                            page.toast("error", "Recording error: " + (event.error?.message || "Unknown error"));
                        };

                        // Initialize audio stream handler
                        if (handler) {
                            page.audioStream = newAudioStream(handler);
                        }

                        // Start recording
                        try {
                            mediaRecorder.start(config.chunkInterval);
                            console.log("Recording started with config:", config);
                        } catch (e) {
                            console.error("Error starting recorder:", e);
                            vnode.dom.textContent = "Error: Could not start recording.";
                        }

                    }).catch(err => {
                        console.error('Error accessing microphone:', err);
                        let errorMsg = "Error: Could not access microphone.";

                        if (err.name === 'NotAllowedError') {
                            errorMsg = "Error: Microphone access denied. Please allow microphone access and try again.";
                        } else if (err.name === 'NotFoundError') {
                            errorMsg = "Error: No microphone found. Please check your audio devices.";
                        } else if (err.name === 'NotReadableError') {
                            errorMsg = "Error: Microphone is being used by another application.";
                        }

                        vnode.dom.textContent = errorMsg;
                        page.toast("error", errorMsg);
                    });
            },

            onremove: function () {
                stopRecording();
            }
        });

        return recorder?.view || "";
    }

    function newAudioStream(h) {
        return {
            onaudioupdate: (v) => {

            },
            onaudiosttupdate: (m) => {
                if (h) {
                    h(m);
                }
            },
            onaudiouerror: (v) => {

            }
        };
    }

    function stopRecording() {
        if (recorder) {
            if (recorder.recorder) {
                recorder.recorder.stop();
            }
            if (recorder.stream) {
                recorder.stream.getTracks().forEach(track => track.stop());
            }
            if (recorder.motionAnalyzer) {
                recorder.motionAnalyzer.destroy();
            }
            recorder = undefined;
        }
    }

    function extractText(dataB64) {
        window.dbgBase64 = dataB64;
        let vprops = { "audio_sample": dataB64, "uid": page.uid() };
        m.request({ method: 'POST', url: g_application_path + "/rest/voice/tts", withCredentials: true, body: vprops }).then((d) => {
            console.log("Received", d);
        }).catch((x) => {
            page.toast("error", "Failed to extract text from audio - is the audio service running?");
        });

    }

    function clearAudioSource() {
        // console.error("Clear audio sources");
        for (let id in audioSource) {
            let aud = audioSource[id];
            if (aud && aud.started && aud.context.state != "closed") {
                // aud.source.stop();
                aud.context.close();
            }
        }
        audioSource = {};
        audioMap = {};

    }
    function unconfigureAudio(enabled) {
        if (enabled) return;
        //clearMagic8(true);
        // console.info("Unconfiguring audio visualizers");

        upNext = [];
        clearAudioSource();
        for (let id in visualizers) {

            if (visualizers[id]) {
                if (!visualizers[id].pending && !visualizers[id].lateLoad) {
                    visualizers[id].stop();
                    visualizers[id].destroy();
                }
                delete visualizers[id];
            }
        }
    }

    function configureVisualizer(aud, autoPlay) {
        let props = {
            height: 60,
            overlay: true,
            bgAlpha: 0,
            showBgColor: true,
            showSource: false,
            gradient: "prism",
            showScaleY: false,
            showScaleX: false
        };

        if (visualizers[aud.id] && !visualizers[aud.id].lateLoad) {
            /// console.warn("Audio visualizer already configured for", aud.id);
            return;
        }

        visualizers[aud.id] = { pending: true };

        let oM = getAudioMapForContainer(aud.id);
        if (!oM) {
            console.warn("Failed to find map for " + aud.id);
            console.warn(audioMap);
            return;
        }
        console.info("Configuring audio visualizer for", aud.id, oM.name);
        //let cont = aud.parentNode;

        createAudioSource(oM.name, oM.profileId, oM.content).then((o) => {
            if (!o) {
                console.warn("Failed to retrieve audio source", oM.name);
                console.warn(audioMap);
                console.warn(audioSource);
                return;
            }
            let props1 = Object.assign({ source: o.source, onclick: function () { togglePlayAudioSource(o); } }, props);
            let audioMotion = new AudioMotionAnalyzer(aud, props1);
            visualizers[aud.id] = audioMotion;
            if (autoPlay || oM.autoPlay) {
                if (getRunningAudioSources().length > 0) {
                    upNext.push(o);
                }
                else {
                    togglePlayAudioSource(o);
                }
            }
        });
    }

    function configureAudio(enabled) {
        if (!enabled) {
            // unconfigureAudio();
            return;
        }
        //let aa = document.querySelectorAll("audio");
        let aa = document.querySelectorAll("div[id*='chatAudioContainer-']");

        for (let i = 0; i < aa.length; i++) {
            let aud = aa[i];
            if (visualizers[aud.id]) continue;
            if (i < (aa.length - 2)) {
                visualizers[aud.id] = { lateLoad: true };
            }
            else {
                configureVisualizer(aud);
            }
        }
    }

    function getAudioMapForContainer(containerId) {
        let aM = Object.values(audioMap).filter(k => k.containerId == containerId);
        if (!aM.length) {
            return;
        }
        return aM[0];
    }

    function getRunningAudioSources() {
        return Object.values(audioSource).filter(aud => aud.started && aud.context.state == "running");
    }

    function stopAudioSources(aud) {
        // Stop legacy audio sources
        let running = getRunningAudioSources();

        running.forEach(r => {
            console.log("Stopping other audio sources", r.id, aud?.id, (r.id != aud?.id), r);
            if (!aud || r.id != aud.id) {
                togglePlayAudioSource(r, false, true);
            }
        });

        // ALSO stop component-based players in registry
        audioRegistry.players.forEach((player, messageId) => {
            if (player.context && player.context.state === "running") {
                // Don't stop the audio we're about to play
                if (!aud || player !== aud) {
                    console.log("Stopping registry player:", messageId);
                    player.context.suspend();
                    player.started = false;
                }
            }
        });
    }


    function togglePlayMagic8(aud, aud2) {
        // console.log("Toggle play Magic8", aud, aud2);
        togglePlayAudioSource(aud, true);
    }

    function togglePlayAudioSource(aud, autoStop, noStart) {
        if (autoStop) {
            upNext = [];
        }
        if (typeof aud == "string") {
            if (visualizers[aud] && visualizers[aud].lateLoad) {
                console.log("Late configure visualizer", aud);
                stopAudioSources();
                configureVisualizer(document.querySelectorAll("div[id*='" + aud + "']")[0], true);
                return;
            }

            let am = getAudioMapForContainer(aud);
            if (am && audioSource[am.name]) {
                aud = audioSource[am.name];
            }

        }
        if (!aud) {
            console.warn("Invalid audio reference");
            return;
        }

        if (autoStop) {
            stopAudioSources(aud);
        }
        if (noStart) {
            return;
        }

        // Check if already started
        if (!aud.started) {
            // First time playing - create and start source
            if (!aud.context) {
                console.warn("Audio context not available");
                return;
            }

            // Re-create the source node for every playback to avoid DOMException
            aud.source = aud.context.createBufferSource();
            aud.source.buffer = aud.buffer;
            aud.source.connect(aud.context.destination);

            // Reconnect any visualizers that are using this audio source
            // Find visualizers connected to this player
            audioRegistry.visualizers.forEach((analyzer, containerId) => {
                // Check if this visualizer is for the same messageId
                // We need to find the messageId from the player
                let matchingMessageId = null;
                audioRegistry.players.forEach((player, msgId) => {
                    if (player === aud) {
                        matchingMessageId = msgId;
                    }
                });

                if (matchingMessageId && containerId.includes(matchingMessageId)) {
                    console.log("Reconnecting visualizer", containerId, "to new source");
                    try {
                        analyzer.disconnectInput();
                        analyzer.connectInput(aud.source);
                    } catch (e) {
                        console.warn("Error reconnecting visualizer:", e);
                    }
                }
            });

            if (aud.context.state === "suspended") {
                aud.context.resume().then(() => {
                    try {
                        aud.source.start(0);
                        aud.started = true;
                        // Trigger redraw if m is available
                        if (typeof m !== 'undefined' && m.redraw) {
                            m.redraw();
                        }
                    } catch (e) {
                        console.error("Error starting audio:", e);
                    }
                });
            } else {
                aud.source.start(0);
                aud.started = true;
                // Trigger redraw if m is available
                if (typeof m !== 'undefined' && m.redraw) {
                    m.redraw();
                }
            }

            aud.source.onended = function () {
                aud.started = false;
                // Trigger redraw if m is available
                if (typeof m !== 'undefined' && m.redraw) {
                    m.redraw();
                }
                if (upNext.length > 0) {
                    togglePlayAudioSource(upNext.shift());
                }
            };
        } else if (aud.context.state == "suspended") {
            // Resume paused audio
            console.log("Resume audio");
            aud.context.resume();
            // Trigger redraw if m is available
            if (typeof m !== 'undefined' && m.redraw) {
                m.redraw();
            }
        } else if (aud.context.state == "running") {
            // Pause playing audio
            console.log("Suspend audio");
            aud.context.suspend();
            // Trigger redraw if m is available
            if (typeof m !== 'undefined' && m.redraw) {
                m.redraw();
            }
        } else {
            console.error("Handle state " + aud.context.state);
        }
    }

    function createAudioVisualizer(name, idx, profileId, autoPlay, content) {
        let contId = "chatAudioContainer-" + (idx);
        let aud = m("div", { class: "audio-container block w-full", id: "chatAudioContainer-" + (idx), onclick: function () { togglePlayAudioSource(contId, true); } }, "");
        if (!audioMap[name]) {
            audioMap[name] = { id: page.uid(), index: idx, name, profileId, content, autoPlay, containerId: contId, pending: false };
        }

        return aud;
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

    async function createAudioSource(name, profileId, content) {
        if (content) {
            /// Strip emojis out - https://stackoverflow.com/questions/10992921/how-to-remove-emoji-code-using-javascript
            content = content.replace(/([\u2700-\u27BF]|[\uE000-\uF8FF]|\uD83C[\uDC00-\uDFFF]|\uD83D[\uDC00-\uDFFF]|[\u2011-\u26FF]|\uD83E[\uDD10-\uDDFF])/g, "");
            /// Escape quotes
            content = content.replace(/["\*]+/g, "");
        }
        let tmpAud;
        if (!content || content.length == 0) {
            console.warn("No content provided for audio source creation");
            return;
        }
        if (!audioMap[name]) {
            tmpAud = audioMap[name] = { name, profileId, content, pending: false };
        }
        if (!audioMap[name].data && !audioMap[name].pending) {
            audioMap[name].pending = true;
            let vprops = { "text": content, "speed": 1.2, voiceProfileId: profileId };
            if (!vprops.voiceProfileId) {
                vprops.engine = "piper";
                vprops.speaker = "en_GB-alba-medium";
            }
            console.log("Synthesize '" + name + "'");
            let d;
            try {
                d = await m.request({ method: 'POST', url: g_application_path + "/rest/voice/" + name, withCredentials: true, body: vprops });
            }
            catch (e) {
                console.error("Error synthesizing audio:", e);
                console.error(vprops);
            }
            if (!audioMap[name]) {
                console.error("Ruh-Roh, Raggy");
                audioMap[name] = tmpAud;
            }
            if (d) {
                audioMap[name].data = d;
                audioMap[name].pending = false;
            }
            else {
                page.toast("error", "Failed to synthesize audio - is the audio service running?");
                audioMap[name].error = true;
                audioMap[name].pending = false;
            };
        }

        if (audioMap[name] && audioMap[name].data && !audioMap[name].error && !audioMap[name].pending) {

            let o = audioMap[name].data;
            if (!audioSource[name]) {
                let audioContext = new AudioContext();
                let audioBuffer = await audioContext.decodeAudioData(base64ToArrayBuffer(o.dataBytesStore));
                // Create initial source node for visualizers
                let sourceNode = audioContext.createBufferSource();
                sourceNode.buffer = audioBuffer;
                sourceNode.connect(audioContext.destination);

                audioSource[name] = {
                    id: page.uid(),
                    context: audioContext,
                    buffer: audioBuffer,
                    started: false,
                    source: sourceNode
                }
            }
        }
        return audioSource[name];
    }

    let recording = false;

    function toggleRecord() {
        recording = !recording;
    }

    function recordButton() {
        return m("button", { class: "button", onclick: toggleRecord }, m("span", { class: "material-symbols-outlined material-icons-24" }, "adaptive_audio_mic" + (recording ? "" : "_off")));
    }

    function recordField(ctl) {
        if (!recognition) {
            // Find the correct SpeechRecognition object for the browser
            let SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;

            // Check if the browser supports the API
            if (SpeechRecognition) {
                recognition = new SpeechRecognition();

                // --- Configuration ---
                recognition.continuous = true; // Keep listening even after a pause
                recognition.lang = 'en-US';    // Set the language
                recognition.interimResults = true; // Get results as they are being spoken
            } else {
                // If not supported, check for specific browsers and show a message.
                const ua = navigator.userAgent.toLowerCase();
                if (ua.includes("firefox")) {
                    page.toast("info", "To enable speech-to-text, go to about:config and set media.webspeech.recognition.enable to true.", 10000);
                } else if (navigator.brave) {
                    page.toast("info", "To enable speech-to-text in Brave, go to brave://settings/shields and set 'Fingerprinting blocking' to 'Standard' or 'Allow all'.", 10000);
                }

                // If not supported, return an empty element so nothing is rendered.
                return m.fragment("");
            }
        }

        // The rest of the function remains the same as it was correctly implemented in the previous step.

        let currentHandler;
        let finalTranscript = '';

        return m('button.button', {
            class: (recording ? 'active animate-pulse' : '') + " p-1",
            onclick: function (e) {
                if (recording) {
                    recognition.stop();
                    return;
                }

                let baseText = ctl(null);
                if (baseText && baseText.length > 0) baseText += " ";

                recording = true;
                finalTranscript = '';

                recognition.onresult = (event) => {
                    let interimTranscript = '';
                    for (let i = event.resultIndex; i < event.results.length; ++i) {
                        const transcript = event.results[i][0].transcript;
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

                recognition.onend = () => {
                    recording = false;
                    m.redraw();
                };
                recognition.start();
            },
        }, m('span.material-symbols-outlined.material-icons-20', recording ? 'mic' : 'mic_off'));
    }

    let recognition;



    let toneCtx;
    let leftOsc, rightOsc;
    let leftGain, rightGain;
    let masterGain;
    let merger;
    let baseFreq = 440;
    let minBeat = 4;
    let maxBeat = 6;
    let sweepDurationMin = 10;
    let sweepInterval;

    function startBinauralSweep() {
        if (!bgAudio) {
            return;
        }
        toneCtx = new (window.AudioContext || window.webkitAudioContext)();

        // Oscillators
        leftOsc = toneCtx.createOscillator();
        rightOsc = toneCtx.createOscillator();

        // Gains
        leftGain = toneCtx.createGain();
        rightGain = toneCtx.createGain();
        masterGain = toneCtx.createGain();

        let dcBlocker = toneCtx.createBiquadFilter();
        dcBlocker.type = "highpass";
        dcBlocker.frequency.value = 20; // 20 Hz blocks DC


        // Set master gain to 0 before connecting
        masterGain.gain.setValueAtTime(0, toneCtx.currentTime);

        // Merger to route left/right to stereo
        merger = toneCtx.createChannelMerger(2);

        // Connect chains
        leftOsc.connect(leftGain).connect(merger, 0, 0); // Left channel
        rightOsc.connect(rightGain).connect(merger, 0, 1); // Right channel

        // Connect merger to master gain
        merger.connect(masterGain);

        // Connect master gain to DC blocker
        masterGain.connect(dcBlocker);

        // Connect DC blocker to destination
        dcBlocker.connect(toneCtx.destination);

        // Set frequencies
        leftOsc.frequency.setValueAtTime(baseFreq, toneCtx.currentTime);
        rightOsc.frequency.setValueAtTime(baseFreq + maxBeat, toneCtx.currentTime);

        // Start oscillators
        leftOsc.start();
        rightOsc.start();

        // Now ramp gain up smoothly AFTER oscillators are started, with a slight delay
        const rampTime = 0.5; // seconds
        const targetVolume = 0.35;
        const delay = 0.05; // 50ms delay

        const startTime = toneCtx.currentTime + delay;
        masterGain.gain.cancelScheduledValues(toneCtx.currentTime);
        masterGain.gain.setValueAtTime(0, toneCtx.currentTime);
        masterGain.gain.linearRampToValueAtTime(targetVolume, startTime + rampTime);

        // Start the frequency sweep
        scheduleSweep();
        sweepInterval = setInterval(scheduleSweep, sweepDurationMin * 60 * 1000);
    }

    function stopBinauralSweep() {
        if (!bgAudio) {
            return;
        }

        if (sweepInterval) clearInterval(sweepInterval);

        if (masterGain && toneCtx) {
            const now = toneCtx.currentTime;
            masterGain.gain.cancelScheduledValues(now);
            masterGain.gain.setValueAtTime(masterGain.gain.value, now);
            masterGain.gain.linearRampToValueAtTime(0, now + 0.3); // ramp down over 0.3s

            setTimeout(() => {
                if (leftOsc) {
                    try { leftOsc.stop(); } catch { }
                    leftOsc.disconnect();
                }
                if (rightOsc) {
                    try { rightOsc.stop(); } catch { }
                    rightOsc.disconnect();
                }
                if (masterGain) masterGain.disconnect();
                if (toneCtx) toneCtx.close();

                leftOsc = rightOsc = leftGain = rightGain = masterGain = merger = toneCtx = null;
            }, 320); // Wait for ramp down before stopping/disconnecting
        } else {
            // fallback: just stop everything
            if (leftOsc) { try { leftOsc.stop(); } catch { } leftOsc.disconnect(); }
            if (rightOsc) { try { rightOsc.stop(); } catch { } rightOsc.disconnect(); }
            if (masterGain) masterGain.disconnect();
            if (toneCtx) toneCtx.close();

            leftOsc = rightOsc = leftGain = rightGain = masterGain = merger = toneCtx = null;
        }
    }

    function scheduleSweep() {
        const now = toneCtx.currentTime;
        const halfCycle = (sweepDurationMin * 60) / 2;

        rightOsc.frequency.cancelScheduledValues(now);
        rightOsc.frequency.setValueAtTime(baseFreq + maxBeat, now);
        rightOsc.frequency.linearRampToValueAtTime(baseFreq + minBeat, now + halfCycle);
        rightOsc.frequency.linearRampToValueAtTime(baseFreq + maxBeat, now + 2 * halfCycle);
    }

    /*
    let toneCtx;
    let leftOsc, rightOsc;
    let leftGain, rightGain;
    let merger;

    function startBinauralSweep(baseFreq = 440, beatFreq = 4) {

      toneCtx = new (window.AudioContext || window.webkitAudioContext)();

      // Create two oscillators
      leftOsc = toneCtx.createOscillator();
      rightOsc = toneCtx.createOscillator();

      // Left: base frequency, Right: base + beat frequency
      leftOsc.frequency.value = baseFreq;
      rightOsc.frequency.value = baseFreq + beatFreq;

      // Create stereo panning
      leftGain = toneCtx.createGain();
      leftGain.gain.value = 0.2;
      rightGain = toneCtx.createGain();
    rightGain.gain.value = 0.2;
      // Pan hard left/right
      const splitter = toneCtx.createChannelSplitter(2);
      const merger = toneCtx.createChannelMerger(2);

      leftOsc.connect(leftGain);
      rightOsc.connect(rightGain);

      leftGain.connect(merger, 0, 0);  // left to left channel
      rightGain.connect(merger, 0, 1); // right to right channel

      merger.connect(toneCtx.destination);

      // Start oscillators
      leftOsc.start();
      rightOsc.start();
    }

    function stopBinauralSweep() {
        return;
      if (leftOsc) leftOsc.stop();
      if (rightOsc) rightOsc.stop();
      if (toneCtx) toneCtx.close();
    }
    */

    /**
     * Stop and cleanup all audio when chat conversation changes or is deleted
     */
    function stopAndCleanupAllAudio() {
        console.log("Stopping and cleaning up all audio");

        // Stop all component-based players in registry
        audioRegistry.players.forEach((player) => {
            if (player.context) {
                if (player.context.state === "running") {
                    player.context.suspend();
                }
                player.started = false;
            }
        });

        // Stop all legacy audio sources
        Object.values(audioSource).forEach(aud => {
            if (aud.context && aud.context.state === "running") {
                aud.context.suspend();
                aud.started = false;
            }
        });

        // Clear state tracking
        audioRegistry.activePlayer = null;
        audioRegistry.lastPlayedMessageId = null;
        audioRegistry.queue = [];

        // Destroy all visualizers
        audioRegistry.visualizers.forEach((analyzer) => {
            try {
                analyzer.stop();
                analyzer.destroy();
            } catch (e) {
                console.warn("Error destroying visualizer:", e);
            }
        });
        audioRegistry.visualizers.clear();

        // Clear legacy visualizers
        Object.values(visualizers).forEach(viz => {
            if (viz && typeof viz === 'object' && viz.destroy) {
                try {
                    viz.destroy();
                } catch (e) {
                    console.warn("Error destroying legacy visualizer:", e);
                }
            }
        });

        // Clear magic8 state
        if (magic8 && magic8.audio1) {
            if (magic8.audio1.context && magic8.audio1.context.state === "running") {
                magic8.audio1.context.suspend();
            }
        }
        if (magic8 && magic8.audio2) {
            if (magic8.audio2.context && magic8.audio2.context.state === "running") {
                magic8.audio2.context.suspend();
            }
        }
    }

    let audio = {
        // New component-based API
        AudioPlayer,
        AudioVisualizer,
        getOrCreatePlayer,
        hasPlayer,
        getOrCreateVisualizer,
        connectVisualizerToPlayer,
        pauseAllPlayers,
        resumePlayer,
        getActiveOrLastMessageId,
        startPlayingMessage,
        getMessageIdSync,
        getMessageId,
        hashContentSync,
        hashContent,
        stopAndCleanupAllAudio,

        // Legacy API
        configureAudio,
        unconfigureAudio,
        togglePlayAudio,
        createAudioVisualizer,
        createAudioSource,
        clearAudioSource: clearAudioSource,
        hasAudioMap: (name) => audioMap[name] ? true : false,
        recordButton,
        recordField,
        recordWithVisualizer,
        extractText,
        recording: () => recording,
        configureMagic8,
        getMagic8View,
        clearMagic8,
        startBinauralSweep,
        stopBinauralSweep,
        stopAudioSources,
        component: {

            oncreate: function (x) {

            },
            oninit: function () {

            },
            onupdate: function () {

            },
            view: function (x) {
                return renderRecordButton();
            }

        }
    };

    page.components.audio = audio;

}());
