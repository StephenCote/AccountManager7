(function () {
    let audioMap = {};
    let audioSource = {};
    let visualizers = {};
    let recorder;
    let upNext = [];

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
        console.log("Clear Magic8");

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

    function configureMagic8(inst, chatCfg, audioMagic8, prune) {
        if (!audioMagic8) {
            return;
        }

        if (magic8.configuring) {
            // console.warn("Magic8 is already configuring, skipping...");
            return;
        }

        let aMsg = chatCfg?.history?.messages;
        if (!aMsg || !aMsg.length) {
            return;
        }

        let messagesToProcess = aMsg.slice(-2);
        let currentContent = messagesToProcess.map(m => m?.content || "").join("|");

        // If already configured with the same content AND visualizers exist, don't reconfigure
        if (magic8.lastContent === currentContent && magic8.audioMotionTop && magic8.audioMotionBottom) {
            /// console.log("Magic8 already configured with same content, skipping...");
            return;
        }

        // Check if canvases exist before proceeding
        let canvasTop = document.getElementById("waveform-top");
        let canvasBottom = document.getElementById("waveform-bottom");
        if (!canvasTop || !canvasBottom) {
            return;
        }

        let contentChanged = magic8.lastContent !== currentContent;

        // Only clear and reconfigure if content has actually changed
        if (contentChanged) {
            console.log("Magic8 content changed, reconfiguring...");
            clearMagic8(false);
            magic8.lastContent = currentContent;
        } else if (!magic8.audioMotionTop || !magic8.audioMotionBottom) {
            // Only reconfigure visualizers if they don't exist but content is the same
            console.log("Magic8 visualizers missing, recreating...");
        } else {
            // Everything is already configured properly
            return;
        }

        magic8.configuring = true;
        let aP = [];

        // Get profile IDs
        let sysProfileId = chatCfg?.system?.profile?.objectId;
        let usrProfileId = chatCfg?.user?.profile?.objectId;
        if (!sysProfileId || !usrProfileId) {
            console.warn("No system or user profile for chat");
            magic8.configuring = false;
            return;
        }

        // Only create new audio sources if content changed or they don't exist
        if (contentChanged || !magic8.audio1 || !magic8.audio2) {
            messagesToProcess.forEach((m, i) => {
                if (!m) return;
                let actualIndex = aMsg.length - 2 + i;
                let name = inst.api.objectId() + " - " + actualIndex;
                let profId = (m.role == "assistant") ? sysProfileId : usrProfileId;

                let cnt = prune ? prune(m.content) : m.content;
                if (cnt.length) {
                    aP.push(createAudioSource(name, profId, cnt).then((aud) => {
                        if (!aud) return;

                        aud.name = name; // Store name for cleanup

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
            // Ensure we still have valid canvases after async operations
            canvasTop = document.getElementById("waveform-top");
            canvasBottom = document.getElementById("waveform-bottom");
            if (!canvasTop || !canvasBottom) {
                magic8.configuring = false;
                return;
            }

            // Only create new visualizers if they don't exist
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

                // Set up click handlers
                canvasTop.onclick = function (e) {
                    togglePlayMagic8(magic8.audio1, magic8.audio2);
                };
                canvasBottom.onclick = function (e) {
                    togglePlayMagic8(magic8.audio2, magic8.audio1);
                };
            }

            // Clear any existing upNext queue to prevent old audio from auto-starting
            upNext = [];

            // Auto-start the most recent audio when content changes
            if (contentChanged && magic8.lastAudio && magic8["audio" + magic8.lastAudio]) {
                let currentAudio = magic8["audio" + magic8.lastAudio];
                console.log("Starting Magic8 audio after content change:", magic8.lastAudio);
                setTimeout(() => {
                    togglePlayAudioSource(currentAudio, true);
                }, 100);
            }

            magic8.configuring = false;
            console.log("Magic8 configuration complete");
        }).catch(err => {
            console.error("Error configuring Magic8:", err);
            magic8.configuring = false;
        });
    }

    function cycleMagic8Image(){

    }
    let images = [];
    let imgBase = 172;
    let imgUrl;
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
        if(!profile && imgBase && images.length == 0){
            console.log("Loading magic8 images from base", imgBase);
            let q = am7client.newQuery("data.data");
            q.field("groupId", imgBase);
            q.range(0, 0);
            imgBase = 0;
            page.search(q).then((res) => {

                if (res && res.results) {
                    images = res.results.map((r) => {
                        return g_application_path + "/media/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + r.groupPath + "/" + r.name;
                    });
                    console.log(images);
                    m.redraw();
                }
            });
        }
        if(images.length > 0){
            imgUrl = images[Math.floor(Math.random() * images.length)];
            gimg = m("img", {
                src: imgUrl,
                class: "rounded-full",
                style: {
                    position: "absolute",
                    top: 0,
                    left: 0,
                    width: "100%",
                    height: "100%",
                    objectFit: "contain",
                    objectPosition: "center",
                    zIndex: 1,
                    opacity: 0.2,
                    pointerEvents: "none"
                }
            });
            setTimeout(() => {
                m.redraw();
            }, 3000);
                    
                
        }


        return m("div", {
            key: "magic8-container", // Use a stable key instead of magic8.id
            class: `
      relative aspect-square w-[90vw] max-w-[600px] max-h-[600px] mx-auto
      rounded-full overflow-hidden
      bg-gradient-to-br from-slate-100 via-slate-200 to-slate-300
      ring-2 ring-white/20 shadow-[inset_0_10px_20px_rgba(255,255,255,0.1),inset_0_-10px_20px_rgba(0,0,0,0.2)]
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

            // Top waveform canvas
            m("div", {
                id: "waveform-top",
                class: `
        absolute top-0 left-0 w-full h-1/2 z-10
      `
            }),

            // Bottom waveform canvas (inverted)
            m("div", {
                id: "waveform-bottom",
                class: `
        absolute bottom-0 left-0 w-full h-1/2 z-10
        transform scale-y-[-1]
      `
            }),

            // ...rest of your overlays and effects...
            m("div", {
                class: `
        absolute inset-0 rounded-full pointer-events-none
        bg-[radial-gradient(circle_at_50%_50%,rgba(255,255,255,0.15)_0%,transparent_70%)]
      `
            }),
            m("div", {
                class: `
        absolute top-0 left-0 w-full h-full pointer-events-none rounded-full
        bg-[radial-gradient(circle_at_30%_30%,rgba(255,255,255,0.3)_0%,transparent_40%)]
        mix-blend-screen
      `
            }),
            m("div", {
                class: `
        absolute bottom-0 left-0 w-full h-1/2
        bg-gradient-to-t from-black/20 to-transparent
        pointer-events-none
      `
            }),
            m("canvas", {
                id: "spiral-overlay",
                class: `
                    absolute z-20 pointer-events-none
                    opacity-20 w-full h-full top-0 left-0
                `,
                oncreate: ({ dom }) => {
                    // drawSpiral(dom);
                    drawMandala(dom);
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
                radius = i * 0.07 * pulse;
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
                                console.log("Sending chunk...", chunk.size, "bytes");
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
        console.info("Configuring audio visualizer for", aud.id);
        let oM = getAudioMapForContainer(aud.id);
        if (!oM) {
            console.warn("Failed to find map for " + aud.id);
            console.warn(audioMap);
            return;
        }

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
        let running = getRunningAudioSources();

        running.forEach(r => {
            console.log("Stopping other audio sources", r);
            if (!aud || r.id !== aud.id) {
                togglePlayAudioSource(r, false);
            }
        });
    }


    function togglePlayMagic8(aud, aud2) {
        console.log("Toggle play Magic8", aud, aud2);
        togglePlayAudioSource(aud, true);
    }

    function togglePlayAudioSource(aud, autoStop) {
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
        if (!aud.started) {
            if (aud.context.state === "suspended") {
                aud.context.resume().then(() => {
                    try { aud.source.start(0); } catch { }
                    aud.started = true;
                });
            } else if (aud.context.state === "running") {
                aud.source.start(0);
                aud.started = true;
            } else {
                console.warn("Handle state " + aud.context.state);
            }
            aud.source.onended = function () {
                aud.started = false;
                aud.source = aud.context.createBufferSource();
                aud.source.buffer = aud.buffer;
                if (upNext.length > 0) {
                    togglePlayAudioSource(upNext.shift());
                }
            };
        }
        else if (aud.context.state == "suspended") {
            console.log("Resume");
            aud.context.resume();
        }
        else if (aud.context.state != "closed") {
            console.log("Suspend");
            aud.context.suspend();
        }
        else {
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
            // console.log("Synthethize '" + name + "'");
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
                let sourceNode = audioContext.createBufferSource();
                sourceNode.buffer = audioBuffer;
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


    let toneCtx;
    let leftOsc, rightOsc;
    let leftGain, rightGain;
    let merger;
    let baseFreq = 440;
    let minBeat = 4;
    let maxBeat = 6;
    let sweepDurationMin = 10; // total cycle time in minutes
    let sweepInterval;

    function startBinauralSweep() {
        toneCtx = new (window.AudioContext || window.webkitAudioContext)();

        const masterGain = toneCtx.createGain();
        masterGain.gain.value = 0.35;

        leftOsc = toneCtx.createOscillator();
        rightOsc = toneCtx.createOscillator();

        leftGain = toneCtx.createGain();
        rightGain = toneCtx.createGain();
        merger = toneCtx.createChannelMerger(2);




        // Connect left
        leftOsc.connect(leftGain);
        leftGain.connect(merger, 0, 0);

        // Connect right
        rightOsc.connect(rightGain);
        rightGain.connect(merger, 0, 1);

        merger.connect(masterGain).connect(toneCtx.destination);

        // Set base frequency
        leftOsc.frequency.value = baseFreq;
        rightOsc.frequency.value = baseFreq + maxBeat; // Start at highest beat

        // Start oscillators
        leftOsc.start();
        rightOsc.start();

        // Start the sweep cycle
        scheduleSweep();
        sweepInterval = setInterval(scheduleSweep, sweepDurationMin * 60 * 1000);
    }

    function scheduleSweep() {
        const now = toneCtx.currentTime;
        const halfCycle = (sweepDurationMin * 60) / 2;

        // Descend from maxBeat to minBeat
        rightOsc.frequency.cancelScheduledValues(now);
        rightOsc.frequency.setValueAtTime(baseFreq + maxBeat, now);
        rightOsc.frequency.linearRampToValueAtTime(baseFreq + minBeat, now + halfCycle);

        // Ascend back from minBeat to maxBeat
        rightOsc.frequency.linearRampToValueAtTime(baseFreq + maxBeat, now + 2 * halfCycle);
    }

    function stopBinauralSweep() {
        if (leftOsc) leftOsc.stop();
        if (rightOsc) rightOsc.stop();
        if (toneCtx) toneCtx.close();
        if (sweepInterval) clearInterval(sweepInterval);
    }



    let audio = {
        configureAudio,
        unconfigureAudio,
        togglePlayAudio,
        createAudioVisualizer,
        createAudioSource,
        clearAudioSource: clearAudioSource,
        hasAudioMap: (name) => audioMap[name] ? true : false,
        recordButton,
        recordWithVisualizer,
        extractText,
        recording: () => recording,
        configureMagic8,
        getMagic8View,
        clearMagic8,
        startBinauralSweep,
        stopBinauralSweep,
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
