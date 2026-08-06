import { describe, it, expect, vi, beforeAll } from 'vitest';
import Base64 from '../core/base64.js';
import {
    SETUP_SERVERS,
    SETUP_ORGANIZATIONS,
    SETUP_MIN_PASSWORD_LENGTH,
    SETUP_CACHE_KEY,
    decideUnauthenticatedRoute,
    resolveUnauthenticatedRoute,
    readSetupCache,
    clearSetupCache,
    validateServerUrl,
    validatePassword,
    validateSetupForm,
    buildSetupPayload,
    serversToFields,
    tokenFromUrl
} from '../core/setupSupport.js';

function fakeStorage(initial) {
    let data = Object.assign({}, initial || {});
    return {
        getItem: (k) => (Object.prototype.hasOwnProperty.call(data, k) ? data[k] : null),
        setItem: (k, v) => { data[k] = "" + v; },
        removeItem: (k) => { delete data[k]; },
        _data: () => data
    };
}

function goodValues(over) {
    return Object.assign({
        adminPassword: "adminPassw0rd",
        adminPasswordConfirm: "adminPassw0rd",
        setupToken: "tok-123",
        initialUserName: "",
        initialUserPassword: "",
        initialUserOrganization: "/Public",
        serverSd: "",
        serverFace: "",
        serverTag: "",
        serverVoiceTts: "",
        serverVoiceStt: "",
        serverEmbedding: ""
    }, over || {});
}

describe('setup routing decision', () => {

    it('routes to /setup only when the server explicitly reports initialized === false', () => {
        expect(decideUnauthenticatedRoute({ initialized: false })).toBe("/setup");
    });

    it('routes to /sig when initialized === true', () => {
        expect(decideUnauthenticatedRoute({ initialized: true })).toBe("/sig");
    });

    it('routes to /sig when the state is missing or ambiguous (no oracle, no guessing)', () => {
        expect(decideUnauthenticatedRoute(null)).toBe("/sig");
        expect(decideUnauthenticatedRoute(undefined)).toBe("/sig");
        expect(decideUnauthenticatedRoute({})).toBe("/sig");
        // A truthy non-boolean must not be treated as "needs setup"
        expect(decideUnauthenticatedRoute({ initialized: "false" })).toBe("/sig");
    });

    it('resolve: uninitialized server sends the operator to /setup and does not cache', async () => {
        let storage = fakeStorage();
        let probe = vi.fn().mockResolvedValue({ initialized: false, servers: { sd: "http://x:1" } });
        let rt = await resolveUnauthenticatedRoute(probe, storage);
        expect(rt).toBe("/setup");
        expect(probe).toHaveBeenCalledTimes(1);
        expect(readSetupCache(storage)).toBe(false);
    });

    it('resolve: initialized server caches the negative result and skips the second probe', async () => {
        let storage = fakeStorage();
        let probe = vi.fn().mockResolvedValue({ initialized: true });

        let rt1 = await resolveUnauthenticatedRoute(probe, storage);
        expect(rt1).toBe("/sig");
        expect(storage._data()[SETUP_CACHE_KEY]).toBe("1");

        let rt2 = await resolveUnauthenticatedRoute(probe, storage);
        expect(rt2).toBe("/sig");
        expect(probe).toHaveBeenCalledTimes(1);

        clearSetupCache(storage);
        let rt3 = await resolveUnauthenticatedRoute(probe, storage);
        expect(rt3).toBe("/sig");
        expect(probe).toHaveBeenCalledTimes(2);
    });

    it('resolve: a failing/absent probe falls back to /sig and is NOT cached (may be transient)', async () => {
        let storage = fakeStorage();
        let probe = vi.fn().mockRejectedValue(new Error("boom"));
        let rt = await resolveUnauthenticatedRoute(probe, storage);
        expect(rt).toBe("/sig");
        expect(storage._data()[SETUP_CACHE_KEY]).toBeUndefined();

        let probe2 = vi.fn().mockResolvedValue(undefined);
        expect(await resolveUnauthenticatedRoute(probe2, storage)).toBe("/sig");
        expect(storage._data()[SETUP_CACHE_KEY]).toBeUndefined();
    });

    it('resolve: works with no storage at all (private mode / storage disabled)', async () => {
        let probe = vi.fn().mockResolvedValue({ initialized: false });
        expect(await resolveUnauthenticatedRoute(probe, null)).toBe("/setup");
        expect(probe).toHaveBeenCalledTimes(1);
    });
});

describe('setup server URL validation', () => {

    it('accepts http and https URLs', () => {
        expect(validateServerUrl("http://192.168.1.42:7801").valid).toBe(true);
        expect(validateServerUrl("https://sd.example.com/api").valid).toBe(true);
        expect(validateServerUrl("  http://localhost:8123  ").valid).toBe(true);
    });

    it('rejects non-http schemes and bare hosts', () => {
        expect(validateServerUrl("192.168.1.42:7801").valid).toBe(false);
        expect(validateServerUrl("ftp://host/x").valid).toBe(false);
        expect(validateServerUrl("file:///etc/passwd").valid).toBe(false);
        expect(validateServerUrl("http://").valid).toBe(false);
        expect(validateServerUrl("javascript:alert(1)").valid).toBe(false);
    });

    it('treats empty as valid (optional field keeps the boot value)', () => {
        expect(validateServerUrl("").valid).toBe(true);
        expect(validateServerUrl(null).valid).toBe(true);
        expect(validateServerUrl(undefined).valid).toBe(true);
        expect(validateServerUrl("   ").valid).toBe(true);
    });

    it('produces an error message when invalid', () => {
        expect(validateServerUrl("nope").error).toMatch(/http/);
    });
});

describe('setup password validation', () => {

    it('requires a value', () => {
        expect(validatePassword("").valid).toBe(false);
        expect(validatePassword(null).valid).toBe(false);
        expect(validatePassword("   ").valid).toBe(false);
    });

    it('enforces the minimum length', () => {
        let short = "a".repeat(SETUP_MIN_PASSWORD_LENGTH - 1);
        expect(validatePassword(short).valid).toBe(false);
        expect(validatePassword(short).error).toMatch(/at least/);
        expect(validatePassword("a".repeat(SETUP_MIN_PASSWORD_LENGTH)).valid).toBe(true);
    });

    it('accepts symbol-only passwords of sufficient length', () => {
        expect(validatePassword("!@#$%^&*()").valid).toBe(true);
    });
});

describe('setup form validation', () => {

    it('passes with only the admin password + token', () => {
        let r = validateSetupForm(goodValues());
        expect(r.valid).toBe(true);
        expect(r.errors).toEqual({});
    });

    it('fails when the confirmation does not match', () => {
        let r = validateSetupForm(goodValues({ adminPasswordConfirm: "somethingElse" }));
        expect(r.valid).toBe(false);
        expect(r.errors.adminPasswordConfirm).toMatch(/do not match/);
        expect(r.errors.adminPassword).toBeUndefined();
    });

    it('fails when the confirmation is empty', () => {
        let r = validateSetupForm(goodValues({ adminPasswordConfirm: "" }));
        expect(r.valid).toBe(false);
        expect(r.errors.adminPasswordConfirm).toBeTruthy();
    });

    it('fails when the setup token is missing', () => {
        let r = validateSetupForm(goodValues({ setupToken: "" }));
        expect(r.valid).toBe(false);
        expect(r.errors.setupToken).toBeTruthy();
    });

    it('fails when the admin password is too short', () => {
        let r = validateSetupForm(goodValues({ adminPassword: "short", adminPasswordConfirm: "short" }));
        expect(r.valid).toBe(false);
        expect(r.errors.adminPassword).toMatch(/at least/);
    });

    it('requires both name and password once either initial-user field is filled', () => {
        let r1 = validateSetupForm(goodValues({ initialUserName: "tester" }));
        expect(r1.valid).toBe(false);
        expect(r1.errors.initialUserPassword).toBeTruthy();

        let r2 = validateSetupForm(goodValues({ initialUserPassword: "userPassw0rd" }));
        expect(r2.valid).toBe(false);
        expect(r2.errors.initialUserName).toBeTruthy();

        let r3 = validateSetupForm(goodValues({ initialUserName: "tester", initialUserPassword: "userPassw0rd" }));
        expect(r3.valid).toBe(true);
    });

    it('rejects an organization outside the allowed list', () => {
        let r = validateSetupForm(goodValues({
            initialUserName: "tester",
            initialUserPassword: "userPassw0rd",
            initialUserOrganization: "/System"
        }));
        expect(r.valid).toBe(false);
        expect(r.errors.initialUserOrganization).toBeTruthy();
    });

    it('reports a bad URL against the specific server field', () => {
        let r = validateSetupForm(goodValues({ serverVoiceTts: "192.168.1.42:8001" }));
        expect(r.valid).toBe(false);
        expect(r.errors.serverVoiceTts).toBeTruthy();
        expect(r.errors.serverSd).toBeUndefined();
    });

    it('validates every one of the six server fields', () => {
        let over = {};
        SETUP_SERVERS.forEach(s => { over[s.field] = "bad-url"; });
        let r = validateSetupForm(goodValues(over));
        expect(r.valid).toBe(false);
        SETUP_SERVERS.forEach(s => { expect(r.errors[s.field]).toBeTruthy(); });
    });
});

describe('setup payload construction', () => {

    it('base64-encodes the admin credential and omits optional blocks', () => {
        let p = buildSetupPayload(goodValues());
        expect(Base64.decode(p.credential)).toBe("adminPassw0rd");
        expect(p.initialUser).toBeUndefined();
        expect(p.servers).toBeUndefined();
    });

    it('includes the initial user with an encoded credential', () => {
        let p = buildSetupPayload(goodValues({
            initialUserName: "  tester  ",
            initialUserPassword: "userPassw0rd",
            initialUserOrganization: "/Development"
        }));
        expect(p.initialUser.name).toBe("tester");
        expect(Base64.decode(p.initialUser.credential)).toBe("userPassw0rd");
        expect(p.initialUser.organization).toBe("/Development");
    });

    it('defaults the initial user organization to /Public', () => {
        let p = buildSetupPayload(goodValues({
            initialUserName: "tester",
            initialUserPassword: "userPassw0rd",
            initialUserOrganization: ""
        }));
        expect(p.initialUser.organization).toBe(SETUP_ORGANIZATIONS[0]);
        expect(SETUP_ORGANIZATIONS).toContain("/Public");
    });

    it('maps model field names onto the dotted wire keys and drops empty entries', () => {
        let p = buildSetupPayload(goodValues({
            serverSd: "http://sd:7801",
            serverVoiceTts: " http://tts:8001 ",
            serverEmbedding: "http://emb:8123"
        }));
        expect(p.servers).toEqual({
            "sd": "http://sd:7801",
            "voice.tts": "http://tts:8001",
            "embedding": "http://emb:8123"
        });
    });

    it('never sends a plaintext password field', () => {
        let p = buildSetupPayload(goodValues({
            initialUserName: "tester",
            initialUserPassword: "userPassw0rd"
        }));
        let json = JSON.stringify(p);
        expect(json).not.toContain("adminPassw0rd");
        expect(json).not.toContain("userPassw0rd");
    });
});

describe('setup state prefill', () => {

    it('maps the six wire keys onto model fields', () => {
        let f = serversToFields({
            "sd": "http://sd:7801",
            "face": "http://face:8003",
            "tag": "http://tag:8000",
            "voice.tts": "http://tts:8001",
            "voice.stt": "http://stt:8002",
            "embedding": "http://emb:8123"
        });
        expect(f).toEqual({
            serverSd: "http://sd:7801",
            serverFace: "http://face:8003",
            serverTag: "http://tag:8000",
            serverVoiceTts: "http://tts:8001",
            serverVoiceStt: "http://stt:8002",
            serverEmbedding: "http://emb:8123"
        });
    });

    it('ignores missing, empty and unknown keys', () => {
        expect(serversToFields(undefined)).toEqual({});
        expect(serversToFields(null)).toEqual({});
        expect(serversToFields({ sd: "", bogus: "http://x" })).toEqual({});
    });

    it('an absent servers block yields no prefill and is not an error', () => {
        /// GET /rest/setup/state omits `servers` when no valid setup token was supplied. That
        /// must degrade to "empty fields", never to a failure or a "wrong token" claim.
        let state = { initialized: false };
        expect(serversToFields(state.servers)).toEqual({});
        expect(decideUnauthenticatedRoute(state)).toBe("/setup");
        /// A partial block still prefills what it does carry.
        expect(serversToFields({ embedding: "http://emb:8123" })).toEqual({ serverEmbedding: "http://emb:8123" });
    });

    it('every server has a generic placeholder for the empty (no-prefill) case', () => {
        SETUP_SERVERS.forEach(s => {
            expect(s.placeholder).toMatch(/^https?:\/\//);
            /// Placeholders must not leak an internal host
            expect(s.placeholder).not.toMatch(/\d+\.\d+\.\d+\.\d+/);
            expect(validateServerUrl(s.placeholder).valid).toBe(true);
        });
    });
});

describe('setup page model + form wiring', () => {

    let am7model;
    let setupComponent;

    beforeAll(async () => {
        /// Importing the view registers the "setup" model and am7model.forms.setup, exactly as
        /// the app does at startup (router.js imports it statically for the core /setup route).
        setupComponent = (await import('../views/setup.js')).default;
        am7model = (await import('../core/model.js')).am7model;
    });

    it('registers the setup model and form', () => {
        expect(typeof setupComponent.view).toBe('function');
        expect(am7model.getModel("setup")).toBeDefined();
        expect(am7model.forms.setup).toBeDefined();
        expect(am7model.forms.setup.commands.runSetup.action).toBe("runSetup");
    });

    it('has a model field behind every form field (no typos between the two)', () => {
        let model = am7model.getModel("setup");
        let modelFields = model.fields.map(f => f.name);
        Object.keys(am7model.forms.setup.fields).forEach(f => {
            expect(modelFields).toContain(f);
        });
    });

    it('has a model + form field for each of the six servers', () => {
        let model = am7model.getModel("setup");
        let modelFields = model.fields.map(f => f.name);
        SETUP_SERVERS.forEach(s => {
            expect(modelFields).toContain(s.field);
            expect(am7model.forms.setup.fields[s.field]).toBeDefined();
            expect(am7model.forms.setup.fields[s.field].label).toBe(s.label);
            expect(am7model.forms.setup.fields[s.field].placeholder).toBe(s.placeholder);
        });
    });

    it('server fields carry no rules, so an empty (unprefilled) form still submits', () => {
        let model = am7model.getModel("setup");
        SETUP_SERVERS.forEach(s => {
            let f = model.fields.filter(x => x.name === s.field)[0];
            expect(f).toBeDefined();
            expect(f.rules).toBeUndefined();
        });
    });

    it('offers only /Public and /Development as organizations', () => {
        expect(am7model.forms.setup.fields.initialUserOrganization.values).toEqual(SETUP_ORGANIZATIONS);
        expect(SETUP_ORGANIZATIONS).not.toContain("/System");
    });

    it('masks the password and token inputs', () => {
        expect(am7model.forms.setup.fields.adminPassword.type).toBe("password");
        expect(am7model.forms.setup.fields.adminPasswordConfirm.type).toBe("password");
        expect(am7model.forms.setup.fields.initialUserPassword.type).toBe("password");
        expect(am7model.forms.setup.fields.setupToken.type).toBe("password");
    });

    it('schema rules reject an empty instance and accept a filled one', () => {
        let inst = am7model.newInstance("setup", am7model.forms.setup);
        expect(inst.validate()).toBe(false);
        expect(inst.validationErrors.adminPassword).toBeTruthy();
        expect(inst.validationErrors.setupToken).toBeTruthy();
        /// Optional fields must not be flagged
        expect(inst.validationErrors.initialUserName).toBeUndefined();
        expect(inst.validationErrors.serverEmbedding).toBeUndefined();

        inst.api.adminPassword("adminPassw0rd");
        inst.api.adminPasswordConfirm("adminPassw0rd");
        inst.api.setupToken("tok-123");
        expect(inst.validate()).toBe(true);
    });

    it('schema rules enforce the admin password minimum length', () => {
        let inst = am7model.newInstance("setup", am7model.forms.setup);
        inst.api.adminPassword("short");
        inst.api.adminPasswordConfirm("short");
        inst.api.setupToken("tok-123");
        expect(inst.validate()).toBe(false);
        expect(inst.validationErrors.adminPassword).toBeTruthy();
    });

    it('defaults the organization to /Public on a new instance', () => {
        let inst = am7model.newInstance("setup", am7model.forms.setup);
        expect(inst.api.initialUserOrganization()).toBe("/Public");
    });
});

describe('setup token from URL', () => {

    it('reads ?token= from the query string', () => {
        expect(tokenFromUrl("?token=abc123", "#!/setup")).toBe("abc123");
    });

    it('reads token= from the hash route query (hash routing)', () => {
        expect(tokenFromUrl("", "#!/setup?token=xyz789")).toBe("xyz789");
    });

    it('prefers the query string when both are present', () => {
        expect(tokenFromUrl("?token=fromSearch", "#!/setup?token=fromHash")).toBe("fromSearch");
    });

    it('returns null when absent', () => {
        expect(tokenFromUrl("", "#!/setup")).toBe(null);
        expect(tokenFromUrl(null, null)).toBe(null);
        expect(tokenFromUrl("?other=1", "#!/setup")).toBe(null);
        expect(tokenFromUrl("?token=", "#!/setup")).toBe(null);
    });
});
