(function () {
    let audioMap = {};
    let audioSource = {};
    let visualizers = {};
    let recorder;

    function newRecorder(){
        return {
            uid: page.uid
        }
    }

    function togglePlayAudio(id){
      let aud = document.getElementById(id);
      if(!aud) return;
      aud.paused ? aud.play() : aud.pause();
    }

    function isStreamSilent() {
        if(!recorder || !recorder.analyzer){
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
    function recordWithVisualizer(stream, handler, saveHandler){
        if(recorder){
            return recorder.view;
        }
        recorder = newRecorder();

       recorder.view = m("div", {
            id: "recorder-visualizer",
            class: "w-[80%]",
            // `oncreate` is the Mithril lifecycle hook that runs after the DOM element is created.
            // This is the ideal place to initialize a third-party library like AudioMotionAnalyzer.
            oncreate: function(vnode) {
                // Check if a visualizer is already running
                if (visualizers.recorder) {
                    // Optionally, destroy the old one if you want to re-initialize
                    visualizers.recorder.destroy();
                }

                navigator.mediaDevices.getUserMedia({ audio: true })
                    .then(stream => {
                        let audioCtx = new(window.AudioContext || window.webkitAudioContext)();
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
                        async function getAudioBase64(blob){
                            let buff = await blob.arrayBuffer();
                            return ab_b64(new Uint8Array(buff));
                        }
                        async function sendChunk(chunk, close){
                            window.dbgBlob = chunk;
                            console.log("Sending chunk ...");
                            let b64 = await getAudioBase64(chunk);
                            
                            await page.wss.send("audio", b64, undefined, undefined);
                            if(close){
                                page.audioStream = undefined;
                            }
                        }
                        mediaRecorder.ondataavailable = event => {
                            
                            if (event.data.size > 0) {
                                const isSilent = isStreamSilent();
                                if (!isSilent) {
                                    lastSilence = 0;
                                    chunks.push(event.data);
                                    if(stream){
                                        sendChunk(event.data);
                                    }
                                }
                                else {
                                    //console.log('Status: Recording (Silence Detected)');
                                    lastSilence++;
                                    if(lastSilence >= maxSilence){
                                        console.log("Restarting recorder ...");
                                        lastSilence = 0;
                                        mediaRecorder.stop();
                                        mediaRecorder.start(2000);
                                    }
                                }
                            }
                            else{
                                console.log("No data");
                            }

                        };

                        mediaRecorder.onstop = () => {
                            if(chunks.length == 0){
                                return;
                            }
                            let tchunks = chunks;
                            let audioBlob = new Blob(tchunks, { type: 'audio/webm' });
                            if(!stream){
                                sendChunk(audioBlob, true);
                            }
                            if(saveHandler){
                                getAudioBase64(audioBlob).then((b64)=>{
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
            onremove: function() {
                stopRecording();
            }
        });
        return recorder?.view || "";
    }

    function newAudioStream(h){
        return {
            onaudioupdate: (v)=> {

            },
            onaudiosttupdate: (m)=> {
                if(h){
                    h(m);
                }
            },
            onaudiouerror: (v)=> {

            }
        };
    }

    function stopRecording(){
        if (recorder) {
            if(recorder.recorder){
                recorder.recorder.stop();
            }
            if (recorder.stream) {
                recorder.stream.getTracks().forEach(track => track.stop());
            }
            if(recorder.motionAnalyzer){
                recorder.motionAnalyzer.destroy();
            }
            recorder = undefined;
        }
    }

    function extractText(dataB64){
        window.dbgBase64 = dataB64;
        let vprops = {"audio_sample": dataB64, "uid": page.uid()};
            m.request({method: 'POST', url: g_application_path + "/rest/voice/tts", withCredentials: true, body: vprops}).then((d) => {
                console.log("Received", d);
                }).catch((x)=>{
                page.toast("error", "Failed to extract text from audio - is the audio service running?");
            });

    }
    
    function cleanup(){
        audioMap = {};
    }

    function unconfigureAudio(enabled) {
      if(enabled) return;
      for(let id in visualizers){
        console.log("Unconfiguring audio visualizer for", id);
        if(visualizers[id]){
          visualizers[id].stop();
          visualizers[id].destroy();
          delete visualizers[id];
        }
      }
    }

    function configureAudio(enabled) {
      if(!enabled){
        unconfigureAudio();
        return;
      }
      let aa = document.querySelectorAll("audio");
      for(let i = 0; i < aa.length; i++){
        let aud = aa[i];
        if(visualizers[aud.id]){
          /// console.warn("Audio visualizer already configured for", aud.id);
          continue;
        }
        console.info("Configuring audio visualizer for", aud.id);
        let cont = aud.parentNode;
        let props = {
          source: aud,
          height: 60,
          overlay: true,
          bgAlpha: 0,
          showBgColor: true,
          showSource: false,
          gradient: "prism",
          showScaleY: false,
          showScaleX: false

        };
        const audioMotion = new AudioMotionAnalyzer(cont, props);
        visualizers[aud.id] = audioMotion;
      }
    }

    function createAudioVisualizer(name, idx, profileId, autoPlay, content){
        let aud;
        if(!audioMap[name]){
            audioMap[name] = {pending:true}
            aud = m("div", {class: "audio-container"}, "Synthesizing...");
            console.log("Synthethize " + name);
            let vprops = {"text": content, "speed": 1.2, voiceProfileId: profileId};
            if(!vprops.voiceProfileId){
                vprops.engine = "piper";
                vprops.speaker = "en_GB-alba-medium";
            }
            m.request({method: 'POST', url: g_application_path + "/rest/voice/" + name, withCredentials: true, body: vprops}).then((d) => {
                audioMap[name] = d;
                }).catch((x)=>{
                page.toast("error", "Failed to synthesize audio - is the audio service running?");
                audioMap[name] = {error:true};
            });
        }

        else if(audioMap[name] && !audioMap[name].error && !audioMap[name].pending){
            let path = g_application_path + "/media/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + audioMap[name].groupPath + "/Voice - " + name + ".mp3";
            let props = {
                class: "hide",
                preload: "auto"
                // ,controls: "controls"
            };
            if(autoPlay){
                props.autoplay = "autoplay";
            }
            let mt = audioMap[name].contentType;
            if (mt && mt.match(/mpeg3$/)) mt = "audio/mpeg";
            props.id = "chatAudio-" + (idx);
            props.crossorigin = "use-credentials"; 
            aud = m("div", {class: "audio-container", id: "chatAudioContainer-" + (idx), onclick: function(){page.components.audio.togglePlayAudio(props.id);}}, m("audio", props,
                m("source", { src: path, type: mt })
            ));
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

     async function createAudioSource(name, profileId, content){

        if(!audioMap[name]){
            audioMap[name] = {pending:true}
            let vprops = {"text": content, "speed": 1.2, voiceProfileId: profileId};
            if(!vprops.voiceProfileId){
                vprops.engine = "piper";
                vprops.speaker = "en_GB-alba-medium";
            }
            console.log("Synthethize " + name);
            let d = await m.request({method: 'POST', url: g_application_path + "/rest/voice/" + name, withCredentials: true, body: vprops});
            if(d){
                audioMap[name] = d;
            }
            else{
                page.toast("error", "Failed to synthesize audio - is the audio service running?");
                audioMap[name] = {error:true};
            };
        }

        if(audioMap[name] && !audioMap[name].error && !audioMap[name].pending){

            let o = audioMap[name];
            if(!audioSource[name]){
                console.log("Creating audio source for " + name);
                let audioContext = new AudioContext();
                let audioBuffer = await audioContext.decodeAudioData(base64ToArrayBuffer(o.dataBytesStore));
                let sourceNode = audioContext.createBufferSource();
                sourceNode.buffer = audioBuffer;
                audioSource[name] = {
                    context: audioContext,
                    source: sourceNode,
                    started: false
                }
                // sourceNode.start(0);
                //audioContext.suspend();
            }
        }
        return audioSource[name];
    }

    let recording = false;
    
    function toggleRecord(){
        recording = !recording;
    }

    function recordButton(){
        return m("button", { class: "button", onclick: toggleRecord }, m("span", { class: "material-symbols-outlined material-icons-24" }, "adaptive_audio_mic" + (recording ? "" : "_off")));
    }


    let audio = {
        configureAudio,
        unconfigureAudio,
        togglePlayAudio,
        createAudioVisualizer,
        createAudioSource,
        clearAudioSource: () => {
            for(let id in audioSource){
                let aud = audioSource[id];
                if(aud && aud.started && aud.context.state != "closed"){
                    // aud.source.stop();
                    aud.context.close();
                }
            }
            audioMap = {};
        },
        cleanup,
        recordButton,
        recordWithVisualizer,
        extractText,
        recording: ()=> recording,
        component: {
            
            oncreate: function (x) {

            },
            oninit: function(){

            },
            onupdate: function(){

            },
            view: function (x) {
                return renderRecordButton();
            }

        }
    };

    page.components.audio = audio;

}());
