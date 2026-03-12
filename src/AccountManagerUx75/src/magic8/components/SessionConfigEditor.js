/**
 * SessionConfigEditor.js — Configuration UI for Magic8 sessions (ESM)
 * Port of Ux7 client/view/magic8/components/SessionConfigEditor.js (1,861 lines)
 */
import m from 'mithril';
import { am7model } from '../../core/model.js';
import { am7client } from '../../core/am7client.js';
import { am7view } from '../../core/view.js';
import { uwm } from '../../core/am7client.js';
import { AudioEngine } from '../audio/AudioEngine.js';
import { SessionDirector } from '../ai/SessionDirector.js';

function getPage() { return am7model._page; }

let am7sd = null;
let _sdLoaded = false;
function _ensureSd() {
    if (_sdLoaded) return Promise.resolve(am7sd);
    _sdLoaded = true;
    return import('../../components/sdConfig.js').then(mod => { am7sd = mod.am7sd; return am7sd; }).catch(() => null);
}

const SessionConfigEditor = {
    oninit(vnode) {
        _ensureSd().then(() => m.redraw());
        this.config = vnode.attrs.config || this._getDefaultConfig();
        this.sdConfigs = [];
        this.textSources = [];
        this.savedSessions = [];
        this.selectedImageGroups = [];
        this.isLoading = true;
        this.createNewSdConfig = false;
        this.createNewTextSource = false;
        this.newTextName = '';
        this.newTextContent = '';
        this.newTextSourceType = 'note';
        this.voiceSources = [];
        this.voiceProfiles = [];
        this.createNewVoiceSource = false;
        this.newVoiceName = '';
        this.newVoiceContent = '';
        this.newVoiceSourceType = 'note';
        this.chatConfigs = [];
        this.isSaving = false;
        this.loadingSession = false;
        this.directorTestRunning = false;
        this.directorTestResults = null;
        this.selectedSdDetails = null;
        this.loadingSdDetails = false;
        this.fetchingRandomSd = false;
        this.randomSdExtras = null;
        this.sdModelList = ['juggernautXL_ragnarokBy.safetensors','dreamshaperXL_v21TurboDPMSDE','chilloutmix_Ni','realismFromHadesXL_lightningV3','realmixXL_V10.safetensors','lustifySDXLNSFW_endgame.safetensors','ponyRealism_V22.safetensors','sdXL_v10VAEFix'];
        this._loadOptions();
    },

    _getDefaultConfig() {
        return {
            name: 'New Session',
            display: { fullscreen: false, showControls: true, controlsAutoHide: 5000 },
            biometrics: { enabled: true, captureInterval: 5, updateInterval: 500, smoothingFactor: 0.1 },
            audio: {
                binauralEnabled: true, baseFreq: 440, minBeat: 2, maxBeat: 8,
                sweepStart: 8, sweepTrough: 2, sweepEnd: 8, sweepDurationMin: 5,
                fadeInSec: 2, fadeOutSec: 3, panEnabled: false, panSpeed: 0.1, panDepth: 0.8,
                isochronicEnabled: false, isochronicFreq: 200, isochronicRate: 6, isochronicVolume: 0.15,
                visualizerEnabled: false, visualizerOpacity: 0.35, visualizerMode: 2, visualizerGradient: 'prism'
            },
            images: { baseGroups: [], cycleInterval: 5000, crossfadeDuration: 1000, includeGenerated: true },
            visuals: {
                effects: [], mode: 'single', transitionInterval: 30000, transitionDuration: 2000,
                particleCount: 100, spiralSpeed: 0.01, mandalaLayers: 4, tunnelRings: 12
            },
            imageGeneration: {
                enabled: false, sdConfigId: null, captureInterval: 120000, maxGeneratedImages: 20,
                sdInline: {
                    model: '', refinerModel: '', style: 'art',
                    description: 'ethereal dreamlike portrait, soft lighting, mystical atmosphere',
                    imageAction: 'posing in a surreal setting',
                    negativePrompt: 'Washed out colors, illogical, disgusting, bad anatomy, errors, glitches, mistakes, low resolution, pixilated, blurry, out of focus, low res, mutated, distorted, melting, cropped, disproportionate, wonky, low quality, compressed, muddy colors, overexposed, mosaic, rotten, fake, low poly, lacking detail, watermark, malformed, failed, failure, extra fingers, cloned face, missing legs, extra arms, fused fingers, too many fingers, poorly drawn face',
                    denoisingStrength: 0.65, steps: 30, cfg: 7, sampler: 'dpmpp_2m', scheduler: 'Karras',
                    seed: -1, width: 512, height: 512, hires: false, bodyStyle: 'full body',
                    imageSetting: 'random', refinerSteps: 20, refinerScheduler: 'Karras',
                    refinerSampler: 'dpmpp_2m', refinerCfg: 7, refinerMethod: 'PostApply',
                    refinerUpscale: 2, refinerUpscaleMethod: 'pixel-lanczos', refinerControlPercentage: 0.2
                }
            },
            text: { enabled: false, sourceObjectId: null, sourceType: 'note', displayDuration: 5000, loop: true },
            voice: { enabled: false, sourceObjectId: null, sourceType: 'note', voiceProfileId: null, loop: true },
            recording: { enabled: false, autoStart: true, maxDurationMin: 30 },
            director: { enabled: false, command: '', intervalMs: 60000, testMode: false, imageTags: '', interactive: false, openChatMode: false, chatConfigObjectId: null }
        };
    },

    async _loadOptions() {
        const page = getPage();
        try {
            await page.makePath("auth.group", "data", "~/Magic8/SDConfigs");
            await page.makePath("auth.group", "data", "~/Magic8/TextSequences");
            await page.makePath("auth.group", "data", "~/Magic8/Configs");
            await page.makePath("auth.group", "data", "~/Magic8/VoiceSequences");

            this.savedSessions = await this._listDataInGroup('~/Magic8/Configs');
            this.sdConfigs = await this._listDataInGroup('~/Magic8/SDConfigs');

            // Load chat configs from ~/Chat for the open chat mode dropdown
            try {
                let chatDir = await page.findObject("auth.group", "DATA", "~/Chat");
                if (chatDir?.objectId) {
                    this.chatConfigs = await page.listObjects("olio.llm.chatConfig", chatDir.objectId, null, 0, 50) || [];
                }
            } catch (e) { this.chatConfigs = []; }

            const notes = await this._listNotesInGroup('~/Magic8/TextSequences');
            const textObjects = await this._listDataInGroup('~/Magic8/TextSequences');
            this.textSources = [...notes.map(n => ({ ...n, sourceType: 'note' })), ...textObjects.map(t => ({ ...t, sourceType: 'data' }))];

            const voiceNotes = await this._listNotesInGroup('~/Magic8/VoiceSequences');
            const voiceData = await this._listDataInGroup('~/Magic8/VoiceSequences');
            this.voiceSources = [...voiceNotes.map(n => ({ ...n, sourceType: 'note' })), ...voiceData.map(t => ({ ...t, sourceType: 'data' }))];

            this.voiceProfiles = await this._listVoiceProfiles();

            if (page.components.dnd?.workingSet) {
                const groups = page.components.dnd.workingSet.filter(w => w[am7model.jsonModelKey] === 'auth.group');
                for (const grp of groups) {
                    if (this.config.images.baseGroups.indexOf(grp.objectId) === -1) this.config.images.baseGroups.push(grp.objectId);
                    if (!this.selectedImageGroups.find(g => g.objectId === grp.objectId)) this.selectedImageGroups.push({ id: grp.id, objectId: grp.objectId, name: grp.name });
                }
            }

            if (am7sd?.fetchModels) {
                let models = await am7sd.fetchModels();
                if (models?.length > 0) this.sdModelList = models;
            }

            this.isLoading = false;
            m.redraw();
        } catch (err) {
            console.error('SessionConfigEditor: Failed to load options:', err);
            this.isLoading = false;
            m.redraw();
        }
    },

    async _listDataInGroup(groupPath) {
        const page = getPage();
        try {
            let dir = await page.findObject("auth.group", "DATA", groupPath);
            if (!dir?.objectId) return [];
            return await am7client.list("data.data", dir.objectId, null, 0, 0) || [];
        } catch (err) { return []; }
    },

    async _listNotesInGroup(groupPath) {
        const page = getPage();
        try {
            let dir = await page.findObject("auth.group", "DATA", groupPath);
            if (!dir?.objectId) return [];
            return await am7client.list("data.note", dir.objectId, null, 0, 0) || [];
        } catch (err) { return []; }
    },

    async _listVoiceProfiles() {
        const page = getPage();
        try {
            let q = am7view.viewQuery(am7model.newInstance("identity.voice"));
            let qr = await page.search(q);
            return qr?.results || [];
        } catch (err) { return []; }
    },

    async _loadSdConfigDetails(objectId) {
        if (!objectId) { this.selectedSdDetails = null; return; }
        const page = getPage();
        this.loadingSdDetails = true; m.redraw();
        try {
            let q = am7view.viewQuery(am7model.newInstance("data.data"));
            q.entity.request.push("dataBytesStore");
            q.field("objectId", objectId);
            let qr = await page.search(q);
            if (qr?.results?.length) {
                am7model.updateListModel(qr.results);
                const obj = qr.results[0];
                this.selectedSdDetails = obj.dataBytesStore?.length ? JSON.parse(uwm.base64Decode(obj.dataBytesStore)) : null;
            } else this.selectedSdDetails = null;
        } catch (err) { this.selectedSdDetails = null; }
        this.loadingSdDetails = false; m.redraw();
    },

    async _fetchRandomSdConfig() {
        this.fetchingRandomSd = true; m.redraw();
        try {
            const cfg = await m.request({ method: 'GET', url: am7client.base() + '/olio/randomImageConfig', withCredentials: true });
            if (cfg) {
                const sd = this.config.imageGeneration.sdInline;
                for (const key of ['style','description','imageAction','model','refinerModel','sampler','steps','cfg','denoisingStrength','width','height']) {
                    if (cfg[key]) sd[key] = cfg[key];
                }
                const coreKeys = new Set(['schema','objectId','id','ownerId','groupId','groupPath','organizationId','name','description','imageAction','model','refinerModel','sampler','scheduler','refinerSampler','refinerScheduler','steps','refinerSteps','cfg','refinerCfg','seed','width','height','denoisingStrength','style','imageCount','hires','refinerMethod','refinerUpscaleMethod','refinerUpscale','refinerControlPercentage','bodyStyle','customPrompt','shared','referenceImageId','imageSetting','contentType','compressionType']);
                const extras = {};
                for (const [key, value] of Object.entries(cfg)) {
                    if (!coreKeys.has(key) && value != null && value !== '') extras[key] = value;
                }
                this.randomSdExtras = Object.keys(extras).length > 0 ? extras : null;
                if (this.randomSdExtras) Object.assign(sd, this.randomSdExtras);
            }
        } catch (err) { console.warn('SessionConfigEditor: Failed to fetch random SD config:', err); }
        this.fetchingRandomSd = false; m.redraw();
    },

    async _saveNewVoiceSource() {
        if (this.isSaving || !this.newVoiceName.trim() || !this.newVoiceContent.trim()) return;
        const page = getPage();
        this.isSaving = true; m.redraw();
        try {
            let dir = await page.findObject("auth.group", "DATA", "~/Magic8/VoiceSequences");
            if (!dir?.objectId) dir = await page.makePath("auth.group", "data", "~/Magic8/VoiceSequences");
            let saved;
            if (this.newVoiceSourceType === 'note') {
                let obj = am7model.newPrimitive("data.note");
                obj.name = this.newVoiceName.trim(); obj.groupId = dir.id; obj.groupPath = dir.path; obj.text = this.newVoiceContent.trim();
                saved = await page.createObject(obj);
            } else {
                let obj = am7model.newPrimitive("data.data");
                obj.name = this.newVoiceName.trim(); obj.contentType = "text/plain"; obj.compressionType = "none";
                obj.groupId = dir.id; obj.groupPath = dir.path; obj.dataBytesStore = uwm.base64Encode(this.newVoiceContent.trim());
                saved = await page.createObject(obj);
            }
            if (saved?.objectId) {
                this.voiceSources.push({ ...saved, sourceType: this.newVoiceSourceType });
                this.config.voice.sourceObjectId = saved.objectId;
                this.config.voice.sourceType = this.newVoiceSourceType;
                this.createNewVoiceSource = false; this.newVoiceName = ''; this.newVoiceContent = '';
            }
        } catch (err) { console.error('SessionConfigEditor: Failed to save voice source:', err); }
        this.isSaving = false; m.redraw();
    },

    async _loadSavedSession(objectId) {
        if (!objectId) return;
        const page = getPage();
        try {
            let q = am7view.viewQuery(am7model.newInstance("data.data"));
            q.entity.request.push("dataBytesStore");
            q.field("objectId", objectId);
            let qr = await page.search(q);
            if (!qr?.results?.length) return;
            am7model.updateListModel(qr.results);
            const obj = qr.results[0];
            if (!obj.dataBytesStore?.length) return;
            let decoded = uwm.base64Decode(obj.dataBytesStore);
            decoded = decoded.replace(/[\x00-\x1f]/g, (ch) => ch === '\n' ? '\\n' : ch === '\r' ? '\\r' : ch === '\t' ? '\\t' : '');
            const loaded = JSON.parse(decoded);
            const defaults = this._getDefaultConfig();
            this.config = Object.assign({}, defaults, loaded);
            for (const key of ['display','biometrics','audio','images','visuals','text','voice','recording','director']) {
                this.config[key] = Object.assign({}, defaults[key], loaded[key] || {});
            }
            if (loaded.visuals?.effects) this.config.visuals.effects = [...loaded.visuals.effects];
            this.config.imageGeneration = Object.assign({}, defaults.imageGeneration, loaded.imageGeneration || {});
            if (loaded.imageGeneration?.sdInline) this.config.imageGeneration.sdInline = Object.assign({}, defaults.imageGeneration.sdInline, loaded.imageGeneration.sdInline);
            if (!this.config.imageGeneration.sdConfigId && loaded.imageGeneration?.sdInline) {
                this.createNewSdConfig = true; this.selectedSdDetails = null;
                const defaultKeys = new Set(Object.keys(defaults.imageGeneration.sdInline));
                const extras = {};
                for (const [key, value] of Object.entries(this.config.imageGeneration.sdInline)) {
                    if (!defaultKeys.has(key) && value != null && value !== '') extras[key] = value;
                }
                this.randomSdExtras = Object.keys(extras).length > 0 ? extras : null;
            } else {
                this.createNewSdConfig = false;
                if (this.config.imageGeneration.sdConfigId) this._loadSdConfigDetails(this.config.imageGeneration.sdConfigId);
            }
            this.selectedImageGroups = [];
            if (this.config.images.baseGroups?.length > 0) {
                for (const oid of this.config.images.baseGroups) {
                    let name = oid;
                    if (page.components.dnd?.workingSet) {
                        const found = page.components.dnd.workingSet.find(w => w.objectId === oid);
                        if (found) name = found.name;
                    }
                    this.selectedImageGroups.push({ objectId: oid, name });
                }
            }
            m.redraw();
        } catch (err) { console.error('SessionConfigEditor: Failed to load saved session:', err); }
    },

    async _saveNewSdConfig() {
        if (this.isSaving) return;
        const page = getPage();
        const sdInline = this.config.imageGeneration.sdInline;
        if (!sdInline) return;
        this.isSaving = true; m.redraw();
        try {
            let dir = await page.findObject("auth.group", "DATA", "~/Magic8/SDConfigs");
            if (!dir?.objectId) dir = await page.makePath("auth.group", "data", "~/Magic8/SDConfigs");
            let obj = am7model.newPrimitive("data.data");
            obj.name = this.config.name + ' SD Config';
            obj.contentType = "application/json"; obj.compressionType = "none";
            obj.groupId = dir.id; obj.groupPath = dir.path;
            obj.dataBytesStore = uwm.base64Encode(JSON.stringify(sdInline));
            let saved = await page.createObject(obj);
            if (saved?.objectId) { this.sdConfigs.push(saved); this.config.imageGeneration.sdConfigId = saved.objectId; this.createNewSdConfig = false; }
        } catch (err) { console.error('SessionConfigEditor: Failed to save SD config:', err); }
        this.isSaving = false; m.redraw();
    },

    async _saveNewTextSource() {
        if (this.isSaving || !this.newTextName.trim() || !this.newTextContent.trim()) return;
        const page = getPage();
        this.isSaving = true; m.redraw();
        try {
            let dir = await page.findObject("auth.group", "DATA", "~/Magic8/TextSequences");
            if (!dir?.objectId) dir = await page.makePath("auth.group", "data", "~/Magic8/TextSequences");
            let saved;
            if (this.newTextSourceType === 'note') {
                let obj = am7model.newPrimitive("data.note");
                obj.name = this.newTextName.trim(); obj.groupId = dir.id; obj.groupPath = dir.path; obj.text = this.newTextContent.trim();
                saved = await page.createObject(obj);
            } else {
                let obj = am7model.newPrimitive("data.data");
                obj.name = this.newTextName.trim(); obj.contentType = "text/plain"; obj.compressionType = "none";
                obj.groupId = dir.id; obj.groupPath = dir.path; obj.dataBytesStore = uwm.base64Encode(this.newTextContent.trim());
                saved = await page.createObject(obj);
            }
            if (saved?.objectId) {
                this.textSources.push({ ...saved, sourceType: this.newTextSourceType });
                this.config.text.sourceObjectId = saved.objectId;
                this.config.text.sourceType = this.newTextSourceType;
                this.createNewTextSource = false; this.newTextName = ''; this.newTextContent = '';
            }
        } catch (err) { console.error('SessionConfigEditor: Failed to save text source:', err); }
        this.isSaving = false; m.redraw();
    },

    view(vnode) {
        const { onSave, onCancel } = vnode.attrs;
        if (this.isLoading) {
            return m('.session-config-editor.flex.items-center.justify-center.h-screen.bg-gray-900.text-white', [
                m('.text-center', [
                    m('.animate-spin.w-8.h-8.border-4.border-blue-500.border-t-transparent.rounded-full.mx-auto.mb-4'),
                    m('p', 'Loading configuration options...')
                ])
            ]);
        }

        return m('.session-config-editor.min-h-screen.bg-gray-900.text-white.p-4.overflow-auto', {
            style: { paddingBottom: 'max(24px, env(safe-area-inset-bottom))' }
        }, [
            m('.max-w-2xl.mx-auto', [
                // Header
                m('.text-center.mb-8', [m('h1.text-3xl.font-bold.mb-2', 'Magic8 Session'), m('p.text-gray-400', 'Configure your immersive experience')]),

                // Load Saved Session
                this.savedSessions.length > 0 && m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
                    m('h3.text-lg.font-medium.mb-3', 'Load Saved Session'),
                    m('select.w-full.p-3.bg-gray-700.rounded-lg.text-white', {
                        disabled: this.loadingSession,
                        onchange: async (e) => {
                            if (!e.target.value) return;
                            this.loadingSession = true; m.redraw();
                            await this._loadSavedSession(e.target.value);
                            this.loadingSession = false; m.redraw();
                        }
                    }, [
                        m('option', { value: '', selected: true }, this.loadingSession ? 'Loading...' : '-- Select a saved session --'),
                        ...this.savedSessions.map(s => m('option', { value: s.objectId }, s.name))
                    ])
                ]),

                // Session Name
                m('.config-section.mb-6', [
                    m('label.block.text-sm.font-medium.mb-2', 'Session Name'),
                    m('input.w-full.p-3.bg-gray-800.rounded-lg.text-white', { type: 'text', value: this.config.name, oninput: (e) => this.config.name = e.target.value })
                ]),

                // Biometrics
                m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
                    m('h3.text-lg.font-medium.mb-4', 'Biometric Adaptation'),
                    m('label.flex.items-center.gap-3.cursor-pointer', [
                        m('input.w-5.h-5.rounded', { type: 'checkbox', checked: this.config.biometrics.enabled, onchange: (e) => this.config.biometrics.enabled = e.target.checked }),
                        m('span', 'Enable facial analysis color adaptation')
                    ]),
                    this.config.biometrics.enabled && m('.mt-4', [
                        m('label.block.text-sm.text-gray-400.mb-1', 'Face Scan Interval (seconds)'),
                        m('.flex.items-center.gap-2', [
                            m('input.flex-1', { type: 'range', min: 2, max: 30, step: 1, value: this.config.biometrics.captureInterval, oninput: (e) => this.config.biometrics.captureInterval = parseInt(e.target.value) }),
                            m('span.text-sm.w-10.text-right', this.config.biometrics.captureInterval + 's')
                        ])
                    ])
                ]),

                // Audio
                this._renderAudioSection(),

                // Background Images
                this._renderImagesSection(),

                // Visual Effects
                this._renderVisualsSection(),

                // Image Generation
                this._renderImageGenSection(),

                // Text Sequence
                this._renderTextSection(),

                // Voice Sequence
                this._renderVoiceSection(),

                // AI Session Director
                this._renderDirectorSection(),

                // Recording
                m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
                    m('h3.text-lg.font-medium.mb-4', 'Session Recording'),
                    m('label.flex.items-center.gap-3.cursor-pointer', [
                        m('input.w-5.h-5.rounded', { type: 'checkbox', checked: this.config.recording.enabled, onchange: (e) => this.config.recording.enabled = e.target.checked }),
                        m('span', 'Enable video recording')
                    ]),
                    this.config.recording.enabled && m('.mt-4.ml-2.space-y-4', [
                        m('label.flex.items-center.gap-3.cursor-pointer', [
                            m('input.w-5.h-5.rounded', { type: 'checkbox', checked: this.config.recording.autoStart, onchange: (e) => this.config.recording.autoStart = e.target.checked }),
                            m('span', 'Auto-start when session begins')
                        ]),
                        m('.space-y-1', [
                            m('label.text-sm.text-gray-400', 'Max duration: ' + this.config.recording.maxDurationMin + ' min'),
                            m('input.w-full', { type: 'range', min: 5, max: 120, step: 5, value: this.config.recording.maxDurationMin, oninput: (e) => this.config.recording.maxDurationMin = parseInt(e.target.value) })
                        ])
                    ])
                ]),

                // Action Buttons
                m('.flex.flex-col.gap-3.mt-8.sm\\:flex-row.sm\\:gap-4', [
                    m('button.flex-1.py-3.px-6.bg-gray-700.rounded-lg.font-medium', { style: { minHeight: '44px' }, onclick: onCancel }, 'Cancel'),
                    m('button.flex-1.py-3.px-6.bg-indigo-600.rounded-lg.font-medium', { style: { minHeight: '44px' }, onclick: () => onSave?.(this.config) }, 'Start Session')
                ])
            ])
        ]);
    },

    // ── Render helpers (split out to keep view() readable) ──────────

    _renderAudioSection() {
        const PRESETS = AudioEngine.FREQUENCY_PRESETS || {};
        const BANDS = AudioEngine.BRAINWAVE_BANDS || {};
        const SOLFEGGIO = AudioEngine.SOLFEGGIO_FREQS || {};

        return m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
            m('h3.text-lg.font-medium.mb-4', 'Audio'),
            // Binaural
            m('label.flex.items-center.gap-3.cursor-pointer.mb-4', [
                m('input.w-5.h-5.rounded', { type: 'checkbox', checked: this.config.audio.binauralEnabled, onchange: (e) => this.config.audio.binauralEnabled = e.target.checked }),
                m('span', 'Enable binaural beats')
            ]),
            this.config.audio.binauralEnabled && m('.ml-8.space-y-4.mb-6', [
                // Preset
                m('.flex.items-center.gap-4', [
                    m('label.w-36', 'Preset'),
                    m('select.flex-1.bg-gray-700.rounded.p-2.text-sm', {
                        value: this.config.audio.preset || '',
                        onchange: (e) => {
                            const presetName = e.target.value;
                            this.config.audio.preset = presetName || null;
                            if (presetName && PRESETS[presetName]) {
                                const p = PRESETS[presetName];
                                this.config.audio.baseFreq = p.baseFreq;
                                this.config.audio.startBand = p.startBand;
                                this.config.audio.troughBand = p.troughBand;
                                this.config.audio.endBand = p.endBand;
                                this.config.audio.sweepStart = BANDS[p.startBand]?.mid || 10;
                                this.config.audio.sweepTrough = BANDS[p.troughBand]?.mid || 5.5;
                                this.config.audio.sweepEnd = BANDS[p.endBand]?.mid || 10;
                            }
                        }
                    }, [m('option', { value: '' }, '(Custom)'), ...Object.entries(PRESETS).map(([key, p]) => m('option', { value: key }, p.label))])
                ]),
                this.config.audio.preset && PRESETS[this.config.audio.preset] && m('.text-sm.text-gray-400.ml-40', PRESETS[this.config.audio.preset].desc),
                // Carrier
                m('.flex.items-center.gap-4', [
                    m('label.w-36', 'Carrier Frequency'),
                    m('select.flex-1.bg-gray-700.rounded.p-2.text-sm', {
                        value: this.config.audio.baseFreq || 200,
                        onchange: (e) => { this.config.audio.baseFreq = parseInt(e.target.value); this.config.audio.preset = null; }
                    }, [...Object.entries(SOLFEGGIO).map(([freq, info]) => m('option', { value: freq }, info.label)), m('option', { value: 200 }, '200 Hz (Standard)'), m('option', { value: 440 }, '440 Hz (Concert A)')])
                ]),
                SOLFEGGIO[this.config.audio.baseFreq] && m('.text-sm.text-gray-400.ml-40', SOLFEGGIO[this.config.audio.baseFreq].desc),
                // Bands
                m('.text-sm.text-gray-400.mb-2', 'Brainwave Band Transitions'),
                m('.grid.gap-2', { style: 'grid-template-columns: 1fr 1fr 1fr' }, ['Start Band', 'Trough Band', 'End Band'].map((label, idx) => {
                    const field = ['startBand', 'troughBand', 'endBand'][idx];
                    const sweepField = ['sweepStart', 'sweepTrough', 'sweepEnd'][idx];
                    return m('.flex.flex-col.gap-1', [
                        m('label.text-xs.text-gray-500', label),
                        m('select.bg-gray-700.rounded.p-2.text-sm', {
                            value: this.config.audio[field] || ['alpha', 'theta', 'alpha'][idx],
                            onchange: (e) => { this.config.audio[field] = e.target.value; this.config.audio[sweepField] = BANDS[e.target.value]?.mid || 10; this.config.audio.preset = null; }
                        }, Object.entries(BANDS).map(([key, b]) => m('option', { value: key }, b.label)))
                    ]);
                })),
                // Sweep duration + fades
                this._rangeField('Sweep Duration', 'audio', 'sweepDurationMin', 1, 20, 1, v => v + ' min'),
                this._rangeField('Fade In', 'audio', 'fadeInSec', 0.5, 10, 0.5, v => v + 's', true),
                this._rangeField('Fade Out', 'audio', 'fadeOutSec', 0.5, 10, 0.5, v => v + 's', true)
            ]),
            // Q-Sound
            this.config.audio.binauralEnabled && [
                m('label.flex.items-center.gap-3.cursor-pointer.mb-4', [
                    m('input.w-5.h-5.rounded', { type: 'checkbox', checked: this.config.audio.panEnabled, onchange: (e) => this.config.audio.panEnabled = e.target.checked }),
                    m('span', 'Q-Sound spatial panning')
                ]),
                this.config.audio.panEnabled && m('.ml-8.space-y-4.mb-6', [
                    this._rangeField('Pan Speed', 'audio', 'panSpeed', 0.02, 0.5, 0.02, v => v.toFixed(2), true),
                    this._rangeField('Pan Depth', 'audio', 'panDepth', 0.1, 1.0, 0.1, v => v.toFixed(1), true)
                ])
            ],
            // Isochronic
            m('label.flex.items-center.gap-3.cursor-pointer.mb-4', [
                m('input.w-5.h-5.rounded', { type: 'checkbox', checked: this.config.audio.isochronicEnabled, onchange: (e) => this.config.audio.isochronicEnabled = e.target.checked }),
                m('span', 'Isochronic tones')
            ]),
            this.config.audio.isochronicEnabled && m('.ml-8.space-y-4.mb-6', [
                this._rangeField('Tone Frequency', 'audio', 'isochronicFreq', 100, 500, 10, v => v + ' Hz'),
                this._rangeField('Pulse Rate', 'audio', 'isochronicRate', 2, 15, 0.5, v => v + ' Hz', true),
                this._rangeField('Volume', 'audio', 'isochronicVolume', 0.05, 0.5, 0.05, v => v.toFixed(2), true)
            ]),
            // Visualizer
            m('label.flex.items-center.gap-3.cursor-pointer.mb-4', [
                m('input.w-5.h-5.rounded', { type: 'checkbox', checked: this.config.audio.visualizerEnabled, onchange: (e) => this.config.audio.visualizerEnabled = e.target.checked }),
                m('span', 'Audio visualizer overlay')
            ]),
            this.config.audio.visualizerEnabled && m('.ml-8.space-y-4', [
                this._rangeField('Opacity', 'audio', 'visualizerOpacity', 0.1, 0.6, 0.05, v => Math.round(v * 100) + '%', true),
                m('.flex.items-center.gap-4', [
                    m('label.w-36', 'Gradient'),
                    m('select.flex-1.p-2.bg-gray-700.rounded.text-sm', { value: this.config.audio.visualizerGradient, onchange: (e) => this.config.audio.visualizerGradient = e.target.value },
                        ['prism', 'rainbow', 'classic'].map(v => m('option', { value: v }, v.charAt(0).toUpperCase() + v.slice(1))))
                ])
            ])
        ]);
    },

    _renderImagesSection() {
        return m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
            m('h3.text-lg.font-medium.mb-4', 'Background Images'),
            m('p.text-sm.text-gray-400.mb-3', 'Groups are imported from the blender tray automatically'),
            this.selectedImageGroups.length > 0 ? [
                m('.space-y-1.mb-4', this.selectedImageGroups.map(grp =>
                    m('.flex.items-center.justify-between.p-2.bg-gray-700.rounded', [
                        m('.flex.items-center.gap-2', [m('span.material-symbols-outlined.text-base.text-gray-400', 'folder'), m('span.text-sm', grp.name)]),
                        m('button.text-gray-400.p-1', { onclick: () => { this.selectedImageGroups = this.selectedImageGroups.filter(g => g.objectId !== grp.objectId); this.config.images.baseGroups = this.config.images.baseGroups.filter(oid => oid !== grp.objectId); } }, m('span.material-symbols-outlined.text-sm', 'close'))
                    ])
                )),
                m('.text-xs.text-gray-500', this.selectedImageGroups.length + ' group(s) selected')
            ] : m('.p-3.bg-gray-700.rounded.text-gray-500.text-sm', 'No groups selected'),
            m('.space-y-4.mt-4', [
                this._rangeField('Cycle Interval', 'images', 'cycleInterval', 2000, 30000, 1000, v => (v / 1000) + 's'),
                this._rangeField('Crossfade', 'images', 'crossfadeDuration', 200, 3000, 200, v => (v / 1000).toFixed(1) + 's'),
                m('label.flex.items-center.gap-3.cursor-pointer', [
                    m('input.w-4.h-4.rounded', { type: 'checkbox', checked: this.config.images.includeGenerated, onchange: (e) => this.config.images.includeGenerated = e.target.checked }),
                    m('span.text-sm', 'Include AI-generated images in rotation')
                ])
            ])
        ]);
    },

    _renderVisualsSection() {
        return m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
            m('h3.text-lg.font-medium.mb-4', 'Visual Effects'),
            m('.grid.gap-2.mb-4', { class: 'grid-cols-2 sm:grid-cols-4' },
                ['particles', 'spiral', 'mandala', 'tunnel', 'hypnoDisc'].map(effect =>
                    m('label.flex.items-center.gap-2.cursor-pointer.p-2.bg-gray-700.rounded', [
                        m('input.w-4.h-4.rounded', {
                            type: 'checkbox', checked: this.config.visuals.effects.includes(effect),
                            onchange: (e) => {
                                if (e.target.checked) { if (!this.config.visuals.effects.includes(effect)) this.config.visuals.effects.push(effect); }
                                else this.config.visuals.effects = this.config.visuals.effects.filter(ef => ef !== effect);
                            }
                        }),
                        m('span.text-sm.capitalize', effect)
                    ])
                )
            ),
            m('.flex.items-center.gap-4.mb-4', [
                m('label.w-24', 'Mode'),
                m('select.flex-1.p-2.bg-gray-700.rounded.text-sm', { value: this.config.visuals.mode, onchange: (e) => this.config.visuals.mode = e.target.value }, [
                    m('option', { value: 'single' }, 'Single'), m('option', { value: 'cycle' }, 'Cycle'), m('option', { value: 'combined' }, 'Combined')
                ])
            ]),
            this.config.visuals.effects.includes('particles') && this._rangeField('Particle Count', 'visuals', 'particleCount', 50, 200, 10, v => v),
            this.config.visuals.effects.includes('spiral') && this._rangeField('Spiral Speed', 'visuals', 'spiralSpeed', 0.005, 0.03, 0.001, v => v.toFixed(3), true),
            this.config.visuals.effects.includes('mandala') && this._rangeField('Mandala Layers', 'visuals', 'mandalaLayers', 2, 6, 1, v => v),
            this.config.visuals.effects.includes('tunnel') && this._rangeField('Tunnel Rings', 'visuals', 'tunnelRings', 8, 20, 1, v => v)
        ]);
    },

    _renderImageGenSection() {
        return m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
            m('h3.text-lg.font-medium.mb-4', 'AI Image Generation'),
            m('label.flex.items-center.gap-3.cursor-pointer.mb-4', [
                m('input.w-5.h-5.rounded', { type: 'checkbox', checked: this.config.imageGeneration.enabled, onchange: (e) => this.config.imageGeneration.enabled = e.target.checked }),
                m('span', 'Enable SD image generation from camera')
            ]),
            this.config.imageGeneration.enabled && m('.ml-8.space-y-4', [
                // Toggle existing vs new
                m('.flex.gap-2.mb-4', [
                    m('button', { class: 'px-3 py-1 rounded text-sm ' + (!this.createNewSdConfig ? 'bg-indigo-600 text-white' : 'bg-gray-700 text-gray-300'), onclick: () => { this.createNewSdConfig = false; } }, 'Select Existing'),
                    m('button', { class: 'px-3 py-1 rounded text-sm ' + (this.createNewSdConfig ? 'bg-indigo-600 text-white' : 'bg-gray-700 text-gray-300'), onclick: () => { this.createNewSdConfig = true; this.config.imageGeneration.sdConfigId = null; } }, 'Create New')
                ]),
                !this.createNewSdConfig && [
                    this.sdConfigs.length > 0 ? m('select.w-full.p-2.bg-gray-700.rounded', {
                        value: this.config.imageGeneration.sdConfigId || '',
                        onchange: (e) => { this.config.imageGeneration.sdConfigId = e.target.value || null; this._loadSdConfigDetails(e.target.value || null); }
                    }, [m('option', { value: '' }, '-- Default Config --'), ...this.sdConfigs.map(c => m('option', { value: c.objectId }, c.name))]) : m('.p-3.bg-gray-700.rounded.text-gray-400.text-sm', 'No saved configs found')
                ],
                this.createNewSdConfig && [
                    m('.grid.gap-4.mb-3', { class: 'grid-cols-1 sm:grid-cols-2' }, [
                        m('div', [m('label.block.text-sm.text-gray-400.mb-1', 'Model'), m('select.w-full.p-2.bg-gray-700.rounded.text-sm', { value: this.config.imageGeneration.sdInline.model, onchange: (e) => this.config.imageGeneration.sdInline.model = e.target.value }, [m('option', { value: '' }, '-- Select --'), ...this.sdModelList.map(v => m('option', { value: v }, v))])]),
                        m('div', [m('label.block.text-sm.text-gray-400.mb-1', 'Refiner'), m('select.w-full.p-2.bg-gray-700.rounded.text-sm', { value: this.config.imageGeneration.sdInline.refinerModel, onchange: (e) => this.config.imageGeneration.sdInline.refinerModel = e.target.value }, [m('option', { value: '' }, '-- None --'), ...this.sdModelList.map(v => m('option', { value: v }, v))])])
                    ]),
                    m('label.block.text-sm.text-gray-400.mb-1', 'Description'),
                    m('textarea.w-full.p-2.bg-gray-700.rounded.text-sm', { rows: 2, value: this.config.imageGeneration.sdInline.description, oninput: (e) => this.config.imageGeneration.sdInline.description = e.target.value }),
                    m('label.block.text-sm.text-gray-400.mb-1.mt-3', 'Image Action'),
                    m('input.w-full.p-2.bg-gray-700.rounded.text-sm', { type: 'text', value: this.config.imageGeneration.sdInline.imageAction, oninput: (e) => this.config.imageGeneration.sdInline.imageAction = e.target.value }),
                    m('.flex.gap-2.mt-3', [
                        m('button', { class: 'px-4 py-2 rounded text-sm font-medium ' + (this.isSaving ? 'bg-gray-600' : 'bg-green-600 text-white'), disabled: this.isSaving, onclick: () => this._saveNewSdConfig() }, this.isSaving ? 'Saving...' : 'Save Config')
                    ])
                ],
                this._rangeField('Capture Interval', 'imageGeneration', 'captureInterval', 60000, 600000, 30000, v => (v / 60000).toFixed(1) + ' min')
            ])
        ]);
    },

    _renderTextSection() {
        return m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
            m('h3.text-lg.font-medium.mb-4', 'Hypnotic Text'),
            m('label.flex.items-center.gap-3.cursor-pointer.mb-4', [
                m('input.w-5.h-5.rounded', { type: 'checkbox', checked: this.config.text.enabled, onchange: (e) => this.config.text.enabled = e.target.checked }),
                m('span', 'Enable text sequence')
            ]),
            this.config.text.enabled && m('.ml-8.space-y-4', [
                m('.flex.gap-2.mb-4', [
                    m('button', { class: 'px-3 py-1 rounded text-sm ' + (!this.createNewTextSource ? 'bg-indigo-600 text-white' : 'bg-gray-700 text-gray-300'), onclick: () => { this.createNewTextSource = false; } }, 'Select Existing'),
                    m('button', { class: 'px-3 py-1 rounded text-sm ' + (this.createNewTextSource ? 'bg-indigo-600 text-white' : 'bg-gray-700 text-gray-300'), onclick: () => { this.createNewTextSource = true; this.config.text.sourceObjectId = null; } }, 'Create New')
                ]),
                !this.createNewTextSource && this.textSources.length > 0 && m('select.w-full.p-2.bg-gray-700.rounded', {
                    value: this.config.text.sourceObjectId || '',
                    onchange: (e) => { const s = this.textSources.find(t => t.objectId === e.target.value); this.config.text.sourceObjectId = e.target.value || null; this.config.text.sourceType = s?.sourceType || 'note'; }
                }, [m('option', { value: '' }, '-- Select --'), ...this.textSources.map(t => m('option', { value: t.objectId }, `${t.name} (${t.sourceType})`))]),
                this.createNewTextSource && [
                    m('input.w-full.p-2.bg-gray-700.rounded.text-sm', { type: 'text', placeholder: 'Name', value: this.newTextName, oninput: (e) => this.newTextName = e.target.value }),
                    m('textarea.w-full.p-2.bg-gray-700.rounded.text-sm.font-mono.mt-3', { rows: 6, placeholder: 'One phrase per line', value: this.newTextContent, oninput: (e) => this.newTextContent = e.target.value }),
                    m('button.mt-3.px-4.py-2.rounded.text-sm.font-medium', { class: this.isSaving ? 'bg-gray-600' : 'bg-green-600 text-white', disabled: this.isSaving || !this.newTextName.trim() || !this.newTextContent.trim(), onclick: () => this._saveNewTextSource() }, this.isSaving ? 'Saving...' : 'Save & Select')
                ],
                m('label.flex.items-center.gap-3.cursor-pointer.mt-4', [
                    m('input.w-5.h-5.rounded', { type: 'checkbox', checked: this.config.text.loop, onchange: (e) => this.config.text.loop = e.target.checked }),
                    m('span', 'Loop text sequence')
                ]),
                this._rangeField('Display Duration', 'text', 'displayDuration', 2000, 15000, 1000, v => (v / 1000) + 's')
            ])
        ]);
    },

    _renderVoiceSection() {
        return m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
            m('h3.text-lg.font-medium.mb-4', 'Voice Sequence'),
            m('label.flex.items-center.gap-3.cursor-pointer.mb-4', [
                m('input.w-5.h-5.rounded', { type: 'checkbox', checked: this.config.voice.enabled, onchange: (e) => this.config.voice.enabled = e.target.checked }),
                m('span', 'Enable voice sequence playback')
            ]),
            this.config.voice.enabled && m('.ml-8.space-y-4', [
                m('.flex.gap-2.mb-4', [
                    m('button', { class: 'px-3 py-1 rounded text-sm ' + (!this.createNewVoiceSource ? 'bg-indigo-600 text-white' : 'bg-gray-700 text-gray-300'), onclick: () => { this.createNewVoiceSource = false; } }, 'Select Existing'),
                    m('button', { class: 'px-3 py-1 rounded text-sm ' + (this.createNewVoiceSource ? 'bg-indigo-600 text-white' : 'bg-gray-700 text-gray-300'), onclick: () => { this.createNewVoiceSource = true; this.config.voice.sourceObjectId = null; } }, 'Create New')
                ]),
                !this.createNewVoiceSource && this.voiceSources.length > 0 && m('select.w-full.p-2.bg-gray-700.rounded', {
                    value: this.config.voice.sourceObjectId || '',
                    onchange: (e) => { const s = this.voiceSources.find(t => t.objectId === e.target.value); this.config.voice.sourceObjectId = e.target.value || null; this.config.voice.sourceType = s?.sourceType || 'note'; }
                }, [m('option', { value: '' }, '-- Select --'), ...this.voiceSources.map(t => m('option', { value: t.objectId }, `${t.name} (${t.sourceType})`))]),
                this.createNewVoiceSource && [
                    m('input.w-full.p-2.bg-gray-700.rounded.text-sm', { type: 'text', placeholder: 'Name', value: this.newVoiceName, oninput: (e) => this.newVoiceName = e.target.value }),
                    m('textarea.w-full.p-2.bg-gray-700.rounded.text-sm.font-mono.mt-3', { rows: 6, placeholder: 'One phrase per line', value: this.newVoiceContent, oninput: (e) => this.newVoiceContent = e.target.value }),
                    m('button.mt-3.px-4.py-2.rounded.text-sm.font-medium', { class: this.isSaving ? 'bg-gray-600' : 'bg-green-600 text-white', disabled: this.isSaving || !this.newVoiceName.trim() || !this.newVoiceContent.trim(), onclick: () => this._saveNewVoiceSource() }, this.isSaving ? 'Saving...' : 'Save & Select')
                ],
                // Voice Profile
                m('label.block.mb-1.mt-4', 'Voice Profile'),
                this.voiceProfiles.length > 0 ? m('select.w-full.p-2.bg-gray-700.rounded', {
                    value: this.config.voice.voiceProfileId || '',
                    onchange: (e) => this.config.voice.voiceProfileId = e.target.value || null
                }, [m('option', { value: '' }, '-- Default Voice --'), ...this.voiceProfiles.map(vp => m('option', { value: vp.objectId }, vp.name || 'piper - default'))]) : m('.p-2.bg-gray-700.rounded.text-gray-400.text-sm', 'No voice profiles found'),
                m('label.flex.items-center.gap-3.cursor-pointer.mt-4', [
                    m('input.w-5.h-5.rounded', { type: 'checkbox', checked: this.config.voice.loop, onchange: (e) => this.config.voice.loop = e.target.checked }),
                    m('span', 'Loop voice sequence')
                ])
            ])
        ]);
    },

    _renderDirectorSection() {
        return m('.config-section.mb-6.p-4.bg-gray-800.rounded-lg', [
            m('h3.text-lg.font-medium.mb-4', 'AI Session Director'),
            m('label.flex.items-center.gap-3.cursor-pointer.mb-3', [
                m('input.w-5.h-5.rounded', { type: 'checkbox', checked: this.config.director.enabled, onchange: (e) => this.config.director.enabled = e.target.checked }),
                m('span', 'Enable AI Session Director')
            ]),
            m('p.text-xs.text-gray-400.mb-3', 'LLM-powered orchestration that adjusts audio, visuals, and labels based on session state.'),
            this.config.director.enabled && m('.space-y-4.mt-3.pl-2.border-l-2.border-gray-700', [
                m('.config-field', [
                    m('label.block.text-sm.font-medium.mb-1', 'Session Command / Intent'),
                    m('textarea.w-full.bg-gray-900.rounded.px-3.py-2.text-sm', { rows: 3, placeholder: 'Guide me through a deep relaxation journey...', value: this.config.director.command, oninput: (e) => this.config.director.command = e.target.value })
                ]),
                m('.config-field', [
                    m('label.block.text-sm.font-medium.mb-1', 'Check-in Interval: ' + Math.round(this.config.director.intervalMs / 1000) + 's'),
                    m('input.w-full', { type: 'range', min: 30000, max: 300000, step: 15000, value: this.config.director.intervalMs, oninput: (e) => this.config.director.intervalMs = parseInt(e.target.value) })
                ]),
                // Open Chat Mode
                m('label.flex.items-center.gap-3.cursor-pointer', [
                    m('input.w-4.h-4.rounded', { type: 'checkbox', checked: !!this.config.director.openChatMode, onchange: (e) => { this.config.director.openChatMode = e.target.checked; if (!e.target.checked) this.config.director.chatConfigObjectId = null; } }),
                    m('span.text-sm', 'Open Chat Mode (free conversation with TTS overlay)')
                ]),
                this.config.director.openChatMode && m('.config-field.mt-2.ml-6', [
                    m('label.block.text-xs.text-gray-400.mb-1', 'Chat Config (from ~/Chat)'),
                    m('select.w-full.bg-gray-900.rounded.px-3.py-2.text-sm', {
                        value: this.config.director.chatConfigObjectId || '',
                        onchange: (e) => { this.config.director.chatConfigObjectId = e.target.value || null; }
                    }, [
                        m('option', { value: '' }, '— Select a Chat Config —'),
                        ...this.chatConfigs.map(c => m('option', { value: c.objectId, selected: c.objectId === this.config.director.chatConfigObjectId }, c.name))
                    ])
                ]),
                // Interactive + Test
                m('label.flex.items-center.gap-3.cursor-pointer', [
                    m('input.w-4.h-4.rounded', { type: 'checkbox', checked: this.config.director.interactive, onchange: (e) => this.config.director.interactive = e.target.checked }),
                    m('span.text-sm', 'Interactive mode')
                ]),
                m('label.flex.items-center.gap-3.cursor-pointer', [
                    m('input.w-4.h-4.rounded', { type: 'checkbox', checked: this.config.director.testMode, onchange: (e) => this.config.director.testMode = e.target.checked }),
                    m('span.text-sm', 'Test Mode')
                ]),
                m('.config-field.mt-2', [
                    m('button', {
                        class: 'px-4 py-2 rounded text-sm font-medium ' + (this.directorTestRunning ? 'bg-gray-600 text-gray-400' : 'bg-yellow-600 text-white'),
                        disabled: this.directorTestRunning,
                        onclick: async () => {
                            this.directorTestRunning = true; this.directorTestResults = null; m.redraw();
                            try {
                                const director = new SessionDirector();
                                this.directorTestResults = await director.runDiagnostics(this.config.director.command || 'Diagnostic test');
                                director.dispose();
                            } catch (err) { this.directorTestResults = [{ name: 'Test runner', pass: false, detail: err.message }]; }
                            this.directorTestRunning = false; m.redraw();
                        }
                    }, this.directorTestRunning ? 'Testing...' : 'Test Director')
                ]),
                this.directorTestResults && m('.mt-3.p-3.bg-gray-900.rounded.text-sm.font-mono.space-y-1',
                    this.directorTestResults.map(r => m('.flex.items-start.gap-2', [
                        m('span', { style: { color: r.pass ? '#4ade80' : '#f87171', minWidth: '20px' } }, r.pass ? 'OK' : 'XX'),
                        m('span.text-gray-300', r.name),
                        r.detail && m('span.text-gray-500', ' - ' + r.detail)
                    ]))
                )
            ])
        ]);
    },

    /** Helper: render a labeled range slider for config[section][field] */
    _rangeField(label, section, field, min, max, step, fmt, isFloat) {
        const val = this.config[section][field];
        return m('.flex.items-center.gap-4', [
            m('label.w-36', label),
            m('input.flex-1', {
                type: 'range', min, max, step, value: val,
                oninput: (e) => { this.config[section][field] = isFloat ? parseFloat(e.target.value) : parseInt(e.target.value); }
            }),
            m('span.w-16.text-right', typeof fmt === 'function' ? fmt(val) : val)
        ]);
    }
};

export { SessionConfigEditor };
export default SessionConfigEditor;
