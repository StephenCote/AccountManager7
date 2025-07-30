(function () {
    let audioMap = {};
    let audioSource = {};
    let visualizers = {};
    let recorder;
    let upNext = [];

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
        // You may need to adjust this threshold based on microphone sensitivity
        const SILENCE_THRESHOLD = 3;

        // Create a buffer to hold the time-domain data
        const bufferLength = recorder.analyzer.fftSize;
        const dataArray = new Uint8Array(bufferLength);

        // Get the current waveform data
        recorder.analyzer.getByteTimeDomainData(dataArray);

        // Find the maximum amplitude in the buffer
        let maxAmplitude = 0;
        for (let i = 0; i < bufferLength; i++) {
            // The values are 0-255, with 128 being the center (silence)
            const amplitude = Math.abs(dataArray[i] - 128);
            if (amplitude > maxAmplitude) {
                maxAmplitude = amplitude;
            }
        }
        console.log(maxAmplitude + " < " + SILENCE_THRESHOLD);
        return maxAmplitude < SILENCE_THRESHOLD;
    }
    /// currently only setup for live streaming to extract text, not to actually record the audio
    ///
    function recordWithVisualizer(stream, handler, saveHandler) {
        if (recorder) {
            return recorder.view;
        }
        recorder = newRecorder();

        recorder.view = m("div", {
            id: "recorder-visualizer",
            class: "w-[80%]",
            // `oncreate` is the Mithril lifecycle hook that runs after the DOM element is created.
            // This is the ideal place to initialize a third-party library like AudioMotionAnalyzer.
            oncreate: function (vnode) {
                // Check if a visualizer is already running
                if (visualizers.recorder) {
                    // Optionally, destroy the old one if you want to re-initialize
                    visualizers.recorder.destroy();
                }

                navigator.mediaDevices.getUserMedia({ audio: true })
                    .then(stream => {
                        let audioCtx = new (window.AudioContext || window.webkitAudioContext)();
                        let analyzer = audioCtx.createAnalyser();
                        analyzer.fftSize = 2048;
                        recorder.analyzer = analyzer;
                        analyzer.volume = 0;
                        /// console.log(analyzer)
                        let source = audioCtx.createMediaStreamSource(stream);
                        // let gain = audioCtx.createGain();
                        // gain.gain.value = 0;
                        source.connect(analyzer);
                        // analyzer.connect(gain);
                        // gain.connect(audioCtx.destination)


                        let props = {
                            source: analyzer,
                            height: 40,
                            overlay: true,
                            bgAlpha: 0,
                            gradient: 'prism',
                            showBgColor: true,
                            showSource: false,
                            gradient: "prism",
                            showScaleY: false,
                            showScaleX: false
                        };

                        recorder.motionAnalyzer = new AudioMotionAnalyzer(vnode.dom, props);
                        recorder.motionAnalyzer.volume = 0;
                        recorder.context = audioCtx;
                        recorder.stream = stream;
                        let chunks = [];
                        const mediaRecorder = new MediaRecorder(stream);
                        let maxSilence = 5;
                        let lastSilence = 0;

                        function ab_b64(buf) {
                            return btoa(buf.reduce(
                                function (data, val) {
                                    return data + String.fromCharCode(val);
                                },
                                ""
                            ));
                        }
                        async function getAudioBase64(blob) {
                            let buff = await blob.arrayBuffer();
                            return ab_b64(new Uint8Array(buff));
                        }
                        async function sendChunk(chunk, close) {
                            window.dbgBlob = chunk;
                            console.log("Sending chunk ...");
                            let b64 = await getAudioBase64(chunk);

                            await page.wss.send("audio", b64, undefined, undefined);
                            if (close) {
                                page.audioStream = undefined;
                            }
                        }
                        mediaRecorder.ondataavailable = event => {

                            if (event.data.size > 0) {
                                const isSilent = isStreamSilent();
                                if (!isSilent) {
                                    lastSilence = 0;
                                    chunks.push(event.data);
                                    if (stream) {
                                        sendChunk(event.data);
                                    }
                                }
                                else {
                                    //console.log('Status: Recording (Silence Detected)');
                                    lastSilence++;
                                    if (lastSilence >= maxSilence) {
                                        console.log("Restarting recorder ...");
                                        lastSilence = 0;
                                        mediaRecorder.stop();
                                        mediaRecorder.start(2000);
                                    }
                                }
                            }
                            else {
                                console.log("No data");
                            }

                        };

                        mediaRecorder.onstop = () => {
                            if (chunks.length == 0) {
                                return;
                            }
                            let tchunks = chunks;
                            let audioBlob = new Blob(tchunks, { type: 'audio/webm' });
                            if (!stream) {
                                sendChunk(audioBlob, true);
                            }
                            if (saveHandler) {
                                getAudioBase64(audioBlob).then((b64) => {
                                    saveHandler("audio/webm", b64);
                                });
                            }

                        };
                        recorder.recorder = mediaRecorder;
                        page.audioStream = newAudioStream(handler);
                        mediaRecorder.start(2000);


                    }).catch(err => {
                        console.error('Error accessing microphone:', err);
                        // Optionally, display an error in the UI
                        vnode.dom.textContent = "Error: Could not access microphone.";
                    });
            },
            // `onremove` is another lifecycle hook that runs before the DOM element is removed.
            // This is the perfect place to clean up, stop the stream, and destroy the analyzer.
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

    function cleanup() {
        audioMap = {};
    }
    function clearAudioSource() {

        for (let id in audioSource) {
            let aud = audioSource[id];
            if (aud && aud.started && aud.context.state != "closed") {
                // aud.source.stop();
                aud.context.close();
            }
        }
        audioMap = {};

    }
    function unconfigureAudio(enabled) {
        if (enabled) return;
        // console.info("Unconfiguring audio visualizers");
        clearAudioSource();
        for (let id in visualizers) {

            if (visualizers[id]) {
                if(!visualizers[id].pending){
                    visualizers[id].stop();
                    visualizers[id].destroy();
                }
                delete visualizers[id];
            }
        }
    }
    
    function configureVisualizer(aud, autoPlay){
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
                console.warn("Failed to retrieve audio source")
            }
            if (!o) {
                console.warn("Failed to retrieve audio source", oM.name);
                console.warn(audioMap);
                console.warn(audioSource);
                return;
            }
            let props1 = Object.assign({ source: o.source, onclick: function(){togglePlayAudioSource(o);} }, props);
            let audioMotion = new AudioMotionAnalyzer(aud, props1);
            visualizers[aud.id] = audioMotion;
            if (autoPlay || oM.autoPlay) {
                if(getRunningAudioSources().length > 0){
                    upNext.push(o);
                }
                else{
                    togglePlayAudioSource(o);
                }
            }
        });
    }

    function configureAudio(enabled) {
        if (!enabled) {
            unconfigureAudio();
            return;
        }
        //let aa = document.querySelectorAll("audio");
        let aa = document.querySelectorAll("div[id*='chatAudioContainer-']");

        for (let i = 0; i < aa.length; i++) {
            let aud = aa[i];
            if(visualizers[aud.id]) continue;
            if(i < (aa.length - 2)){
                visualizers[aud.id] = { lateLoad: true };
            }
            else{
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
    
    function getRunningAudioSources(){
        return Object.values(audioSource).filter(aud => aud.started && aud.context.state == "running");
    }

    function stopAudioSources(aud){
        let running = getRunningAudioSources();

        running.forEach(r => {
            console.log("Stopping other audio sources", r);
            if (!aud || r.id !== aud.id) {
                togglePlayAudioSource(r, true);
            }
        });
    }

    function togglePlayAudioSource(aud, autoStop) {
        if(autoStop){
            upNext = [];
        }
        if (typeof aud == "string") {
            if(visualizers[aud] && visualizers[aud].lateLoad){
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

        if(!autoStop){
            stopAudioSources(aud);
        }
        if (!aud.started) {
            if (aud.context.state === "suspended") {
                aud.context.resume().then(() => {
                    try{ aud.source.start(0); } catch{}
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
                if(upNext.length > 0){
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
            console.warn("Handle state " + aud.context.state);
        }
    }

    function createAudioVisualizer(name, idx, profileId, autoPlay, content) {
        let contId = "chatAudioContainer-" + (idx);
        let aud = m("div", { class: "audio-container block w-full mx-auto", id: "chatAudioContainer-" + (idx), onclick: function () { togglePlayAudioSource(contId, true); } }, "");
        if (!audioMap[name]) {
            audioMap[name] = { id: page.uid(), index: idx, name, profileId, content, autoPlay, containerId: contId, pending: false };
        }

        return aud;
    }

    function base64ToArrayBuffer(base64) {
        const cleanedBase64 = base64.replace(/^data:audio\/\w+;base64,/, '');

        const binaryString = atob(cleanedBase64);
        const len = binaryString.length;
        const bytes = new Uint8Array(len);

        for (let i = 0; i < len; i++) {
            bytes[i] = binaryString.charCodeAt(i);
        }

        return bytes.buffer;
    }

    async function createAudioSource(name, profileId, content) {
        if (!content || content.length == 0) {
            console.warn("No content provided for audio source creation");
            return;
        }
        if (!audioMap[name]) {
            audioMap[name] = { name, profileId, content, pending: false };
        }
        if (!audioMap[name].data && !audioMap[name].pending) {
            audioMap[name].pending = true;
            let vprops = { "text": content, "speed": 1.2, voiceProfileId: profileId };
            if (!vprops.voiceProfileId) {
                vprops.engine = "piper";
                vprops.speaker = "en_GB-alba-medium";
            }
            console.log("Synthethize '" + name + "'");
            let d;
            try{
                d = await m.request({ method: 'POST', url: g_application_path + "/rest/voice/" + name, withCredentials: true, body: vprops });
            }
            catch(e){
                console.error("Error synthesizing audio:", e);
                console.error(vprops);
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


    let audio = {
        configureAudio,
        unconfigureAudio,
        togglePlayAudio,
        createAudioVisualizer,
        createAudioSource,
        clearAudioSource: clearAudioSource,
        hasAudioMap: (name) => audioMap[name] ? true : false,
        cleanup,
        recordButton,
        recordWithVisualizer,
        extractText,
        recording: () => recording,
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
