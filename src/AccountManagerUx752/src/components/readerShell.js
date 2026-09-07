/**
 * Shared paginated reader shell — ChapBook Review Redesign §3.7 / decision D5.
 *
 * Extracted from PictureBook's `pictureBookView` so PictureBook and ChapBook share ONE page-flip
 * reader: an implicit cover (page 0) + one-page-at-a-time navigation, keyboard nav
 * (Arrow/Home/End/Esc), on-screen chevrons, page dots, fullscreen, and whole-book self-contained
 * HTML export. Only the per-page renderer and the image-URL resolver differ between the two callers.
 *
 * The shell is a CONTROLLED component: nav state lives in a caller-owned `state` object
 * (`{ currentPage, fullscreen }`) passed via `attrs`, mutated in place. This is deliberate — the
 * caller's route wrapper can still read `state.fullscreen` to decide whether to render layout chrome
 * (PictureBook's `/picture-book/:bookObjectId` route does exactly this), which a component-private
 * state field could not support.
 *
 * Attrs:
 *   state            {currentPage, fullscreen}   caller-owned, mutated in place (REQUIRED)
 *   pages            array                        page records; cover is the implicit page 0 (REQUIRED)
 *   title            string                       book title (header + cover + export)
 *   pageNoun         string='Page'                used in the "<noun> X of N" label + dot titles
 *   coverLabel       string='Cover'
 *   loading          bool                         when true, body = renderLoading()
 *   error            any                          when truthy, body = renderError(error)
 *   renderLoading()   → vnode
 *   renderError(err)  → vnode
 *   renderEmpty()     → vnode                     body when pages.length === 0 (and not loading/error)
 *   renderCover(nav)  → vnode                     cover page body (page 0)
 *   renderPage(page, pageNumber, nav) → vnode     one page body (pageNumber is 1-based)
 *   imageUrlFor(page) → url|null                  image URL resolver (used by the export engine)
 *   onBack()                                      back button (hidden in fullscreen); omit ⇒ no button
 *   backTitle        string
 *   actionsLeft(nav)  → vnode|array               header buttons between the Next-arrow and Export
 *   actionsRight(nav) → vnode|array               header buttons between Export and Fullscreen
 *   enableExport     bool=true
 *   enableFullscreen bool=true
 *   containerClass   string='p-4 flex flex-col h-full'
 *   bodyClass        string='flex-1 overflow-y-auto max-w-3xl mx-auto w-full'
 *   headerClass      string='flex items-center gap-3 mb-4'
 *   Export params: exportTitleSuffix, exportNameFallback, exportNameSuffix, exportToast,
 *                  exportCountNoun, coverImageUrl(), exportCss(), exportPageHtml(page,i,imgB64,total)
 *
 * The `nav` object handed to slot fns exposes { pages, total, currentPage, fullscreen, goToPage,
 * setFullscreen, toggleFullscreen } so caller-provided cover/page/header content can drive navigation.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';

// ── Export engine (shared) ────────────────────────────────────────────

export function escHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

// Default print stylesheet — identical to PictureBook's original export CSS so PictureBook exports
// byte-for-byte unchanged. ChapBook reuses the same generic book styling.
export function defaultExportCss() {
    return `
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: Georgia, 'Times New Roman', serif; background: #1a1a2e; color: #e0e0e0; }
.book { max-width: 900px; margin: 0 auto; }
.cover { position: relative; min-height: 80vh; display: flex; align-items: flex-end; justify-content: center;
         background: linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%); overflow: hidden; }
.cover img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; opacity: 0.6; }
.cover-overlay { position: relative; z-index: 1; text-align: center; padding: 3rem 2rem;
                  background: linear-gradient(transparent, rgba(0,0,0,0.8)); width: 100%; }
.cover h1 { font-size: 3rem; font-weight: 700; text-shadow: 0 2px 8px rgba(0,0,0,0.7); margin-bottom: 0.5rem; }
.cover p { font-size: 1.1rem; opacity: 0.7; }
.scene { padding: 3rem 2rem; border-bottom: 1px solid #2a2a3e; }
.scene img { width: 100%; max-height: 60vh; object-fit: contain; border-radius: 4px; margin-bottom: 1.5rem; display: block; }
.scene h2 { font-size: 1.6rem; margin-bottom: 0.75rem; color: #e8d5b7; }
.scene .blurb { font-size: 1.1rem; line-height: 1.8; max-width: 700px; color: #c8c8d0; }
.scene .characters { margin-top: 1rem; font-size: 0.85rem; color: #8888aa; }
.scene .page-num { margin-top: 1.5rem; font-size: 0.75rem; color: #555; text-align: center; }
@media print { .cover { min-height: auto; page-break-after: always; }
               .scene { page-break-inside: avoid; } }
`;
}

async function fetchImageAsBase64(url) {
    try {
        let resp = await fetch(url, { credentials: 'include' });
        if (!resp.ok) return null;
        let blob = await resp.blob();
        return new Promise(function (resolve) {
            let reader = new FileReader();
            reader.onloadend = function () { resolve(reader.result); };
            reader.readAsDataURL(blob);
        });
    } catch (e) {
        return null;
    }
}

// Default per-page export section — reproduces PictureBook's original scene HTML shape (image, title,
// blurb, character list, page number) so an unparameterised export matches the legacy output exactly.
function defaultPageHtml(s, i, imgB64, total) {
    let chars = Array.isArray(s.characters)
        ? s.characters.map(function (c) { return typeof c === 'string' ? c : (c && c.name) || ''; }).filter(Boolean).join(', ')
        : '';
    let out = '\n    <div class="scene">\n';
    if (imgB64) out += '      <img src="' + imgB64 + '" alt="' + escHtml(s.title || '') + '" />\n';
    out += '      <h2>' + escHtml(s.title || 'Scene ' + (i + 1)) + '</h2>\n';
    out += '      <p class="blurb">' + escHtml(s.description || s.summary || '') + '</p>\n';
    if (chars) out += '      <div class="characters">' + escHtml(chars) + '</div>\n';
    out += '      <div class="page-num">Page ' + (i + 1) + ' of ' + total + '</div>\n';
    out += '    </div>\n';
    return out;
}

/**
 * Build and download a self-contained HTML book. Entirely client-side: each image is fetched and
 * inlined as base64, so the file needs no backend. Reusable outside the shell (e.g. the ChapBook
 * review toolbar's Export). Resolves when the download has been triggered.
 */
export async function exportBook(opts) {
    let pages = opts.pages || [];
    if (!pages.length) return;
    let title = opts.title || '';
    let noun = opts.countNoun || 'Page';

    let coverUrl = opts.coverImageUrl ? opts.coverImageUrl() : (opts.imageUrlFor ? opts.imageUrlFor(pages[0]) : null);
    let coverB64 = coverUrl ? await fetchImageAsBase64(coverUrl) : null;

    let sections = '';
    for (let i = 0; i < pages.length; i++) {
        let p = pages[i];
        let imgUrl = opts.imageUrlFor ? opts.imageUrlFor(p) : null;
        let imgB64 = imgUrl ? await fetchImageAsBase64(imgUrl) : null;
        sections += opts.buildPageHtml
            ? opts.buildPageHtml(p, i, imgB64, pages.length)
            : defaultPageHtml(p, i, imgB64, pages.length);
    }

    let css = opts.css || defaultExportCss();
    let html = '<!DOCTYPE html>\n<html lang="en">\n<head>\n<meta charset="UTF-8">\n'
        + '<meta name="viewport" content="width=device-width, initial-scale=1.0">\n'
        + '<title>' + escHtml(title) + ' — ' + (opts.titleSuffix || 'Picture Book') + '</title>\n'
        + '<style>\n' + css + '\n</style>\n</head>\n<body>\n'
        + '  <div class="book">\n'
        + '    <div class="cover">\n'
        + (coverB64 ? '      <img src="' + coverB64 + '" alt="Cover" />\n' : '')
        + '      <div class="cover-overlay">\n'
        + '        <h1>' + escHtml(title) + '</h1>\n'
        + '        <p>' + pages.length + ' ' + noun + (pages.length !== 1 ? 's' : '') + '</p>\n'
        + '      </div>\n'
        + '    </div>\n'
        + sections
        + '  </div>\n</body>\n</html>';

    let blob = new Blob([html], { type: 'text/html' });
    let url = URL.createObjectURL(blob);
    let a = document.createElement('a');
    a.href = url;
    a.download = (title || opts.nameFallback || 'book').replace(/[^a-zA-Z0-9_-]/g, '_') + (opts.nameSuffix || '-book.html');
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);

    if (opts.toast) page.toast('success', opts.toast);
}

// ── Nav helper ─────────────────────────────────────────────────────────

function buildNav(a) {
    let pages = a.pages || [];
    let total = pages.length + 1; // cover + pages
    let state = a.state;
    return {
        pages: pages,
        total: total,
        currentPage: state.currentPage,
        fullscreen: !!state.fullscreen,
        goToPage: function (n) {
            state.currentPage = Math.max(0, Math.min(n, total - 1));
            m.redraw();
        },
        setFullscreen: function (v) { state.fullscreen = !!v; m.redraw(); },
        toggleFullscreen: function () { state.fullscreen = !state.fullscreen; m.redraw(); }
    };
}

function renderHeader(a, nav, exporting, doExport) {
    let pages = a.pages || [];
    let noun = a.pageNoun || 'Page';
    let cp = nav.currentPage;
    let fs = nav.fullscreen;
    let pageLabel = cp === 0 ? (a.coverLabel || 'Cover') : (noun + ' ' + cp + ' of ' + pages.length);

    return m('div', { class: a.headerClass || 'flex items-center gap-3 mb-4' }, [
        (a.onBack && !fs) ? m('button', {
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300',
            title: a.backTitle || 'Back',
            onclick: a.onBack
        }, m('span', { class: 'material-symbols-outlined' }, 'arrow_back')) : null,

        m('button', {
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300 disabled:opacity-30',
            disabled: cp === 0,
            onclick: function () { nav.goToPage(cp - 1); }
        }, m('span', { class: 'material-symbols-outlined' }, 'chevron_left')),

        m('div', { class: 'flex-1 text-center' }, [
            m('span', { class: 'font-semibold text-sm' }, a.title),
            m('span', { class: 'text-gray-400 text-xs ml-2' }, pageLabel)
        ]),

        m('button', {
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300 disabled:opacity-30',
            disabled: cp >= nav.total - 1,
            onclick: function () { nav.goToPage(cp + 1); }
        }, m('span', { class: 'material-symbols-outlined' }, 'chevron_right')),

        a.actionsLeft ? a.actionsLeft(nav) : null,

        a.enableExport !== false ? m('button', {
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300',
            title: 'Export as HTML',
            disabled: exporting || !pages.length,
            onclick: function () { doExport(a); }
        }, m('span', { class: 'material-symbols-outlined text-lg' }, exporting ? 'hourglass_empty' : 'download')) : null,

        a.actionsRight ? a.actionsRight(nav) : null,

        a.enableFullscreen !== false ? m('button', {
            class: 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300',
            title: fs ? 'Exit fullscreen' : 'Fullscreen',
            onclick: function () { nav.toggleFullscreen(); }
        }, m('span', { class: 'material-symbols-outlined text-lg' }, fs ? 'fullscreen_exit' : 'fullscreen')) : null
    ]);
}

function renderDots(a, nav) {
    let total = nav.total;
    if (total <= 1) return null;
    let noun = a.pageNoun || 'Page';
    let cp = nav.currentPage;
    return m('div', { class: 'flex justify-center gap-2 mt-4 py-2' },
        Array.from({ length: total }, function (_, i) {
            let active = i === cp;
            return m('button', {
                key: i,
                class: 'w-2.5 h-2.5 rounded-full transition-colors ' +
                    (active
                        ? 'bg-blue-500'
                        : 'bg-gray-300 dark:bg-gray-600 hover:bg-gray-400 dark:hover:bg-gray-500'),
                title: i === 0 ? (a.coverLabel || 'Cover') : (noun + ' ' + i),
                onclick: function () { nav.goToPage(i); }
            });
        })
    );
}

// ── Component (closure — per-instance export flag + keyboard binding) ──

export const ReaderShell = function () {
    let exporting = false;
    let keyHandler = null;
    let latestAttrs = null;

    async function doExport(a) {
        if (exporting || !(a.pages || []).length) return;
        exporting = true;
        m.redraw();
        try {
            await exportBook({
                pages: a.pages,
                title: a.title,
                imageUrlFor: a.imageUrlFor,
                coverImageUrl: a.coverImageUrl,
                countNoun: a.exportCountNoun || a.pageNoun,
                buildPageHtml: a.exportPageHtml,
                css: a.exportCss ? a.exportCss() : null,
                titleSuffix: a.exportTitleSuffix,
                nameFallback: a.exportNameFallback,
                nameSuffix: a.exportNameSuffix,
                toast: a.exportToast
            });
        } finally {
            exporting = false;
            m.redraw();
        }
    }

    function onKey(e) {
        let a = latestAttrs;
        if (!a) return;
        // Don't hijack arrows/Home/End while the user is typing in a form control (e.g. ChapBook's
        // render-config dialog sits over the reader). PictureBook's reader has no such inputs, so this
        // guard never fires there — its behavior is unchanged.
        let t = e.target;
        if (t) {
            let tag = (t.tagName || '').toUpperCase();
            if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || t.isContentEditable) return;
        }
        let nav = buildNav(a);
        if (e.key === 'ArrowRight' || e.key === 'Right') { e.preventDefault(); nav.goToPage(nav.currentPage + 1); }
        else if (e.key === 'ArrowLeft' || e.key === 'Left') { e.preventDefault(); nav.goToPage(nav.currentPage - 1); }
        else if (e.key === 'Home') { e.preventDefault(); nav.goToPage(0); }
        else if (e.key === 'End') { e.preventDefault(); nav.goToPage(nav.total - 1); }
        else if (e.key === 'Escape' && nav.fullscreen) { e.preventDefault(); nav.setFullscreen(false); }
    }

    return {
        oncreate: function () {
            keyHandler = onKey;
            document.addEventListener('keydown', keyHandler);
        },
        onremove: function () {
            if (keyHandler) document.removeEventListener('keydown', keyHandler);
        },
        view: function (vnode) {
            let a = vnode.attrs;
            latestAttrs = a;
            let nav = buildNav(a);
            let pages = a.pages || [];
            let fs = nav.fullscreen;
            let containerClass = fs
                ? 'fixed inset-0 z-50 bg-gray-900 text-white overflow-y-auto p-6'
                : (a.containerClass || 'p-4 flex flex-col h-full');

            let body;
            if (a.loading) body = a.renderLoading ? a.renderLoading() : null;
            else if (a.error) body = a.renderError ? a.renderError(a.error) : null;
            else if (pages.length === 0) body = a.renderEmpty ? a.renderEmpty() : null;
            else {
                let cp = nav.currentPage;
                body = m('div', { class: a.bodyClass || 'flex-1 overflow-y-auto max-w-3xl mx-auto w-full' }, [
                    cp === 0
                        ? (a.renderCover ? a.renderCover(nav) : null)
                        : (a.renderPage ? a.renderPage(pages[cp - 1], cp, nav) : null),
                    renderDots(a, nav)
                ]);
            }

            return m('div', { class: containerClass }, [
                renderHeader(a, nav, exporting, doExport),
                body
            ]);
        }
    };
};
