// Card Game v2 — Rendering: Card Components
// Extracted from cardGame-v2.js: rarityStars, NeedBar, StatBlock, cornerIcon, cardTypeIcon, iconDetail, D20Dice
(function() {
    "use strict";
    const C = window.CardGame.Constants;

    // ── Rarity Display ───────────────────────────────────────────────
    function rarityStars(rarity) {
        let n = ({ COMMON: 1, UNCOMMON: 2, RARE: 3, EPIC: 4, LEGENDARY: 5 })[rarity] || 1;
        return Array.from({ length: 5 }, (_, i) =>
            m("span", { class: "cg2-rarity-star" + (i < n ? " cg2-rarity-star-active" : "") }, "\u2605")
        );
    }

    // ── Need Bar Component ───────────────────────────────────────────
    function NeedBar() {
        return {
            view(vnode) {
                let { label, current, max, color, abbrev } = vnode.attrs;
                let pct = Math.max(0, Math.min(100, (current / max) * 100));
                return m("div", { class: "cg2-need-row" }, [
                    m("span", { class: "cg2-need-label" }, abbrev || label),
                    m("div", { class: "cg2-need-track" }, [
                        m("div", { class: "cg2-need-fill", style: { width: pct + "%", background: color } })
                    ]),
                    m("span", { class: "cg2-need-value" }, current + "/" + max)
                ]);
            }
        };
    }

    // ── Stat Block Component ─────────────────────────────────────────
    function StatBlock() {
        return {
            view(vnode) {
                let stats = vnode.attrs.stats;
                let order = [["STR", "AGI", "END"], ["INT", "MAG", "CHA"]];
                return m("div", { class: "cg2-stat-grid" },
                    order.map(row =>
                        m("div", { class: "cg2-stat-row" },
                            row.map(s =>
                                m("div", { class: "cg2-stat-cell" }, [
                                    m("span", { class: "cg2-stat-abbrev" }, s),
                                    m("span", { class: "cg2-stat-val" }, stats[s] || 0)
                                ])
                            )
                        )
                    )
                );
            }
        };
    }

    // ── Corner Icon Helper ───────────────────────────────────────────
    function cornerIcon(type, position) {
        let cfg = C.CARD_TYPES[type] || C.CARD_TYPES.item;
        let icon = cfg.icon;
        if (type === "item") icon = C.ITEM_SUBTYPE_ICONS.weapon; // default; overridden per card
        let style = {
            position: "absolute", color: cfg.color,
            lineHeight: "1"
        };
        // Offset below top stacking border and left of right stacking border (v3.1)
        if (position === "top-left")     { style.top = "calc(var(--card-stack-top) + 4px)"; style.left = "8px"; }
        if (position === "top-right")    { style.top = "calc(var(--card-stack-top) + 4px)"; style.right = "calc(var(--card-stack-right) + 6px)"; }
        if (position === "bottom-right") { style.bottom = "6px"; style.right = "calc(var(--card-stack-right) + 6px)"; style.transform = "rotate(180deg)"; }
        return m("span", { class: "material-symbols-outlined cg2-corner-icon", style }, icon);
    }

    // Get icon for card type (for stack display)
    function cardTypeIcon(type) {
        let cfg = C.CARD_TYPES[type] || C.CARD_TYPES.item;
        return cfg.icon;
    }

    // Helper: render a detail row with icon instead of text prefix
    function iconDetail(icon, text, extraClass) {
        if (!text) return null;
        return m("div", { class: "cg2-card-detail cg2-icon-detail" + (extraClass ? " " + extraClass : "") }, [
            m("span", { class: "material-symbols-outlined cg2-detail-icon" }, icon),
            m("span", text)
        ]);
    }

    // ── D20 Dice Component (inline SVG with embedded number) ─────────
    function D20Dice() {
        return {
            view(vnode) {
                let { value, rolling, winner } = vnode.attrs;
                let displayVal = value || "?";

                // SVG paths from d20-1435790057.svg
                return m("div", {
                    class: "cg2-d20-wrap" + (rolling ? " cg2-d20-rolling" : " cg2-d20-final") + (winner ? " cg2-d20-winner" : "")
                }, [
                    m("svg", {
                        viewBox: "0 0 360 360",
                        class: "cg2-d20-svg",
                        xmlns: "http://www.w3.org/2000/svg"
                    }, [
                        // Main blue faces
                        m("g", { fill: "#3372A0" }, [
                            m("polygon", { points: "178.3,13.9 38.6,94.5 178.3,105.8" }),
                            m("polygon", { points: "34.8,104.6 34.8,263.4 93.4,252.3" }),
                            m("polygon", { points: "177.2,109.1 35.7,97.7 96,249.7" }),
                            m("polygon", { points: "261.3,251.9 180,111 98.7,251.9" }),
                            m("polygon", { points: "181.7,13.9 181.7,105.8 321.4,94.5" }),
                            m("polygon", { points: "264,249.7 324.3,97.7 182.8,109.1" }),
                            m("polygon", { points: "188,343.7 322.1,266.2 264.9,255.4" }),
                            m("polygon", { points: "325.2,263.4 325.2,104.6 266.6,252.3" }),
                            m("polygon", { points: "99.5,255.2 180,347.7 260.5,255.2" }),
                            m("polygon", { points: "37.9,266.2 172,343.7 95.1,255.4" })
                        ]),
                        // White highlights
                        m("g", { fill: "#FFFFFF", opacity: "0.25" }, [
                            m("polygon", { points: "178.3,13.9 38.6,94.5 68.1,96.9" }),
                            m("polygon", { points: "34.8,104.6 34.8,263.4 43.3,261.8" }),
                            m("polygon", { points: "108.5,228.1 35.7,97.7 96,249.7" }),
                            m("polygon", { points: "192.9,133.4 180,111 98.7,251.9 116.9,251.9" }),
                            m("polygon", { points: "181.7,13.9 181.7,105.8 209.3,103.6" }),
                            m("polygon", { points: "195.5,131.1 324.3,97.7 182.8,109.1" }),
                            m("polygon", { points: "188,343.7 282.5,258.7 264.9,255.4" }),
                            m("polygon", { points: "282.3,255.2 325.2,104.6 266.6,252.3" }),
                            m("polygon", { points: "99.5,255.2 180,347.7 118,255.4" }),
                            m("polygon", { points: "37.9,266.2 103.7,265.3 95.1,255.4" })
                        ]),
                        // Dark outline
                        m("path", { fill: "#303030", d: "M328.4,99.6c0-3.1-1.7-6-4.4-7.6L183.9,11.3c-2.4-1.4-5.4-1.4-7.9,0L35.6,92.9c-2.5,1.5-4.1,4.2-4.1,7.1l-0.1,161.8c0,2.8,1.5,5.4,3.9,6.8L176.1,350c2.4,1.4,5.3,1.4,7.7,0l140.8-81.3c2.4-1.4,3.9-4,3.9-6.8L328.4,99.6z M267.1,250.9l55.8-140.4c0.5-1.2,2.3-0.9,2.3,0.4v150.9c0,0.8-0.7,1.3-1.4,1.2L268,252.5C267.3,252.4,266.8,251.6,267.1,250.9z M102.2,255.2h155.7c1,0,1.6,1.2,0.9,2l-77.8,89.4c-0.5,0.6-1.4,0.6-1.8,0l-77.8-89.4C100.6,256.5,101.1,255.2,102.2,255.2z M181.1,112.9L260.2,250c0.5,0.8-0.1,1.8-1.1,1.8H100.8c-0.9,0-1.5-1-1.1-1.8l79.2-137.2C179.4,112.1,180.6,112.1,181.1,112.9z M184.7,109l137.7-11.1c0.9-0.1,1.6,0.8,1.2,1.7l-58.7,147.8c-0.4,0.9-1.7,1-2.2,0.2l-78.9-136.7C183.3,110,183.8,109,184.7,109z M95.1,247.3L36.4,99.5c-0.3-0.8,0.3-1.7,1.2-1.7L175.3,109c0.9,0.1,1.4,1,1,1.8L97.3,247.5C96.8,248.4,95.5,248.3,95.1,247.3z M181.7,104.5V16c0-0.9,1-1.5,1.8-1.1L318,92.6c1,0.6,0.7,2.2-0.5,2.3L183,105.7C182.3,105.8,181.7,105.2,181.7,104.5z M178.3,16v88.5c0,0.7-0.6,1.3-1.3,1.2L42.5,94.8c-1.2-0.1-1.5-1.7-0.5-2.3l134.5-77.6C177.3,14.5,178.3,15.1,178.3,16z M92,252.5l-55.8,10.6c-0.7,0.1-1.4-0.4-1.4-1.2V111c0-1.3,1.9-1.7,2.3-0.4l55.8,140.4C93.2,251.6,92.7,252.4,92,252.5z M41.1,265.6l53.3-10.1c0.4-0.1,0.9,0.1,1.1,0.4l71.7,82.3c0.9,1-0.3,2.5-1.5,1.9l-125-72.2C39.7,267.3,40,265.8,41.1,265.6z M265.6,255.5l53.3,10.1c1.1,0.2,1.4,1.7,0.4,2.2l-125,72.2c-1.2,0.7-2.4-0.8-1.5-1.9l71.7-82.3C264.7,255.6,265.1,255.4,265.6,255.5z" }),
                        // Number text - centered on the front face
                        m("text", {
                            x: "180",
                            y: "200",
                            "text-anchor": "middle",
                            "dominant-baseline": "middle",
                            fill: "#FFFFFF",
                            "font-size": displayVal.toString().length > 1 ? "70" : "80",
                            "font-weight": "900",
                            "font-family": "Arial, sans-serif",
                            style: "text-shadow: 0 2px 4px rgba(0,0,0,0.5)"
                        }, displayVal)
                    ])
                ]);
            }
        };
    }

    window.CardGame = window.CardGame || {};
    window.CardGame.Rendering = window.CardGame.Rendering || {};
    Object.assign(window.CardGame.Rendering, {
        rarityStars, NeedBar, StatBlock, cornerIcon, cardTypeIcon, iconDetail, D20Dice
    });
})();
