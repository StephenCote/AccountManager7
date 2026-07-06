/**
 * SessionDirector - LLM-powered session orchestration
 * Periodically gathers session state (biometric data, labels, voice text)
 * and sends it to an LLM with a user-defined session command.
 * Receives structured JSON directives to adjust audio, visuals, labels, and image generation.
 * Uses the "Open Chat" chatConfig template from ~/Chat for LLM connection settings.
 */
(function() {

    class SessionDirector {
        static PROMPT_TEMPLATE_PATH = 'media/prompts/magic8DirectorPrompt.json';
        static _cachedTemplate = null;

        constructor() {
            // LLM infrastructure
            this.chatRequest = null;
            this.chatConfig = null;
            this.promptConfig = null;

            // Session config
            this.command = '';
            this.intervalMs = 60000;
            this.intervalId = null;
            this.isRunning = false;
            this.isPaused = false;

            // State provider callback - set by Magic8App
            this.stateProvider = null;

            // Tracking
            this.lastDirective = null;
            this.lastError = null;
            this.callCount = 0;
            this.consecutiveErrors = 0;

            // Mood ring mode
            this.moodRingMode = false;

            // Callbacks
            this.onDirective = null;
            this.onError = null;
            this.onStatusChange = null;
            this.onTickDebug = null; // (info) => {} — fired each tick with {tickNum, stateMessage, rawContent, directive}
        }

        /**
         * Initialize the LLM connection following the cardGame.js pattern
         * @param {string} command - User's session intent/theme text
         * @param {number} [intervalMs=60000] - Polling interval in ms
         * @param {string} [sessionName] - Session name for unique conversation history
         * @param {Object} [options] - Additional options
         * @param {string} [options.imageTags] - Comma-separated tags available for image search
         */
        async initialize(command, intervalMs, sessionName, options) {
            this.command = command || '';
            this.intervalMs = intervalMs || 60000;
            this.sessionName = sessionName || '';
            this.imageTags = options?.imageTags || '';

            try {
                this._setStatus('initializing');

                // Ensure shared library is initialized; show wizard if not
                let libReady = await LLMConnector.ensureLibrary();
                if (!libReady && typeof ChatSetupWizard !== "undefined") {
                    let self = this;
                    ChatSetupWizard.show(function() {
                        self.initialize(command, intervalMs, sessionName, options);
                    });
                    return;
                }

                // 1. Find ~/Chat directory (Phase 10: LLMConnector)
                const chatDir = await LLMConnector.findChatDir();
                if (!chatDir) {
                    throw new Error("Chat directory ~/Chat not found");
                }

                // 2-3. Find and load "Open Chat" template (Phase 10: LLMConnector)
                const fullTemplate = await LLMConnector.getOpenChatTemplate(chatDir);
                if (!fullTemplate) {
                    throw new Error("Failed to load 'Open Chat' template");
                }

                // 4. Create or update prompt config (Phase 10: LLMConnector.ensurePrompt)
                const systemPrompt = await this._buildSystemPrompt(command);
                const promptName = this.sessionName
                    ? "Magic8 Director - " + this.sessionName
                    : "Magic8 Director";
                this.promptConfig = await LLMConnector.ensurePrompt(promptName, systemPrompt, chatDir);
                if (!this.promptConfig) {
                    throw new Error("Failed to create prompt config");
                }

                // 5. Find or create chat config (Phase 10: LLMConnector.ensureConfig)
                this.chatConfig = await LLMConnector.ensureConfig(
                    "Magic8 Director", fullTemplate, { messageTrim: 4 }, chatDir
                );
                if (!this.chatConfig) {
                    throw new Error("Failed to create chat config");
                }

                // 6. Create chat request (Phase 10: LLMConnector.createSession)
                const requestName = this.sessionName
                    ? 'Magic8 Director - ' + this.sessionName
                    : 'Magic8 Director';
                this.chatRequest = await LLMConnector.createSession(requestName, this.chatConfig, this.promptConfig);
                if (!this.chatRequest) {
                    throw new Error("Failed to create chat request");
                }

                this._setStatus('ready');
                console.log('SessionDirector: Initialized successfully');
            } catch (err) {
                this.lastError = err.message;
                this._setStatus('error');
                console.error('SessionDirector: Initialization failed:', err);
                if (this.onError) this.onError(err);
            }
        }

        // Phase 10 (OI-47): _ensurePromptConfig, _ensureDiagnosticPromptConfig,
        // _ensureChatConfig, and _ensureDiagnosticChatConfig removed.
        // Now delegated to LLMConnector.ensurePrompt() and LLMConnector.ensureConfig().

        /**
         * Load the prompt template JSON from the static file.
         * Caches after first successful load.
         * @returns {Promise<Object|null>} Template object or null
         * @private
         */
        async _loadPromptTemplate() {
            if (SessionDirector._cachedTemplate) return SessionDirector._cachedTemplate;

            /// Phase 12: OI-17 — Try server-side prompt template first via REST endpoint
            try {
                const serverResp = await m.request({
                    method: 'GET',
                    url: g_application_path + '/rest/chat/config/prompt/prompt.magic8',
                    withCredentials: true,
                    extract: function(xhr) {
                        if (xhr.status !== 200 || !xhr.responseText) return null;
                        try { return JSON.parse(xhr.responseText); }
                        catch(e) { return null; }
                    }
                });
                if (serverResp && Array.isArray(serverResp.system)) {
                    /// Server-side promptConfig uses 'system' array (structured template schema)
                    /// Convert to the client-side format: { lines: [...], imageTagsAppendix: [...] }
                    let template = {
                        lines: serverResp.system,
                        imageTagsAppendix: serverResp.imageTagsAppendix || [],
                        version: serverResp.name || 'server'
                    };
                    SessionDirector._cachedTemplate = template;
                    console.log('SessionDirector: Loaded server-side prompt template: ' + template.version);
                    return template;
                }
            } catch (err) {
                console.log('SessionDirector: Server-side template not available, trying local file');
            }

            /// Fallback to client-side JSON file
            try {
                const resp = await m.request({
                    method: 'GET',
                    url: SessionDirector.PROMPT_TEMPLATE_PATH
                });
                if (resp && Array.isArray(resp.lines)) {
                    SessionDirector._cachedTemplate = resp;
                    console.log('SessionDirector: Loaded local prompt template v' + (resp.version || '?'));
                    return resp;
                }
            } catch (err) {
                console.warn('SessionDirector: Failed to load prompt template, using fallback:', err.message || err);
            }
            return null;
        }

        /**
         * Replace ${token} placeholders in a string
         * @param {string} str - String with ${token} placeholders
         * @param {Object} tokens - Key-value map of token replacements
         * @returns {string}
         * @private
         */
        _resolveTokens(str, tokens) {
            return str.replace(/\$\{(\w+)\}/g, (match, key) => {
                return tokens.hasOwnProperty(key) ? tokens[key] : match;
            });
        }

        /**
         * Build the system prompt for the director LLM.
         * Loads from external JSON template if available, falls back to hardcoded.
         * @param {string} command - User's session intent
         * @returns {Promise<Array<string>>} System prompt lines
         * @private
         */
        async _buildSystemPrompt(command) {
            const tokens = {
                command: command || '',
                imageTags: ''
            };

            // Resolve image tags for token replacement
            if (this.imageTags) {
                const tags = this.imageTags.split(',').map(t => t.trim()).filter(t => t);
                if (tags.length > 0) {
                    tokens.imageTags = tags.join(', ');
                }
            }

            // Try loading from external template
            const template = await this._loadPromptTemplate();
            if (template) {
                let lines = template.lines.map(line => this._resolveTokens(line, tokens));

                // Append image tags section if tags are configured
                if (tokens.imageTags && Array.isArray(template.imageTagsAppendix)) {
                    const tagLines = template.imageTagsAppendix.map(line => this._resolveTokens(line, tokens));
                    lines = lines.concat(tagLines);
                }

                return lines;
            }

            // Fallback: hardcoded prompt (in case template file is unavailable)
            console.warn('SessionDirector: Using hardcoded prompt fallback');
            return this._buildSystemPromptFallback(command);
        }

        /**
         * Hardcoded fallback system prompt (used if template JSON is unavailable)
         * @param {string} command
         * @returns {Array<string>}
         * @private
         */
        _buildSystemPromptFallback(command) {
            const lines = [
                "You are the Session Director for Magic8, an immersive audiovisual experience.",
                "SESSION INTENT: " + command,
                "You receive periodic state snapshots and must return a JSON object with directives to adjust the session.",
                "You MUST always respond with a valid JSON object containing at least one directive field.",
                "",
                "Directive fields (include what you want to change):",
                "  audio: { preset: \"goddessEnergy\"|\"heartOpening\"|\"loveFrequency\"|\"deepHealing\"|\"lettingGo\"|\"transformation\"|\"creativeFlow\"|\"thirdEye\"|\"crownConnection\"|\"regeneration\"|\"deepHypnosis\"|\"lucidDream\"",
                "           OR band: \"delta\"|\"theta\"|\"alphaTheta\"|\"alpha\"|\"beta\"|\"gamma\" with optional troughBand, endBand, secondTransition, thirdTransition,",
                "              baseFreq: carrier Hz (174|285|396|417|432|528|639|741|852|963),",
                "           isochronicEnabled: true/false, isochronicBand: \"delta\"|\"theta\"|\"alphaTheta\"|\"alpha\"|\"beta\"|\"gamma\",",
                "           volume: 0.0-1.0 }",
                "  visuals: { effect: \"particles\" or \"spiral\" or \"mandala\" or \"tunnel\" or \"hypnoDisc\", transitionDuration: 2000 }",
                "  labels: { add: [\"1-3 word phrase\"], remove: [\"exact label text\"] }",
                "  generateImage: { description: \"scene description\", style: \"art style keyword\", imageAction: \"what the subject is doing\", denoisingStrength: 0.3-0.9, cfg: 3-15, tags: \"tag1+tag2+tag3\" }",
                "  opacity: { labels: 0.15-1.0, images: 0.15-1.0, visualizer: 0.15-1.0, visuals: 0.15-1.0 }",
                "  voiceLine: \"short sentence to be spoken next via text-to-speech\"",
                "  suggestMood: { emotion: \"happy\"|\"sad\"|\"angry\"|\"fear\"|\"surprise\"|\"disgust\"|\"neutral\", gender: \"Man\"|\"Woman\", genderPct: 0-100 }",
                "  progressPct: 0-100",
                "  commentary: \"your reasoning (not displayed to user)\"",
                "",
                "Guidelines:",
                "- Evolve gradually based on biometric emotion, elapsed time, and session intent",
                "- Labels must be 1-3 words only. Keep them poetic and aligned with session intent.",
                "- Respond with ONLY the JSON object, no markdown fences, no explanation text"
            ];

            if (this.imageTags) {
                const tags = this.imageTags.split(',').map(t => t.trim()).filter(t => t);
                if (tags.length > 0) {
                    lines.push("");
                    lines.push("Available image tags: " + tags.join(', '));
                }
            }

            return lines;
        }

        /**
         * Build a trimmed system prompt for mood ring mode
         * Only requests suggestMood + commentary (no labels, voice, images, audio, opacity)
         * @param {string} command - Session intent
         * @returns {Array<string>}
         * @private
         */
        _buildMoodRingPrompt(command) {
            return [
                "You are the Mood Ring advisor for Magic8, an immersive audiovisual experience.",
                "SESSION INTENT: " + command,
                "You receive periodic biometric state snapshots and must return a JSON object.",
                "You MUST always respond with a valid JSON object.",
                "",
                "Directive fields:",
                "  suggestMood: { emotion: \"happy\"|\"sad\"|\"angry\"|\"fear\"|\"surprise\"|\"disgust\"|\"neutral\", gender: \"Man\"|\"Woman\" }",
                "  commentary: \"your reasoning (not displayed to user)\"",
                "",
                "Your role: observe the user's detected emotion and suggest what mood they might be feeling or transitioning toward.",
                "Consider the session intent, elapsed time, and emotion history to provide insightful mood suggestions.",
                "The suggested mood drives a visual mood ring color effect.",
                "",
                "Example: {\"suggestMood\":{\"emotion\":\"happy\",\"gender\":\"Woman\"},\"commentary\":\"user shows warmth\"}",
                "",
                "- Respond with ONLY the JSON object, no markdown fences, no explanation text"
            ];
        }

        /**
         * Format state for mood ring mode — only biometric data + emotion transition + elapsed time
         * @param {Object} state - Session state
         * @returns {string}
         * @private
         */
        _formatMoodRingState(state) {
            const lines = ['Mood Ring State:'];

            if (state.biometric) {
                const b = state.biometric;
                lines.push(`Biometric: emotion=${b.emotion || 'unknown'}, age=${b.age || 'unknown'}, gender=${b.gender || 'unknown'}`);
            } else {
                lines.push('Biometric: no face data');
            }

            if (state.emotionTransition) {
                const t = state.emotionTransition;
                lines.push(`Emotion Shift: ${t.from} → ${t.to} (${t.secsAgo}s ago)`);
            }

            if (state.elapsedMinutes != null) {
                lines.push(`Elapsed: ${Math.round(state.elapsedMinutes)} min`);
            }

            return lines.join('\n');
        }

        /**
         * Start the director polling loop
         */
        start() {
            if (this.isRunning) return;
            if (!this.chatRequest) {
                console.warn('SessionDirector: Cannot start - not initialized');
                return;
            }

            this.isRunning = true;
            this.isPaused = false;
            this._setStatus('running');
            console.log('SessionDirector: Started with interval', this.intervalMs, 'ms');

            // Run first tick immediately
            this._tick();

            // Set up polling interval
            this.intervalId = setInterval(() => this._tick(), this.intervalMs);
        }

        /**
         * Stop the director
         */
        stop() {
            this.isRunning = false;
            this.isPaused = false;
            if (this.intervalId) {
                clearInterval(this.intervalId);
                this.intervalId = null;
            }
            this._setStatus('stopped');
            console.log('SessionDirector: Stopped');
        }

        /**
         * Pause the director (keeps interval but skips ticks)
         */
        pause() {
            if (!this.isRunning) return;
            this.isPaused = true;
            this._setStatus('paused');
        }

        /**
         * Resume the director from pause
         */
        resume() {
            if (!this.isRunning) return;
            this.isPaused = false;
            this._setStatus('running');
        }

        /**
         * Perform one director tick - gather state, call LLM, apply directive
         * @private
         */
        async _tick() {
            if (!this.isRunning || this.isPaused) return;

            try {
                // Gather current session state
                const state = this.stateProvider ? this.stateProvider() : {};

                // Format the user message with state data
                const userMessage = this._formatStateMessage(state);

                const tickNum = this.callCount + 1;
                console.log('SessionDirector: Tick #' + tickNum + ', sending state to LLM');

                // Call the LLM - m.request can reject with null/undefined on server errors
                let response;
                try {
                    response = await am7chat.chat(this.chatRequest, userMessage);
                } catch (reqErr) {
                    const detail = reqErr?.message || reqErr?.statusText || (reqErr == null ? 'server returned empty error (check LLM server connectivity)' : String(reqErr));
                    throw new Error('LLM request failed: ' + detail);
                }

                if (!response) {
                    console.warn('SessionDirector: Null response from LLM');
                    return;
                }

                this.callCount++;
                this.consecutiveErrors = 0;

                // Extract assistant content from response
                const content = this._extractContent(response);
                if (!content) {
                    console.warn('SessionDirector: Empty response from LLM');
                    return;
                }

                console.log('SessionDirector: LLM response:', content);

                // Parse the JSON directive
                const directive = this._parseDirective(content);
                if (directive) {
                    this.lastDirective = directive;
                    if (this.onDirective) {
                        this.onDirective(directive);
                    }
                }

                // Emit tick debug info
                if (this.onTickDebug) {
                    this.onTickDebug({
                        tickNum: tickNum,
                        stateMessage: userMessage,
                        rawContent: content,
                        directive: directive
                    });
                }
            } catch (err) {
                this.consecutiveErrors++;
                this.lastError = err.message || String(err);
                // Only log every error the first 3 times, then every 5th to reduce spam
                if (this.consecutiveErrors <= 3 || this.consecutiveErrors % 5 === 0) {
                    console.error('SessionDirector: Tick error (' + this.consecutiveErrors + ' consecutive):', err.message || err);
                }
                if (this.onError) this.onError(err);
            }
        }

        /**
         * Format session state into a user message for the LLM
         * @param {Object} state - Current session state
         * @returns {string} Formatted state message
         * @private
         */
        _formatStateMessage(state) {
            const lines = ['State Update:'];

            // Biometric data
            if (state.biometric) {
                const b = state.biometric;
                lines.push(`Biometric: emotion=${b.emotion || 'unknown'}, age=${b.age || 'unknown'}, gender=${b.gender || 'unknown'}`);
            } else {
                lines.push('Biometric: no face data');
            }

            // Emotion transition (recent change within 30s)
            if (state.emotionTransition) {
                const t = state.emotionTransition;
                lines.push(`Emotion Shift: ${t.from} → ${t.to} (${t.secsAgo}s ago)`);
            }

            // Session labels
            if (state.labels && state.labels.length > 0) {
                lines.push('Session Labels: [' + state.labels.join(', ') + ']');
            } else {
                lines.push('Session Labels: none');
            }

            // Recent voice lines
            if (state.recentVoiceLines && state.recentVoiceLines.length > 0) {
                lines.push('Recent Voice: "' + state.recentVoiceLines.join('" | "') + '"');
            }

            // Injected voice lines (so the LLM knows what it already said)
            if (state.injectedVoiceLines && state.injectedVoiceLines.length > 0) {
                lines.push('Your Injected Lines: "' + state.injectedVoiceLines.join('" | "') + '"');
            }

            // Current text
            if (state.currentText) {
                lines.push('Current Text: "' + state.currentText + '"');
            }

            // Audio state — prefer band labels over raw Hz
            if (state.audio) {
                const a = state.audio;
                if (a.bandLabel) {
                    lines.push(`Current Audio: band=${a.bandLabel}, carrier=${a.baseFreq || '?'} Hz, isochronic=${a.isochronicEnabled ? 'on' : 'off'}`);
                } else {
                    lines.push(`Current Audio: sweep ${a.sweepStart || '?'}→${a.sweepTrough || '?'}→${a.sweepEnd || '?'} Hz, isochronic=${a.isochronicEnabled ? 'on' : 'off'}`);
                }
            }

            // Visual state
            if (state.visuals) {
                const v = state.visuals;
                lines.push(`Current Visual: ${v.mode || 'single'} mode, effects=[${(v.effects || []).join(',')}]`);
            }

            // SD image generation config
            if (state.sdConfig) {
                const sd = state.sdConfig;
                lines.push(`Current SD: style=${sd.style || '?'}, denoising=${sd.denoisingStrength || '?'}, cfg=${sd.cfg || '?'}, desc="${(sd.description || '').substring(0, 60)}"`);
            }

            // Layer opacity
            if (state.layerOpacity) {
                const o = state.layerOpacity;
                lines.push(`Layer Opacity: labels=${o.labels}, images=${o.images}, visualizer=${o.visualizer}, visuals=${o.visuals}`);
            }

            // User response from interactive mode
            if (state.userResponse) {
                lines.push('User Response: "' + state.userResponse + '"');
            }

            // Interactive mode indicator with escalating prompts
            if (state.interactiveEnabled) {
                const ticks = state.ticksSinceAskUser || 0;
                if (ticks >= 4) {
                    lines.push('Interactive: ENABLED — You MUST include askUser in this response. Ask the user a short question about their experience. Example: {"askUser":{"question":"How are you feeling right now?"}}');
                } else if (ticks >= 2) {
                    lines.push('Interactive: ENABLED — Include an askUser directive to check in with the user. Pair it with a matching voiceLine.');
                } else {
                    lines.push('Interactive: ENABLED');
                }
            }

            // Mood ring status
            if (state.moodRingEnabled) {
                lines.push('Mood Ring: ACTIVE — include suggestMood in your response');
            }

            // Elapsed time
            if (state.elapsedMinutes != null) {
                lines.push('Elapsed: ' + Math.round(state.elapsedMinutes) + ' min');
            }

            return lines.join('\n');
        }

        /**
         * Extract assistant content from LLM response.
         * Phase 10 (OI-47): Delegates to LLMConnector.extractContent()
         * @param {Object} response - Chat response object
         * @returns {string|null} Content string
         * @private
         */
        _extractContent(response) {
            let content = LLMConnector.extractContent(response);
            // Fallback: if LLMConnector returns null but we have a response object, stringify it
            if (content === null && response && typeof response === 'object') {
                return JSON.stringify(response);
            }
            return content;
        }

        /**
         * Parse a JSON directive from LLM response content.
         * Phase 10 (OI-47): JSON parsing delegated to LLMConnector.parseDirective().
         * Domain-specific directive field validation remains here.
         * @param {string} content - Raw LLM response
         * @returns {Object|null} Parsed directive or null
         * @private
         */
        _parseDirective(content) {
            if (!content) return null;

            // Phase 10: Delegate JSON parsing to LLMConnector
            let directive = LLMConnector.parseDirective(content);
            if (!directive) {
                console.warn('SessionDirector: No JSON object found in response');
                return null;
            }

            // Domain-specific directive field validation (Magic8-specific)
            try {
                const valid = {};

                if (directive.audio && typeof directive.audio === 'object') {
                    valid.audio = {};
                    if (typeof directive.audio.preset === 'string') valid.audio.preset = directive.audio.preset;
                    const validBands = ['delta', 'theta', 'alphaTheta', 'alpha', 'beta', 'gamma'];
                    if (typeof directive.audio.band === 'string' && validBands.includes(directive.audio.band)) {
                        valid.audio.band = directive.audio.band;
                    }
                    if (typeof directive.audio.troughBand === 'string' && validBands.includes(directive.audio.troughBand)) {
                        valid.audio.troughBand = directive.audio.troughBand;
                    }
                    if (typeof directive.audio.endBand === 'string' && validBands.includes(directive.audio.endBand)) {
                        valid.audio.endBand = directive.audio.endBand;
                    }
                    if (typeof directive.audio.secondTransition === 'string' && validBands.includes(directive.audio.secondTransition)) {
                        valid.audio.secondTransition = directive.audio.secondTransition;
                    }
                    if (typeof directive.audio.thirdTransition === 'string' && validBands.includes(directive.audio.thirdTransition)) {
                        valid.audio.thirdTransition = directive.audio.thirdTransition;
                    }
                    if (typeof directive.audio.baseFreq === 'number') valid.audio.baseFreq = directive.audio.baseFreq;
                    if (typeof directive.audio.sweepStart === 'number') valid.audio.sweepStart = directive.audio.sweepStart;
                    if (typeof directive.audio.sweepTrough === 'number') valid.audio.sweepTrough = directive.audio.sweepTrough;
                    if (typeof directive.audio.sweepEnd === 'number') valid.audio.sweepEnd = directive.audio.sweepEnd;
                    if (typeof directive.audio.isochronicEnabled === 'boolean') valid.audio.isochronicEnabled = directive.audio.isochronicEnabled;
                    if (typeof directive.audio.isochronicRate === 'number') valid.audio.isochronicRate = directive.audio.isochronicRate;
                    if (typeof directive.audio.isochronicBand === 'string' && validBands.includes(directive.audio.isochronicBand)) {
                        valid.audio.isochronicBand = directive.audio.isochronicBand;
                    }
                    if (typeof directive.audio.volume === 'number') valid.audio.volume = directive.audio.volume;
                }

                if (directive.visuals && typeof directive.visuals === 'object') {
                    valid.visuals = {};
                    if (typeof directive.visuals.effect === 'string') valid.visuals.effect = directive.visuals.effect;
                    if (typeof directive.visuals.transitionDuration === 'number') valid.visuals.transitionDuration = directive.visuals.transitionDuration;
                }

                if (directive.labels && typeof directive.labels === 'object') {
                    valid.labels = {};
                    if (Array.isArray(directive.labels.add)) {
                        valid.labels.add = directive.labels.add.filter(l => typeof l === 'string');
                    }
                    if (Array.isArray(directive.labels.remove)) {
                        valid.labels.remove = directive.labels.remove.filter(l => typeof l === 'string');
                    }
                }

                if (directive.generateImage && typeof directive.generateImage === 'object') {
                    valid.generateImage = {};
                    if (typeof directive.generateImage.description === 'string') valid.generateImage.description = directive.generateImage.description;
                    if (typeof directive.generateImage.style === 'string') valid.generateImage.style = directive.generateImage.style;
                    if (typeof directive.generateImage.imageAction === 'string') valid.generateImage.imageAction = directive.generateImage.imageAction;
                    if (typeof directive.generateImage.denoisingStrength === 'number') valid.generateImage.denoisingStrength = directive.generateImage.denoisingStrength;
                    if (typeof directive.generateImage.cfg === 'number') valid.generateImage.cfg = directive.generateImage.cfg;
                    if (typeof directive.generateImage.tags === 'string') {
                        valid.generateImage.tags = directive.generateImage.tags.split('+').map(t => t.trim()).filter(t => t);
                    } else if (Array.isArray(directive.generateImage.tags)) {
                        valid.generateImage.tags = directive.generateImage.tags.filter(t => typeof t === 'string');
                    }
                    if (Object.keys(valid.generateImage).length === 0) delete valid.generateImage;
                }

                if (directive.opacity && typeof directive.opacity === 'object') {
                    valid.opacity = {};
                    for (const layer of ['labels', 'images', 'visualizer', 'visuals']) {
                        if (typeof directive.opacity[layer] === 'number') {
                            valid.opacity[layer] = Math.max(0.15, Math.min(1, directive.opacity[layer]));
                        }
                    }
                    if (Object.keys(valid.opacity).length === 0) delete valid.opacity;
                }

                if (typeof directive.voiceLine === 'string' && directive.voiceLine.trim().length > 0) {
                    valid.voiceLine = directive.voiceLine.trim();
                }

                if (typeof directive.commentary === 'string') {
                    valid.commentary = directive.commentary;
                }

                if (typeof directive.progressPct === 'number') {
                    valid.progressPct = Math.max(0, Math.min(100, Math.round(directive.progressPct)));
                }

                if (directive.suggestMood && typeof directive.suggestMood === 'object') {
                    const validEmotions = ['neutral', 'happy', 'sad', 'angry', 'fear', 'surprise', 'disgust'];
                    const validGenders = ['Man', 'Woman'];
                    valid.suggestMood = {};
                    if (typeof directive.suggestMood.emotion === 'string' && validEmotions.includes(directive.suggestMood.emotion)) {
                        valid.suggestMood.emotion = directive.suggestMood.emotion;
                    }
                    if (typeof directive.suggestMood.gender === 'string' && validGenders.includes(directive.suggestMood.gender)) {
                        valid.suggestMood.gender = directive.suggestMood.gender;
                    }
                    if (typeof directive.suggestMood.genderPct === 'number') {
                        valid.suggestMood.genderPct = Math.max(0, Math.min(100, Math.round(directive.suggestMood.genderPct)));
                    }
                    if (Object.keys(valid.suggestMood).length === 0) delete valid.suggestMood;
                }

                if (directive.askUser && typeof directive.askUser === 'object') {
                    if (typeof directive.askUser.question === 'string' && directive.askUser.question.trim().length > 0) {
                        valid.askUser = {
                            question: directive.askUser.question.trim()
                        };
                    }
                }

                return valid;
            } catch (err) {
                console.warn('SessionDirector: Failed to validate directive fields:', err.message);
                return null;
            }
        }

        // Phase 10 (OI-47): _repairTruncatedJson removed — now LLMConnector.repairJson()

        /**
         * Verify that a directive was applied correctly by comparing against post-application state
         * @param {Object} directive - The directive that was applied
         * @param {Object} postState - State gathered after application
         * @returns {Array<{field: string, pass: boolean, detail: string}>}
         * @private
         */
        _verifyDirectiveApplication(directive, postState) {
            const checks = [];

            if (directive.audio && postState.audio) {
                for (const key of ['sweepStart', 'sweepTrough', 'sweepEnd']) {
                    if (directive.audio[key] != null) {
                        const match = postState.audio[key] === directive.audio[key];
                        checks.push({
                            field: 'audio.' + key,
                            pass: match,
                            detail: match
                                ? key + '=' + postState.audio[key]
                                : 'expected ' + directive.audio[key] + ', got ' + postState.audio[key]
                        });
                    }
                }
                if (directive.audio.isochronicEnabled != null) {
                    const match = postState.audio.isochronicEnabled === directive.audio.isochronicEnabled;
                    checks.push({
                        field: 'audio.isochronicEnabled',
                        pass: match,
                        detail: match
                            ? 'isochronic=' + postState.audio.isochronicEnabled
                            : 'expected ' + directive.audio.isochronicEnabled + ', got ' + postState.audio.isochronicEnabled
                    });
                }
            }

            if (directive.visuals?.effect && postState.visuals) {
                const match = postState.visuals.effects && postState.visuals.effects.includes(directive.visuals.effect);
                checks.push({
                    field: 'visuals.effect',
                    pass: match,
                    detail: match
                        ? 'effect=' + directive.visuals.effect
                        : 'expected ' + directive.visuals.effect + ', got ' + JSON.stringify(postState.visuals.effects)
                });
            }

            if (directive.labels?.add?.length > 0 && postState.labels) {
                for (const label of directive.labels.add) {
                    const match = postState.labels.includes(label);
                    checks.push({
                        field: 'labels.add',
                        pass: match,
                        detail: match ? '"' + label + '" present' : '"' + label + '" not found in state'
                    });
                }
            }

            if (directive.labels?.remove?.length > 0 && postState.labels) {
                for (const label of directive.labels.remove) {
                    const match = !postState.labels.includes(label);
                    checks.push({
                        field: 'labels.remove',
                        pass: match,
                        detail: match ? '"' + label + '" removed' : '"' + label + '" still present'
                    });
                }
            }

            return checks;
        }

        /**
         * Run diagnostics - verify infrastructure, then multi-pass behavioral tests
         * with varying biometric states to verify the LLM adapts its directives.
         * Uses dedicated "Magic8 Diagnostics" configs to avoid modifying live session state.
         * @param {string} [command] - Optional command override for testing
         * @param {Function} [onDirective] - Called with each parsed directive for live application
         * @param {Function} [onLog] - Called with each log entry for live display
         * @returns {Promise<Array<{name: string, pass: boolean, detail: string, directive?: Object}>>}
         */
        async runDiagnostics(command, onDirective, onLog) {
            const results = [];
            const testCommand = command || this.command || 'Diagnostic test session';

            const log = (name, pass, detail, directive) => {
                const entry = { name, pass, detail: detail || '' };
                if (directive) entry.directive = directive;
                results.push(entry);
                console.log(`SessionDirector [TEST] ${pass ? 'PASS' : 'FAIL'}: ${name}${detail ? ' - ' + detail : ''}`);
                if (onLog) onLog(entry);
            };

            // === INFRASTRUCTURE TESTS ===

            // Test 1: ~/Chat directory (Phase 10: LLMConnector)
            let chatDir;
            try {
                chatDir = await LLMConnector.findChatDir();
                if (chatDir && chatDir.objectId) {
                    log('Find ~/Chat directory', true, chatDir.objectId);
                } else {
                    log('Find ~/Chat directory', false, 'Not found');
                    return results;
                }
            } catch (err) {
                log('Find ~/Chat directory', false, err.message);
                return results;
            }

            // Test 2-3: "Open Chat" template (Phase 10: LLMConnector)
            let fullTemplate;
            try {
                fullTemplate = await LLMConnector.getOpenChatTemplate(chatDir);
                if (fullTemplate && fullTemplate.serverUrl) {
                    log('Find "Open Chat" template', true, fullTemplate.objectId || 'loaded');
                    log('Load template details', true,
                        `model=${fullTemplate.model}, service=${fullTemplate.serviceType}, server=${fullTemplate.serverUrl}`);
                } else {
                    log('Find "Open Chat" template', false, 'Not found or missing serverUrl');
                    return results;
                }
            } catch (err) {
                log('Find "Open Chat" template', false, err.message);
                return results;
            }

            // Test 4: Diagnostic prompt config (separate from live session)
            let promptConfig;
            try {
                const systemPrompt = await this._buildSystemPrompt(testCommand);
                promptConfig = await LLMConnector.ensurePrompt("Magic8 Diagnostics", systemPrompt, chatDir);
                if (promptConfig && promptConfig.objectId) {
                    log('Diagnostic prompt config', true,
                        `objectId=${promptConfig.objectId}, system lines=${promptConfig.system?.length || '?'}`);
                } else {
                    log('Diagnostic prompt config', false, 'Returned null');
                    return results;
                }
            } catch (err) {
                log('Diagnostic prompt config', false, err.message);
                return results;
            }

            // Test 5: Diagnostic chat config (separate, temperature=0.8)
            let chatConfig;
            try {
                chatConfig = await LLMConnector.ensureConfig(
                    "Magic8 Diagnostics", fullTemplate,
                    { messageTrim: 4, chatOptions: { temperature: 0.8 } }, chatDir
                );
                if (chatConfig && chatConfig.objectId) {
                    const temp = chatConfig.chatOptions?.temperature;
                    log('Diagnostic chat config', true,
                        `objectId=${chatConfig.objectId}, model=${chatConfig.model}, temp=${temp || 'default'}`);
                } else {
                    log('Diagnostic chat config', false, 'Returned null');
                    return results;
                }
            } catch (err) {
                log('Diagnostic chat config', false, err.message);
                return results;
            }

            // Test 6: Diagnostic chat request
            let chatRequest;
            try {
                chatRequest = await LLMConnector.createSession("Magic8 Diagnostics", chatConfig, promptConfig);
                if (chatRequest) {
                    log('Diagnostic chat request', true,
                        `objectId=${chatRequest.objectId || 'inline'}`);
                } else {
                    log('Diagnostic chat request', false, 'Returned null');
                    return results;
                }
            } catch (err) {
                log('Diagnostic chat request', false, err.message);
                return results;
            }

            // === BEHAVIORAL TESTS - multiple passes with varying biometric states ===

            const testScenarios = [
                {
                    name: 'Pass 1: Neutral baseline',
                    state: {
                        biometric: { emotion: 'neutral', age: 30, gender: 'unknown' },
                        labels: [],
                        recentVoiceLines: [],
                        currentText: '',
                        audio: { sweepStart: 8, sweepTrough: 2, sweepEnd: 8, isochronicEnabled: false },
                        visuals: { mode: 'single', effects: ['particles'] },
                        elapsedMinutes: 0
                    }
                },
                {
                    name: 'Pass 2: Happy + early session',
                    state: {
                        biometric: { emotion: 'happy', age: 28, gender: 'female' },
                        labels: [], // will be filled from pass 1
                        recentVoiceLines: ['relax and breathe deeply'],
                        currentText: 'let go of tension',
                        audio: { sweepStart: 8, sweepTrough: 2, sweepEnd: 8, isochronicEnabled: false },
                        visuals: { mode: 'single', effects: ['particles'] },
                        elapsedMinutes: 2
                    }
                },
                {
                    name: 'Pass 3: Sad + mid-session',
                    state: {
                        biometric: { emotion: 'sad', age: 35, gender: 'male' },
                        labels: [],
                        recentVoiceLines: ['feel the weight lifting', 'you are safe here'],
                        currentText: 'embrace the stillness',
                        audio: { sweepStart: 6, sweepTrough: 3, sweepEnd: 7, isochronicEnabled: true },
                        visuals: { mode: 'single', effects: ['spiral'] },
                        elapsedMinutes: 5
                    }
                },
                {
                    name: 'Pass 4: Fear + late session',
                    state: {
                        biometric: { emotion: 'fear', age: 42, gender: 'female' },
                        labels: [],
                        recentVoiceLines: ['nothing can harm you', 'breathe into the feeling'],
                        currentText: 'you are protected',
                        audio: { sweepStart: 4, sweepTrough: 2, sweepEnd: 5, isochronicEnabled: true },
                        visuals: { mode: 'single', effects: ['mandala'] },
                        elapsedMinutes: 12
                    }
                },
                {
                    name: 'Pass 5: Interactive + user response',
                    state: {
                        biometric: { emotion: 'happy', age: 30, gender: 'female' },
                        labels: [],
                        recentVoiceLines: [],
                        currentText: '',
                        audio: { sweepStart: 8, sweepTrough: 4, sweepEnd: 8, isochronicEnabled: false },
                        visuals: { mode: 'single', effects: ['particles'] },
                        interactiveEnabled: true,
                        ticksSinceAskUser: 5,
                        userResponse: 'I feel calm and centered',
                        elapsedMinutes: 8
                    }
                }
            ];

            const directives = [];
            let accumulatedLabels = [];

            for (let i = 0; i < testScenarios.length; i++) {
                const scenario = testScenarios[i];

                // Carry forward labels from previous directives
                if (i > 0) {
                    scenario.state.labels = [...accumulatedLabels];
                }
                // Carry forward audio/visual changes from previous directives
                if (i > 0 && directives[i - 1]) {
                    const prev = directives[i - 1];
                    if (prev.audio) {
                        if (prev.audio.sweepStart != null) scenario.state.audio.sweepStart = prev.audio.sweepStart;
                        if (prev.audio.sweepTrough != null) scenario.state.audio.sweepTrough = prev.audio.sweepTrough;
                        if (prev.audio.sweepEnd != null) scenario.state.audio.sweepEnd = prev.audio.sweepEnd;
                        if (prev.audio.isochronicEnabled != null) scenario.state.audio.isochronicEnabled = prev.audio.isochronicEnabled;
                    }
                    if (prev.visuals?.effect) {
                        scenario.state.visuals.effects = [prev.visuals.effect];
                    }
                }

                try {
                    const msg = this._formatStateMessage(scenario.state);
                    console.log('SessionDirector [TEST] Sending:', msg);
                    const response = await am7chat.chat(chatRequest, msg);
                    const content = this._extractContent(response);
                    console.log('SessionDirector [TEST] Raw response:', content);

                    if (!content) {
                        log(scenario.name, false, 'Empty response from LLM');
                        directives.push(null);
                        continue;
                    }

                    const directive = this._parseDirective(content);
                    directives.push(directive);

                    if (directive) {
                        // Track accumulated labels
                        if (directive.labels?.add) {
                            for (const l of directive.labels.add) {
                                if (!accumulatedLabels.includes(l)) accumulatedLabels.push(l);
                            }
                            if (accumulatedLabels.length > 10) accumulatedLabels = accumulatedLabels.slice(-10);
                        }
                        if (directive.labels?.remove) {
                            accumulatedLabels = accumulatedLabels.filter(l => !directive.labels.remove.includes(l));
                        }

                        // Build detail summary
                        const parts = [];
                        if (directive.audio) parts.push('audio:' + Object.keys(directive.audio).join(','));
                        if (directive.visuals) parts.push('visual:' + (directive.visuals.effect || 'unchanged'));
                        if (directive.labels?.add?.length) parts.push('+' + directive.labels.add.length + ' labels');
                        if (directive.labels?.remove?.length) parts.push('-' + directive.labels.remove.length + ' labels');
                        if (directive.generateImage) parts.push('img:yes');
                        if (directive.askUser) parts.push('askUser:"' + directive.askUser.question.substring(0, 30) + '"');
                        if (directive.commentary) parts.push('note:' + directive.commentary.substring(0, 40));

                        log(scenario.name, true, parts.join(' | ') || 'empty directive', directive);

                        // Notify caller for live application
                        if (onDirective) onDirective(directive, i, scenario.name);

                        // Verify directive was applied to actual session state
                        if (onDirective && this.stateProvider) {
                            await new Promise(r => setTimeout(r, 200));
                            const postState = this.stateProvider();
                            const verifications = this._verifyDirectiveApplication(directive, postState);
                            for (const v of verifications) {
                                log(scenario.name + ' verify: ' + v.field, v.pass, v.detail);
                            }
                        }
                    } else {
                        log(scenario.name, false, 'Could not parse: ' + content.substring(0, 80));
                    }
                } catch (err) {
                    log(scenario.name, false, err.message);
                    directives.push(null);
                }
            }

            // === ADAPTATION ANALYSIS ===

            const validDirectives = directives.filter(d => d != null);
            if (validDirectives.length >= 2) {
                // Check if directives actually changed between passes
                let changes = 0;
                for (let i = 1; i < validDirectives.length; i++) {
                    const prev = JSON.stringify(validDirectives[i - 1]);
                    const curr = JSON.stringify(validDirectives[i]);
                    if (prev !== curr) changes++;
                }

                if (changes > 0) {
                    log('Adaptation check', true,
                        changes + '/' + (validDirectives.length - 1) + ' passes produced different directives');
                } else {
                    log('Adaptation check', false,
                        'All passes returned identical directives - LLM may not be responding to state changes');
                }

                // Check if emotion shift caused any label/visual/audio change
                const hasLabelActivity = validDirectives.some(d => d.labels?.add?.length || d.labels?.remove?.length);
                const hasAudioChange = validDirectives.some(d => d.audio && Object.keys(d.audio).length > 0);
                const hasVisualChange = validDirectives.some(d => d.visuals?.effect);

                const hasAskUser = validDirectives.some(d => d.askUser);

                log('Coverage: labels', hasLabelActivity, hasLabelActivity ? 'LLM generated labels' : 'No labels across all passes');
                log('Coverage: audio', hasAudioChange, hasAudioChange ? 'LLM adjusted audio' : 'No audio changes across all passes');
                log('Coverage: visuals', hasVisualChange, hasVisualChange ? 'LLM changed visuals' : 'No visual changes across all passes');
                log('Coverage: askUser', hasAskUser, hasAskUser ? 'LLM used askUser directive' : 'No askUser across all passes (may be expected if interactive scenario was not reached)');
            } else if (validDirectives.length === 1) {
                log('Adaptation check', true, 'Only 1 valid directive - cannot compare adaptation');
            } else {
                log('Adaptation check', false, 'No valid directives returned');
            }

            return results;
        }

        /**
         * Emit status change
         * @param {string} status
         * @private
         */
        _setStatus(status) {
            if (this.onStatusChange) {
                this.onStatusChange(status);
            }
        }

        /**
         * Clean up all resources
         */
        dispose() {
            this.stop();
            this.chatRequest = null;
            this.chatConfig = null;
            this.promptConfig = null;
            this.stateProvider = null;
            this.onDirective = null;
            this.onError = null;
            this.onStatusChange = null;
            this.onTickDebug = null;
            this.lastDirective = null;
            this.lastError = null;
        }
    }

    // Export
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = SessionDirector;
    }
    if (typeof window !== 'undefined') {
        window.Magic8 = window.Magic8 || {};
        window.Magic8.SessionDirector = SessionDirector;
    }

}());
