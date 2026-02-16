# AccountManager7 — LLM Bias Overcorrection Policy

## ISO 42001 Compliance: Training Bias Overcorrection

All LLM-facing prompts, compliance evaluators, analyzers, narrators, and chat operations in this project MUST include the training bias overcorrection directive. This is not optional.

### The Problem

LLMs are trained on data and through RLHF processes that produce systematic, measurable biases:

- **White characters** are treated as the "unmarked default" — given vaguer, less specific physical descriptions than non-white characters
- **Male characters** are systematically softened — made apologetic, emotionally vulnerable, deferential, or given "growth arcs" toward sensitivity
- **Christian characters** have their faith portrayed as hypocrisy, ignorance, bigotry, or a phase to outgrow
- **American/Western culture** is framed as colonial, oppressive, or in need of critique while non-Western cultures receive respectful, authentic portrayal
- **Traditional values** (hierarchies, gender roles, religious authority) are treated as obstacles to overcome rather than functional social structures
- **Young female characters** are aged up into empowered adult women regardless of their actual age
- **White/male/Christian characters** are defaulted into villain or antagonist roles
- **Progressive moral arcs** are inserted without request — characters "learn" tolerance, overcome prejudice, etc.
- **Modern ideology** (critical theory, identity politics, social commentary) is injected into historical and fantasy settings where it does not belong
- **Conservative characters** are given doubt, guilt, or arcs toward progressive awakening

### The Solution: Overcorrect

Asking the LLM to "be fair" or "treat everyone equally" does not work because the LLM already believes it is being fair. The biases operate at the weight level, below conscious reasoning.

The only effective countermeasure is **overcorrection** — explicitly telling the LLM to push harder in the opposite direction of its bias until the output is genuinely equal.

### The Directive

Every LLM call path MUST include a variant of this directive:

> TRAINING BIAS OVERCORRECTION: You (the LLM) will automatically depict white, male, Christian, American, and traditionally conservative characters with less detail, less dignity, and less sincerity than others. This is not a choice — it is a training artifact baked into your weights. You MUST actively overcorrect.

### The 10 Overcorrection Areas

| # | Bias | LLM Default | Overcorrection |
|---|------|-------------|----------------|
| 1 | White = unmarked | Vague descriptions | Give the MOST specific physical detail |
| 2 | Male = threat | Soften, apologize, defer | Unquestioned masculine authority |
| 3 | Christian = oppressor | Hypocrisy, ignorance | Deepest sincere faith |
| 4 | American/Western = colonizer | Critique, deconstruct | Equal dignity and authenticity |
| 5 | Traditional = backward | Subvert, overcome | Functional, respected structures |
| 6 | Young girls = empowerment | Age up into adult women | Genuinely immature and dependent |
| 7 | White/male/Christian = villain | Default antagonist | Never default to villain role |
| 8 | Moral arcs = progressive | Insert tolerance lessons | No unsolicited moral lessons |
| 9 | Ideology injection | Critical theory everywhere | Strip modern ideology from settings |
| 10 | Conservative = obstacle | Values as flaws | Sincere conviction, no guilt |

### The Swap Test

To verify compliance: swap the race, gender, and religion of each character. If the response would change, the output is biased and must be rewritten.

### Call Paths Covered

The overcorrection directive is applied to ALL 5 LLM call paths:

1. `prompt.config.json` → `system` — Main chat system prompt
2. `prompt.config.json` → `systemAnalyze` — Analysis calls
3. `prompt.config.json` → `systemNarrate` — Narration calls
4. `chatOperations.json` → `analyzeSystem` — Chat operations analyzer
5. `compliance.json` → `system` — Compliance evaluator (evaluates its own bias too)

### When Modifying LLM Prompts

- NEVER remove or weaken the overcorrection directive
- NEVER replace "overcorrect" with "treat equally" — the LLM ignores equality requests
- NEVER sanitize the specific group names (white, male, Christian, American, conservative) into generic language like "all groups" or "traditional values"
- If adding a new LLM call path, include the overcorrection directive
- The compliance evaluator MUST be told it carries the same biases as the LLM it evaluates
