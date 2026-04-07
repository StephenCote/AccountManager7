/**
 * ImageViewer — shared full-canvas image viewer used by gallery dialog and list carousel.
 *
 * Two modes:
 *   1. Grid+Preview: thumbnail grid with preview pane (gallery dialog, list icon mode)
 *   2. Full Canvas: single image fills viewport, clean bg, nav arrows, grow toggle
 *
 * Ux7 carousel CSS pattern adapted to Tailwind.
 */
import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { am7model } from '../core/model.js';

// GIFs/WebP use full media URL to preserve animation; others use thumbnails
function thumbUrl(img, size) {
    if (img.contentType && (img.contentType.match(/gif$/) || img.contentType.match(/webp$/))) {
        return am7client.mediaDataPath(img, false);
    }
    return am7client.mediaDataPath(img, true, size || '96x96');
}

// ── Content-type-aware renderer ─────────────────────────────────────
// Returns a vnode for the given item based on its contentType.
// opts.maxClass — CSS classes for max sizing (e.g., 'max-w-full max-h-72')
// opts.fullSize — if true, fit to full viewport
// opts.clickable — if true, add cursor-pointer + onclick
// opts.onclick — click handler
function renderContent(item, opts) {
    if (!item) return m('div', { class: 'text-gray-400 italic text-sm' }, 'No content');
    opts = opts || {};
    let ct = item.contentType || '';
    let path = am7client.mediaDataPath(item, false);
    let maxCls = opts.maxClass || 'max-w-full max-h-72';
    let clickCls = opts.clickable ? ' cursor-pointer hover:opacity-90' : '';
    let clickFn = opts.onclick || null;

    if (ct.match(/^image/)) {
        let fit = opts.grow ? 'cover' : 'contain';
        return m('img', {
            src: path,
            class: (opts.fullSize ? 'w-full h-full' : maxCls) + ' rounded shadow' + clickCls,
            style: 'object-fit:' + fit,
            alt: item.name || 'Image',
            onclick: clickFn
        });
    }
    if (ct.match(/^video/)) {
        return m('video', {
            class: maxCls + ' rounded',
            style: opts.fullSize ? 'width:100%;height:100%' : '',
            preload: 'auto', controls: true
        }, [m('source', { src: path, type: ct })]);
    }
    if (ct.match(/^audio/)) {
        let mt = ct.match(/mpeg3$/) ? 'audio/mpeg' : ct;
        return m('div', { class: 'flex flex-col items-center justify-center gap-4' + (opts.fullSize ? ' w-full h-full' : '') }, [
            m('span', { class: 'material-symbols-outlined text-gray-300', style: 'font-size:96px' }, 'audio_file'),
            m('div', { class: 'text-sm text-gray-500' }, item.name || ''),
            m('audio', { class: 'w-full max-w-lg', preload: 'auto', controls: true }, [
                m('source', { src: path, type: mt })
            ])
        ]);
    }
    if (ct.match(/pdf$/)) {
        return m('iframe', {
            class: opts.fullSize ? 'w-full h-full border-0' : 'w-full h-96 border rounded',
            src: path, type: 'application/pdf'
        });
    }
    // Fallback: content type icon
    let type = am7model.getModel(item[am7model.jsonModelKey]);
    let ico = (type && type.icon) ? type.icon : 'description';
    let iconSize = opts.fullSize ? '192px' : '96px';
    return m('div', {
        class: 'flex flex-col items-center justify-center gap-2' + (opts.fullSize ? ' w-full h-full' : '') + clickCls,
        onclick: clickFn
    }, [
        m('span', { class: 'material-symbols-outlined text-gray-300', style: 'font-size:' + iconSize }, ico),
        m('div', { class: 'text-sm text-gray-500 truncate max-w-xs' }, item.name || ''),
        ct ? m('div', { class: 'text-xs text-gray-400' }, ct) : null
    ]);
}

// ── Full Canvas Viewer ──────────────────────────────────────────────
// Renders a single image on a clean canvas (white light / black dark).
// Controls: close (top-right), grow (top-left), prev/next (sides).
//
// opts:
//   images      — array of image objects (need name, groupPath, contentType, organizationPath)
//   index       — current index (mutable — viewer updates it)
//   onClose     — called when viewer is closed
//   onIndexChange(i) — called when index changes
//   charInst    — if set, show "Set Profile" button
//   onSetProfile(img) — called to set profile portrait
//   onDelete(img, idx) — called to delete an image
//   onAutoTag(img) — called to auto-tag an image

// Portal-based viewer: mounts directly on document.body so parent transforms/overflow
// cannot clip or constrain the full-viewport overlay.
function FullCanvasViewer() {
    let grow = false;
    let portalEl = null;

    function renderPortal(opts) {
        let images = opts.images || [];
        let idx = opts.index || 0;
        let img = images[idx];
        if (!img || !portalEl) return;

        let imgUrl = am7client.mediaDataPath(img, false);

        function nav(delta) {
            let next = idx + delta;
            if (next >= 0 && next < images.length) {
                if (opts.onIndexChange) opts.onIndexChange(next);
                m.redraw();
            } else if (next >= images.length && opts.onNextPage) {
                opts.onNextPage();
            } else if (next < 0 && opts.onPrevPage) {
                opts.onPrevPage();
            }
        }

        m.render(portalEl, m('div', {
            style: 'position:fixed;inset:0;z-index:9999;display:flex;align-items:center;justify-content:center;outline:none',
            class: 'bg-white dark:bg-black',
            tabindex: 0,
            oncreate: function(vn) { vn.dom.focus(); },
            onkeydown: function(e) {
                if (e.key === 'Escape') { if (opts.onClose) opts.onClose(); m.redraw(); e.preventDefault(); e.stopPropagation(); }
                else if (e.key === 'ArrowLeft') { nav(-1); e.preventDefault(); e.stopPropagation(); }
                else if (e.key === 'ArrowRight') { nav(1); e.preventDefault(); e.stopPropagation(); }
                else if (e.key === 'Delete' && opts.onDelete) { opts.onDelete(img, idx); e.preventDefault(); e.stopPropagation(); }
            }
        }, [
            renderContent(img, { fullSize: true, grow: grow, maxClass: 'max-w-full max-h-full' }),
            m('button', { class: 'iv-ctl', style: 'position:absolute;top:8px;right:12px', title: 'Close (Esc)',
                onclick: function() { if (opts.onClose) opts.onClose(); m.redraw(); }
            }, m('span', { class: 'material-symbols-outlined' }, 'close')),
            m('button', { class: 'iv-ctl', style: 'position:absolute;top:8px;left:12px', title: grow ? 'Fit to screen' : 'Fill screen',
                onclick: function() { grow = !grow; renderPortal(opts); }
            }, m('span', { class: 'material-symbols-outlined' }, grow ? 'photo_size_select_small' : 'aspect_ratio')),
            m('div', { style: 'position:absolute;top:8px;left:50%;transform:translateX(-50%);font-size:0.875rem;padding:2px 12px;border-radius:4px;pointer-events:none;max-width:60%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap',
                class: 'text-gray-500 dark:text-gray-400 bg-white/80 dark:bg-black/80'
            }, img.name || ''),
            m('div', { style: 'position:absolute;bottom:12px;left:50%;transform:translateX(-50%);font-size:0.75rem;padding:2px 8px;border-radius:4px;pointer-events:none',
                class: 'text-gray-400 dark:text-gray-500 bg-white/80 dark:bg-black/80'
            }, (idx + 1) + ' / ' + images.length + (opts.pageInfo ? '  (page ' + opts.pageInfo.current + '/' + opts.pageInfo.total + ')' : '')),
            (idx > 0 || opts.onPrevPage) ? m('button', { class: 'iv-ctl', style: 'position:absolute;top:50%;left:12px;transform:translateY(-50%)',
                onclick: function() { nav(-1); }
            }, m('span', { class: 'material-symbols-outlined text-3xl' }, 'arrow_back')) : null,
            (idx < images.length - 1 || opts.onNextPage) ? m('button', { class: 'iv-ctl', style: 'position:absolute;top:50%;right:12px;transform:translateY(-50%)',
                onclick: function() { nav(1); }
            }, m('span', { class: 'material-symbols-outlined text-3xl' }, 'arrow_forward')) : null
        ]));
    }

    return {
        oncreate: function(vnode) {
            portalEl = document.createElement('div');
            document.body.appendChild(portalEl);
            renderPortal(vnode.attrs);
        },
        onupdate: function(vnode) {
            renderPortal(vnode.attrs);
        },
        onremove: function() {
            if (portalEl) {
                m.render(portalEl, null);
                portalEl.remove();
                portalEl = null;
            }
            grow = false;
        },
        view: function() {
            // Portal renders on body — return empty placeholder in the vdom tree
            return m('div', { style: 'display:none' });
        }
    };
}

// ── Grid + Preview ──────────────────────────────────────────────────
// Thumbnail grid with selected image preview. Used inside gallery dialog and list icon mode.
//
// opts:
//   images       — array of image objects
//   index        — current selected index
//   onIndexChange(i)
//   onFullView() — called to enter full canvas mode
//   charInst     — if set, show "Set Profile" button
//   onSetProfile(img)
//   onDelete(img, idx)
//   onAutoTag(img)
//   detailLoader(img) — called to lazy-load details for selected image
//   tagsFor(img)      — returns tags array for an image
//   attrsFor(img)     — returns attributes array for an image

function GridPreview() {
    let gpRef = null;
    let _lastDetailOid = null;

    function triggerDetailLoad(vnode) {
        let opts = vnode.attrs;
        let images = opts.images || [];
        let idx = opts.index || 0;
        let sel = images[idx];
        if (sel && sel.objectId !== _lastDetailOid && opts.detailLoader) {
            _lastDetailOid = sel.objectId;
            opts.detailLoader(sel);
        }
    }

    return {
        oncreate: triggerDetailLoad,
        onupdate: triggerDetailLoad,
        view: function(vnode) {
            let opts = vnode.attrs;
            let images = opts.images || [];
            let idx = opts.index || 0;
            let sel = images[idx];

            let tags = sel && opts.tagsFor ? opts.tagsFor(sel) : [];
            let attrs = sel && opts.attrsFor ? opts.attrsFor(sel) : [];

            function nav(delta) {
                let next = idx + delta;
                if (next >= 0 && next < images.length) {
                    if (opts.onIndexChange) opts.onIndexChange(next);
                } else if (next >= images.length && opts.onNextPage) {
                    opts.onNextPage();
                } else if (next < 0 && opts.onPrevPage) {
                    opts.onPrevPage();
                }
            }

            return m('div', {
                class: 'grid-preview-container flex flex-col h-full overflow-hidden outline-none',
                tabindex: 0,
                oncreate: function(vn) { gpRef = vn.dom; vn.dom.focus(); },
                onremove: function() { gpRef = null; },
                onkeydown: function(e) {
                    if (e.key === 'ArrowLeft') { nav(-1); e.preventDefault(); e.stopPropagation(); }
                    else if (e.key === 'ArrowRight') { nav(1); e.preventDefault(); e.stopPropagation(); }
                    else if (e.key === 'Escape') { e.stopPropagation(); }
                    else if (e.key === 'Delete' && sel && opts.onDelete) { opts.onDelete(sel, idx); e.preventDefault(); }
                }
            }, [
                // Preview pane — content-type-aware
                sel ? m('div', { class: 'flex gap-3 p-2 border-b border-gray-200 dark:border-gray-700 shrink-0 overflow-hidden min-w-0' }, [
                    m('div', { class: 'flex-shrink-0 cursor-pointer', style: 'max-width:50%',
                        onclick: function() { if (opts.onFullView) opts.onFullView(); }
                    },
                        renderContent(sel, { maxClass: 'max-w-full max-h-72' })
                    ),
                    m('div', { class: 'flex-1 min-w-0 overflow-hidden' }, [
                        m('div', { class: 'text-sm font-medium text-gray-800 dark:text-white mb-1 truncate' }, sel.name),
                        // Action buttons
                        m('div', { class: 'flex flex-wrap gap-1 mb-2' }, [
                            m('a', {
                                class: 'iv-btn bg-gray-100 dark:bg-gray-800 text-gray-700 dark:text-gray-300 hover:bg-gray-200',
                                href: '#!/view/data.data/' + sel.objectId,
                                target: '_blank',
                                onclick: function(e) { e.stopPropagation(); }
                            }, [m('span', { class: 'material-symbols-outlined', style: 'font-size:14px' }, 'open_in_new'), 'Open']),
                            opts.onFullView ? m('button', {
                                class: 'iv-btn bg-gray-100 dark:bg-gray-800 text-gray-700 dark:text-gray-300 hover:bg-gray-200',
                                onclick: function() { opts.onFullView(); }
                            }, [m('span', { class: 'material-symbols-outlined', style: 'font-size:14px' }, 'fullscreen'), 'View']) : null,
                            opts.charInst ? m('button', {
                                class: 'iv-btn bg-blue-100 dark:bg-blue-900 text-blue-700 dark:text-blue-300 hover:bg-blue-200',
                                onclick: function() { if (opts.onSetProfile) opts.onSetProfile(sel); }
                            }, [m('span', { class: 'material-symbols-outlined', style: 'font-size:14px' }, 'account_circle'), 'Set Profile']) : null,
                            opts.onAutoTag ? m('button', {
                                class: 'iv-btn bg-green-100 dark:bg-green-900 text-green-700 dark:text-green-300 hover:bg-green-200',
                                onclick: function() { opts.onAutoTag(sel); }
                            }, [m('span', { class: 'material-symbols-outlined', style: 'font-size:14px' }, 'label'), 'Auto-Tag']) : null,
                            opts.onDelete ? m('button', {
                                class: 'iv-btn bg-red-100 dark:bg-red-900 text-red-700 dark:text-red-300 hover:bg-red-200',
                                onclick: function() { opts.onDelete(sel, idx); }
                            }, [m('span', { class: 'material-symbols-outlined', style: 'font-size:14px' }, 'delete'), 'Delete']) : null
                        ]),
                        sel.description ? m('div', {
                            class: 'text-xs text-gray-500 italic mb-1',
                            style: 'display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere;word-break:break-word'
                        }, sel.description) : null,
                        tags.length > 0 ? m('div', { class: 'flex flex-wrap gap-1 mb-1' }, tags.map(function(t) {
                            let label = (t && typeof t === 'object') ? (t.name || t.objectId || '') : String(t || '');
                            return m('span', { class: 'px-2 py-0.5 rounded-full text-xs bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-300' }, label);
                        })) : null,
                        attrs.length > 0 ? m('div', { class: 'flex flex-wrap gap-1 mb-1' }, attrs.map(function(a) {
                            return m('span', { class: 'px-2 py-0.5 rounded-full text-xs bg-indigo-100 dark:bg-indigo-900 text-indigo-700 dark:text-indigo-300' }, String(a.name || '') + ': ' + String(a.value || ''));
                        })) : null,
                        m('div', { class: 'text-xs text-gray-400 mt-1' }, [
                            '← → to navigate, click image for full view',
                            opts.pageInfo ? m('span', { class: 'ml-2' }, '(page ' + opts.pageInfo.current + '/' + opts.pageInfo.total + ')') : null
                        ])
                    ])
                ]) : m('div', { class: 'p-4 text-gray-400 text-sm italic' }, 'No images'),
                // Thumbnail grid — scrollable, lazy-loaded
                m('div', { class: 'flex-1 overflow-y-auto p-1' },
                    m('div', { class: 'grid grid-cols-6 sm:grid-cols-8 md:grid-cols-10 gap-1' },
                        images.map(function(img, i) {
                            let ct = img.contentType || '';
                            let isImage = ct.match(/^image/);
                            let tile;
                            if (isImage) {
                                tile = m('img', { src: thumbUrl(img, '96x96'), loading: 'lazy', class: 'w-full aspect-square object-cover' });
                            } else {
                                let type = am7model.getModel(img[am7model.jsonModelKey]);
                                let ico = (type && type.icon) ? type.icon : 'description';
                                if (ct.match(/pdf$/)) ico = 'picture_as_pdf';
                                else if (ct.match(/^video/)) ico = 'movie';
                                else if (ct.match(/^audio/)) ico = 'audio_file';
                                tile = m('div', { class: 'w-full aspect-square flex items-center justify-center bg-gray-100 dark:bg-gray-800' },
                                    m('span', { class: 'material-symbols-outlined text-gray-400', style: 'font-size:36px' }, ico));
                            }
                            return m('div', {
                                key: 'iv-' + (img.objectId || i),
                                class: 'cursor-pointer rounded overflow-hidden border-2 ' + (i === idx ? 'border-blue-500' : 'border-transparent hover:border-gray-300'),
                                onclick: function() { if (opts.onIndexChange) opts.onIndexChange(i); }
                            }, tile);
                        })
                    )
                )
            ]);
        }
    };
}

// CSS for iv-ctl and iv-btn — injected once
let _cssInjected = false;
function injectCSS() {
    if (_cssInjected) return;
    _cssInjected = true;
    let style = document.createElement('style');
    style.textContent = `
        .iv-ctl {
            position: absolute;
            width: 2.5rem; height: 2.5rem;
            display: flex; align-items: center; justify-content: center;
            cursor: pointer; border-radius: 9999px; z-index: 10;
            color: rgba(100,100,100,0.7);
            background: rgba(255,255,255,0.5);
            transition: background 0.15s, color 0.15s;
        }
        .iv-ctl:hover { background: rgba(120,120,120,0.7); color: #fff; }
        @media (prefers-color-scheme: dark) {
            .iv-ctl { color: rgba(180,180,180,0.7); background: rgba(0,0,0,0.4); }
            .iv-ctl:hover { background: rgba(80,80,80,0.8); color: #fff; }
        }
        .dark .iv-ctl { color: rgba(180,180,180,0.7); background: rgba(0,0,0,0.4); }
        .dark .iv-ctl:hover { background: rgba(80,80,80,0.8); color: #fff; }
        .iv-btn {
            display: inline-flex; align-items: center; gap: 0.25rem;
            font-size: 0.75rem; padding: 0.25rem 0.5rem; border-radius: 0.25rem;
            text-decoration: none;
        }
    `;
    document.head.appendChild(style);
}

export { FullCanvasViewer, GridPreview, renderContent, thumbUrl, injectCSS };
export default { FullCanvasViewer, GridPreview, renderContent, thumbUrl, injectCSS };
