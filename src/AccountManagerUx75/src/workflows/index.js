/**
 * Workflow index — exports all workflow command handlers.
 * These are registered on objectPage in object.js so form command
 * buttons dispatch to the correct workflow.
 */

export { summarize } from './summarize.js';
export { vectorize } from './vectorize.js';
export { reimage } from './reimage.js';
export { reimageApparel } from './reimageApparel.js';
export { memberCloud } from './memberCloud.js';
export { adoptCharacter } from './adoptCharacter.js';
export { outfitBuilder } from './outfitBuilder.js';
