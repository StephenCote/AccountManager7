/**
 * CardGame Designer — Material Icon Picker
 * Searchable grid of Material Symbols icons for the card layout designer.
 *
 * Depends on: Material Symbols CSS font (already loaded)
 * Exposes: window.CardGame.Designer.IconPicker
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.Designer = window.CardGame.Designer || {};

    // ── Curated icon list organized by category ────────────────────
    const ICON_CATEGORIES = {
        "All":      null, // special: shows everything
        "Combat":   ["swords", "shield", "bolt", "gps_fixed", "casino", "target",
                     "flash_on", "local_fire_department", "water_drop", "air", "ac_unit",
                     "whatshot", "psychology", "psychology_alt", "smart_toy"],
        "People":   ["person", "group", "face", "badge", "military_tech", "school",
                     "fitness_center", "accessibility_new", "directions_run", "sports_martial_arts"],
        "Items":    ["checkroom", "back_hand", "build", "key", "lock", "lock_open",
                     "inventory_2", "backpack", "diamond", "auto_awesome"],
        "Nature":   ["explore", "landscape", "forest", "park", "terrain", "water",
                     "pets", "eco", "spa", "grass", "wb_sunny", "nights_stay"],
        "Magic":    ["auto_fix_high", "blur_on", "flare", "star", "stars", "brightness_high",
                     "all_inclusive", "sync", "refresh", "loop", "motion_photos_on"],
        "Status":   ["favorite", "heart_broken", "warning", "error", "info", "help",
                     "check_circle", "cancel", "block", "visibility", "visibility_off"],
        "UI":       ["category", "layers", "tune", "settings", "dashboard", "view_agenda",
                     "grid_view", "list", "table_rows", "horizontal_rule"],
        "Chat":     ["chat_bubble", "forum", "campaign", "record_voice_over", "mic",
                     "volume_up", "music_note", "audiotrack"],
        "Game":     ["playing_cards", "sports_esports", "extension", "rocket_launch",
                     "science", "biotech", "skull", "church", "castle", "temple_hindu"],
        "Nav":      ["north", "south", "east", "west", "my_location", "pin_drop",
                     "flag", "tour", "signpost"],
        "Stats":    ["straighten", "balance", "speed", "timer", "hourglass_empty",
                     "trending_up", "trending_down", "signal_cellular_alt"],
        "Objects":  ["crown", "ring", "animation", "palette", "brush", "draw",
                     "edit_note", "text_fields", "image", "photo_library"],
        "Arrows":   ["arrow_upward", "arrow_downward", "arrow_forward", "arrow_back",
                     "expand_more", "expand_less", "chevron_right", "chevron_left",
                     "open_in_new", "download", "upload", "share", "content_copy",
                     "delete", "add", "remove", "add_circle", "remove_circle",
                     "more_vert", "more_horiz", "menu", "close"],
        "Emotes":   ["sentiment_very_satisfied", "sentiment_satisfied", "sentiment_neutral",
                     "sentiment_dissatisfied", "sentiment_very_dissatisfied",
                     "mood", "theater_comedy", "celebration", "emoji_events"],
        "Food":     ["restaurant", "local_bar", "local_cafe", "cake"],
        "Tools":    ["construction", "handyman", "precision_manufacturing", "carpenter"],
        "Weather":  ["thunderstorm", "cloudy", "foggy", "tornado"],
        "Shapes":   ["circle", "square", "hexagon", "pentagon", "change_history",
                     "crop_portrait", "crop_landscape"],
        "Cards":    ["add_reaction", "design_services", "style", "color_lens",
                     "flip_to_back", "flip_to_front", "table_restaurant",
                     "photo_camera", "burst_mode", "collections",
                     "save", "file_download", "print", "picture_as_pdf"]
    };

    // Flat list for backwards compat
    const ICON_LIST = Object.values(ICON_CATEGORIES).filter(Boolean).flat();

    let searchQuery = "";
    let selectedCategory = "All";
    let selectedCallback = null;
    let pickerVisible = false;

    // ── Filter icons by search query and category ───────────────────
    function filteredIcons() {
        let list = selectedCategory === "All" ? ICON_LIST : (ICON_CATEGORIES[selectedCategory] || ICON_LIST);
        if (!searchQuery) return list;
        let q = searchQuery.toLowerCase();
        return list.filter(function(name) {
            return name.toLowerCase().includes(q);
        });
    }

    // ── Open the picker ─────────────────────────────────────────────
    function openPicker(callback) {
        selectedCallback = callback;
        pickerVisible = true;
        searchQuery = "";
        selectedCategory = "All";
        m.redraw();
    }

    function closePicker() {
        pickerVisible = false;
        selectedCallback = null;
        m.redraw();
    }

    // ── IconPicker Component ────────────────────────────────────────
    function IconPickerOverlay() {
        return {
            view: function() {
                if (!pickerVisible) return null;

                let icons = filteredIcons();

                let catKeys = Object.keys(ICON_CATEGORIES);

                return m("div", { class: "cg2-icon-picker-overlay", onclick: function(e) { if (e.target === e.currentTarget) closePicker(); } },
                    m("div", { class: "cg2-icon-picker-panel" }, [
                        // Header
                        m("div", { class: "cg2-icon-picker-header" }, [
                            m("span", { class: "material-symbols-outlined", style: { fontSize: "18px", marginRight: "4px" } }, "category"),
                            m("span", { style: { fontWeight: 700, fontSize: "14px" } }, "Select Icon"),
                            m("span", { style: { marginLeft: "auto", fontSize: "11px", color: "#888" } }, icons.length + " icons"),
                            m("button", {
                                class: "cg2-btn cg2-btn-sm",
                                onclick: closePicker
                            }, "\u00d7")
                        ]),
                        // Search
                        m("div", { class: "cg2-icon-picker-search" },
                            m("input", {
                                type: "text",
                                placeholder: "Type to filter icons...",
                                value: searchQuery,
                                oninput: function(e) { searchQuery = e.target.value; },
                                oncreate: function(vnode) { vnode.dom.focus(); }
                            })
                        ),
                        // Category tabs
                        m("div", { class: "cg2-icon-picker-cats" },
                            catKeys.map(function(cat) {
                                return m("button", {
                                    class: "cg2-icon-picker-cat" + (selectedCategory === cat ? " cg2-icon-picker-cat-active" : ""),
                                    onclick: function() { selectedCategory = cat; m.redraw(); }
                                }, cat);
                            })
                        ),
                        // Grid
                        m("div", { class: "cg2-icon-picker-grid" },
                            icons.map(function(name) {
                                return m("div", {
                                    class: "cg2-icon-picker-item",
                                    title: name.replace(/_/g, " "),
                                    onclick: function() {
                                        if (selectedCallback) selectedCallback(name);
                                        closePicker();
                                    }
                                }, [
                                    m("span", { class: "material-symbols-outlined" }, name),
                                    m("span", { class: "cg2-icon-picker-name" }, name.replace(/_/g, " "))
                                ]);
                            })
                        )
                    ])
                );
            }
        };
    }

    // ── Inline icon picker button (for property panel) ──────────────
    function IconPickerButton() {
        return {
            view: function(vnode) {
                let currentIcon = vnode.attrs.value || "";
                let onChange = vnode.attrs.onchange;

                return m("div", { class: "cg2-icon-picker-btn-wrap" }, [
                    currentIcon ? m("span", {
                        class: "material-symbols-outlined",
                        style: { fontSize: "18px", verticalAlign: "middle", marginRight: "4px" }
                    }, currentIcon) : null,
                    m("button", {
                        class: "cg2-btn cg2-btn-sm",
                        onclick: function() {
                            openPicker(function(icon) {
                                if (onChange) onChange(icon);
                            });
                        }
                    }, currentIcon ? "Change" : "Pick Icon"),
                    currentIcon ? m("button", {
                        class: "cg2-btn cg2-btn-sm",
                        style: { marginLeft: "4px" },
                        onclick: function() { if (onChange) onChange(""); }
                    }, "\u00d7") : null
                ]);
            }
        };
    }

    // ── Expose ──────────────────────────────────────────────────────
    window.CardGame.Designer.IconPicker = {
        ICON_LIST: ICON_LIST,
        IconPickerOverlay: IconPickerOverlay,
        IconPickerButton: IconPickerButton,
        openPicker: openPicker,
        closePicker: closePicker
    };

    console.log("[CardGame] Designer.IconPicker loaded");
}());
