/**
 * Feature profile resolution (§3.7 of aiDocs/UxFeatureFlagDesign.md).
 *
 * Extracted out of router.js's refreshApplication() for the same reason featureRoute.js was:
 * router.js cannot be imported by a unit test (it eagerly builds the whole view graph and
 * page.navigable), so logic left inline there can only be asserted against by reading the file as
 * TEXT. Three such source-text assertions previously "covered" this function's failure branch and
 * would have stayed green if the branch were wired wrong — which is a fake test by
 * .claude/rules/llm-conduct.md. This module takes every dependency as an argument so the real
 * behavior is executable.
 *
 * Precedence: ?features= (dev only) -> server org config -> __FEATURE_PROFILE__ -> 'standard'.
 */

/// Resolve the feature profile.
///
/// deps:
///   devMode       - boolean; gates the URL override. Not a security control (§5), but the override
///                   should not be a supported production surface.
///   search        - the raw location.search string (may be '' or undefined).
///   user          - the principal, or null when unauthenticated. The server is only consulted for
///                   an authenticated user.
///   getFeatureConfig - async () => serverConfig. Must not throw for the caller; we catch.
///   buildProfile  - the __FEATURE_PROFILE__ value, or null/undefined when the define is absent.
///
/// Returns { profile, configFailed }. `profile` is either a profile NAME (string) or an explicit
/// array of feature ids. `configFailed` is true only when the server call genuinely failed, and the
/// caller is expected to surface a visible notice in that case.
async function resolveFeatureProfile(deps) {
    let d = deps || {};
    let profile = null;
    let configFailed = false;

    /// 1. URL override — dev only.
    if (d.devMode) {
        let urlFeatures = null;
        try {
            urlFeatures = new URLSearchParams(d.search || '').get('features');
        } catch (e) { /* no location.search */ }
        if (urlFeatures) profile = urlFeatures;
    }

    /// 2. Server org config. FAILURE must be distinguished from a legitimately small set: after D1
    /// the read path force-includes `core`, so ["core"] is the smallest LEGAL answer and is exactly
    /// the `minimal` profile. Treating a short array as failure would make `minimal` unreachable and
    /// permanently fail open to `full`. Failure = thrown/rejected, non-2xx (am7client.get swallows
    /// the error and resolves undefined), or a body whose `features` is missing / not an array.
    /// The old code accepted [] as valid, because an empty array is truthy in JS, and caught only
    /// the throw. On failure keep failing OPEN to 'full' — see the §3.7 implementation note.
    if (profile == null && d.user != null) {
        let serverConfig;
        try {
            serverConfig = await d.getFeatureConfig();
        } catch (e) {
            configFailed = true;
            console.warn('[router] Feature config request failed', e);
        }
        if (!configFailed) {
            if (serverConfig && Array.isArray(serverConfig.features)) {
                profile = serverConfig.features;
            } else {
                configFailed = true;
                console.warn('[router] Feature config response was missing a features array', serverConfig);
            }
        }
        if (configFailed) profile = 'full';
    }

    /// 3./4. Build define, then the genuine no-signal default.
    if (profile == null) {
        profile = (d.buildProfile ? d.buildProfile : null) || 'standard';
    }

    return { profile: profile, configFailed: configFailed };
}

export { resolveFeatureProfile };
