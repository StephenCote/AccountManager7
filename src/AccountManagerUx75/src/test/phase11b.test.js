import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// --- 11b-1 & 11b-2: List control with group nav + search ---
import { newListControl } from '../views/list.js';

describe('List Control (Phase 11b)', () => {
    it('exports newListControl factory', () => {
        expect(typeof newListControl).toBe('function');
    });

    it('newListControl returns object with view and renderContent', () => {
        let ctrl = newListControl();
        expect(ctrl.view).toBeDefined();
        expect(typeof ctrl.view.oninit).toBe('function');
        expect(typeof ctrl.view.oncreate).toBe('function');
        expect(typeof ctrl.view.onupdate).toBe('function');
        expect(typeof ctrl.view.onremove).toBe('function');
        expect(typeof ctrl.view.view).toBe('function');
        expect(typeof ctrl.renderContent).toBe('function');
    });

    it('newListControl returns pagination accessor', () => {
        let ctrl = newListControl();
        expect(typeof ctrl.pagination).toBe('function');
        let pg = ctrl.pagination();
        expect(pg).toBeDefined();
        expect(typeof pg.update).toBe('function');
        expect(typeof pg.new).toBe('function');
    });

    it('exposes _testHelpers with search, nav, carousel, and toggle functions', () => {
        let ctrl = newListControl();
        expect(typeof ctrl._testHelpers).toBe('function');
        let helpers = ctrl._testHelpers();
        // Original helpers
        expect(typeof helpers.doSearch).toBe('function');
        expect(typeof helpers.clearSearch).toBe('function');
        expect(typeof helpers.navigateToChildGroup).toBe('function');
        expect(typeof helpers.renderGroupBreadcrumb).toBe('function');
        expect(typeof helpers.renderChildGroups).toBe('function');
        // Carousel helpers (Fix C)
        expect(typeof helpers.openItem).toBe('function');
        expect(typeof helpers.closeSelected).toBe('function');
        expect(typeof helpers.toggleCarousel).toBe('function');
        expect(typeof helpers.moveCarousel).toBe('function');
        expect(typeof helpers.moveCarouselTo).toBe('function');
        // Toggle helpers (Fix D, E, F)
        expect(typeof helpers.toggleContainer).toBe('function');
        expect(typeof helpers.toggleInfo).toBe('function');
        expect(typeof helpers.listSystemType).toBe('function');
        // Column defaults (Fix B)
        expect(typeof helpers.getColumns).toBe('function');
    });
});

// --- 11b-3: Explorer view ---
import { newExplorerControl } from '../views/explorer.js';

describe('Explorer Control (Phase 11b-3)', () => {
    it('exports newExplorerControl factory', () => {
        expect(typeof newExplorerControl).toBe('function');
    });

    it('newExplorerControl returns object with view and renderContent', () => {
        let ctrl = newExplorerControl();
        expect(ctrl.view).toBeDefined();
        expect(typeof ctrl.view.oninit).toBe('function');
        expect(typeof ctrl.view.onremove).toBe('function');
        expect(typeof ctrl.view.view).toBe('function');
        expect(typeof ctrl.renderContent).toBe('function');
    });

    it('newExplorerControl has toggleFullMode', () => {
        let ctrl = newExplorerControl();
        expect(typeof ctrl.toggleFullMode).toBe('function');
    });

    it('newExplorerControl has editItem', () => {
        let ctrl = newExplorerControl();
        expect(typeof ctrl.editItem).toBe('function');
    });

    it('newExplorerControl has cancelView', () => {
        let ctrl = newExplorerControl();
        expect(typeof ctrl.cancelView).toBe('function');
    });
});

// --- Search and nav function existence ---
describe('Search and nav helpers', () => {
    it('doSearch function exists and is callable', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        expect(typeof helpers.doSearch).toBe('function');
        expect(helpers.doSearch.length).toBe(1); // takes 1 param (value)
    });

    it('clearSearch function exists and is callable', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        expect(typeof helpers.clearSearch).toBe('function');
        expect(helpers.clearSearch.length).toBe(0); // no params
    });

    it('navigateToChildGroup function exists', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        expect(typeof helpers.navigateToChildGroup).toBe('function');
        expect(helpers.navigateToChildGroup.length).toBe(1); // takes 1 param (group)
    });
});

// --- Group breadcrumb and child groups rendering ---
describe('Group breadcrumb', () => {
    it('renderGroupBreadcrumb returns null when no path loaded', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        let result = helpers.renderGroupBreadcrumb();
        expect(result).toBeNull();
    });

    it('renderChildGroups returns null when no groups loaded', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        let result = helpers.renderChildGroups();
        expect(result).toBeNull();
    });
});

// --- Carousel functions (Fix C) ---
describe('Carousel functions', () => {
    it('openItem is a function that takes 1 param', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        expect(typeof helpers.openItem).toBe('function');
        expect(helpers.openItem.length).toBe(1);
    });

    it('closeSelected is callable with no params', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        expect(typeof helpers.closeSelected).toBe('function');
        expect(helpers.closeSelected.length).toBe(0);
    });

    it('toggleCarousel is callable with no params', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        expect(typeof helpers.toggleCarousel).toBe('function');
        expect(helpers.toggleCarousel.length).toBe(0);
    });

    it('moveCarousel takes a delta param', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        expect(typeof helpers.moveCarousel).toBe('function');
        expect(helpers.moveCarousel.length).toBe(1);
    });

    it('moveCarouselTo takes an index param', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        expect(typeof helpers.moveCarouselTo).toBe('function');
        expect(helpers.moveCarouselTo.length).toBe(1);
    });
});

// --- Per-model column defaults (Fix B) ---
describe('Per-model column defaults', () => {
    it('getColumns returns model-specific columns for data.data', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        let cols = helpers.getColumns('data.data');
        expect(cols).toContain('name');
        expect(cols).toContain('contentType');
    });

    it('getColumns returns model-specific columns for olio.charPerson', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        let cols = helpers.getColumns('olio.charPerson');
        expect(cols).toContain('name');
        expect(cols).toContain('firstName');
        expect(cols).toContain('lastName');
    });

    it('getColumns returns model-specific columns for auth.group', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        let cols = helpers.getColumns('auth.group');
        expect(cols).toContain('name');
        expect(cols).toContain('type');
        expect(cols).toContain('path');
    });

    it('getColumns returns fallback columns for unknown type', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        let cols = helpers.getColumns('some.unknown.type');
        expect(cols).toContain('name');
        expect(cols.length).toBeGreaterThanOrEqual(1);
    });
});

// --- Toggle functions (Fix D, E, F) ---
describe('Toggle functions', () => {
    it('toggleContainer is callable', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        expect(typeof helpers.toggleContainer).toBe('function');
    });

    it('toggleInfo is callable', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        expect(typeof helpers.toggleInfo).toBe('function');
    });

    it('listSystemType is callable', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        expect(typeof helpers.listSystemType).toBe('function');
    });
});

// --- closeView (ported from Ux7) ---
describe('closeView', () => {
    it('listPage has closeView function', () => {
        let ctrl = newListControl();
        expect(typeof ctrl.closeView).toBe('function');
    });
});
