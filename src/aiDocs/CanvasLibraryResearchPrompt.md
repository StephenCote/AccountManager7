# Research prompt — canvas/graph library for the PictureBook 2.0 pre-production board

Paste the block below into a **web-enabled** Claude session. It is self-contained.

Context it encodes: `AccountManagerUx752` is Vite 6 + **Mithril 2.2.8**, Tailwind 3, no graph or canvas
library today (deps: `mithril`, `marked`, `material-icons`, `material-symbols`, `file-icon-vectors`,
`pdfjs-dist`, `sql.js`, `turndown`). Stephen's steer: **use an npm library, likely direct-canvas.**

---

## PROMPT (copy from here)

I need a recommendation for an npm library to build an interactive "pre-production board" canvas in an
existing web app. Please research current (2026) options and give me a concrete recommendation with
trade-offs. Prioritise libraries that are actively maintained, permissively licensed, and small.

### The stack — this is the hard constraint

- **Mithril 2.2.8** (not React, not Vue, not Svelte). Vanilla-JS component model with its own redraw
  lifecycle (`m.redraw()`, `oncreate`/`onupdate`/`onremove` lifecycle hooks on vnodes).
- Vite 6 build, Tailwind 3 for styling, plain JS (no TypeScript requirement, TS types welcome).
- Corporate environment: **license matters** (MIT/Apache-2.0/BSD strongly preferred; avoid GPL/AGPL and
  avoid anything with a commercial-use clause such as some "open core" diagramming SDKs).
- Bundle size matters. The app currently has no canvas/graph dependency at all, so this is net-new weight.

**Most React-oriented graph libraries are disqualified or need a wrapper.** Please be explicit about
which candidates are framework-agnostic (usable from plain JS / imperative API) versus React-only, and
do not recommend a React-only library without saying what wrapping it from Mithril would actually cost.

### What the board must do

A freeform spatial "casting and style" board, similar to the node boards in tools like PAI, tldraw or
Figma's canvas — but much simpler:

1. **Infinite, pannable, zoomable plane** with a dotted-grid background.
2. **Cards positioned at arbitrary x/y** with varying sizes and aspect ratios — tall portraits and wide
   landscapes side by side. Not a grid, not a list, not an auto-layout graph.
3. Each card shows **an image thumbnail** (generated art, 512-1024px source), a small **type badge**, and
   a **text handle** like `character_@johan` or `image_style_bible`.
4. **Entity chips** beneath cards (small coloured pills naming characters).
5. **Drag to arrange**; positions persist to a backend (x/y/w/h per card).
6. **Select a card**, and per-card actions (regenerate, pin, compare variants) via a popover.
7. Several **candidate variants per handle** — the board is a casting call: generate N, compare, pick one.
8. **Edges are optional.** The reference UI draws *no visible connecting lines at all*, relying on handles
   and chips instead. So automatic graph layout (dagre/elk/force-directed) is **not** required, and I'd
   rather not pay for it. If a library's main value is layout, it is probably the wrong fit.

### Scale

Tens of cards typically, low hundreds worst case. Each card has an image. This is well within DOM
capability, so I want an honest answer on whether a library is needed at all.

### The specific questions

1. **Canvas/WebGL vs DOM.** At this scale, with image-bearing cards and text that must stay crisp and
   selectable, is a `<canvas>`-based library actually better than absolutely-positioned DOM elements
   under a single CSS `transform: translate() scale()` wrapper plus SVG for any optional edges? Give the
   real trade-offs: text rendering quality, accessibility, hit-testing, image decode/memory, zoom
   sharpness, and how much code the DOM approach actually costs to get pan/zoom/drag right (inertia,
   pinch-zoom, trackpad vs wheel, zoom-to-cursor, touch).
2. **Concrete candidates.** For each: npm name, weekly downloads, last publish, license, minified+gzipped
   size, framework coupling, and whether it is imperative (mountable into a DOM node Mithril owns) or
   declarative/React-bound. Please consider at least: `konva`, `fabric`, `pixi.js`, `@tldraw/*`,
   `jointjs`, `cytoscape`, `sigma`, `@antv/x6`, `panzoom` / `@panzoom/panzoom`, `svg-pan-zoom`,
   `d3-zoom` (as a primitive rather than a whole framework), `reactflow`/`@xyflow` (note licensing and
   framework coupling), and anything current I have missed.
3. **The minimal-dependency option.** What would it take to use only a small pan/zoom primitive (e.g.
   `d3-zoom` or `@panzoom/panzoom`) over DOM cards, and hand-roll the rest? Sketch the approach and be
   honest about which of the fiddly parts (pinch-zoom, zoom-to-cursor, drag-vs-pan disambiguation,
   momentum, coordinate conversion between screen and world space) that primitive does and does not
   solve.
4. **Mithril integration pattern.** For the top recommendation, show a small concrete example of mounting
   it inside a Mithril component: which lifecycle hooks to use, how to avoid Mithril's virtual-DOM diff
   fighting the library's own DOM mutations (typically a container vnode where Mithril must not manage
   children), and how to feed changes back into Mithril state without redraw loops.
5. **Persistence shape.** Card geometry will be stored server-side as four integers per card
   (`canvasX`, `canvasY`, `canvasW`, `canvasH`). Any reason a given library would fight that — e.g.
   because it owns its own scene-graph serialization format?
6. **Accessibility.** Cards need keyboard reachability and screen-reader labels; the project runs
   `@axe-core/playwright`. Which approach makes that achievable rather than an afterthought? This is a
   real point in favour of DOM that I want tested, not assumed.

### Output I want

- A single clear **recommendation** with a one-paragraph justification.
- A **runner-up** and the condition under which I should prefer it.
- A short **comparison table** (name, license, size, framework coupling, canvas vs DOM, maintenance).
- The **minimal integration sketch** for the recommendation, in plain JS against Mithril 2.
- Any library you would explicitly **rule out**, and why — especially licensing traps and abandoned
  packages with healthy-looking download counts.

Be skeptical of popularity as a proxy for fit here: the requirement is unusually simple (no auto-layout,
no edge routing, few nodes) and I would rather add 5KB than 300KB. If the honest answer is "you don't
need a library for this," say so and make the case.
