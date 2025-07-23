(function () {
    let audioMap = {};
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
        return maxAmplitude < SILENCE_THRESHOLD;
    }

    function recordWithVisualizer(){
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
                        /// console.log(analyzer)
                        let source = audioCtx.createMediaStreamSource(stream);
                        source.connect(analyzer);
                        console.log(source.context.destination);

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
                        recorder.context = audioCtx;
                        recorder.stream = stream;
                        let chunks = [];
                        const mediaRecorder = new MediaRecorder(stream);
                        let chunkStream = [];
                        let shifting = false;
                        async function shiftChunkStream(){
                            if(shifting || !chunkStream.length) return;
                            console.log("Start shift")
                            shifting = true;
                            let dat = await page.blobToBase64(chunkStream.shift());
                            page.wss.send("audio", dat, undefined, undefined);
                            console.log("End shift");
                            shifting = false;
                        }
                        mediaRecorder.ondataavailable = async event => {
                            
                            if (event.data.size > 0) {
                                const isSilent = isStreamSilent();
                                // console.log('Audio chunk available:', event.data);
                                // console.log('Is chunk silent?', isSilent);
                                
                                if (!isSilent) {
                                    chunks.push(event.data);
                                    chunkStream.push(event.data);
                                    shiftChunkStream();
                                    //let dat = await page.blobToBase64(event.data);
                                    //page.wss.send("audio", Base64.decode(event.data), undefined, undefined);
                                } else {
                                    // console.log('Status: Recording (Silence Detected)');
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
      
                            const audioBlob = new Blob(tchunks, { type: 'audio/webm' });
                            /// complete?
                            shiftChunkStream();

                        };
                        recorder.recorder = mediaRecorder;
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

        else if(audioMap[name] && !audioMap[name].error){
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
