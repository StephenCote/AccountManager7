(function(){
    let canvas, ctx, particles;
    // This will be updated by the Mithril component based on biometrics
    let particleColor = { r: 150, g: 180, b: 255 }; 
    const PARTICLE_COUNT = 100;
    let imgBase = [282, 281, 283, 284, 265, 266, 267]; // [218, 220, 130, 172, 173];//[307]; //
    let imageUrls = [];

    /*
        "https://images.unsplash.com/photo-1485470733090-0aae1788d5af?q=80&w=1920&auto=format&fit=crop", "https://images.unsplash.com/photo-1476610182048-b716b8518a2a?q=80&w=1920&auto=format&fit=crop", "https://images.unsplash.com/photo-1594724889613-2895123d49f6?q=80&w=1920&auto=format&fit=crop", "https://images.unsplash.com/photo-1448375240586-882707db888b?q=80&w=1920&auto=format&fit=crop", "https://images.unsplash.com/photo-150220952416៤-acea59203632?q=80&w=1920&auto=format&fit=crop", "https://images.unsplash.com/photo-1505373333342-429910b3765e?q=80&w=1920&auto=format&fit=crop"
        ],
    */
    let canvasInitialized = false;
    function setupCanvas() {
        if(canvasInitialized) return;
        canvas = document.getElementById('visual-canvas');
        if (!canvas){
            console.error("Canvas element not found.");
            return;
        }
        canvasInitialized = true;
        ctx = canvas.getContext('2d');
        canvas.width = window.innerWidth;
        canvas.height = window.innerHeight;

        particles = [];
        for (let i = 0; i < PARTICLE_COUNT; i++) {
            particles.push(createParticle());
        }

        animateCanvas();
    }

    function createParticle(p) {
        return {
            x: p ? p.x : Math.random() * canvas.width,
            y: p ? p.y : Math.random() * canvas.height,
            vx: (Math.random() - 0.5) * 0.5,
            vy: (Math.random() - 0.5) * 0.5,
            radius: Math.random() * 3 + 1,
            alpha: Math.random() * 0.5 + 0.1
        };
    }

    function animateCanvas() {
        if (!ctx) return;
        ctx.clearRect(0, 0, canvas.width, canvas.height);

        particles.forEach(p => {
            p.x += p.vx;
            p.y += p.vy;

            if (p.x < 0 || p.x > canvas.width) p.vx *= -1;
            if (p.y < 0 || p.y > canvas.height) p.vy *= -1;
            
            p.alpha -= 0.001;
            if(p.alpha <= 0){
                Object.assign(p, createParticle());
            }

            ctx.beginPath();
            ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
            // Use the dynamic particleColor
            ctx.fillStyle = `rgba(${particleColor.r}, ${particleColor.g}, ${particleColor.b}, ${p.alpha})`;
            ctx.fill();
        });

        requestAnimationFrame(animateCanvas);
    }
    
    window.addEventListener('resize', () => {
        if (canvas) {
            canvas.width = window.innerWidth;
            canvas.height = window.innerHeight;
        }
    });

        //let faceProfile;
    function handleFaceMetricCapture(imageData) {
      if(imageData && imageData.results?.length){
        let id = imageData.results[0];
        HypnoApp.processBiometricData(id);
        //console.log(id);
        /*
        faceProfile = {
            emotion: id.dominant_emotion,
            emotions: Object.fromEntries(
                Object.entries(id.emotion_scores).map(([key, value]) => {let vf = value; return [key, vf.toFixed(2)];})
            ),

            gender: id.dominant_gender,
            genders: Object.fromEntries(
                Object.entries(id.gender_scores).map(([key, value]) => {let vf = value; return [key, vf.toFixed(2)];})
            ),

            race: id.dominant_race,
            races: Object.fromEntries(
                Object.entries(id.race_scores).map(([key, value]) => {let vf = value; return [key, vf.toFixed(2)];})
            ),
            age: id.age
            };
                console.log(id);
              */
      }

    }

    // --- Mithril.js Application ---
    const HypnoApp = {
        // --- State Management ---
        enableAudio: false,
        isStarted: false,
        audioReady: false,
        synth: null,
        lfo: null,
        distractionText: "...",
        coreText: "",
        imageUrl: "",
        biometricData: null,
        biometricClues: [],
        theme: {
            background: "bg-gray-900",
            mainText: "text-blue-100",
            shadow: "rgba(180, 210, 255, 0.4)",
        },

        // --- Content Arrays ---
        distractions: [
            "Listen to the space between sounds...", "The feeling of now is the memory of later...", "What if right was left?", "Remember to forget...", "A thought is just a visitor...", "Up is down if you allow it...", "Focus on everything and nothing...", "The future was yesterday...", "Notice your breath without changing it...", "See the silence..."
        ],
        coreMessages: ["peace of mind", "purity of essence", "peace and harmony"],
        
        // --- Color & Theme Mappings ---
        emotionThemes: {
            neutral: { background: "bg-gray-800", mainText: "text-blue-200", shadow: "rgba(180, 210, 255, 0.4)" },
            happy:   { background: "bg-yellow-900", mainText: "text-yellow-100", shadow: "rgba(255, 230, 150, 0.5)" },
            sad:     { background: "bg-indigo-900", mainText: "text-indigo-200", shadow: "rgba(190, 200, 255, 0.4)" },
            angry:   { background: "bg-red-900", mainText: "text-red-200", shadow: "rgba(255, 180, 180, 0.5)" },
            fear:    { background: "bg-purple-900", mainText: "text-purple-200", shadow: "rgba(220, 180, 255, 0.4)" },
            surprise:{ background: "bg-pink-900", mainText: "text-pink-200", shadow: "rgba(255, 180, 220, 0.5)" },
            disgust: { background: "bg-green-900", mainText: "text-green-200", shadow: "rgba(180, 255, 180, 0.4)" },
        },

        raceColors: {
            white: [255, 220, 180],
            black: [100, 75, 50],
            asian: [255, 235, 160],
            'East Asian': [255, 235, 160],
            'Southeast Asian': [255, 235, 160],
            indian: [200, 160, 120],
            'middle eastern': [220, 190, 150],
            'latino hispanic': [180, 130, 90]
        },

        genderColors: {
            Man: [180, 200, 255], // A cool, light blue
            Woman: [255, 200, 220] // A warm, light pink
        },

        // --- Biometric Processing ---
        processBiometricData: function(data) {
            //console.log(data);
            HypnoApp.biometricData = data;
            let clues = [];

            // 1. Update Theme based on Emotion
            const emotion = data.dominant_emotion || "neutral";
            //console.log(data.dominant_emotion, emotion);
            HypnoApp.theme = HypnoApp.emotionThemes[emotion] || HypnoApp.emotionThemes.neutral;
            clues.push(`A state of ${emotion}.`);

            // 2. Generate Particle Color from Race and Gender
            let raceBlendedColor = null;
            let raceTotalScore = 0;
            for (const race in data.race_scores) {
                if (data.race_scores[race] > 0 && HypnoApp.raceColors[race]) {
                    raceTotalScore += data.race_scores[race];
                }
            }
            if (raceTotalScore > 0) {
                let r = 0, g = 0, b = 0;
                for (const race in data.race_scores) {
                        if (data.race_scores[race] > 0 && HypnoApp.raceColors[race]) {
                        const weight = data.race_scores[race] / raceTotalScore;
                        r += HypnoApp.raceColors[race][0] * weight;
                        g += HypnoApp.raceColors[race][1] * weight;
                        b += HypnoApp.raceColors[race][2] * weight;
                    }
                }
                raceBlendedColor = { r: r, g: g, b: b };
                clues.push(`A tapestry of heritage.`);
            }

            let genderBlendedColor = null;
            let genderTotalScore = 0;
            for (const gender in data.gender_scores) {
                if (data.gender_scores[gender] > 0 && HypnoApp.genderColors[gender]) {
                    genderTotalScore += data.gender_scores[gender];
                }
            }
            if (genderTotalScore > 0) {
                let r = 0, g = 0, b = 0;
                for (const gender in data.gender_scores) {
                    if (data.gender_scores[gender] > 0 && HypnoApp.genderColors[gender]) {
                        const weight = data.gender_scores[gender] / genderTotalScore;
                        r += HypnoApp.genderColors[gender][0] * weight;
                        g += HypnoApp.genderColors[gender][1] * weight;
                        b += HypnoApp.genderColors[gender][2] * weight;
                    }
                }
                genderBlendedColor = { r: r, g: g, b: b };
            }

            if (raceBlendedColor && genderBlendedColor) {
                // Average the two colors
                particleColor = {
                    r: Math.round((raceBlendedColor.r + genderBlendedColor.r) / 2),
                    g: Math.round((raceBlendedColor.g + genderBlendedColor.g) / 2),
                    b: Math.round((raceBlendedColor.b + genderBlendedColor.b) / 2)
                };
            } else if (raceBlendedColor) {
                particleColor = { r: Math.round(raceBlendedColor.r), g: Math.round(raceBlendedColor.g), b: Math.round(raceBlendedColor.b) };
            } else if (genderBlendedColor) {
                particleColor = { r: Math.round(genderBlendedColor.r), g: Math.round(genderBlendedColor.g), b: Math.round(genderBlendedColor.b) };
            }

            // 3. Generate Clues for Age and Gender
            if (data.age) {
                clues.push(`${data.age} years of experience.`);
            }
            if (data.dominant_gender) {
                clues.push(`An expression of ${data.dominant_gender.toLowerCase()} energy.`);
            }
            
            HypnoApp.biometricClues = clues;
            //console.log(this, HypnoApp.biometricClues);
            m.redraw();
        },

        // --- Component Methods ---
        startExperience: function() {
            if(HypnoApp.isStarted){
                return;
            }            

            HypnoApp.isStarted = true;

                if(!page.components.camera.devices().length){
                    page.components.camera.initializeAndFindDevices(handleFaceMetricCapture);
                }
                else{
                    page.components.camera.startCapture(handleFaceMetricCapture);
                }

            if (imgBase.length && imageUrls.length == 0) {
                let q = am7client.newQuery("data.data");
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
                window.dbgQ = q;
                imgBase = [];
                am7client.search(q, function(res){

                    if (res && res.results) {
                        imageUrls = res.results.map((r) => {
                            return g_application_path + "/media/Public/data.data" + r.groupPath + "/" + r.name;
                        });
                        console.log(imageUrls.length);
                        
                        HypnoApp.cycleContent();
                        m.redraw();
                    }
                });
            }

            
            if (HypnoApp.enableAudio && !HypnoApp.audioReady) {
                Tone.start().then(() => {
                    HypnoApp.synth = new Tone.Synth({ oscillator: { type: 'sine' }, envelope: { attack: 0.1, decay: 0.2, sustain: 0.5, release: 1 } }).toDestination();
                    HypnoApp.lfo = new Tone.LFO({ frequency: "8n", min: 200, max: 220 }).connect(HypnoApp.synth.frequency).start();
                    Tone.Transport.start();
                    HypnoApp.synth.triggerAttack(210);
                    HypnoApp.audioReady = true;
                });
            }
            
        },
        
        cycleContent: function() {
            HypnoApp.distractionText = HypnoApp.distractions[Math.floor(Math.random() * HypnoApp.distractions.length)];
            HypnoApp.coreText = HypnoApp.coreMessages[Math.floor(Math.random() * HypnoApp.coreMessages.length)];
            HypnoApp.imageUrl = (imageUrls.length ? imageUrls[Math.floor(Math.random() * imageUrls.length)] : "");
            m.redraw();
        },

        // --- Lifecycle Hooks ---
        oncreate: function(vnode) {

            HypnoApp.contentInterval = setInterval(() => HypnoApp.cycleContent(), 5000);
            HypnoApp.cycleContent();
            //console.log("HypnoApp created");
            // Initial biometric data processing with the provided example
            const initialData = {"age":41,"dominant_gender":"Man","dominant_emotion":"neutral","dominant_race":"white","emotion_scores":{"angry":0.18,"disgust":0,"fear":0.09,"happy":0,"sad":0.32,"surprise":0.01,"neutral":0.39},"race_scores":{"asian":1.46,"indian":0.09,"black":0.01,"white":87.78,"middle eastern":4.13,"latino hispanic":6.5},"gender_scores":{"Woman":0.01,"Man":99.98},"face_confidence":0.94};
            HypnoApp.processBiometricData(initialData);
        },
        oninit: function(){

        },
        onupdate: function(){

        },
        onremove: function() {
            //console.log("HypnoApp removed");
            page.components.camera.stopCapture();
            clearInterval(HypnoApp.contentInterval);
            if (HypnoApp.synth) {
                HypnoApp.synth.triggerRelease();
                HypnoApp.lfo.stop();
                Tone.Transport.stop();
            }
        },

        // --- View ---
        view: function() {
            if (!HypnoApp.isStarted) {


                return [m(".h-screen.w-screen.flex.items-center.justify-center.bg-gray-900",
                    m(".text-center.p-8.bg-gray-800.rounded-lg.shadow-2xl", [
                        m("h1.text-3xl.font-bold.mb-4.text-blue-200", "Biometric Feedback Session"),
                        m("p.text-lg.mb-6.text-gray-300", "This experience adapts to you. Please use headphones for the best effect."),
                        m("button.px-8.py-3.bg-blue-600.text-white.font-bold.rounded-full.hover:bg-blue-500.transition-colors.duration-300.shadow-lg", { onclick: () => HypnoApp.startExperience() }, "Begin")
                    ])
                ), page.components.dialog.loadDialog()];
            }
            
            return [m(".relative.h-screen.w-screen.transition-colors.duration-1000", { class: HypnoApp.theme.background }, [
                m("img.absolute.top-0.left-0.w-full.h-full.object-cover.z-0.opacity-50.animate-[fadeIn_3s_ease-in-out]", { src: HypnoApp.imageUrl, alt: "Background" }),
                m("canvas", {oncreate: setupCanvas, id: "visual-canvas", class: "absolute top-0 left-0 z-5"}),
                m(".absolute.top-0.left-0.z-10.h-full.w-full.p-4.bg-black.bg-opacity-40", {  }, [
                    
                    // Distraction Text (Top)
                    m("div.absolute.top-1/4.left-1/2.-translate-x-1/2.text-center", {},
                        m("p.text-xl.text-gray-300.opacity-75[style={text-shadow: '0 0 5px rgba(0,0,0,0.7)'}]", {  }, HypnoApp.distractionText)
                    ),

                    // Core Message (Center)
                    m("div.absolute.top-1/2.left-1/2.-translate-x-1/2.-translate-y-1/2.text-center", { },
                        m("h1", {class : "text-5xl font-bold transition-colors duration-1000 opacity-75", 
                            class: HypnoApp.theme.mainText,
                            style: { textShadow: `0 0 8px rgba(255, 255, 255, 0.3), 0 0 20px ${HypnoApp.theme.shadow}` }
                            
                        }, HypnoApp.coreText.toUpperCase())
                    ),
                    
                    // Biometric Clues (Bottom)
                    m(".absolute.bottom-8.left-1/2.-translate-x-1/2.text-center.text-sm.text-gray-400.opacity-60.p-2.rounded-lg.bg-black.bg-opacity-20",
                        HypnoApp.biometricClues.map(clue => m("p", {  }, clue))
                    )
                ])
            ]), page.components.camera.videoView(), page.components.dialog.loadDialog()];
        }
    };

    page.views.hyp = HypnoApp;
}());