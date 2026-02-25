/**
 * CardGame Designer — Export Pipeline
 * Renders cards at print resolution using html2canvas, packages into ZIP via JSZip.
 *
 * Depends on:
 *   - window.html2canvas (loaded from node_modules)
 *   - window.JSZip (loaded from node_modules)
 *   - window.CardGame.Constants (CARD_SIZES, CARD_TYPES)
 *   - window.CardGame.Designer.LayoutConfig (getLayout)
 *   - window.CardGame.Designer.LayoutRenderer (LayoutCardFace, renderCardToContainer)
 *
 * Exposes: window.CardGame.Designer.ExportPipeline
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.Designer = window.CardGame.Designer || {};

    function C() { return window.CardGame.Constants; }
    function LC() { return window.CardGame.Designer.LayoutConfig; }
    function LR() { return window.CardGame.Designer.LayoutRenderer; }

    // ── Export state ────────────────────────────────────────────────
    let exportState = {
        active: false,
        cancelled: false,
        total: 0,
        completed: 0,
        currentCard: "",
        error: null
    };

    function resetState() {
        exportState.active = false;
        exportState.cancelled = false;
        exportState.total = 0;
        exportState.completed = 0;
        exportState.currentCard = "";
        exportState.error = null;
    }

    // ── Check if libraries are available ────────────────────────────
    function checkLibraries() {
        let missing = [];
        if (!window.html2canvas) missing.push("html2canvas");
        if (!window.JSZip) missing.push("JSZip");
        return missing;
    }

    // ── Sanitize filename ──────────────────────────────────────────
    function sanitizeFilename(name) {
        return (name || "card").replace(/[^a-zA-Z0-9_\-\s]/g, "").replace(/\s+/g, "_").substring(0, 40);
    }

    // ── Render a single card to a canvas element ────────────────────
    async function renderCardToCanvas(card, deck, sizeKey, bgImage) {
        let sz = C().CARD_SIZES[sizeKey];
        if (!sz) throw new Error("Unknown card size: " + sizeKey);

        let pxW = sz.px[0];
        let pxH = sz.px[1];

        // Determine the render type for layout lookup
        let cardType = card.type;
        if (cardType === "item" && card.subtype === "consumable") {
            // consumable uses item layout
        }

        let layout = LC().getLayout(deck, cardType, sizeKey);

        // Create offscreen container
        let container = document.createElement("div");
        container.style.width = pxW + "px";
        container.style.height = pxH + "px";
        container.style.position = "fixed";
        container.style.left = "-9999px";
        container.style.top = "0";
        container.style.overflow = "hidden";
        container.style.backgroundColor = "#fff";
        container.style.fontFamily = "system-ui, -apple-system, sans-serif";

        // Set CSS variables for the print size
        let scale = pxW / 180; // base card is 180px
        container.style.setProperty("--card-width", pxW + "px");
        container.style.setProperty("--card-height", pxH + "px");
        container.style.setProperty("--card-stack-top", Math.round(22 * scale) + "px");
        container.style.setProperty("--card-stack-right", Math.round(18 * scale) + "px");

        document.body.appendChild(container);

        try {
            // Render the card using Mithril
            m.render(container, m("div", {
                style: {
                    width: pxW + "px",
                    height: pxH + "px",
                    position: "relative",
                    overflow: "hidden",
                    fontSize: Math.round(10 * scale) + "px"
                }
            }, m(LR().LayoutCardFace, {
                card: card,
                deck: deck,
                sizeKey: sizeKey,
                layoutConfig: layout,
                bgImage: bgImage,
                noPreview: true
            })));

            // Wait for images to load
            let images = container.querySelectorAll("img");
            if (images.length > 0) {
                await Promise.all(Array.from(images).map(function(img) {
                    if (img.complete) return Promise.resolve();
                    return new Promise(function(resolve) {
                        img.onload = resolve;
                        img.onerror = resolve; // proceed even if image fails
                        // Timeout after 5s
                        setTimeout(resolve, 5000);
                    });
                }));
            }

            // Small delay for font rendering
            await new Promise(function(r) { setTimeout(r, 100); });

            // Capture with html2canvas
            let canvas = await window.html2canvas(container, {
                width: pxW,
                height: pxH,
                scale: 1, // already at target resolution
                useCORS: true,
                allowTaint: true,
                backgroundColor: "#ffffff",
                logging: false
            });

            return canvas;
        } finally {
            // Clean up
            m.render(container, null);
            document.body.removeChild(container);
        }
    }

    // ── Convert canvas to blob ──────────────────────────────────────
    function canvasToBlob(canvas, format, quality) {
        return new Promise(function(resolve) {
            let mimeType = format === "jpg" ? "image/jpeg" : "image/png";
            let q = format === "jpg" ? (quality || 90) / 100 : undefined;
            canvas.toBlob(function(blob) {
                resolve(blob);
            }, mimeType, q);
        });
    }

    // ── Export all cards as ZIP ──────────────────────────────────────
    async function exportDeck(deck, options) {
        let missing = checkLibraries();
        if (missing.length > 0) {
            throw new Error("Missing libraries: " + missing.join(", ") + ". Please reload the page.");
        }

        let sizeKey = options.sizeKey || "poker";
        let format = options.format || "png";
        let quality = options.quality || 90;
        let includeBack = options.includeBack || false;
        let bgImage = options.bgImage || null;

        let cards = deck?.cards || [];
        if (cards.length === 0) throw new Error("Deck has no cards");

        // Get unique cards
        let uniqueMap = {};
        cards.forEach(function(card, i) {
            let sig = card.name + "|" + card.type + "|" + (card.subtype || "");
            if (!uniqueMap[sig]) {
                uniqueMap[sig] = { card: card, index: i };
            }
        });
        let uniqueCards = Object.values(uniqueMap);

        resetState();
        exportState.active = true;
        exportState.total = uniqueCards.length * (includeBack ? 2 : 1);
        m.redraw();

        let zip = new window.JSZip();
        let ext = format === "jpg" ? ".jpg" : ".png";
        let sz = C().CARD_SIZES[sizeKey];
        let deckName = sanitizeFilename(deck.deckName || "deck");

        // Add a manifest/info file
        zip.file("info.txt", [
            "Deck: " + (deck.deckName || "Unknown"),
            "Size: " + (sz?.label || sizeKey),
            "Dimensions: " + (sz?.px[0] || "?") + " x " + (sz?.px[1] || "?") + " px",
            "DPI: " + (sz?.dpi || 300),
            "Format: " + format.toUpperCase(),
            "Cards: " + uniqueCards.length,
            "Exported: " + new Date().toISOString()
        ].join("\n"));

        try {
            for (let i = 0; i < uniqueCards.length; i++) {
                if (exportState.cancelled) break;

                let card = uniqueCards[i].card;
                exportState.currentCard = card.name || ("Card " + (i + 1));
                m.redraw();

                // Render front
                let canvas = await renderCardToCanvas(card, deck, sizeKey, bgImage);
                let blob = await canvasToBlob(canvas, format, quality);

                let fileName = String(i + 1).padStart(3, "0") + "-" + sanitizeFilename(card.name) + "-" + card.type + ext;
                zip.file("fronts/" + fileName, blob);

                exportState.completed++;
                m.redraw();

                // Render back (if requested)
                if (includeBack && !exportState.cancelled) {
                    // TODO: implement card back rendering with layout
                    // For now, skip back rendering
                    exportState.completed++;
                    m.redraw();
                }

                // Yield to UI thread
                await new Promise(function(r) { setTimeout(r, 10); });
            }

            if (exportState.cancelled) {
                resetState();
                return null;
            }

            // Generate ZIP
            exportState.currentCard = "Building ZIP...";
            m.redraw();

            let zipBlob = await zip.generateAsync({
                type: "blob",
                compression: "DEFLATE",
                compressionOptions: { level: 6 }
            }, function(meta) {
                // Progress callback
                exportState.currentCard = "Compressing... " + Math.round(meta.percent) + "%";
                m.redraw();
            });

            // Trigger download
            let url = URL.createObjectURL(zipBlob);
            let a = document.createElement("a");
            a.href = url;
            a.download = deckName + "-" + sizeKey + "-cards.zip";
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            setTimeout(function() { URL.revokeObjectURL(url); }, 5000);

            resetState();
            return { count: uniqueCards.length, size: zipBlob.size };

        } catch (e) {
            exportState.error = e.message;
            exportState.active = false;
            m.redraw();
            throw e;
        }
    }

    // ── Cancel export ───────────────────────────────────────────────
    function cancelExport() {
        exportState.cancelled = true;
    }

    // ── Expose ──────────────────────────────────────────────────────
    window.CardGame.Designer.ExportPipeline = {
        exportState: exportState,
        checkLibraries: checkLibraries,
        renderCardToCanvas: renderCardToCanvas,
        canvasToBlob: canvasToBlob,
        exportDeck: exportDeck,
        cancelExport: cancelExport,
        resetState: resetState
    };

    console.log("[CardGame] Designer.ExportPipeline loaded");
}());
