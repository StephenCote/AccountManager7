/**
 * Image Token Library — resolves ${image.tag1.tag2} tokens in LLM responses (ESM)
 * Port of Ux7 am7imageTokens pattern.
 */
import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { applicationPath } from '../core/config.js';

const TOKEN_REGEX = /\$\{image\.([^}]+)\}/g;
const OBJECTID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

let cache = {};

function parse(content) {
    if (!content) return [];
    let tokens = [];
    let match;
    let regex = new RegExp(TOKEN_REGEX.source, 'g');
    while ((match = regex.exec(content)) !== null) {
        let key = match[1];
        let tags = key.split(".");
        tokens.push({ key: key, tags: tags, start: match.index, end: match.index + match[0].length, full: match[0] });
    }
    return tokens;
}

function buildThumbnailUrl(obj, size) {
    if (!obj || !obj.groupPath || !obj.name) return null;
    let org = am7client.dotPath(am7client.currentOrganization);
    return applicationPath + "/thumbnail/" + org + "/data.data" + obj.groupPath + "/" + obj.name + "/" + (size || "256x256");
}

async function resolveToken(token) {
    let key = token.key;
    if (cache[key] && !cache[key].loading) return cache[key];

    cache[key] = { url: null, loading: true, error: null };

    try {
        // Direct objectId reference
        if (token.tags.length === 1 && OBJECTID_REGEX.test(token.tags[0])) {
            let obj = await am7client.getFull("data.data", token.tags[0]);
            if (obj) {
                cache[key] = { url: buildThumbnailUrl(obj, "256x256"), loading: false, error: null };
            } else {
                cache[key] = { url: null, loading: false, error: "Not found" };
            }
            m.redraw();
            return cache[key];
        }

        // Tag-based search: search data.data for objects matching tags
        let q = {
            schema: "data.data",
            type: "data.data",
            startRecord: 0,
            recordCount: 1,
            fields: []
        };

        // Build search from tags
        token.tags.forEach(function(tag) {
            q.fields.push({ name: "name", comparator: "LIKE", value: "%" + tag + "%" });
        });

        let result = await new Promise(function(resolve) {
            am7client.search(q, function(v) { resolve(v); });
        });

        if (result && result.results && result.results.length > 0) {
            let obj = result.results[0];
            cache[key] = { url: buildThumbnailUrl(obj, "256x256"), loading: false, error: null };
        } else {
            cache[key] = { url: null, loading: false, error: "No match for tags: " + token.tags.join(", ") };
        }
    } catch(e) {
        cache[key] = { url: null, loading: false, error: e.message || "Resolution failed" };
    }

    m.redraw();
    return cache[key];
}

/**
 * Process content string, replacing image tokens with HTML.
 * Initiates async resolution for unresolved tokens.
 */
function processContent(content) {
    if (!content) return content;
    let tokens = parse(content);
    if (tokens.length === 0) return content;

    let result = content;
    // Process in reverse to preserve indices
    for (let i = tokens.length - 1; i >= 0; i--) {
        let token = tokens[i];
        let cached = cache[token.key];

        if (!cached) {
            // Start async resolution
            resolveToken(token);
            // Show placeholder
            let placeholder = '<span class="inline-block w-16 h-16 bg-gray-200 dark:bg-gray-700 rounded animate-pulse" title="Loading image..."></span>';
            result = result.substring(0, token.start) + placeholder + result.substring(token.end);
        } else if (cached.loading) {
            let placeholder = '<span class="inline-block w-16 h-16 bg-gray-200 dark:bg-gray-700 rounded animate-pulse" title="Loading..."></span>';
            result = result.substring(0, token.start) + placeholder + result.substring(token.end);
        } else if (cached.url) {
            let img = '<img src="' + cached.url + '" class="inline-block max-w-[256px] max-h-[256px] rounded my-1" alt="' + token.key + '" />';
            result = result.substring(0, token.start) + img + result.substring(token.end);
        } else {
            // Error or not found — show broken indicator
            let broken = '<span class="inline-block text-xs text-gray-400" title="' + (cached.error || "Not found") + '">[image: ' + token.key + ']</span>';
            result = result.substring(0, token.start) + broken + result.substring(token.end);
        }
    }

    return result;
}

const am7imageTokens = {
    parse,
    processContent,
    resolveToken,
    cache,
    clearCache: function() { cache = {}; }
};

export { am7imageTokens };
export default am7imageTokens;
