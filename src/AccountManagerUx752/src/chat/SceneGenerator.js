/**
 * SceneGenerator — SD scene generation config dialog + generation (ESM)
 * Uses shared SdConfigPanel (same form as reimage / picture book).
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { applicationPath } from '../core/config.js';
import { SdConfigPanel } from '../components/SdConfigPanel.js';
import { Dialog } from '../components/dialogCore.js';
import { am7sd } from '../components/sdConfig.js';
import { am7model } from '../core/model.js';

// ── SD Config State ─────────────────────────────────────────────────
//
// The config is a REAL olio.sd.config built from the server template, not a hand-rolled object.
// A saved config is overlaid onto a fresh template each time.
//
// Persistence is SERVER-SIDE (was localStorage). The config is saved per-chat, keyed to the durable
// olio.llm.chatConfig objectId so every session of the same chat reuses it, plus an optional
// chat-global shared config. Both persist as olio.sd.config records via am7sd.saveConfig (with the
// legacy data.data blob read-fallback) — the same mechanism charPerson reimage uses. The chat-global
// name is deliberately distinct from the character-image shared 'sharedSD.json' so saving a chat's
// shared config never overwrites the character one.

const SHARED_CHAT_NAME = "sharedChatSD.json";

/// Per-chat config record name — keyed to the durable chatConfig objectId (not the ephemeral session),
/// so the saved config is reused across every session/conversation of the same chat. null when no
/// chatConfig objectId is available (e.g. a session with no bound chatConfig); the shared config is
/// used alone in that case.
function perChatConfigName() {
    return _chatConfigObjectId ? ("sdcfg-chat-" + _chatConfigObjectId) : null;
}

let sdConfig = null;        // olio.sd.config entity (null until ensureSdConfig resolves)
let sdConfigInst = null;    // am7model instance wrapping sdConfig (forms.sdConfig)
let sdModels = [];
let sdLoras = [];
let _generating = false;
let _sessionObjectId = null;
let _chatConfigObjectId = null;  // durable olio.llm.chatConfig objectId — the per-chat config key
let _onGenerated = null;
let _saveShared = false;    // "Save as Shared Config" checkbox state

// Chat-specific pins applied on top of the real olio.sd.config template. Mirrors
// pictureBook.js's pinPictureBookDefaults so both features render through the same pipeline.
function pinChatSceneDefaults(entity) {
    // FLUX.2 multi-reference composite, same pipeline the picture book uses. Server-side
    // compositeMode supersedes the legacy useKontext boolean; chat's historical default when
    // neither is set was Kontext, the stitched-panel-strip path that rendered the reference sheet
    // into the scene as a propped-up board (AccountManagerObjects7/media/flux/bad.composite.png).
    entity.compositeMode = "flux2";
    entity.hires = false;
    return entity;
}

// HISTORY: this file used to define the config as a hand-rolled flat object rather than an
// olio.sd.config. That was the root cause of "Invalid model value for param Model -
// 'OfficialStableDiffusion/sd_xl_base_1.0'" on a node lacking that checkpoint: model:"" was sent
// verbatim, the server's resolveModel() saw a blank and fell through to the olio.sd.config schema
// default, which is necessarily node-specific. Being hand-rolled it also missed every schema field the
// picture book relies on (flux2Cfg / flux2Steps / flux2ReferenceSize / flux2IncludeLandscapeRef /
// seed / ...), so none of that reached chat even though both features hit the same server code.
// The saved config is now overlaid onto a real server template instead - see ensureSdConfig.
// Keys that must never be carried over from a saved config onto a fresh template: identity fields,
// and the model/refinerModel pair. A saved model is what caused the reported failure - it was ""
// (or a checkpoint name valid only on the node it was saved from), and overlaying either onto a
// template that already carries a VALID model for THIS node reintroduces the bug. So the per-chat /
// shared records are ALSO saved without model/refinerModel (stripNeverRestore before saveConfig).
const SD_CONFIG_IDENTITY = ['id', 'objectId', 'urn', 'ownerId', 'groupId', 'organizationId',
    'groupPath', 'organizationPath', 'narration'];
const SD_CONFIG_NEVER_RESTORE = ['model', 'refinerModel'];

/// Overlay a saved config's tweaks onto a fresh template entity. Skips identity, the model pair, and
/// any null/blank value — a blank must not overwrite a good template value, which is precisely how ""
/// reached the server. Unknown legacy keys are dropped by virtue of only copying what the model has.
function overlaySaved(entity, stored) {
    if (!stored) return;
    for (let k in stored) {
        if (SD_CONFIG_IDENTITY.includes(k) || SD_CONFIG_NEVER_RESTORE.includes(k)) continue;
        if (k === am7model.jsonModelKey) continue;
        let v = stored[k];
        if (v === undefined || v === null || v === '' || v === 0) continue;
        if (!(k in entity)) continue;
        entity[k] = v;
    }
}

/// Drop the built config so the next ensureSdConfig() rebuilds from the template for a different chat.
/// The config is a module singleton keyed to _chatConfigObjectId; without this, switching chats would
/// return the previous chat's memoized instance and load/save the wrong record.
function resetConfig() {
    sdConfig = null;
    sdConfigInst = null;
    _sdConfigPromise = null;
}

/// Build the chat scene config as a REAL olio.sd.config, the same way the picture-book wizard does
/// (am7sd.buildEntity -> /olio/randomImageConfig -> am7model.prepareInstance). Saved user tweaks are
/// overlaid ON TOP of that template rather than being the config, so the template's node-valid model
/// and the full schema field set always survive.
let _sdConfigPromise = null;
async function ensureSdConfig() {
    if (sdConfigInst) return sdConfigInst;
    if (_sdConfigPromise) return _sdConfigPromise;
    _sdConfigPromise = (async function () {
        try {
            let entity = await am7sd.buildEntity();
            if (!entity) entity = am7model.newPrimitive('olio.sd.config');
            if (!entity[am7model.jsonModelKey]) entity[am7model.jsonModelKey] = 'olio.sd.config';
            SD_CONFIG_IDENTITY.forEach(function (k) { delete entity[k]; });

            /// Overlay the saved config server-side: prefer THIS chat's own saved config (keyed to the
            /// chatConfig objectId), else fall back to the chat-global shared config. stripNeverRestore
            /// drops the persisted model/refinerModel so the template's node-valid model always survives.
            let stored = null;
            try {
                let name = perChatConfigName();
                if (name) stored = await am7sd.loadConfig(name);
                if (!stored) stored = await am7sd.loadConfig(SHARED_CHAT_NAME);
            } catch (e) { stored = null; }
            if (stored) {
                am7sd.stripNeverRestore(stored);
                overlaySaved(entity, stored);
            }

            pinChatSceneDefaults(entity);
            am7sd.fillStyleDefaults(entity);
            sdConfig = entity;
            sdConfigInst = am7model.prepareInstance(entity, am7model.forms.sdConfig);
        } catch (e) {
            console.warn('[SceneGenerator] Failed to build SD config:', e);
        }
        m.redraw();
        return sdConfigInst;
    })();
    return _sdConfigPromise;
}

/// Persist the current config server-side (was localStorage). Always saves the per-chat record when a
/// chatConfig objectId is available; also saves the chat-global shared record when the "Save as Shared"
/// checkbox is set. model/refinerModel are stripped first so a config saved on one node cannot poison
/// another. Called on Generate, mirroring the reimage workflow (save-then-generate), not on every edit.
async function persistConfig() {
    if (!sdConfig) return false;
    let out = am7sd.stripNeverRestore(Object.assign({}, sdConfig));
    delete out[am7model.jsonModelKey];
    let name = perChatConfigName();
    let ok = false;
    try {
        if (name) ok = await am7sd.saveConfig(name, out);
        if (_saveShared) await am7sd.saveConfig(SHARED_CHAT_NAME, out);
    } catch (e) {
        console.warn("[SceneGenerator] Failed to persist SD config:", e);
        return false;
    }
    return ok;
}

/// Load the chat-global shared config and overlay it onto the current in-panel config. Explicit user
/// action from the "Load Shared" button — mirrors reimage's "Load Shared". Mutates sdConfig in place
/// (the panel renders it directly), stripping the node-specific model pair first.
async function loadShared() {
    let stored = null;
    try { stored = await am7sd.loadConfig(SHARED_CHAT_NAME); }
    catch (e) { stored = null; }
    if (!stored) {
        page.toast("warn", "No shared chat SD config found");
        return;
    }
    if (!sdConfig) return;
    am7sd.stripNeverRestore(stored);
    overlaySaved(sdConfig, stored);
    am7sd.fillStyleDefaults(sdConfig);
    page.toast("success", "Loaded shared chat SD config");
    m.redraw();
}

async function loadModels() {
    if (sdModels.length > 0) return;
    try {
        let result = await m.request({
            method: 'GET',
            url: applicationPath + "/rest/olio/sdModels",
            withCredentials: true
        });
        sdModels = Array.isArray(result) ? result : [];
    } catch(e) {
        sdModels = [];
    }
    m.redraw();
}

async function loadLoras() {
    if (sdLoras.length > 0) return;
    try {
        let result = await m.request({
            method: 'GET',
            url: applicationPath + "/rest/olio/sdLoras",
            withCredentials: true
        });
        sdLoras = Array.isArray(result) ? result : [];
    } catch(e) {
        sdLoras = [];
    }
    m.redraw();
}

// ── Generation ──────────────────────────────────────────────────────

async function doGenerate() {
    if (!_sessionObjectId || _generating) return;
    /// The config is built asynchronously from the server template, so a fast click could otherwise
    /// POST a null body - or, worse, whatever partial object happened to exist. Await it here rather
    /// than trusting that show() finished.
    await ensureSdConfig();
    if (!sdConfig) {
        page.clearToast();
        page.toast("error", "Scene generation unavailable: could not load the image configuration");
        return;
    }
    _generating = true;

    /// Persist the config BEFORE closing so this chat's scene settings survive for reuse — mirrors the
    /// reimage workflow (save-then-generate). Failure here must not block generation.
    await persistConfig();

    /// Close the dialog immediately on click so the user gets clear feedback
    /// that generation started — generation takes 20-90s and silently leaving
    /// the dialog open looks broken. Status updates flow via toasts instead,
    /// mirroring the reimage workflow.
    Dialog.close();
    page.clearToast();
    page.toast("info", "Generating scene...", -1);
    m.redraw();

    try {
        let result = await m.request({
            method: 'POST',
            url: applicationPath + "/rest/chat/" + encodeURIComponent(_sessionObjectId) + "/generateScene",
            withCredentials: true,
            body: sdConfig
        });

        page.clearToast();
        if (_onGenerated && result) {
            _onGenerated(result);
        }
        if (result) {
            page.toast("success", "Scene generated");

            /// Open the gallery popup to the new image — mirrors the reimage
            /// UX. imageGallery uses charInst.entity.profile.portrait.groupId
            /// to pick the directory; synthesize a minimal shape so the gallery
            /// loads the scenes directory (where the backend stores them under
            /// ~/Gallery/Scenes/<label>) and the new image is prefetched at the top.
            if (page.imageGallery) {
                let sceneInst = { entity: { profile: { portrait: result } } };
                page.imageGallery([result], sceneInst);
            }
        } else {
            page.toast("error", "Scene generation failed: no result");
        }
    } catch(e) {
        console.error("[SceneGenerator] generateScene failed:", e);
        page.clearToast();
        page.toast("error", "Scene generation failed: " + (e.message || e));
    }

    _generating = false;
    m.redraw();
}

// ── Public API ──────────────────────────────────────────────────────

/// Resolve the scene gallery groupPath for a chat. The backend stores
/// scenes under "~/Gallery/Scenes/<sysFirstName> and <usrFirstName>"
/// (see ChatService.generateScene line 1393 — Chat.ScenePromptResult.label
/// is set from systemChar.firstName + " and " + userChar.firstName).
/// Returns null if either character is missing.
function sceneGalleryPathFor(chatCfg) {
    if (!chatCfg || !chatCfg.system || !chatCfg.user) return null;
    let sysName = chatCfg.system.firstName || (chatCfg.system.name || "").split(" ")[0];
    let usrName = chatCfg.user.firstName || (chatCfg.user.name || "").split(" ")[0];
    if (!sysName || !usrName) return null;
    return "~/Gallery/Scenes/" + sysName + " and " + usrName;
}

/// Look up the data.group for a path without creating it. Returns the
/// group record or null. Used to detect "no scenes yet" so the gallery
/// flow can auto-open the generator.
async function findGroupByPath(path) {
    try {
        let g = await page.findObject("auth.group", "DATA", path);
        return g || null;
    } catch (e) {
        return null;
    }
}

const SceneGenerator = {
    show: function(sessionObjectId, onGenerated, chatConfigObjectId) {
        _sessionObjectId = sessionObjectId;
        _onGenerated = onGenerated || null;
        /// The config is a module singleton keyed to the chat. When the chat changes, drop the
        /// previously-built config so ensureSdConfig() rebuilds and loads THIS chat's saved record
        /// (not the last chat's memoized instance).
        let newCcid = chatConfigObjectId || null;
        if (newCcid !== _chatConfigObjectId) {
            _chatConfigObjectId = newCcid;
            resetConfig();
        }
        loadModels();
        loadLoras();
        /// Kicked off, not awaited — show() is called from a click handler. The panel renders a
        /// loading state until the template resolves, and doGenerate awaits it independently.
        ensureSdConfig();

        Dialog.open({
            title: "Scene Generation",
            size: "lg",
            content: {
                view: function() {
                    /// sdConfig is null until the server template resolves. Rendering the panel with a
                    /// null config would throw, and rendering it with a hand-made stand-in is what this
                    /// change exists to remove - so show a loading state instead.
                    if (!sdConfig) {
                        return m("div", { class: "p-4" }, "Loading image configuration...");
                    }
                    return m("div", { class: "p-4", style: "max-height: 70vh; overflow-y: auto;" }, [
                        /// Load the chat-global shared config into the panel — mirrors reimage's "Load Shared".
                        m("div", { class: "flex flex-wrap gap-2 mb-2" }, [
                            m("button", {
                                class: "button",
                                title: "Load shared chat config",
                                onclick: loadShared
                            }, [
                                m("span", { class: "material-symbols-outlined md-18 mr-1" }, "open_in_new"),
                                "Load Shared"
                            ])
                        ]),
                        m(SdConfigPanel, {
                            config: sdConfig,
                            models: sdModels,
                            loras: sdLoras,
                            /// Persistence is now save-on-Generate (server-side), so a panel edit only
                            /// needs to redraw — it no longer writes through on every change.
                            onChange: function() { m.redraw(); }
                        }),
                        /// "Save as Shared" — when checked, Generate also writes the chat-global shared
                        /// record (sharedChatSD.json), in addition to always saving this chat's own config.
                        m("div", { class: "flex items-center gap-2 mt-2" }, [
                            m("input", {
                                type: "checkbox",
                                checked: _saveShared,
                                onchange: function(e) { _saveShared = e.target.checked; }
                            }),
                            m("label", { class: "field-label" }, "Save as Shared Config")
                        ])
                    ]);
                }
            },
            actions: [
                {
                    label: "Cancel",
                    icon: "cancel",
                    onclick: function() { Dialog.close(); }
                },
                {
                    label: _generating ? "Generating..." : "Generate Scene",
                    icon: _generating ? "progress_activity" : "auto_awesome",
                    primary: true,
                    disabled: _generating,
                    onclick: doGenerate
                }
            ]
        });
    },

    /// Open the gallery of previously-generated scenes for this chat. The
    /// gallery's action bar gets a "Generate" button that opens the scene
    /// configuration dialog. If no scenes have ever been generated for this
    /// chat (no data.group at the expected path), the generator is opened
    /// immediately instead of an empty gallery — there's nothing to show.
    openSceneGallery: async function(sessionObjectId, chatCfg, onGenerated) {
        /// The per-chat config is keyed to the durable chatConfig objectId, not the ephemeral session,
        /// so every session/conversation of the same chat reuses the same saved scene config.
        let chatConfigObjectId = (chatCfg && chatCfg.chat && chatCfg.chat.objectId) ? chatCfg.chat.objectId : null;
        let path = sceneGalleryPathFor(chatCfg);
        if (!path) {
            /// Can't compute the path (missing characters) — fall back to
            /// the original behaviour: open the generator directly.
            SceneGenerator.show(sessionObjectId, onGenerated, chatConfigObjectId);
            return;
        }

        let group = await findGroupByPath(path);
        if (!group || !group.id) {
            /// First-run: no scenes exist for this character pair. Go
            /// straight to the generator.
            page.toast("info", "No scenes yet — opening generator");
            SceneGenerator.show(sessionObjectId, onGenerated, chatConfigObjectId);
            return;
        }

        /// Existing scene gallery — open it with a Generate action in the
        /// dialog's footer. Clicking Generate closes the gallery (Dialog
        /// supports one modal) and opens the scene config dialog.
        page.imageGallery([], null, {
            directGroupId: group.id,
            title: "Scenes — " + path.replace("~/Gallery/Scenes/", ""),
            extraActions: [
                {
                    label: "Generate",
                    icon: "auto_awesome",
                    primary: true,
                    onclick: function() {
                        Dialog.close();
                        SceneGenerator.show(sessionObjectId, onGenerated, chatConfigObjectId);
                    }
                }
            ]
        });
    },

    hide: function() {
        Dialog.close();
    },

    toggle: function(sessionObjectId, onGenerated, chatConfigObjectId) {
        SceneGenerator.show(sessionObjectId, onGenerated, chatConfigObjectId);
    },

    isVisible: function() { return false; },

    /// Legacy popover view kept for back-compat with any caller still
    /// embedding SceneGenerator.PanelView — now a no-op since show() opens
    /// a proper modal dialog.
    PanelView: {
        view: function() { return null; }
    }
};

// ── Test-only seam ──────────────────────────────────────────────────
// ensureSdConfig / persistConfig / perChatConfigName are module-private; expose them (plus setters and
// a reset) so a Vitest can exercise the server-side per-chat load/save path without opening a Dialog.
// Production code never imports these — it goes through SceneGenerator.show/openSceneGallery.
function __setChatConfigForTest(chatConfigObjectId) {
    _chatConfigObjectId = chatConfigObjectId || null;
    resetConfig();
}
function __setSaveSharedForTest(v) { _saveShared = !!v; }
function __getSdConfigForTest() { return sdConfig; }
function __resetSceneGeneratorForTest() {
    _chatConfigObjectId = null;
    _saveShared = false;
    resetConfig();
}

export { SceneGenerator };
export {
    ensureSdConfig, persistConfig, loadShared, perChatConfigName,
    __setChatConfigForTest, __setSaveSharedForTest, __getSdConfigForTest, __resetSceneGeneratorForTest
};
export default SceneGenerator;
