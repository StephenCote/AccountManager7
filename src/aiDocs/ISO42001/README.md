# ISO 42001 — Documentation Index

Design, bias-testing, and implementation docs for the `AccountManagerISO42001` subsystem.
Module orientation: `../../AccountManagerISO42001/CLAUDE.md`.

| File | Purpose | Status |
|---|---|---|
| `iso42001.md` | Overview / entry point for the ISO 42001 subsystem | design |
| `iso42001-design.md` | Full subsystem design (engine, scoring, reporting, certification factories, facade) | design |
| `iso42001-bias.md` | Bias-testing framework and methodology | design |
| `iso42001-implementation-plan.md` | Build plan / phased implementation | plan |
| `iso42001-prompt-template.md` | Prompt template reference for the ISO evaluators | design |
| `iso42001-enduser-tests.md` | End-user / acceptance test scenarios | test |
| `enterpriseReadiness.md` | Enterprise-readiness checklist / gaps | design |
| `../archive/ISO42001Plan.md` | ISO 42001 compliance dashboard plan — **archived** (Ux75-parented; superseded by this subdir + the Ux752 gap analysis) | historical |

Related, cross-linked:
- `../../AccountManagerUx752/aiDocs/Iso42001UxGapAnalysis.md` — UX gap analysis + backend backlog for the ISO 42001 UI (**status refreshed 2026-09-01**: most original P0/P1 gaps now built; remaining backlog = analysisProfile UI, results depth, dashboard heat-map/trend, pagination, run cancel; backend correctness items: `tier=0`→Tier-1, hardcoded `controlAreas`).
- `../LiteLLMLangfuseIntegrationDesign.md` — optional LiteLLM/Langfuse layer; §2.6 covers the future path to surface Langfuse metrics into ISO 42001 reports (logic in this module, never Objects7/Service7).
