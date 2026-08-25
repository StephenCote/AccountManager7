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
// localStorage holds only the user's tweaks and is overlaid onto a fresh template each time.

const SD_CONFIG_KEY = "am7.sdConfig";

let sdConfig = null;        // olio.sd.config entity (null until ensureSdConfig resolves)
let sdConfigInst = null;    // am7model instance wrapping sdConfig (forms.sdConfig)
let sdModels = [];
let sdLoras = [];
let _generating = false;
let _sessionObjectId = null;
let _onGenerated = null;

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
// The saved blob is now overlaid onto a real server template instead - see ensureSdConfig.
// Keys that must never be carried over from a saved blob onto a fresh template: identity fields,
// and the model/refinerModel pair. The saved blob's model is what caused the reported failure - it
// was "" (or a checkpoint name valid only on the node it was saved from), and overlaying either onto
// a template that already carries a VALID model for THIS node reintroduces the bug.
const SD_CONFIG_IDENTITY = ['id', 'objectId', 'urn', 'ownerId', 'groupId', 'organizationId',
    'groupPath', 'organizationPath', 'narration'];
const SD_CONFIG_NEVER_RESTORE = ['model', 'refinerModel'];

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

            /// Overlay saved tweaks. Skips identity, the model pair, and any null/blank value - a
            /// blank must not overwrite a good template value, which is precisely how "" reached the
            /// server. Unknown legacy keys are dropped by virtue of only copying what the model has.
            let stored = null;
            try {
                let raw = localStorage.getItem(SD_CONFIG_KEY);
                if (raw) stored = JSON.parse(raw);
            } catch (e) { stored = null; }
            if (stored) {
                for (let k in stored) {
                    if (SD_CONFIG_IDENTITY.includes(k) || SD_CONFIG_NEVER_RESTORE.includes(k)) continue;
                    if (k === am7model.jsonModelKey) continue;
                    let v = stored[k];
                    if (v === undefined || v === null || v === '' || v === 0) continue;
                    if (!(k in entity)) continue;
                    entity[k] = v;
                }
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

function saveConfig() {
    try {
        if (!sdConfig) return;
        /// Persist tweaks only - never the model, so a config saved on one node cannot poison another.
        let out = {};
        for (let k in sdConfig) {
            if (SD_CONFIG_IDENTITY.includes(k) || SD_CONFIG_NEVER_RESTORE.includes(k)) continue;
            if (k === am7model.jsonModelKey) continue;
            let v = sdConfig[k];
            if (typeof v === 'function' || v === undefined) continue;
            out[k] = v;
        }
        localStorage.setItem(SD_CONFIG_KEY, JSON.stringify(out));
    } catch(e) {}
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
    show: function(sessionObjectId, onGenerated) {
        _sessionObjectId = sessionObjectId;
        _onGenerated = onGenerated || null;
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
                        m(SdConfigPanel, {
                            config: sdConfig,
                            models: sdModels,
                            loras: sdLoras,
                            onChange: saveConfig
                        })
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
        let path = sceneGalleryPathFor(chatCfg);
        if (!path) {
            /// Can't compute the path (missing characters) — fall back to
            /// the original behaviour: open the generator directly.
            SceneGenerator.show(sessionObjectId, onGenerated);
            return;
        }

        let group = await findGroupByPath(path);
        if (!group || !group.id) {
            /// First-run: no scenes exist for this character pair. Go
            /// straight to the generator.
            page.toast("info", "No scenes yet — opening generator");
            SceneGenerator.show(sessionObjectId, onGenerated);
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
                        SceneGenerator.show(sessionObjectId, onGenerated);
                    }
                }
            ]
        });
    },

    hide: function() {
        Dialog.close();
    },

    toggle: function(sessionObjectId, onGenerated) {
        SceneGenerator.show(sessionObjectId, onGenerated);
    },

    isVisible: function() { return false; },

    /// Legacy popover view kept for back-compat with any caller still
    /// embedding SceneGenerator.PanelView — now a no-op since show() opens
    /// a proper modal dialog.
    PanelView: {
        view: function() { return null; }
    }
};

export { SceneGenerator };
export default SceneGenerator;
