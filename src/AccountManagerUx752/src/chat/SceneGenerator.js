/**
 * SceneGenerator — SD scene generation config dialog + generation (ESM)
 * Uses shared SdConfigPanel (same form as reimage / picture book).
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { applicationPath } from '../core/config.js';
import { SdConfigPanel } from '../components/SdConfigPanel.js';
import { Dialog } from '../components/dialogCore.js';

// ── SD Config State (persisted to localStorage) ─────────────────────

const SD_CONFIG_KEY = "am7.sdConfig";

let sdConfig = loadConfig();
let sdModels = [];
let sdLoras = [];
let _generating = false;
let _sessionObjectId = null;
let _onGenerated = null;

function defaultConfig() {
    return {
        model: "",
        refinerModel: "",
        style: "photograph",
        steps: 40,
        refinerSteps: 40,
        cfg: 5,
        refinerCfg: 5,
        denoisingStrength: 0.65,
        sampler: "dpmpp_2m",
        scheduler: "karras",
        refinerSampler: "dpmpp_2m",
        refinerScheduler: "karras",
        width: "1024",
        height: "768",
        hires: false,
        seed: -1,
        imageCount: 1,
        bodyStyle: "",
        imageAction: "",
        imageSetting: ""
    };
}

function loadConfig() {
    try {
        let stored = localStorage.getItem(SD_CONFIG_KEY);
        if (stored) return Object.assign(defaultConfig(), JSON.parse(stored));
    } catch(e) {}
    return defaultConfig();
}

function saveConfig() {
    try { localStorage.setItem(SD_CONFIG_KEY, JSON.stringify(sdConfig)); } catch(e) {}
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

        Dialog.open({
            title: "Scene Generation",
            size: "lg",
            content: {
                view: function() {
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
