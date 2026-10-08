import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { page } from '../core/pageClient.js';

/**
 * Make Fact workflow — the "Fact" toolbar command on data, user, person, account, role,
 * permission and group forms (formDef.js `commands.fact`, function 'makeFact').
 *
 * Ported from the Ux7 reference (deprecated/AccountManagerUx7/client/view/object.js:1529-1563):
 * build a policy.fact that points at the current record, park it on page.context().pendingEntity,
 * and route to /new/policy.fact/<Facts group> where views/object.js getPrimitive() folds the pending
 * entity into the blank primitive. The user then reviews and saves it in the generic editor.
 *
 * Two deliberate departures from the Ux7 text, both because the AM7 policy.fact model differs from
 * what that code (inherited from AM5/6) assumed:
 *   - Ux7 set `factType` (deprecated on the AM7 model) and `factoryType` (not a field at all). The
 *     AM7 fields the backend actually reads (Objects7 policy/FactUtil: findByUrn(modelType, sourceUrn),
 *     findPath(model, sourceUrl, sourceType)) are `type`, `modelType`, `sourceUrn`, `sourceUrl`,
 *     `sourceType`, so those are what is set here.
 *   - Ux7's role/permission branches tested `name` against /^role$/ and /^permission$/ while `name`
 *     was the fully-qualified model ("auth.role"), so they never matched and every fact came out
 *     FACTORY with sourceType "unknown". Here the branches match the qualified names, so a role
 *     yields a ROLE fact and a permission a PERMISSION fact (with sourceUrl = path and sourceType =
 *     type, which is what FactUtil.getRecordFromFactPath resolves); everything else is a FACTORY fact.
 *
 * Ux752 command signature is (entity, inst, cmd) — see views/object.js getFormCommands().
 */

const SUPPORTED = /^(auth\.group|auth\.role|auth\.permission|identity\.person|identity\.account|system\.user|data\.data|policy\.function|policy\.operation)$/i;

/** Pure: the policy.fact primitive that describes `entity`. Exported for the unit test. */
function buildFact(entity) {
    let modelType = entity[am7model.jsonModelKey];
    let fact = am7model.newPrimitive('policy.fact');
    fact.type = 'FACTORY';
    fact.modelType = modelType;
    fact.sourceUrn = entity.urn;
    fact.sourceType = 'unknown';
    if (/^auth\.role$/i.test(modelType)) {
        fact.type = 'ROLE';
        fact.sourceType = entity.type;
        fact.sourceUrl = entity.path;
    } else if (/^auth\.permission$/i.test(modelType)) {
        fact.type = 'PERMISSION';
        fact.sourceType = entity.type;
        fact.sourceUrl = entity.path;
    } else if (/^auth\.group$/i.test(modelType)) {
        fact.sourceType = entity.type;
        fact.sourceUrl = entity.path;
    }
    fact.name = entity.name + ' Fact';
    fact.description = 'Fact representing a relative link to ' + entity.name + ' as ' + entity.urn
        + ' in organization ' + entity.organizationPath;
    return fact;
}

async function makeFact(entity, inst) {
    if (!entity || !entity[am7model.jsonModelKey] || !entity.urn) {
        page.toast('error', 'No saved record to make a fact from');
        return false;
    }
    let modelType = entity[am7model.jsonModelKey];
    if (!SUPPORTED.test(modelType)) {
        console.error('Unsupported fact import type ' + entity.urn);
        page.toast('error', 'Facts cannot be made from ' + modelType);
        return false;
    }

    // The Facts directory (policy.fact's model group is "Facts" => "~/Facts"). Ux7 used find; make
    // is the same call with create-if-missing, so a fresh user's first fact does not dead-end.
    let group = await page.makePath('auth.group', 'DATA', am7view.path('policy.fact'));
    if (!group || !group.objectId) {
        page.toast('error', 'Could not resolve the Facts directory');
        return false;
    }

    let fact = buildFact(entity);
    // newPrimitive() seeds groupId with the long default (0) and object.js getPrimitive() folds every
    // pendingEntity key except parentId/parentPath/groupPath over the container-derived primitive, so
    // the Facts directory must be carried on the fact itself or the fold resets it to 0.
    fact.groupId = group.id;
    fact.groupPath = group.path;
    page.context().pendingEntity = fact;
    // Ux7 note, still true: a key forces a full DOM rebuild when cycling the editor between objects.
    m.route.set('/new/policy.fact/' + group.objectId, { key: Date.now() });
    return true;
}

export { makeFact, buildFact };
export default makeFact;
