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

    it('exposes _testHelpers with navigation, carousel, and toggle functions', () => {
        let ctrl = newListControl();
        expect(typeof ctrl._testHelpers).toBe('function');
        let helpers = ctrl._testHelpers();
        // Navigation
        expect(typeof helpers.doFilter).toBe('function');
        expect(typeof helpers.navigateUp).toBe('function');
        expect(typeof helpers.navigateDown).toBe('function');
        // renderGroupBreadcrumb removed — breadcrumb is now in navigation.js
        // Carousel helpers
        expect(typeof helpers.openItem).toBe('function');
        expect(typeof helpers.closeSelected).toBe('function');
        expect(typeof helpers.toggleCarousel).toBe('function');
        expect(typeof helpers.moveCarousel).toBe('function');
        expect(typeof helpers.moveCarouselTo).toBe('function');
        expect(typeof helpers.getCurrentResults).toBe('function');
        // Toggle helpers
        expect(typeof helpers.toggleContainer).toBe('function');
        expect(typeof helpers.toggleInfo).toBe('function');
        expect(typeof helpers.listSystemType).toBe('function');
        expect(typeof helpers.toggleGrid).toBe('function');
        // Controller interface
        expect(typeof helpers.getListController).toBe('function');
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

// --- Filter and navigation helpers ---
describe('Filter and nav helpers', () => {
    it('doFilter function exists and is callable', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        expect(typeof helpers.doFilter).toBe('function');
    });

    it('navigateUp function exists and is callable', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        expect(typeof helpers.navigateUp).toBe('function');
    });

    it('navigateDown function exists and is callable', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        expect(typeof helpers.navigateDown).toBe('function');
    });
});

// --- Group breadcrumb ---
// Breadcrumb is now a component in navigation.js (Ux7 pattern), not in list.js

// --- Carousel functions ---
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

// --- Per-model column defaults (now in decorator.js getHeaders) ---
import { am7decorator, getHeaders } from '../components/decorator.js';

describe('Per-model column defaults (decorator getHeaders)', () => {
    it('getHeaders returns columns for data.data', () => {
        let cols = getHeaders('data.data');
        expect(cols).toContain('name');
        expect(cols).toContain('contentType');
    });

    it('getHeaders returns columns for auth.group', () => {
        let cols = getHeaders('auth.group');
        expect(cols).toContain('name');
        expect(cols).toContain('type');
        expect(cols).toContain('path');
    });

    it('am7decorator.map returns default header map', () => {
        let map = am7decorator.map();
        expect(Array.isArray(map)).toBe(true);
        expect(map).toContain('name');
    });
});

// --- Toggle functions ---
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

// --- getListController (ported from Ux7) ---
describe('getListController', () => {
    it('returns controller interface matching Ux7 shape', () => {
        let ctrl = newListControl();
        let helpers = ctrl._testHelpers();
        let ctl = helpers.getListController();
        expect(ctl).toBeDefined();
        expect(typeof ctl.results).toBe('function');
        expect(typeof ctl.edit).toBe('function');
        expect(typeof ctl.move).toBe('function');
        expect(typeof ctl.moveTo).toBe('function');
        expect(typeof ctl.select).toBe('function');
        expect(typeof ctl.open).toBe('function');
        expect(typeof ctl.down).toBe('function');
        expect(typeof ctl.onscroll).toBe('function');
        expect(typeof ctl.toggleCarousel).toBe('function');
        expect(typeof ctl.toggleCarouselFull).toBe('function');
        expect(typeof ctl.toggleCarouselMax).toBe('function');
        expect(typeof ctl.toggleInfo).toBe('function');
        expect(ctl.pagination).toBeDefined();
        expect(ctl.listPage).toBeDefined();
    });
});

// --- closeView (ported from Ux7) ---
describe('closeView', () => {
    it('listPage has closeView function', () => {
        let ctrl = newListControl();
        expect(typeof ctrl.closeView).toBe('function');
    });
});
