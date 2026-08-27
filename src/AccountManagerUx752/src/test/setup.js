// Mithril's mount-redraw captures `schedule = requestAnimationFrame` at import time
// (mithril/mount-redraw.js), falling back to null in a non-DOM env. When an m.request
// completes it calls that schedule and throws "schedule is not a function". Define a
// scheduler on the global BEFORE any test imports mithril so the capture succeeds.
// (Do NOT import mithril here — an ESM import would hoist above this assignment.)
if (typeof globalThis.requestAnimationFrame !== 'function') {
    globalThis.requestAnimationFrame = (cb) => setTimeout(() => cb(Date.now()), 0);
    globalThis.cancelAnimationFrame = (id) => clearTimeout(id);
}
