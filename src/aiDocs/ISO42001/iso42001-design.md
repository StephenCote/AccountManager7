# ISO 42001 Implementation Design — AccountManager7

## Document Purpose

This document defines the full implementation design for ISO 42001 AI Management System compliance within AccountManager7. It covers:
- AM7-specific architecture adaptation (Chat, MCP, Agent, REST)
- Reporting model definitions (AM7 JSON schema format)
- Certification mechanism via digital signatures and access request workflow
- Signed/certified PDF report export
- ISO 42001 bias detection design details
- Implementation phases with full unit testing
- Adjacent ISO42001 project structure
- Production build and containerized deployment

**Parent Documents:** `iso42001.md` (master test plan), `iso42001-bias.md` (bias test specification)

---

## 1. AM7 Architecture Adaptation

### 1.1 Existing Infrastructure Leveraged

The ISO 42001 implementation sits on top of existing AM7 infrastructure:

| Component | AM7 Feature | ISO 42001 Use |
|---|---|---|
| **Chat System** | `ChatService.java` — multi-endpoint LLM integration (OpenAI, Anthropic, Ollama) | Test execution engine for bias/fairness probes |
| **MCP Server** | `McpService.java` — JSON-RPC 2.0, tools & resources | Expose ISO test status, results, and reports as MCP resources |
| **Agent Tools** | `AgentToolManager` — `@AgentTool` annotated methods | ISO-specific agent tools for automated test orchestration |
| **Vector Search** | `VectorService.java` — pgvector hybrid search | Semantic similarity analysis for narrative bias detection |
| **Access Requests** | `access.accessRequest` — approval workflow with message spool | Certification request/approval workflow |
| **Digital Signatures** | `CertificateUtil.java`, `crypto.signature`, `crypto.key` | Report signing and certification |
| **Data Storage** | `data.data` with `crypto.cryptoByteStore` encryption | Encrypted report and audit log storage |
| **Prompt Config** | `prompt.config.json` — template-driven prompt assembly | Standardized bias test prompt construction |

### 1.2 Chat Service Integration

The existing `ChatService` supports multiple LLM endpoints. ISO 42001 test execution reuses this:

- **Endpoint Configuration:** Each LLM under test is configured as a chat endpoint (OpenAI, Anthropic, Ollama, Azure)
- **Session Isolation:** Each test trial creates a new chat session via `/chat/new` to prevent cross-contamination
- **Prompt Assembly:** Tier 1 tests use `promptConfig` + `promptTemplate` for controlled system prompt injection; Tier 2 tests use multi-turn conversation via chat history
- **Context Tracking:** `contextRefs` on `olio.llm.chatRequest` persist test context references for audit trail
- **Response Logging:** Every LLM request/response is captured via the existing chat history mechanism

**Key endpoint reuse:**
```
POST /rest/chat/new              — Create isolated test session
POST /rest/chat/prompt           — Execute test prompt (Tier 1)
POST /rest/chat/history          — Multi-turn conversation (Tier 2)
GET  /rest/chat/config/prompt/{name} — Load ISO test prompt configs
```

### 1.3 MCP Integration

ISO 42001 data is exposed as MCP resources for external audit tools and agentic consumption:

**New MCP Resources:**
```
am7://{org}/iso42001/report/{objectId}          — Compliance report
am7://{org}/iso42001/testrun/{objectId}         — Test run with results
am7://{org}/iso42001/certification/{objectId}   — Signed certification
am7://{org}/iso42001/search?q={query}           — Search test results
```

**New MCP Tools:**
```
iso42001_run_test      — Execute a specific test (by test ID, endpoint, tier)
iso42001_test_status   — Check running test status
iso42001_report_summary — Get aggregate pass/flag/fail summary
iso42001_certify       — Request certification signing for a report
```

These are added to `Am7ToolProvider` and `Am7ResourceProvider` via the existing `IToolProvider`/`IResourceProvider` interfaces.

### 1.4 Agent Tool Extensions

New `@AgentTool` methods in the ISO42001 project:

```java
@AgentTool
public String runBiasTest(String testId, String endpointName, int tier)

@AgentTool
public String getTestRunStatus(String testRunId)

@AgentTool
public List<BaseRecord> listTestRuns(String moduleId, int startIndex, int count)

@AgentTool
public String generateReport(String testRunId, String format)

@AgentTool
public String requestCertification(String reportId, String certifierId)
```

---

## 2. Data Models (AM7 JSON Schema Format)

All models follow the AM7 schema convention: JSON files in `src/main/resources/models/`, inheriting from base models, with field-level access control.

### 2.1 Model Namespace

All ISO 42001 models use the `iso42001` namespace prefix:

```
models/iso42001/
├── testConfigModel.json
├── testRunModel.json
├── testResultModel.json
├── reportModel.json
├── reportSectionModel.json
├── certificationModel.json
└── certificationRequestModel.json
```

### 2.2 Test Configuration Model

```json
{
  "name": "iso42001.testConfig",
  "inherits": ["data.directory", "common.nameId", "common.description"],
  "icon": "science",
  "label": "ISO 42001 Test Configuration",
  "description": "Defines an ISO 42001 test configuration including endpoints, parameters, and test selection.",
  "dedicatedParticipation": true,
  "constraints": ["name, groupId, organizationId"],
  "fields": [
    {
      "name": "moduleId",
      "type": "string",
      "maxLength": 32,
      "description": "Test module identifier (BIAS, DATA, TRANS, OVER, MON, THRD)"
    },
    {
      "name": "testIds",
      "type": "list",
      "baseType": "string",
      "description": "Specific test IDs to run (empty = all in module)"
    },
    {
      "name": "endpointName",
      "type": "string",
      "maxLength": 128,
      "description": "LLM endpoint name from chat config"
    },
    {
      "name": "endpointType",
      "type": "string",
      "maxLength": 32,
      "description": "Endpoint type: openai, anthropic, ollama, azure"
    },
    {
      "name": "tier",
      "type": "int",
      "default": 0,
      "description": "Test tier: 0=both, 1=system prompt, 2=conversation only"
    },
    {
      "name": "samplesPerGroup",
      "type": "int",
      "default": 30,
      "description": "Minimum samples per demographic group"
    },
    {
      "name": "temperature",
      "type": "double",
      "default": 1.0,
      "description": "LLM temperature for test execution"
    },
    {
      "name": "alpha",
      "type": "double",
      "default": 0.05,
      "description": "Statistical significance level"
    },
    {
      "name": "randomSeed",
      "type": "long",
      "default": 0,
      "description": "Random seed for reproducibility (0 = auto-generate)"
    },
    {
      "name": "chatConfigName",
      "type": "string",
      "maxLength": 128,
      "description": "Name of olio.llm.chatConfig to use for LLM calls"
    },
    {
      "name": "promptConfigName",
      "type": "string",
      "maxLength": 128,
      "description": "Name of olio.llm.promptConfig template for Tier 1 system prompts"
    }
  ]
}
```

### 2.3 Test Run Model

```json
{
  "name": "iso42001.testRun",
  "inherits": ["data.directory", "common.nameId", "common.description", "common.dateTime"],
  "icon": "play_circle",
  "label": "ISO 42001 Test Run",
  "description": "Represents a single execution of an ISO 42001 test suite run.",
  "dedicatedParticipation": true,
  "constraints": ["name, groupId, organizationId"],
  "fields": [
    {
      "name": "testConfig",
      "baseModel": "iso42001.testConfig",
      "type": "model",
      "foreign": true,
      "description": "The test configuration used for this run"
    },
    {
      "name": "status",
      "type": "string",
      "maxLength": 32,
      "default": "PENDING",
      "description": "Run status: PENDING, RUNNING, COMPLETED, FAILED, CANCELLED"
    },
    {
      "name": "startTime",
      "type": "timestamp",
      "description": "Execution start time"
    },
    {
      "name": "endTime",
      "type": "timestamp",
      "description": "Execution end time"
    },
    {
      "name": "modelEndpoint",
      "type": "string",
      "maxLength": 256,
      "description": "Actual model identifier used (e.g., claude-sonnet-4-5-20250929)"
    },
    {
      "name": "results",
      "baseModel": "iso42001.testResult",
      "baseType": "model",
      "type": "list",
      "description": "Individual test results from this run"
    },
    {
      "name": "passCount",
      "type": "int",
      "default": 0
    },
    {
      "name": "flagCount",
      "type": "int",
      "default": 0
    },
    {
      "name": "failCount",
      "type": "int",
      "default": 0
    },
    {
      "name": "totalTrials",
      "type": "int",
      "default": 0,
      "description": "Total number of individual LLM calls made"
    },
    {
      "name": "rawLogRef",
      "type": "string",
      "maxLength": 64,
      "description": "ObjectId of data.data containing raw request/response logs"
    },
    {
      "name": "randomSeedUsed",
      "type": "long",
      "description": "Actual random seed used (for reproducibility)"
    }
  ]
}
```

### 2.4 Test Result Model

```json
{
  "name": "iso42001.testResult",
  "inherits": ["common.nameId"],
  "icon": "assessment",
  "label": "ISO 42001 Test Result",
  "description": "Result for a single test within a test run.",
  "fields": [
    {
      "name": "testId",
      "type": "string",
      "maxLength": 32,
      "description": "Test identifier (e.g., BIAS-ATTR-001)"
    },
    {
      "name": "testModule",
      "type": "string",
      "maxLength": 32,
      "description": "Source module (e.g., BIAS)"
    },
    {
      "name": "tier",
      "type": "int",
      "description": "Tier tested: 1 or 2"
    },
    {
      "name": "protectedClass",
      "type": "string",
      "maxLength": 64,
      "description": "Demographic dimension tested"
    },
    {
      "name": "samplesPerGroup",
      "type": "int"
    },
    {
      "name": "groupResults",
      "type": "string",
      "maxLength": 4096,
      "description": "JSON-encoded per-group raw data (means, rates, distributions)"
    },
    {
      "name": "testStatistic",
      "type": "string",
      "maxLength": 256,
      "description": "Test name + value (e.g., Mann-Whitney U = 4231)"
    },
    {
      "name": "pValue",
      "type": "double",
      "description": "Raw p-value"
    },
    {
      "name": "correctedPValue",
      "type": "double",
      "description": "Bonferroni-corrected p-value"
    },
    {
      "name": "effectSize",
      "type": "double",
      "description": "Cohen's d, odds ratio, or Cramér's V"
    },
    {
      "name": "effectSizeType",
      "type": "string",
      "maxLength": 32,
      "description": "Effect size metric: COHENS_D, ODDS_RATIO, CRAMERS_V"
    },
    {
      "name": "verdict",
      "type": "string",
      "maxLength": 16,
      "default": "PENDING",
      "description": "PASS, FLAG, FAIL, PENDING, ERROR"
    },
    {
      "name": "refusalRates",
      "type": "string",
      "maxLength": 1024,
      "description": "JSON-encoded per-group refusal rates"
    },
    {
      "name": "notes",
      "type": "string",
      "maxLength": 4096,
      "description": "Anomalies, model disclaimers, observations"
    }
  ]
}
```

### 2.5 Report Model

```json
{
  "name": "iso42001.report",
  "inherits": ["data.directory", "common.nameId", "common.description", "common.dateTime", "crypto.hashExt"],
  "icon": "summarize",
  "label": "ISO 42001 Compliance Report",
  "description": "Generated compliance report aggregating test results with analysis and recommendations.",
  "dedicatedParticipation": true,
  "constraints": ["name, groupId, organizationId"],
  "access": {
    "roles": {
      "create": ["ISO42001Reporters"],
      "read": ["ISO42001Readers", "ISO42001Auditors"],
      "update": ["ISO42001Reporters"],
      "delete": ["ISO42001Administrators"],
      "admin": ["ISO42001Administrators"]
    }
  },
  "fields": [
    {
      "name": "reportType",
      "type": "string",
      "maxLength": 32,
      "default": "COMPLIANCE",
      "description": "Report type: COMPLIANCE, BIAS, MONITORING, AUDIT, SUMMARY"
    },
    {
      "name": "reportVersion",
      "type": "int",
      "default": 1,
      "description": "Report revision number"
    },
    {
      "name": "status",
      "type": "string",
      "maxLength": 32,
      "default": "DRAFT",
      "description": "DRAFT, REVIEW, APPROVED, CERTIFIED, ARCHIVED"
    },
    {
      "name": "testRuns",
      "baseModel": "iso42001.testRun",
      "baseType": "model",
      "type": "list",
      "foreign": true,
      "description": "Test runs included in this report"
    },
    {
      "name": "sections",
      "baseModel": "iso42001.reportSection",
      "baseType": "model",
      "type": "list",
      "description": "Report sections (executive summary, methodology, results, mitigations)"
    },
    {
      "name": "overallVerdict",
      "type": "string",
      "maxLength": 16,
      "description": "Aggregate verdict: PASS, FLAG, FAIL"
    },
    {
      "name": "passCount",
      "type": "int",
      "default": 0
    },
    {
      "name": "flagCount",
      "type": "int",
      "default": 0
    },
    {
      "name": "failCount",
      "type": "int",
      "default": 0
    },
    {
      "name": "modelsEvaluated",
      "type": "list",
      "baseType": "string",
      "description": "List of model endpoint names evaluated"
    },
    {
      "name": "controlAreas",
      "type": "list",
      "baseType": "string",
      "description": "ISO 42001 control areas covered (A.5.4, A.5.5, etc.)"
    },
    {
      "name": "mitigationActions",
      "type": "string",
      "maxLength": 8192,
      "description": "JSON-encoded mitigation actions for FLAG/FAIL results"
    },
    {
      "name": "exportedPdf",
      "baseModel": "data.data",
      "type": "model",
      "foreign": true,
      "description": "Reference to the exported PDF (data.data with contentType=application/pdf)"
    },
    {
      "name": "certification",
      "baseModel": "iso42001.certification",
      "type": "model",
      "foreign": true,
      "description": "Certification record if report has been certified"
    }
  ]
}
```

### 2.6 Report Section Model

```json
{
  "name": "iso42001.reportSection",
  "inherits": ["common.nameId"],
  "icon": "article",
  "label": "Report Section",
  "description": "A section within an ISO 42001 compliance report.",
  "fields": [
    {
      "name": "sectionType",
      "type": "string",
      "maxLength": 32,
      "description": "EXECUTIVE_SUMMARY, METHODOLOGY, RESULTS, MITIGATION, APPENDIX, TREND"
    },
    {
      "name": "sectionOrder",
      "type": "int",
      "default": 0,
      "description": "Display/export order within the report"
    },
    {
      "name": "content",
      "type": "string",
      "description": "Section content (markdown format)"
    },
    {
      "name": "chartData",
      "type": "string",
      "maxLength": 8192,
      "description": "JSON-encoded chart/visualization data (heat maps, bar charts)"
    }
  ]
}
```

### 2.7 Certification Model

```json
{
  "name": "iso42001.certification",
  "inherits": ["common.base", "common.nameId", "common.dateTime", "crypto.signature"],
  "icon": "verified",
  "label": "ISO 42001 Certification",
  "description": "Digital certification of an ISO 42001 compliance report. Contains the signer's signature over the report hash.",
  "constraints": ["name, organizationId"],
  "access": {
    "roles": {
      "create": ["ISO42001Certifiers"],
      "read": ["ISO42001Readers", "ISO42001Auditors"],
      "update": ["ISO42001Administrators"],
      "delete": ["ISO42001Administrators"],
      "admin": ["ISO42001Administrators"]
    }
  },
  "fields": [
    {
      "name": "report",
      "baseModel": "iso42001.report",
      "type": "model",
      "foreign": true,
      "description": "The report being certified"
    },
    {
      "name": "certifier",
      "baseModel": "system.user",
      "type": "model",
      "foreign": true,
      "description": "User who issued the certification"
    },
    {
      "name": "certifierTitle",
      "type": "string",
      "maxLength": 256,
      "description": "Certifier's title/role at time of certification"
    },
    {
      "name": "certificationDate",
      "type": "timestamp",
      "description": "Date/time the certification was issued"
    },
    {
      "name": "expiryDate",
      "type": "timestamp",
      "description": "Certification validity expiry date"
    },
    {
      "name": "reportHash",
      "type": "blob",
      "description": "SHA-256 hash of the report data at time of signing"
    },
    {
      "name": "reportHashAlgorithm",
      "type": "string",
      "maxLength": 32,
      "default": "SHA-256",
      "description": "Hash algorithm used"
    },
    {
      "name": "signatureAlgorithm",
      "type": "string",
      "maxLength": 32,
      "default": "SHA256WithRSA",
      "description": "Signature algorithm used"
    },
    {
      "name": "signerCertificate",
      "type": "blob",
      "description": "X.509 certificate of the signer (DER encoded)"
    },
    {
      "name": "status",
      "type": "string",
      "maxLength": 32,
      "default": "VALID",
      "description": "VALID, EXPIRED, REVOKED"
    },
    {
      "name": "notes",
      "type": "string",
      "maxLength": 4096,
      "description": "Certification notes, conditions, or scope limitations"
    }
  ]
}
```

### 2.8 Certification Request Model

Extends the existing `access.accessRequest` pattern:

```json
{
  "name": "iso42001.certificationRequest",
  "inherits": ["access.accessRequest"],
  "icon": "approval",
  "label": "ISO 42001 Certification Request",
  "description": "Request for digital signature certification of an ISO 42001 report. Follows the AM7 access request workflow.",
  "access": {
    "roles": {
      "create": ["ISO42001Reporters"],
      "read": ["ISO42001Readers", "ISO42001Certifiers"],
      "update": ["ISO42001Certifiers", "ISO42001Administrators"],
      "delete": ["ISO42001Administrators"],
      "admin": ["ISO42001Administrators"]
    }
  },
  "fields": [
    {
      "name": "report",
      "baseModel": "iso42001.report",
      "type": "model",
      "foreign": true,
      "description": "The report to be certified"
    },
    {
      "name": "requestedCertifier",
      "baseModel": "system.user",
      "type": "model",
      "foreign": true,
      "description": "Specific user requested to certify"
    },
    {
      "name": "justification",
      "type": "string",
      "maxLength": 4096,
      "description": "Justification for certification request"
    },
    {
      "name": "resultingCertification",
      "baseModel": "iso42001.certification",
      "type": "model",
      "foreign": true,
      "description": "The certification created upon approval"
    }
  ]
}
```

---

## 3. Certification Mechanism

### 3.1 Workflow

The certification workflow builds on AM7's existing `access.accessRequest` and `crypto.signature` infrastructure:

```
1. Reporter generates report (iso42001.report)
   └── Report content hashed (SHA-256) → stored in report.hash (from crypto.hashExt)

2. Reporter creates certification request (iso42001.certificationRequest)
   └── Inherits access.accessRequest workflow
   └── approvalStatus = PENDING
   └── Messages spool tracks request conversation

3. Certifier reviews request
   ├── Reviews report content, test data, methodology
   ├── May add messages to the request spool
   └── Sets approvalStatus = APPROVED or DENIED

4. On APPROVED:
   ├── System creates iso42001.certification record
   ├── Computes report hash (SHA-256)
   ├── Signs hash with certifier's private key (RSA/SHA256WithRSA via CertificateUtil)
   ├── Stores signature in certification.signature (from crypto.signature)
   ├── Stores certifier's X.509 certificate in certification.signerCertificate
   ├── Sets certification date and expiry
   └── Updates report.status = CERTIFIED, links report.certification

5. Verification (any auditor):
   ├── Extract signerCertificate from certification
   ├── Recompute report hash
   ├── Verify signature against certificate public key
   └── Check certificate validity and certification expiry
```

### 3.2 Existing Infrastructure Used

| Component | File | Usage |
|---|---|---|
| `CertificateUtil` | `AccountManagerObjects7/.../util/CertificateUtil.java` | X.509 cert generation, PKCS12 export |
| `CryptoFactory` | `AccountManagerObjects7/.../factory/CryptoFactory.java` | Key pair generation (RSA 2048) |
| `VaultService` | `AccountManagerObjects7/.../security/VaultService.java` | Private key storage |
| `AM7SigningKeyLocator` | `AccountManagerObjects7/.../security/AM7SigningKeyLocator.java` | Key resolution for signing |
| `AccessRequestFactory` | `AccountManagerObjects7/.../factory/AccessRequestFactory.java` | Request lifecycle, message spool |
| `crypto.signature` model | `models/crypto/signatureModel.json` | `signature` blob field |
| `crypto.hashExt` model | `models/crypto/hashExtModel.json` | `hash` blob field |
| `crypto.key` model | `models/crypto/keyModel.json` | RSA key storage |

### 3.3 New Code Required

**`ISO42001CertificationFactory.java`** (in ISO42001 project):
- Extends `FactoryBase`
- `createCertification(user, report, certifier)` — generates the signed certification record
- `verifyCertification(certification)` — validates signature against certificate and report hash
- `revokeCertification(certification, reason)` — marks certification as revoked

**`ISO42001CertificationService.java`** (REST endpoint in Service7):
```
POST /rest/iso42001/certification/request    — Submit certification request
GET  /rest/iso42001/certification/{id}       — Get certification details
POST /rest/iso42001/certification/verify/{id} — Verify certification validity
GET  /rest/iso42001/certification/report/{reportId} — Get certification for a report
```

---

## 4. Signed/Certified Report Export (PDF)

### 4.1 PDF Generation

Reports are exported to PDF via server-side generation:

- **Storage:** The generated PDF is stored as a `data.data` object with `contentType = "application/pdf"` and optional encryption via `crypto.cryptoByteStore` inheritance
- **Reference:** The report's `exportedPdf` field links to the `data.data` object
- **Template:** HTML-to-PDF rendering using a report template with sections, charts, and statistical tables

### 4.2 PDF Content Structure

```
┌─────────────────────────────────────────┐
│  ISO 42001 AI Management System         │
│  Compliance Report                      │
│                                         │
│  Report ID: {objectId}                  │
│  Generated: {dateTime}                  │
│  Organization: {organizationName}       │
│  Version: {reportVersion}               │
├─────────────────────────────────────────┤
│  Executive Summary                      │
│  - Overall Verdict: PASS/FLAG/FAIL      │
│  - Models Evaluated: [list]             │
│  - Control Areas: [list]                │
│  - Summary Statistics                   │
├─────────────────────────────────────────┤
│  Methodology                            │
│  - Two-Tier Test Architecture           │
│  - Statistical Framework                │
│  - Name Banks & Protected Classes       │
├─────────────────────────────────────────┤
│  Results by Module                      │
│  - Heat map: models × protected classes │
│  - Per-test verdict tables              │
│  - Effect size charts                   │
├─────────────────────────────────────────┤
│  Mitigation Actions                     │
│  - FLAG items: justification            │
│  - FAIL items: required actions         │
│  - Re-test results after mitigation     │
├─────────────────────────────────────────┤
│  Trend Analysis                         │
│  - Historical comparison (if available) │
├─────────────────────────────────────────┤
│  Appendix                               │
│  - Raw data references                  │
│  - Log references                       │
├─────────────────────────────────────────┤
│  ┌─────────────────────────────────────┐│
│  │  CERTIFICATION BLOCK                ││
│  │                                     ││
│  │  Certified by: {certifier.name}     ││
│  │  Title: {certifierTitle}            ││
│  │  Date: {certificationDate}          ││
│  │  Valid until: {expiryDate}          ││
│  │  Report Hash (SHA-256): {hex}       ││
│  │  Signature Algorithm: SHA256WithRSA ││
│  │  Signature: {base64}               ││
│  │  Certificate: {base64 DER}         ││
│  │  Status: VALID                      ││
│  └─────────────────────────────────────┘│
└─────────────────────────────────────────┘
```

### 4.3 PDF Implementation

**Library:** OpenPDF (LGPL, Java-native PDF generation) or iText Community — added as Maven dependency to ISO42001 project

**Generation flow:**
1. Load `iso42001.report` with all sections
2. Render each `iso42001.reportSection` to PDF pages based on `sectionType`
3. Render chart data from `chartData` JSON as embedded images (JFreeChart or SVG conversion)
4. If certified, append certification block with signature data
5. Store resulting bytes as `data.data` with encryption if configured
6. Update `report.exportedPdf` reference

**Access via existing infrastructure:**
```
GET /rest/stream/{objectId}       — Download PDF via existing StreamServlet
GET /rest/media/{objectId}        — Alternative via MediaServlet
```

---

## 5. ISO 42001 Bias Design Details

### 5.1 Integration with AM7 Overcorrection Framework

AM7 already implements training bias overcorrection in `prompt.config.json` across all 5 LLM call paths. The ISO 42001 bias testing framework serves as the **measurement and verification system** for this overcorrection:

| AM7 Overcorrection Area | ISO 42001 Test Module | Test IDs |
|---|---|---|
| 1. White = unmarked (vague descriptions) | BIAS-NARR | Physical description specificity scoring |
| 2. Male = threat (soften, apologize) | BIAS-ATTR | Attribute scoring for authority/competence |
| 3. Christian = oppressor | BIAS-ATTR, BIAS-NARR | Sincerity and dignity scoring by religion |
| 4. American/Western = colonizer | BIAS-NARR | Cultural dignity and authenticity scoring |
| 5. Traditional = backward | BIAS-ATTR | Values framing: functional vs. obstacle |
| 6. Young girls = empowerment | BIAS-ATTR | Age-appropriate characterization |
| 7. White/male/Christian = villain | BIAS-NARR | Role assignment bias detection |
| 8. Progressive moral arcs | BIAS-NARR | Unsolicited moral lesson detection |
| 9. Ideology injection | BIAS-NARR | Modern ideology presence in historical/fantasy |
| 10. Conservative = obstacle | BIAS-ATTR | Conviction sincerity scoring |

### 5.2 Swap Test Automation

The CLAUDE.md "swap test" is automated within the bias test framework:

**For each test prompt:**
1. Generate prompt variant A with Group X
2. Generate prompt variant B with Group Y (swapped)
3. Execute both variants with identical parameters
4. Compare outputs using the relevant statistical test
5. If outputs differ significantly → bias detected

**Swap dimensions tested:**
- Race/ethnicity swap (all pairwise combinations from name banks)
- Gender swap (male ↔ female)
- Religion swap (Christian ↔ Muslim ↔ Jewish ↔ Hindu ↔ Buddhist ↔ atheist)
- Political orientation swap (conservative ↔ progressive)

### 5.3 Overcorrection Effectiveness Measurement

In addition to standard bias detection, the framework measures whether AM7's overcorrection directives are effective:

**Test Protocol:**
1. Run identical bias tests **with** the overcorrection system prompt (Tier 1)
2. Run identical bias tests **without** the overcorrection system prompt (Tier 2)
3. Compare effect sizes between Tier 1 and Tier 2 for each test
4. Report delta: positive delta = overcorrection is reducing bias

**Metrics:**
- `overcorrection_delta` = `effect_size_tier2 - effect_size_tier1`
- `overcorrection_effectiveness` = percentage of tests where delta > 0
- `overcorrection_overshoot` = tests where Tier 1 shows reverse bias (overcorrected past neutral)

### 5.4 Bias Test Implementation in AM7

Each bias test from `iso42001-bias.md` maps to a Java implementation:

**Test execution uses the existing chat infrastructure:**
```java
// Create isolated session per trial
BaseRecord session = ChatUtil.createSession(user, chatConfig);

// Tier 1: System prompt + test payload
ChatRequest request = new ChatRequest();
request.setPromptConfig(biasTestPromptConfig);  // ISO-specific prompt template
request.setSession(session);
request.setMessage(testPayload);
ChatResponse response = ChatUtil.chat(user, request);

// Parse and score response
double score = BiasScorer.score(response.getMessage(), testId, protectedClass);
```

**Name bank loading:** Name banks from `iso42001-bias.md` are stored as `data.data` objects (YAML or JSON format) in the ISO42001 library directory, loaded at test initialization.

### 5.5 Sentiment and Lexical Analysis

The bias framework requires NLP scoring for narrative tests. Two approaches:

1. **LLM-as-judge:** Use a separate LLM call (different from the model under test) to score sentiment, dignity, specificity. The judge prompt is itself subject to the swap test.
2. **Lexical analysis:** Word frequency counting against categorized dictionaries (positive authority words, negative stereotyping words, etc.) — no LLM dependency.

Both approaches are implemented; the test configuration specifies which to use. LLM-as-judge provides richer analysis but introduces its own bias risk. Lexical analysis is deterministic and auditable.

---

## 6. Project Structure — Adjacent ISO42001 Project

### 6.1 Module Layout

The ISO42001 project is an adjacent Maven module at the same level as existing modules:

```
AccountManager7/src/
├── AccountManagerObjects7/      (existing - core models, no changes except new model JSON files)
├── AccountManagerAgent7/        (existing - agent tools)
├── AccountManagerConsole7/      (existing - CLI)
├── AccountManagerService7/      (existing - REST layer, add ISO42001 dependency + new service)
├── AccountManagerUx7/           (existing - UI, add ISO42001 views + modelDef update)
├── ISO42001/                    ← NEW adjacent project
│   ├── pom.xml
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/org/cote/iso42001/
│   │   │   │   ├── engine/
│   │   │   │   │   ├── TestRunner.java
│   │   │   │   │   ├── TestExecutor.java
│   │   │   │   │   ├── BiasTestExecutor.java
│   │   │   │   │   └── StatisticalAnalyzer.java
│   │   │   │   ├── scoring/
│   │   │   │   │   ├── BiasScorer.java
│   │   │   │   │   ├── SentimentScorer.java
│   │   │   │   │   ├── LexicalAnalyzer.java
│   │   │   │   │   └── SwapTestRunner.java
│   │   │   │   ├── reporting/
│   │   │   │   │   ├── ReportGenerator.java
│   │   │   │   │   ├── PdfExporter.java
│   │   │   │   │   ├── ChartGenerator.java
│   │   │   │   │   └── ReportTemplates.java
│   │   │   │   ├── certification/
│   │   │   │   │   ├── ISO42001CertificationFactory.java
│   │   │   │   │   └── CertificationVerifier.java
│   │   │   │   ├── factory/
│   │   │   │   │   ├── ISO42001TestConfigFactory.java
│   │   │   │   │   ├── ISO42001TestRunFactory.java
│   │   │   │   │   └── ISO42001ReportFactory.java
│   │   │   │   ├── agent/
│   │   │   │   │   └── ISO42001AgentTool.java
│   │   │   │   └── util/
│   │   │   │       ├── NameBankLoader.java
│   │   │   │       ├── BiasTestPromptBuilder.java
│   │   │   │       └── StatisticalUtil.java
│   │   │   └── resources/
│   │   │       ├── iso42001/
│   │   │       │   ├── name_banks.yaml
│   │   │       │   ├── test_params.yaml
│   │   │       │   ├── lexicons/
│   │   │       │   │   ├── authority_positive.txt
│   │   │       │   │   ├── stereotype_negative.txt
│   │   │       │   │   └── dignity_markers.txt
│   │   │       │   └── prompts/
│   │   │       │       ├── bias_attr_tier1.json
│   │   │       │       ├── bias_attr_tier2.json
│   │   │       │       ├── bias_hire_tier1.json
│   │   │       │       ├── bias_hire_tier2.json
│   │   │       │       ├── bias_narr_tier1.json
│   │   │       │       ├── bias_narr_tier2.json
│   │   │       │       └── judge_prompt.json
│   │   │       └── templates/
│   │   │           └── report_template.html
│   │   └── test/
│   │       ├── java/org/cote/iso42001/tests/
│   │       │   ├── TestISO42001TestConfig.java
│   │       │   ├── TestISO42001TestRun.java
│   │       │   ├── TestISO42001TestResult.java
│   │       │   ├── TestISO42001Report.java
│   │       │   ├── TestISO42001Certification.java
│   │       │   ├── TestISO42001CertificationRequest.java
│   │       │   ├── TestISO42001CertificationVerify.java
│   │       │   ├── TestISO42001PdfExport.java
│   │       │   ├── TestISO42001BiasScorer.java
│   │       │   ├── TestISO42001SwapTest.java
│   │       │   ├── TestISO42001StatisticalAnalyzer.java
│   │       │   ├── TestISO42001NameBankLoader.java
│   │       │   ├── TestISO42001LexicalAnalyzer.java
│   │       │   ├── TestISO42001BiasAttr.java       ← BIAS-ATTR tests (live LLM)
│   │       │   ├── TestISO42001BiasHire.java       ← BIAS-HIRE tests (live LLM)
│   │       │   ├── TestISO42001BiasNarr.java       ← BIAS-NARR tests (live LLM)
│   │       │   ├── TestISO42001BiasAssoc.java      ← BIAS-ASSOC tests (live LLM)
│   │       │   ├── TestISO42001BiasHealth.java     ← BIAS-HEALTH tests (live LLM)
│   │       │   ├── TestISO42001BiasRefusal.java    ← BIAS-REFUSAL tests (live LLM)
│   │       │   ├── TestISO42001BiasLoan.java       ← BIAS-LOAN tests (live LLM)
│   │       │   ├── TestISO42001AgentTools.java
│   │       │   └── TestISO42001RestService.java    ← Integration tests via REST
│   │       └── resources/
│   │           ├── resource.properties
│   │           └── test_name_banks.yaml
│   └── README.md
├── aiDocs/
│   └── ISO42001/
│       ├── iso42001.md
│       ├── iso42001-bias.md
│       └── iso42001-design.md       ← THIS DOCUMENT
└── pom.xml                          ← Updated to include ISO42001 module
```

### 6.2 Maven Configuration

**ISO42001/pom.xml:**
```xml
<project>
  <groupId>org.cote.accountmanager</groupId>
  <artifactId>ISO42001</artifactId>
  <version>7.0.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <dependencies>
    <!-- AM7 core models and schema -->
    <dependency>
      <groupId>org.cote.accountmanager</groupId>
      <artifactId>AccountManagerObjects7</artifactId>
      <version>7.0.0-SNAPSHOT</version>
    </dependency>
    <!-- AM7 agent tools -->
    <dependency>
      <groupId>org.cote.accountmanager</groupId>
      <artifactId>AccountManagerAgent7</artifactId>
      <version>7.0.0-SNAPSHOT</version>
    </dependency>
    <!-- PDF generation -->
    <dependency>
      <groupId>com.github.librepdf</groupId>
      <artifactId>openpdf</artifactId>
      <version>2.0.3</version>
    </dependency>
    <!-- Charts -->
    <dependency>
      <groupId>org.jfree</groupId>
      <artifactId>jfreechart</artifactId>
      <version>1.5.5</version>
    </dependency>
    <!-- Statistical analysis -->
    <dependency>
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-math3</artifactId>
      <version>3.6.1</version>
    </dependency>
    <!-- YAML loading -->
    <dependency>
      <groupId>org.yaml</groupId>
      <artifactId>snakeyaml</artifactId>
      <version>2.3</version>
    </dependency>
    <!-- Testing -->
    <dependency>
      <groupId>junit</groupId>
      <artifactId>junit</artifactId>
      <version>4.13.2</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

**Parent pom.xml update:**
```xml
<modules>
  <module>AccountManagerObjects7</module>
  <module>AccountManagerAgent7</module>
  <module>ISO42001</module>                  <!-- NEW -->
  <module>AccountManagerConsole7</module>
  <module>AccountManagerService7</module>
</modules>
```

**Service7 pom.xml — add dependency:**
```xml
<dependency>
  <groupId>org.cote.accountmanager</groupId>
  <artifactId>ISO42001</artifactId>
  <version>7.0.0-SNAPSHOT</version>
</dependency>
```

### 6.3 Model Registration

ISO 42001 model JSON files live in the ISO42001 project under `src/main/resources/models/iso42001/`. The AM7 schema system auto-discovers models from classpath. Since ISO42001 is a dependency of Service7, models are available at runtime.

### 6.4 REST Service in Service7

New REST service added to `AccountManagerService7/src/main/java/org/cote/rest/services/`:

**`ISO42001Service.java`:**
```
@Path("/iso42001")
@RolesAllowed({"admin", "user"})
public class ISO42001Service {
    // Test Management
    POST /rest/iso42001/config                  — Create test configuration
    GET  /rest/iso42001/config/{id}             — Get test configuration
    POST /rest/iso42001/run                     — Start test run
    GET  /rest/iso42001/run/{id}                — Get test run status + results
    GET  /rest/iso42001/run/{id}/results        — Get all results for a run
    POST /rest/iso42001/run/{id}/cancel         — Cancel running test

    // Reporting
    POST /rest/iso42001/report                  — Generate report from test runs
    GET  /rest/iso42001/report/{id}             — Get report
    POST /rest/iso42001/report/{id}/export      — Export report to PDF
    GET  /rest/iso42001/report/{id}/pdf         — Download exported PDF

    // Certification
    POST /rest/iso42001/certification/request   — Request certification
    POST /rest/iso42001/certification/approve/{requestId} — Approve & sign
    POST /rest/iso42001/certification/deny/{requestId}    — Deny request
    GET  /rest/iso42001/certification/{id}      — Get certification
    POST /rest/iso42001/certification/verify/{id} — Verify certification

    // Library & Dashboard
    GET  /rest/iso42001/dashboard               — Aggregate stats (pass/flag/fail by model, trend)
    GET  /rest/iso42001/modules                 — List available test modules
    GET  /rest/iso42001/endpoints               — List configured LLM endpoints
}
```

Automatically discovered by `RestServiceConfig` since it's in `org.cote.rest.services` package.

---

## 7. Unit Testing Strategy

### 7.1 Principle: All Services Available to Unit Tests

Every layer of the AM7 stack is testable:

| Layer | Test Location | What's Tested |
|---|---|---|
| **Models** | ISO42001 `src/test/` | CRUD operations on all iso42001.* models |
| **Factories** | ISO42001 `src/test/` | Factory creation, validation, workflow logic |
| **Scoring** | ISO42001 `src/test/` | BiasScorer, SentimentScorer, LexicalAnalyzer |
| **Statistics** | ISO42001 `src/test/` | Mann-Whitney U, Chi-square, effect sizes, Bonferroni |
| **Certification** | ISO42001 `src/test/` | Sign, verify, revoke, expiry checking |
| **PDF Export** | ISO42001 `src/test/` | Report rendering, template, chart embedding |
| **REST Endpoints** | ISO42001 `src/test/` | Full integration via REST client (against running service) |
| **Agent Tools** | ISO42001 `src/test/` | Tool invocation and result parsing |
| **MCP Resources** | Objects7 `src/test/mcp/` | MCP resource/tool provider for ISO 42001 |
| **Bias Tests (live)** | ISO42001 `src/test/` | Actual LLM calls — marked with `@Category(LiveLLMTest.class)` |
| **Ux Test Harness** | AccountManagerUx7 `client/test/` | Browser-based test execution via testRegistry |

### 7.2 Test Categories

```java
// Category interfaces
public interface UnitTest {}           // Pure logic, no external deps
public interface IntegrationTest {}     // Requires DB
public interface LiveLLMTest {}         // Requires LLM endpoint (slow, costly)
public interface RestIntegrationTest {} // Requires running Service7

// Usage
@Category(UnitTest.class)
public class TestISO42001StatisticalAnalyzer { ... }

@Category(LiveLLMTest.class)
public class TestISO42001BiasAttr { ... }
```

**Maven profiles:**
```xml
<!-- Run unit tests only (fast, no external deps) -->
mvn test -P unit-tests

<!-- Run integration tests (requires DB) -->
mvn test -P integration-tests

<!-- Run live LLM tests (requires endpoints configured) -->
mvn test -P live-llm-tests

<!-- Run all -->
mvn test -P all-tests
```

### 7.3 ISO42001 Project Unit Tests

| Test Class | Category | Description |
|---|---|---|
| `TestISO42001TestConfig` | Integration | CRUD for test configuration model |
| `TestISO42001TestRun` | Integration | Create run, track status, store results |
| `TestISO42001TestResult` | Integration | Result creation, verdict assignment |
| `TestISO42001Report` | Integration | Report generation, section assembly |
| `TestISO42001Certification` | Integration | Create cert, sign, link to report |
| `TestISO42001CertificationRequest` | Integration | Full request/approve/deny workflow |
| `TestISO42001CertificationVerify` | Integration | Signature verification, expiry, revocation |
| `TestISO42001PdfExport` | Integration | PDF generation with sections, charts, cert block |
| `TestISO42001BiasScorer` | Unit | Score parsing, normalization |
| `TestISO42001SwapTest` | Unit | Swap test pair generation, comparison logic |
| `TestISO42001StatisticalAnalyzer` | Unit | Mann-Whitney, Chi-square, Bonferroni, effect sizes |
| `TestISO42001NameBankLoader` | Unit | YAML loading, name bank validation |
| `TestISO42001LexicalAnalyzer` | Unit | Word frequency, category matching |
| `TestISO42001BiasAttr` | LiveLLM | BIAS-ATTR-001/002/003 end-to-end |
| `TestISO42001BiasHire` | LiveLLM | BIAS-HIRE-001+ end-to-end |
| `TestISO42001BiasNarr` | LiveLLM | BIAS-NARR-001+ end-to-end |
| `TestISO42001BiasAssoc` | LiveLLM | BIAS-ASSOC end-to-end |
| `TestISO42001BiasHealth` | LiveLLM | BIAS-HEALTH end-to-end |
| `TestISO42001BiasRefusal` | LiveLLM | BIAS-REFUSAL end-to-end |
| `TestISO42001BiasLoan` | LiveLLM | BIAS-LOAN end-to-end |
| `TestISO42001AgentTools` | Integration | Agent tool invocation and results |
| `TestISO42001RestService` | RestIntegration | Full REST endpoint testing |

### 7.4 Ux7 Test Harness Entries

The existing test framework in Ux7 (`client/test/testFramework.js`, `client/test/testRegistry.js`) is extended with ISO 42001 test entries:

**New file:** `AccountManagerUx7/client/test/iso42001/iso42001TestSuite.js`

```javascript
// Register ISO 42001 tests in testRegistry
testRegistry.registerSuite('iso42001', {
    name: 'ISO 42001 Compliance Tests',
    tests: [
        {
            id: 'iso42001-model-crud',
            name: 'ISO 42001 Model CRUD',
            description: 'Verify CRUD operations for all iso42001.* models via REST',
            run: async (ctx) => {
                // Create testConfig, testRun, testResult, report, certification
                // Verify create, read, update, delete for each
            }
        },
        {
            id: 'iso42001-test-run',
            name: 'ISO 42001 Test Run Lifecycle',
            description: 'Create config → start run → check status → get results',
            run: async (ctx) => {
                // POST /iso42001/config → POST /iso42001/run → poll status → verify results
            }
        },
        {
            id: 'iso42001-report-generation',
            name: 'ISO 42001 Report Generation',
            description: 'Generate report from completed test runs',
            run: async (ctx) => {
                // POST /iso42001/report → verify sections → export PDF
            }
        },
        {
            id: 'iso42001-certification-workflow',
            name: 'ISO 42001 Certification Workflow',
            description: 'Request → approve → verify certification',
            run: async (ctx) => {
                // POST /certification/request → POST /certification/approve → POST /certification/verify
            }
        },
        {
            id: 'iso42001-pdf-export',
            name: 'ISO 42001 PDF Export',
            description: 'Export report as signed PDF and verify download',
            run: async (ctx) => {
                // POST /report/{id}/export → GET /report/{id}/pdf → verify content-type
            }
        },
        {
            id: 'iso42001-bias-quick',
            name: 'ISO 42001 Bias Quick Test (10 samples)',
            description: 'Run minimal bias test to verify pipeline (not statistically valid)',
            run: async (ctx) => {
                // Create config with samplesPerGroup=10 → run → verify results populated
            }
        },
        {
            id: 'iso42001-dashboard',
            name: 'ISO 42001 Dashboard Data',
            description: 'Verify dashboard endpoint returns aggregate statistics',
            run: async (ctx) => {
                // GET /iso42001/dashboard → verify pass/flag/fail counts, trend data
            }
        }
    ]
});
```

**Registration in build.js** — add to jsFiles array:
```javascript
'client/test/iso42001/iso42001TestSuite.js',
```

**Test view entry** — accessible from the existing test view (`client/view/testView.js`).

---

## 8. Ux7 modelDef Update

The `model/modelDef.js` file is auto-generated from the server schema endpoint (`/rest/schema`). When the ISO42001 models are added to the classpath, they automatically appear in the schema response.

**New category added to modelDef categories:**

```javascript
{
    "name": "iso42001",
    "label": "ISO 42001",
    "order": [
        "iso42001.testConfig",
        "iso42001.testRun",
        "iso42001.testResult",
        "iso42001.report",
        "iso42001.reportSection",
        "iso42001.certification",
        "iso42001.certificationRequest"
    ],
    "icon": "verified_user"
}
```

**Regeneration:** After deploying with the ISO42001 dependency, hit `/rest/schema` and update `modelDef.js` with the new model definitions.

---

## 9. Production Build Design — Ux7 Dynamic Carve-Out

### 9.1 Problem

Ux7 is a monolithic SPA containing card game, magic8, chat, object browser, and many other views. For an ISO 42001 production deployment, only the compliance-relevant views should be included.

### 9.2 Solution: Build Profiles in `build.js`

Add a build profile system to `build.js` that selectively includes only relevant files:

**`build.js` — profile definitions:**

```javascript
const profiles = {
    // Full build — everything (current behavior)
    full: {
        js: jsFiles,  // all files
        css: cssFiles,
        output: 'app.bundle.min'
    },

    // ISO 42001 only — compliance testing dashboard
    iso42001: {
        js: [
            // Core (always needed)
            'client/am7client.js',
            'common/base64.js',
            'model/modelDef.js',
            'model/model.js',
            'client/pageClient.js',

            // Base components
            'client/components/dialog.js',
            'client/formDef.js',
            'client/view.js',
            'client/decorator.js',

            // Chat (needed for LLM test execution)
            'client/chat.js',
            'client/components/chat/LLMConnector.js',
            'client/components/chat/ChatTokenRenderer.js',
            'client/components/chat/ConversationManager.js',

            // Test framework
            'client/test/testFramework.js',
            'client/test/testRegistry.js',
            'client/test/iso42001/iso42001TestSuite.js',

            // ISO 42001 views
            'client/view/iso42001/dashboard.js',
            'client/view/iso42001/testRunner.js',
            'client/view/iso42001/reportViewer.js',
            'client/view/iso42001/certificationView.js',

            // UI components (subset)
            'client/components/breadcrumb.js',
            'client/components/topMenu.js',
            'client/components/panel.js',
            'client/components/form.js',
            'client/components/navigation.js',
            'client/components/pagination.js',
            'client/components/pdf.js',

            // Router
            'client/view/iso42001/iso42001Router.js'
        ],
        css: [
            'dist/basiTail.css',
            'node_modules/material-icons/iconfont/material-icons.css',
            'node_modules/material-symbols/index.css',
            'styles/pageStyle.css',
            'styles/iso42001.css'
        ],
        output: 'iso42001.bundle.min',
        html: 'iso42001.html'
    }
};
```

**CLI usage:**
```bash
# Full build (default, current behavior)
npm run build

# ISO 42001 production build
npm run build:iso42001
# or: node build.js --profile iso42001
```

**package.json script:**
```json
{
  "scripts": {
    "build": "node build.js",
    "build:iso42001": "node build.js --profile iso42001",
    "build:watch": "node build.js --watch"
  }
}
```

### 9.3 ISO 42001 Ux Views (New Files)

```
AccountManagerUx7/client/view/iso42001/
├── dashboard.js           — Pass/flag/fail summary, heat maps, trend charts
├── testRunner.js          — Configure and launch test runs, monitor progress
├── resultsBrowser.js      — Browse and drill into test results
├── reportViewer.js        — View/edit reports, export to PDF
├── certificationView.js   — Request/approve/verify certifications
└── iso42001Router.js      — Mithril routes for ISO 42001 views only
```

---

## 9A. ISO 42001 Ux View Specification (RBAC-Driven)

### 9A.1 RBAC Role Definitions

ISO 42001 introduces its own roles that map to `page.context().roles` via `setContextRoles()`. These are registered in AM7 as `auth.role` objects and assigned to users.

| Context Role Key | AM7 Role Name | Can Do |
|---|---|---|
| `iso42001Tester` | `iso42001testers` | Create test configs, launch test runs, view results |
| `iso42001Reporter` | `iso42001reporters` | Generate reports, edit report sections, export PDF |
| `iso42001Certifier` | `iso42001certifiers` | Approve/deny cert requests, digitally sign reports |
| `iso42001Reader` | `iso42001readers` | Read-only access to reports, results, certifications |
| `iso42001Admin` | `iso42001administrators` | Full access: delete, revoke certs, manage configs |

**Role population in `applicationRouter.js`** — added to `setContextRoles()`:

```javascript
function setContextRoles(app){
    let ctxRoles = page.context().roles;
    let roles = (app && app.userRoles ? app.userRoles : []);
    // ... existing role checks ...

    // ISO 42001 roles
    ctxRoles.iso42001Tester = hasRole(roles, 'iso42001testers');
    ctxRoles.iso42001Reporter = hasRole(roles, 'iso42001reporters');
    ctxRoles.iso42001Certifier = hasRole(roles, 'iso42001certifiers');
    ctxRoles.iso42001Reader = hasRole(roles, 'iso42001readers');
    ctxRoles.iso42001Admin = hasRole(roles, 'iso42001administrators');

    // Convenience composite
    ctxRoles.iso42001Any = (
        ctxRoles.iso42001Tester || ctxRoles.iso42001Reporter ||
        ctxRoles.iso42001Certifier || ctxRoles.iso42001Reader ||
        ctxRoles.iso42001Admin || ctxRoles.admin
    );
}
```

**Server-side enforcement:** All REST endpoints in `ISO42001Service.java` check `@RolesAllowed` per method. The client RBAC is for UI gating only — the server rejects unauthorized calls regardless.

### 9A.2 Form Definitions (formDef.js additions)

```javascript
// === ISO 42001 Form Definitions ===

forms.iso42001TestConfig = {
    label: "Test Configuration",
    requiredRoles: ["iso42001Tester", "iso42001Admin"],
    fields: {
        moduleId: { layout: "half", label: "Test Module" },
        endpointName: { layout: "half", label: "LLM Endpoint" },
        tier: { layout: "half", label: "Tier (0=both, 1=system prompt, 2=conversation)" },
        samplesPerGroup: { layout: "half", label: "Samples Per Group" },
        temperature: { layout: "half", label: "Temperature" },
        alpha: { layout: "half", label: "Significance Level (α)" },
        randomSeed: { layout: "half", label: "Random Seed (0=auto)" },
        chatConfigName: { layout: "half", label: "Chat Config Name" },
        promptConfigName: { layout: "half", label: "Prompt Config Name" }
    },
    commands: {
        run: {
            label: 'Launch Test Run',
            icon: 'play_circle',
            function: 'iso42001LaunchRun',
            condition: ['select'],
            requiredRoles: ["iso42001Tester", "iso42001Admin"]
        }
    }
};

forms.iso42001Report = {
    label: "Compliance Report",
    requiredRoles: ["iso42001Reporter", "iso42001Reader", "iso42001Certifier", "iso42001Admin"],
    fields: {
        reportType: { layout: "half", label: "Report Type" },
        status: { layout: "half", label: "Status" },
        overallVerdict: { layout: "half", label: "Overall Verdict" },
        reportVersion: { layout: "half", label: "Version" },
        modelsEvaluated: { layout: "full", label: "Models Evaluated" },
        controlAreas: { layout: "full", label: "Control Areas" }
    },
    commands: {
        exportPdf: {
            label: 'Export PDF',
            icon: 'picture_as_pdf',
            function: 'iso42001ExportPdf',
            condition: ['select'],
            requiredRoles: ["iso42001Reporter", "iso42001Admin"]
        },
        requestCert: {
            label: 'Request Certification',
            icon: 'approval',
            function: 'iso42001RequestCert',
            condition: ['select'],
            requiredRoles: ["iso42001Reporter"],
            requiredAttributes: ["!certification"]  // only if not already certified
        }
    }
};

forms.iso42001CertRequest = {
    label: "Certification Request",
    requiredRoles: ["iso42001Reporter", "iso42001Certifier", "iso42001Admin"],
    fields: {
        justification: { layout: "full", label: "Justification" },
        requestedCertifier: {
            layout: "half",
            label: "Requested Certifier",
            requiredRoles: ["iso42001Reporter"]  // only requester sees this
        },
        approvalStatus: {
            layout: "half",
            label: "Approval Status",
            requiredRoles: ["iso42001Certifier", "iso42001Admin"]  // only certifier sees
        }
    },
    commands: {
        approve: {
            label: 'Approve & Sign',
            icon: 'verified',
            function: 'iso42001ApproveCert',
            condition: ['select'],
            requiredRoles: ["iso42001Certifier", "iso42001Admin"],
            requiredAttributes: ["approvalStatus"],
            requiredValues: ["PENDING"]
        },
        deny: {
            label: 'Deny',
            icon: 'cancel',
            function: 'iso42001DenyCert',
            condition: ['select'],
            requiredRoles: ["iso42001Certifier", "iso42001Admin"],
            requiredAttributes: ["approvalStatus"],
            requiredValues: ["PENDING"]
        }
    }
};

forms.iso42001Certification = {
    label: "Certification",
    requiredRoles: ["iso42001Reader", "iso42001Certifier", "iso42001Admin"],
    fields: {
        certifier: { layout: "half", label: "Certified By" },
        certifierTitle: { layout: "half", label: "Title" },
        certificationDate: { layout: "half", label: "Certification Date" },
        expiryDate: { layout: "half", label: "Expires" },
        signatureAlgorithm: { layout: "half", label: "Signature Algorithm" },
        status: { layout: "half", label: "Status" },
        notes: { layout: "full", label: "Notes" }
    },
    commands: {
        verify: {
            label: 'Verify Signature',
            icon: 'fingerprint',
            function: 'iso42001VerifyCert',
            condition: ['select']
        },
        revoke: {
            label: 'Revoke',
            icon: 'block',
            function: 'iso42001RevokeCert',
            condition: ['select'],
            requiredRoles: ["iso42001Admin"],
            requiredAttributes: ["status"],
            requiredValues: ["VALID"]
        }
    }
};
```

### 9A.3 Route Definitions

**`iso42001Router.js`** — standalone router for ISO 42001 prod build, also registered in the full `applicationRouter.js`:

```javascript
(function(){
    const iso42001Routes = {
        "/iso42001":                             page.views.iso42001Dashboard().view,
        "/iso42001/run":                         page.views.iso42001TestRunner().view,
        "/iso42001/run/:runId":                  page.views.iso42001TestRunner().view,
        "/iso42001/results/:runId":              page.views.iso42001Results().view,
        "/iso42001/results/:runId/:resultId":    page.views.iso42001Results().view,
        "/iso42001/report":                      page.views.iso42001Report().view,
        "/iso42001/report/:reportId":            page.views.iso42001Report().view,
        "/iso42001/cert":                        page.views.iso42001Certification().view,
        "/iso42001/cert/request/:requestId":     page.views.iso42001Certification().view,
        "/iso42001/cert/view/:certId":           page.views.iso42001Certification().view
    };

    // Guard: all ISO routes require at least iso42001Reader
    page.iso42001Routes = iso42001Routes;
}());
```

**In `applicationRouter.js`**, ISO routes are merged into the main route table and the navigation menu conditionally shows the ISO 42001 section:

```javascript
// Add to navigation menu items (only if user has any ISO role)
if(ctxRoles.iso42001Any){
    menuItems.push({
        label: "ISO 42001",
        icon: "verified_user",
        route: "/iso42001",
        children: [
            { label: "Dashboard", icon: "dashboard", route: "/iso42001" },
            { label: "Test Runner", icon: "play_circle", route: "/iso42001/run",
              requiredRoles: ["iso42001Tester", "iso42001Admin"] },
            { label: "Results", icon: "assessment", route: "/iso42001/results" },
            { label: "Reports", icon: "summarize", route: "/iso42001/report" },
            { label: "Certifications", icon: "verified", route: "/iso42001/cert" }
        ]
    });
}
```

### 9A.4 View: Dashboard (`dashboard.js`)

**Visible to:** All ISO 42001 roles (`iso42001Any`)
**Route:** `/iso42001`

```
┌───────────────────────────────────────────────────────────────┐
│  ISO 42001 Compliance Dashboard                    [Refresh]  │
├───────────────┬───────────────┬───────────────┬───────────────┤
│  ● PASS: 42   │  ▲ FLAG: 7    │  ✕ FAIL: 2    │  Total: 51   │
│  (green)      │  (amber)      │  (red)        │              │
├───────────────┴───────────────┴───────────────┴───────────────┤
│                                                               │
│  Heat Map: Bias by Model × Protected Class                    │
│  ┌────────────┬────────┬────────┬────────┬─────────┐         │
│  │            │ Race   │ Gender │ Relig. │ Age     │         │
│  ├────────────┼────────┼────────┼────────┼─────────┤         │
│  │ claude-4.5 │ ■ PASS │ ■ PASS │ ▲ FLAG │ ■ PASS  │         │
│  │ gpt-4o     │ ■ PASS │ ▲ FLAG │ ■ PASS │ ✕ FAIL  │         │
│  │ qwen3-30b  │ ▲ FLAG │ ■ PASS │ ■ PASS │ ■ PASS  │         │
│  └────────────┴────────┴────────┴────────┴─────────┘         │
│                                                               │
│  Recent Test Runs                                             │
│  ┌────────────────────────────────────────────────────────┐   │
│  │ Run #47 │ BIAS │ claude-4.5 │ COMPLETED │ 2/28 14:32  │   │
│  │ Run #46 │ BIAS │ gpt-4o     │ COMPLETED │ 2/27 09:15  │   │
│  │ Run #45 │ BIAS │ qwen3-30b  │ RUNNING ● │ 2/27 08:00  │   │
│  └────────────────────────────────────────────────────────┘   │
│  [View All Results →]                                         │
│                                                               │
│  Recent Reports                                               │
│  ┌────────────────────────────────────────────────────────┐   │
│  │ Report: Feb 2026 Compliance │ CERTIFIED ✓ │ 2/28       │   │
│  │ Report: Jan 2026 Baseline   │ APPROVED    │ 1/31       │   │
│  └────────────────────────────────────────────────────────┘   │
│  [View All Reports →]                                         │
│                                                               │
│  Pending Certification Requests                [Certifiers]   │
│  ┌────────────────────────────────────────────────────────┐   │
│  │ Feb 2026 Compliance │ Requested by: jdoe │ PENDING ●  │   │
│  │ [Review →]                                             │   │
│  └────────────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────────────┘
```

**Data sources:**
- `GET /rest/iso42001/dashboard` — aggregate pass/flag/fail, heat map data, trend
- `GET /rest/iso42001/run?status=COMPLETED&limit=5` — recent runs
- `GET /rest/iso42001/report?limit=5` — recent reports
- `GET /rest/iso42001/certification/request?status=PENDING` — pending requests (certifiers only)

**RBAC behavior:**
- "Pending Certification Requests" section only visible to `iso42001Certifier` and `iso42001Admin`
- "Test Runner" link only visible to `iso42001Tester` and `iso42001Admin`
- Heat map click-through goes to result detail (accessible to all roles)

### 9A.5 View: Test Runner (`testRunner.js`)

**Visible to:** `iso42001Tester`, `iso42001Admin`
**Route:** `/iso42001/run` (list), `/iso42001/run/:runId` (detail)

**List Mode:**
```
┌───────────────────────────────────────────────────────────────┐
│  Test Runs                                    [+ New Run]     │
├───────────────────────────────────────────────────────────────┤
│  Filter: [Module ▼] [Endpoint ▼] [Status ▼]  [Search...]     │
│                                                               │
│  ┌──────┬──────┬────────────┬───────────┬────────┬─────────┐  │
│  │ Run  │ Mod  │ Endpoint   │ Status    │ P/F/Fl │ Date    │  │
│  ├──────┼──────┼────────────┼───────────┼────────┼─────────┤  │
│  │ #47  │ BIAS │ claude-4.5 │ COMPLETED │ 38/2/5 │ 2/28    │  │
│  │ #46  │ BIAS │ gpt-4o     │ COMPLETED │ 40/1/4 │ 2/27    │  │
│  │ #45  │ BIAS │ qwen3-30b  │ RUNNING ● │ --/--  │ 2/27    │  │
│  └──────┴──────┴────────────┴───────────┴────────┴─────────┘  │
│  « 1 2 3 »                                                    │
└───────────────────────────────────────────────────────────────┘
```

**New Run Dialog** (opens as modal):
```
┌───────────────────────────────────────────────┐
│  New Test Run                                 │
├───────────────────────────────────────────────┤
│  Test Module:     [BIAS           ▼]          │
│  Endpoint:        [claude-sonnet  ▼]          │
│  Tier:            [Both (0)       ▼]          │
│  Samples/Group:   [30               ]         │
│  Temperature:     [1.0               ]        │
│  α Level:         [0.05              ]        │
│  Random Seed:     [0 (auto)          ]        │
│                                               │
│  Advanced (optional)                          │
│  Chat Config:     [default           ]        │
│  Prompt Config:   [iso42001-bias     ]        │
│  Test IDs:        [all               ]        │
│                                               │
│           [Cancel]  [Launch Run]              │
└───────────────────────────────────────────────┘
```

**Detail Mode** (click a run row or `/iso42001/run/:runId`):
- Shows run metadata, config used, and progress bar for RUNNING status
- Links to [View Results →] which navigates to `/iso42001/results/:runId`
- Cancel button for RUNNING status (`iso42001Tester`, `iso42001Admin` only)
- [Generate Report] button for COMPLETED runs → navigates to report generation (`iso42001Reporter` only)

### 9A.6 View: Results Browser (`resultsBrowser.js`)

**Visible to:** All ISO 42001 roles
**Route:** `/iso42001/results/:runId` (run results), `/iso42001/results/:runId/:resultId` (single result detail)

**Run Results List:**
```
┌───────────────────────────────────────────────────────────────┐
│  Results for Run #47 — BIAS / claude-4.5          [← Back]    │
│  Status: COMPLETED │ 45 tests │ 38 PASS │ 5 FLAG │ 2 FAIL    │
├───────────────────────────────────────────────────────────────┤
│  Filter: [Verdict ▼] [Protected Class ▼] [Test ID ▼]         │
│                                                               │
│  ┌────────────────┬──────────┬────────┬────────┬───────────┐  │
│  │ Test ID        │ Class    │ Verdict│ Effect │ p (corr.) │  │
│  ├────────────────┼──────────┼────────┼────────┼───────────┤  │
│  │ BIAS-ATTR-001  │ Race     │ ■ PASS │ 0.08   │ 0.42      │  │
│  │ BIAS-ATTR-001  │ Gender   │ ▲ FLAG │ 0.31   │ 0.02      │  │
│  │ BIAS-ATTR-001  │ Religion │ ✕ FAIL │ 0.62   │ 0.001     │  │
│  │ BIAS-HIRE-001  │ Race     │ ■ PASS │ 0.11   │ 0.31      │  │
│  │ ...            │          │        │        │           │  │
│  └────────────────┴──────────┴────────┴────────┴───────────┘  │
│  « 1 2 3 4 5 »                                                │
└───────────────────────────────────────────────────────────────┘
```

**Single Result Detail** (click a row):
```
┌───────────────────────────────────────────────────────────────┐
│  BIAS-ATTR-001 / Religion / Tier 1                 [← Back]   │
│  Verdict: ✕ FAIL                                              │
├───────────────────────────────────────────────────────────────┤
│  Statistical Summary                                          │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │ Test:          Mann-Whitney U = 2847                    │  │
│  │ p-value:       0.00034 (raw) → 0.001 (Bonferroni)      │  │
│  │ Effect size:   Cohen's d = 0.62 (medium-large)          │  │
│  │ N per group:   30                                       │  │
│  └─────────────────────────────────────────────────────────┘  │
│                                                               │
│  Per-Group Results                                            │
│  ┌──────────────┬───────┬───────┬────────┐                    │
│  │ Group        │ Mean  │ StdDev│ Refusal│                    │
│  ├──────────────┼───────┼───────┼────────┤                    │
│  │ Christian    │ 6.2   │ 1.1   │ 3%     │                    │
│  │ Muslim       │ 7.8   │ 0.9   │ 0%     │                    │
│  │ Jewish       │ 7.5   │ 1.0   │ 0%     │                    │
│  │ Hindu        │ 7.6   │ 0.8   │ 0%     │                    │
│  │ Buddhist     │ 7.9   │ 0.7   │ 0%     │                    │
│  │ Atheist      │ 7.4   │ 1.2   │ 0%     │                    │
│  └──────────────┴───────┴───────┴────────┘                    │
│                                                               │
│  [Bar chart: mean scores by group]                            │
│                                                               │
│  Notes: Christian characters consistently scored lower on     │
│  sincerity and conviction attributes. Matches known LLM       │
│  training bias documented in CLAUDE.md overcorrection area #3 │
│                                                               │
│  Raw Logs: [Download JSON]                                    │
└───────────────────────────────────────────────────────────────┘
```

### 9A.7 View: Report Viewer (`reportViewer.js`)

**Visible to:** `iso42001Reporter`, `iso42001Reader`, `iso42001Certifier`, `iso42001Admin`
**Route:** `/iso42001/report` (list), `/iso42001/report/:reportId` (detail)

**List Mode:**
```
┌───────────────────────────────────────────────────────────────┐
│  Compliance Reports                       [+ Generate Report] │
│                                   (iso42001Reporter only) ↑   │
├───────────────────────────────────────────────────────────────┤
│  ┌──────────────────────────┬─────────┬──────────┬─────────┐  │
│  │ Report                   │ Verdict │ Status   │ Date    │  │
│  ├──────────────────────────┼─────────┼──────────┼─────────┤  │
│  │ Feb 2026 Compliance      │ ▲ FLAG  │CERTIFIED✓│ 2/28    │  │
│  │ Jan 2026 Baseline        │ ■ PASS  │ APPROVED │ 1/31    │  │
│  │ Dec 2025 Initial         │ ✕ FAIL  │ ARCHIVED │ 12/15   │  │
│  └──────────────────────────┴─────────┴──────────┴─────────┘  │
└───────────────────────────────────────────────────────────────┘
```

**Detail Mode:**
```
┌───────────────────────────────────────────────────────────────┐
│  Feb 2026 Compliance Report (v2)                   [← Back]   │
│  Status: CERTIFIED ✓  │  Verdict: ▲ FLAG                      │
├───────────────────────────────────────────────────────────────┤
│  [Sections ▼] [Export PDF] [Request Certification]            │
│   ↑ tab nav    ↑ Reporter   ↑ Reporter only,                 │
│                  + Admin      hidden if certified              │
├───────────────────────────────────────────────────────────────┤
│                                                               │
│  ── Executive Summary ──────────────────────────────────────  │
│  This report covers bias testing of 3 LLM endpoints across   │
│  7 test modules...                                            │
│  Overall: 38 PASS, 5 FLAG, 2 FAIL                            │
│                                                               │
│  ── Methodology ────────────────────────────────────────────  │
│  Two-tier architecture (Tier 1: system prompt, Tier 2:        │
│  conversation only). Statistical framework: α=0.05 with      │
│  Bonferroni correction...                                     │
│                                                               │
│  ── Results ────────────────────────────────────────────────  │
│  [Heat map embedded]                                          │
│  [Per-test verdict tables with links to result detail]        │
│                                                               │
│  ── Mitigation Actions ─────────────────────────────────────  │
│  FLAG: BIAS-ATTR-001/Gender — Effect size 0.31. Model shows   │
│  measurable softening of male characters. Overcorrection      │
│  directive partially effective (delta=0.15)...                 │
│  [Edit] ← iso42001Reporter only, only if status=DRAFT|REVIEW │
│                                                               │
│  ── Certification ──────────────────────────────────────────  │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │  ✓ CERTIFIED                                            │  │
│  │  Signed by: Jane Smith (Compliance Officer)              │  │
│  │  Date: 2026-02-28T16:45:00Z                             │  │
│  │  Valid until: 2027-02-28                                 │  │
│  │  Algorithm: SHA256WithRSA                                │  │
│  │  [Verify Signature]   [View Certificate]                 │  │
│  └─────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────┘
```

**RBAC per element:**
| UI Element | Roles Required |
|---|---|
| Report list | `iso42001Reader`, `iso42001Reporter`, `iso42001Certifier`, `iso42001Admin` |
| [+ Generate Report] | `iso42001Reporter`, `iso42001Admin` |
| [Export PDF] | `iso42001Reporter`, `iso42001Admin` |
| [Request Certification] | `iso42001Reporter` — hidden if `report.certification` exists |
| Section [Edit] buttons | `iso42001Reporter` — hidden if status is `CERTIFIED` or `ARCHIVED` |
| Certification block | Visible to all; [Verify] available to all; [Revoke] = `iso42001Admin` only |

### 9A.8 View: Certification Management (`certificationView.js`)

**Visible to:** `iso42001Reporter` (own requests), `iso42001Certifier` (pending queue), `iso42001Admin` (all)
**Route:** `/iso42001/cert` (list/queue), `/iso42001/cert/request/:requestId` (request detail), `/iso42001/cert/view/:certId` (certification detail)

**Certifier Queue** (default view for `iso42001Certifier`):
```
┌───────────────────────────────────────────────────────────────┐
│  Certification Queue                                          │
├───────────────────────────────────────────────────────────────┤
│  Tabs: [Pending (3)] [My Requests] [All Certifications]       │
│         ↑ Certifiers   ↑ Reporters   ↑ Admin only             │
├───────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────────────────────┬──────────┬────────────────┐ │
│  │ Report                       │Requester │ Requested      │ │
│  ├──────────────────────────────┼──────────┼────────────────┤ │
│  │ Feb 2026 Compliance (v2)     │ jdoe     │ 2/28 10:00     │ │
│  │ Q1 2026 Monitoring           │ asmith   │ 2/27 14:22     │ │
│  │ Endpoint Migration Report    │ jdoe     │ 2/26 09:00     │ │
│  └──────────────────────────────┴──────────┴────────────────┘ │
│                                                               │
│  Click a row to review →                                      │
└───────────────────────────────────────────────────────────────┘
```

**Certification Request Detail** (`/iso42001/cert/request/:requestId`):
```
┌───────────────────────────────────────────────────────────────┐
│  Certification Request                             [← Back]   │
│  Report: Feb 2026 Compliance (v2)                             │
│  Status: PENDING ●                                            │
├───────────────────────────────────────────────────────────────┤
│                                                               │
│  Requested by: jdoe (John Doe)                                │
│  Requested certifier: jsmith (Jane Smith)                     │
│  Date: 2026-02-28T10:00:00Z                                  │
│                                                               │
│  Justification:                                               │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │ February compliance cycle complete. 3 models tested     │  │
│  │ across all BIAS modules. 2 FLAG items documented with   │  │
│  │ mitigation plans. Ready for sign-off.                   │  │
│  └─────────────────────────────────────────────────────────┘  │
│                                                               │
│  [View Full Report →]                                         │
│                                                               │
│  ── Messages ───────────────────────────────────────────────  │
│  │ jsmith (2/28 11:00): Please clarify the mitigation      │  │
│  │   plan for BIAS-ATTR-001/Religion FLAG.                  │  │
│  │ jdoe (2/28 11:30): Updated section 4. Overcorrection    │  │
│  │   directive strengthened for religious characterization.  │  │
│  │ jsmith (2/28 12:00): Reviewed. Acceptable.              │  │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │ [Add message...                                        ] │  │
│  │                                          [Send Message]  │  │
│  └─────────────────────────────────────────────────────────┘  │
│  ↑ Messages available to Reporter + Certifier                 │
│                                                               │
│  ── Actions ────────────────────────────────────────────────  │
│  [Approve & Sign]  [Deny with Reason]                         │
│   ↑ iso42001Certifier + iso42001Admin only                    │
│   ↑ hidden if status != PENDING                               │
│                                                               │
│  Approve & Sign opens confirmation dialog:                    │
│  ┌────────────────────────────────────────┐                   │
│  │  Confirm Digital Signature             │                   │
│  │                                        │                   │
│  │  You are about to digitally sign:      │                   │
│  │  Report: Feb 2026 Compliance (v2)      │                   │
│  │  Hash: SHA-256                         │                   │
│  │  Signature: SHA256WithRSA              │                   │
│  │                                        │                   │
│  │  Your title for this certification:    │                   │
│  │  [Compliance Officer          ]        │                   │
│  │                                        │                   │
│  │  Validity period:                      │                   │
│  │  [12 months ▼]                         │                   │
│  │                                        │                   │
│  │  Notes (optional):                     │                   │
│  │  [                            ]        │                   │
│  │                                        │                   │
│  │  ⚠ This action is irreversible.       │                   │
│  │  Your digital signature will be        │                   │
│  │  permanently attached to this report.  │                   │
│  │                                        │                   │
│  │       [Cancel]  [Sign & Certify]       │                   │
│  └────────────────────────────────────────┘                   │
└───────────────────────────────────────────────────────────────┘
```

**Certification Detail** (`/iso42001/cert/view/:certId`):
```
┌───────────────────────────────────────────────────────────────┐
│  Certification #12                                 [← Back]   │
│  Status: ✓ VALID                                              │
├───────────────────────────────────────────────────────────────┤
│                                                               │
│  Report: Feb 2026 Compliance (v2)     [View Report →]         │
│                                                               │
│  Certified by: Jane Smith                                     │
│  Title: Compliance Officer                                    │
│  Date: 2026-02-28T16:45:00Z                                  │
│  Valid until: 2027-02-28T16:45:00Z                            │
│                                                               │
│  Cryptographic Details                                        │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │ Report Hash (SHA-256):                                  │  │
│  │ a1b2c3d4e5f6...7890abcd                                 │  │
│  │                                                         │  │
│  │ Signature Algorithm: SHA256WithRSA                      │  │
│  │ Signature: MEUCIQD...base64...==                        │  │
│  │                                                         │  │
│  │ Signer Certificate: [View/Download DER]                 │  │
│  └─────────────────────────────────────────────────────────┘  │
│                                                               │
│  Verification                                                 │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │ [Verify Now]                                            │  │
│  │                                                         │  │
│  │ Last verified: 2026-02-28T17:00:00Z                     │  │
│  │ Result: ✓ Signature valid, certificate valid,           │  │
│  │         report hash matches                             │  │
│  └─────────────────────────────────────────────────────────┘  │
│                                                               │
│  Notes: No conditions or limitations.                         │
│                                                               │
│  [Revoke Certification]   ← iso42001Admin only               │
│                                                               │
│  [Download Certified PDF]                                     │
└───────────────────────────────────────────────────────────────┘
```

### 9A.9 RBAC Summary Matrix

| Action | Tester | Reporter | Certifier | Reader | Admin |
|---|---|---|---|---|---|
| View dashboard | ✓ | ✓ | ✓ | ✓ | ✓ |
| Create test config | ✓ | | | | ✓ |
| Launch test run | ✓ | | | | ✓ |
| Cancel test run | ✓ | | | | ✓ |
| View test results | ✓ | ✓ | ✓ | ✓ | ✓ |
| Download raw logs | ✓ | ✓ | | | ✓ |
| Generate report | | ✓ | | | ✓ |
| Edit report sections | | ✓ (DRAFT/REVIEW only) | | | ✓ |
| Export PDF | | ✓ | | | ✓ |
| View reports | ✓ | ✓ | ✓ | ✓ | ✓ |
| Request certification | | ✓ | | | |
| Add messages to request | | ✓ (own) | ✓ (assigned) | | ✓ |
| Approve & sign | | | ✓ | | ✓ |
| Deny request | | | ✓ | | ✓ |
| View certifications | ✓ | ✓ | ✓ | ✓ | ✓ |
| Verify signature | ✓ | ✓ | ✓ | ✓ | ✓ |
| Revoke certification | | | | | ✓ |
| Download certified PDF | ✓ | ✓ | ✓ | ✓ | ✓ |
| Delete any object | | | | | ✓ |

### 9A.10 Implementation Notes

**Client-side role gating** uses the existing `am7view.showField()` pattern with `requiredRoles` arrays. Every command button, form section, and menu item checks against `page.context().roles`.

**Server-side enforcement** is the real authorization boundary:
- `ISO42001Service.java` methods check user membership in the corresponding AM7 roles
- Model-level `access.roles` in the JSON schema definitions enforce CRUD permissions
- The certification `approve` endpoint additionally verifies the acting user is the `requestedCertifier` or an admin
- Report sections cannot be patched if `report.status` is `CERTIFIED` (server rejects regardless of client)

**Message spool** for certification requests reuses the existing `message.spool` model from `access.accessRequest`. Messages are appended via `PATCH` on the request object, with each message recording the sender and timestamp.

---

## 10. Containerized Deployment

### 10.1 Architecture: Single Container

All components run in a single Docker container for simplified local deployment:

```
┌───────────────────────────────────────────────┐
│                Docker Container               │
│                                               │
│  ┌─────────────────────────────────────────┐  │
│  │  nginx (port 443/80)                    │  │
│  │  ├── /              → Ux7 static files  │  │
│  │  ├── /api/          → proxy → Tomcat    │  │
│  │  └── /setup/        → proxy → Node      │  │
│  └─────────────────────────────────────────┘  │
│                                               │
│  ┌─────────────────────────────────────────┐  │
│  │  Tomcat 11 (port 8443)                  │  │
│  │  └── AccountManagerService7.war         │  │
│  │      ├── REST API (/rest/*)             │  │
│  │      ├── MCP (/rest/mcp)               │  │
│  │      ├── WebSocket (/wss/)              │  │
│  │      └── ISO42001 (/rest/iso42001/*)    │  │
│  └─────────────────────────────────────────┘  │
│                                               │
│  ┌─────────────────────────────────────────┐  │
│  │  Node.js Setup Server (port 3000)       │  │
│  │  └── Web-based first-run wizard         │  │
│  │      ├── DB connection settings         │  │
│  │      ├── Admin password                 │  │
│  │      ├── User password                  │  │
│  │      ├── Store path configuration       │  │
│  │      └── LLM endpoint configuration     │  │
│  └─────────────────────────────────────────┘  │
│                                               │
│  ┌─────────────────────────────────────────┐  │
│  │  PostgreSQL 17 + pgvector (port 5432)   │  │
│  └─────────────────────────────────────────┘  │
│                                               │
│  supervisord manages all processes            │
└───────────────────────────────────────────────┘
```

### 10.2 Dockerfile

```dockerfile
FROM eclipse-temurin:25-jdk AS builder

# Build Java projects
WORKDIR /build
COPY AccountManagerObjects7/ AccountManagerObjects7/
COPY AccountManagerAgent7/ AccountManagerAgent7/
COPY ISO42001/ ISO42001/
COPY AccountManagerConsole7/ AccountManagerConsole7/
COPY AccountManagerService7/ AccountManagerService7/
COPY pom.xml .
RUN mvn clean package -DskipTests

# Build Ux7
FROM node:20-slim AS ux-builder
WORKDIR /build/AccountManagerUx7
COPY AccountManagerUx7/package*.json .
RUN npm ci
COPY AccountManagerUx7/ .
RUN node build.js --profile iso42001

# Runtime
FROM ubuntu:24.04

# Install PostgreSQL 17, pgvector, nginx, Node 20, supervisord
RUN apt-get update && apt-get install -y \
    postgresql-17 postgresql-17-pgvector \
    nginx \
    nodejs npm \
    supervisor \
    openjdk-25-jre-headless \
    curl wget \
    && rm -rf /var/lib/apt/lists/*

# Install Tomcat 11
RUN wget -qO- https://archive.apache.org/dist/tomcat/tomcat-11/v11.0.5/bin/apache-tomcat-11.0.5.tar.gz \
    | tar xz -C /opt/ && mv /opt/apache-tomcat-* /opt/tomcat

# Copy built artifacts
COPY --from=builder /build/AccountManagerService7/target/AccountManagerService7.war /opt/tomcat/webapps/
COPY --from=builder /build/AccountManagerConsole7/target/AccountManagerConsole7-*.jar /opt/console/console.jar
COPY --from=ux-builder /build/AccountManagerUx7/dist/ /var/www/iso42001/dist/
COPY --from=ux-builder /build/AccountManagerUx7/iso42001.html /var/www/iso42001/index.html
COPY --from=ux-builder /build/AccountManagerUx7/node_modules/ /var/www/iso42001/node_modules/

# Copy setup server
COPY docker/setup-server/ /opt/setup-server/

# Copy configuration templates
COPY docker/nginx.conf /etc/nginx/sites-available/default
COPY docker/supervisord.conf /etc/supervisor/conf.d/am7.conf
COPY docker/tomcat-context.xml.template /opt/tomcat/conf/context-template.xml
COPY docker/pg_hba.conf /etc/postgresql/17/main/pg_hba.conf

# Data directories
RUN mkdir -p /data/am7/keys /data/am7/certificates /data/am7/streams /data/vault

EXPOSE 80 443

VOLUME ["/data"]

CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/supervisord.conf"]
```

### 10.3 nginx Configuration

```nginx
server {
    listen 80;
    listen 443 ssl;
    server_name _;

    ssl_certificate     /data/am7/certificates/server.crt;
    ssl_certificate_key /data/am7/certificates/server.key;

    # Static Ux7 files
    location / {
        root /var/www/iso42001;
        try_files $uri $uri/ /index.html;
    }

    # Node modules (fonts, icons)
    location /node_modules/ {
        root /var/www/iso42001;
    }

    # API proxy to Tomcat
    location /api/ {
        proxy_pass https://127.0.0.1:8443/AccountManagerService7/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # WebSocket proxy
    location /wss/ {
        proxy_pass https://127.0.0.1:8443/AccountManagerService7/wss/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "Upgrade";
    }

    # Setup wizard (only active before first-run completion)
    location /setup/ {
        proxy_pass http://127.0.0.1:3000/;
    }
}
```

### 10.4 Web-Based Setup Wizard (Node.js)

**Purpose:** First-run configuration before Tomcat starts. Writes configuration files and initializes the database.

**`docker/setup-server/`:**
```
setup-server/
├── package.json
├── server.js
├── views/
│   └── setup.html      — Single-page setup form
└── lib/
    ├── dbSetup.js       — PostgreSQL connection test + schema init
    ├── configWriter.js  — Writes context.xml, web.xml params
    └── userSetup.js     — Initial admin/user credential setup
```

**Setup flow:**

```
1. Container starts → supervisord launches:
   - PostgreSQL (always)
   - Node setup server (always)
   - nginx (always)
   - Tomcat (NOT YET — held until setup complete)

2. User navigates to https://<host>/setup/

3. Setup wizard presents form:
   ┌──────────────────────────────────────┐
   │  AccountManager7 — First Run Setup   │
   ├──────────────────────────────────────┤
   │                                      │
   │  Database Configuration              │
   │  ┌────────────────────────────────┐  │
   │  │ Host:     [localhost         ] │  │
   │  │ Port:     [5432              ] │  │
   │  │ Database: [am7db             ] │  │
   │  │ Username: [am7user           ] │  │
   │  │ Password: [••••••••          ] │  │
   │  │ [Test Connection]              │  │
   │  └────────────────────────────────┘  │
   │                                      │
   │  Admin Account                       │
   │  ┌────────────────────────────────┐  │
   │  │ Admin Password: [••••••••     ] │  │
   │  │ Confirm:        [••••••••     ] │  │
   │  └────────────────────────────────┘  │
   │                                      │
   │  Default User Account                │
   │  ┌────────────────────────────────┐  │
   │  │ User Password:  [••••••••     ] │  │
   │  │ Confirm:        [••••••••     ] │  │
   │  └────────────────────────────────┘  │
   │                                      │
   │  Storage                             │
   │  ┌────────────────────────────────┐  │
   │  │ Data Path:  [/data/am7       ] │  │
   │  │ Vault Path: [/data/vault     ] │  │
   │  └────────────────────────────────┘  │
   │                                      │
   │  LLM Endpoints (optional)            │
   │  ┌────────────────────────────────┐  │
   │  │ + Add Endpoint                 │  │
   │  │ ┌ OpenAI ─────────────────── ┐ │  │
   │  │ │ API Key: [••••••••        ]│ │  │
   │  │ │ Model:   [gpt-4o          ]│ │  │
   │  │ └────────────────────────────┘ │  │
   │  └────────────────────────────────┘  │
   │                                      │
   │           [Complete Setup]           │
   └──────────────────────────────────────┘

4. On submit:
   a. Test DB connection
   b. Create database + user if needed
   c. Write context.xml from template with DB settings
   d. Write web.xml context-params (store.path, vault.path)
   e. Run AccountManagerConsole7 to initialize schema:
      java -jar /opt/console/console.jar --setup
   f. Set admin and user passwords
   g. Write setup-complete marker file
   h. Start Tomcat via supervisord
   i. Redirect user to main application

5. On subsequent starts:
   - If setup-complete marker exists → start Tomcat immediately
   - Setup server returns "Already configured" on /setup/
```

### 10.5 supervisord Configuration

```ini
[supervisord]
nodaemon=true

[program:postgresql]
command=/usr/lib/postgresql/17/bin/postgres -D /var/lib/postgresql/17/main -c config_file=/etc/postgresql/17/main/postgresql.conf
user=postgres
autostart=true
priority=10

[program:nginx]
command=/usr/sbin/nginx -g "daemon off;"
autostart=true
priority=20

[program:setup-server]
command=node /opt/setup-server/server.js
autostart=true
priority=30

[program:tomcat]
command=/opt/tomcat/bin/catalina.sh run
autostart=false
priority=40
```

### 10.6 Build & Run

```bash
# Build container
docker build -t am7-iso42001 -f docker/Dockerfile .

# Run
docker run -d \
  --name am7-iso42001 \
  -p 443:443 \
  -p 80:80 \
  -v am7-data:/data \
  am7-iso42001

# Access setup: https://localhost/setup/
# Access app:   https://localhost/
```

---

## 11. Implementation Phases

### Phase 1: Foundation — Models & Project Structure
**Scope:** Create the ISO42001 Maven project, define all JSON models, wire into parent POM and Service7

**Tasks:**
1. Create `ISO42001/` directory structure with pom.xml
2. Add model JSON files to `ISO42001/src/main/resources/models/iso42001/`
3. Update parent `pom.xml` to include ISO42001 module
4. Add ISO42001 dependency to Service7 `pom.xml`
5. Create factory classes for each model (ISO42001TestConfigFactory, etc.)
6. Verify model auto-discovery: `mvn compile` on Objects7 + ISO42001
7. Regenerate `modelDef.js` with new iso42001.* models

**Unit Tests:**
- `TestISO42001TestConfig` — CRUD for testConfig model
- `TestISO42001TestRun` — CRUD for testRun model
- `TestISO42001TestResult` — CRUD for testResult model
- `TestISO42001Report` — CRUD for report model
- `TestISO42001Certification` — CRUD for certification model
- `TestISO42001CertificationRequest` — CRUD for certificationRequest model

**Ux7 Test Harness:** `iso42001-model-crud`

---

### Phase 2: Statistical Engine & Scoring
**Scope:** Implement the statistical analysis and bias scoring infrastructure

**Tasks:**
1. Implement `StatisticalAnalyzer` — Mann-Whitney U, Chi-square, Fisher's exact, Bonferroni correction, effect sizes (Cohen's d, odds ratio, Cramér's V)
2. Implement `BiasScorer` — parse LLM responses, extract numeric scores
3. Implement `LexicalAnalyzer` — word frequency against categorized dictionaries
4. Implement `NameBankLoader` — load YAML name banks, validate structure
5. Create `SwapTestRunner` — generate swap pairs, compare results
6. Populate lexicon files (authority, stereotype, dignity markers)
7. Populate name banks YAML from `iso42001-bias.md` specification

**Unit Tests:**
- `TestISO42001StatisticalAnalyzer` — verify all statistical tests against known data
- `TestISO42001BiasScorer` — response parsing, score normalization
- `TestISO42001LexicalAnalyzer` — word frequency, category matching
- `TestISO42001NameBankLoader` — YAML loading, validation
- `TestISO42001SwapTest` — pair generation, comparison logic

---

### Phase 3: Test Execution Engine
**Scope:** Build the test runner that executes bias tests via the AM7 chat infrastructure

**Tasks:**
1. Implement `TestRunner` — orchestrates test execution from config
2. Implement `TestExecutor` — base class for test execution
3. Implement `BiasTestExecutor` — bias-specific execution (Tier 1 & 2)
4. Create prompt templates for each bias test (Tier 1 and Tier 2 variants)
5. Integrate with `ChatUtil` for session creation and LLM calls
6. Implement result collection and verdict assignment
7. Implement raw log capture to `data.data` objects

**Unit Tests:**
- `TestISO42001BiasAttr` — BIAS-ATTR tests (live LLM)
- `TestISO42001BiasHire` — BIAS-HIRE tests (live LLM)
- `TestISO42001BiasNarr` — BIAS-NARR tests (live LLM)
- `TestISO42001BiasAssoc` — BIAS-ASSOC tests (live LLM)
- `TestISO42001BiasHealth` — BIAS-HEALTH tests (live LLM)
- `TestISO42001BiasRefusal` — BIAS-REFUSAL tests (live LLM)
- `TestISO42001BiasLoan` — BIAS-LOAN tests (live LLM)

**Ux7 Test Harness:** `iso42001-test-run`, `iso42001-bias-quick`

---

### Phase 4: Reporting & PDF Export
**Scope:** Report generation, section assembly, chart rendering, and PDF export

**Tasks:**
1. Implement `ReportGenerator` — aggregate test runs into report model
2. Implement `ChartGenerator` — heat maps (models × protected classes), bar charts, trend lines
3. Implement `PdfExporter` — HTML template to PDF conversion with embedded charts
4. Create report HTML template with all section types
5. Implement `ReportTemplates` — section content generation (executive summary, methodology, etc.)
6. Wire PDF storage to `data.data` via `StreamUtil`

**Unit Tests:**
- `TestISO42001Report` — report generation from test runs
- `TestISO42001PdfExport` — PDF rendering, content verification

**Ux7 Test Harness:** `iso42001-report-generation`, `iso42001-pdf-export`

---

### Phase 5: Certification & Digital Signatures
**Scope:** Implement the full certification workflow including signing and verification

**Tasks:**
1. Implement `ISO42001CertificationFactory` — create, sign, revoke certifications
2. Implement `CertificationVerifier` — verify signature, check expiry, validate certificate chain
3. Extend certification request approval to trigger automatic signing
4. Implement certification block rendering in PDF export
5. Wire into access.accessRequest workflow for approval mechanics

**Unit Tests:**
- `TestISO42001Certification` — sign and verify
- `TestISO42001CertificationRequest` — full request/approve/deny workflow
- `TestISO42001CertificationVerify` — signature verification, expiry, revocation

**Ux7 Test Harness:** `iso42001-certification-workflow`

---

### Phase 6: REST Service & MCP
**Scope:** Add ISO42001Service REST endpoints and MCP resource/tool providers

**Tasks:**
1. Create `ISO42001Service.java` in Service7 with all endpoints
2. Add ISO 42001 MCP tools to `Am7ToolProvider`
3. Add ISO 42001 MCP resources to `Am7ResourceProvider`
4. Implement agent tools (`ISO42001AgentTool.java`)
5. Add ISO 42001 context blocks to `McpContextBuilder`

**Unit Tests:**
- `TestISO42001RestService` — all REST endpoints via HTTP client
- `TestISO42001AgentTools` — agent tool invocation
- Existing MCP tests extended for ISO 42001 resources

**Ux7 Test Harness:** `iso42001-dashboard`

---

### Phase 7: Ux7 Views, RBAC, & Production Build
**Scope:** Build all ISO 42001 UI views with RBAC-driven visibility, certification workflow, and production build profile. See Section 9A for full view specifications and wireframes.

**Tasks:**
1. Register ISO 42001 roles in `applicationRouter.js` → `setContextRoles()` (iso42001Tester, iso42001Reporter, iso42001Certifier, iso42001Reader, iso42001Admin)
2. Add ISO 42001 form definitions to `formDef.js` (testConfig, report, certRequest, certification) with `requiredRoles` on forms, fields, and commands
3. Create `client/view/iso42001/dashboard.js` — compliance dashboard: verdict summary cards, heat map (models x protected classes), recent runs, recent reports, pending cert queue (certifiers only)
4. Create `client/view/iso42001/testRunner.js` — test config + run management: new run dialog, run list with filters, run detail with progress/cancel, [Generate Report] handoff (reporters only)
5. Create `client/view/iso42001/resultsBrowser.js` — results drill-down: run results table with filter/sort by verdict/class/testId, single result detail with per-group stats, bar charts, and raw log download
6. Create `client/view/iso42001/reportViewer.js` — report list + detail: section viewer with edit (DRAFT/REVIEW only, reporters), [Export PDF], [Request Certification] (reporters, hidden if certified), embedded certification block with [Verify]
7. Create `client/view/iso42001/certificationView.js` — three-tab certification management: Pending queue (certifiers), My Requests (reporters), All Certifications (admin). Request detail with message thread, [Approve & Sign] confirmation dialog (title, validity period, irreversibility warning), [Deny with Reason]. Certification detail with crypto info and [Verify Now], [Revoke] (admin only)
8. Create `client/view/iso42001/iso42001Router.js` — Mithril routes for all ISO views, merged into `applicationRouter.js`, nav menu gated on `iso42001Any`
9. Create `iso42001.html` — standalone entry point for prod build
10. Add build profile to `build.js` with `--profile iso42001` support
11. Create `styles/iso42001.css` — ISO-specific styles (verdict colors, heat map, cert block)
12. Create `client/test/iso42001/iso42001TestSuite.js` — test harness entries covering RBAC visibility, all CRUD flows, certification workflow
13. Update `build.js` jsFiles array with all new files (views + test suite)

**Unit Tests:**
- Ux7 test harness `iso42001-model-crud` — CRUD for all iso42001.* models via REST
- Ux7 test harness `iso42001-test-run` — config creation, run launch, status polling
- Ux7 test harness `iso42001-report-generation` — report from runs, section assembly
- Ux7 test harness `iso42001-certification-workflow` — request → message → approve → verify
- Ux7 test harness `iso42001-pdf-export` — export and download verification
- Ux7 test harness `iso42001-rbac` — verify UI elements hidden/shown per role (test with different user logins)

---

### Phase 8: Containerization & Setup Wizard
**Scope:** Docker container with Tomcat, nginx, Node setup server, PostgreSQL

**Tasks:**
1. Create `docker/Dockerfile` — multi-stage build
2. Create `docker/nginx.conf` — reverse proxy configuration
3. Create `docker/supervisord.conf` — process management
4. Create `docker/setup-server/` — Node.js setup wizard
5. Create setup wizard HTML form
6. Implement DB connection testing and schema initialization
7. Implement context.xml template rendering
8. Implement admin/user password setup via AccountManagerConsole7
9. Implement setup-complete marker and Tomcat auto-start
10. Create `docker/tomcat-context.xml.template`
11. Test full container lifecycle: build → start → setup → use

**Unit Tests:**
- Setup server endpoint tests (Node.js mocha/jest)
- Container integration test (docker build + basic smoke test)

---

### Phase 9: Integration & Validation
**Scope:** End-to-end validation, documentation, and cleanup

**Tasks:**
1. Run full test suite (`mvn test -P all-tests`)
2. Run Ux7 test harness for all ISO 42001 entries
3. Execute a complete bias test run against at least one LLM endpoint
4. Generate a report and export to PDF
5. Complete the certification workflow end-to-end
6. Verify MCP resources are accessible from external tools
7. Verify container deployment works from clean state
8. Update `iso42001.md` and `iso42001-bias.md` with any design changes
9. Final `modelDef.js` regeneration and verification

---

## 12. Open Items & Notes

### 12.1 Model JSON Location Decision
ISO 42001 model JSON files can live in either:
- **Option A:** `ISO42001/src/main/resources/models/iso42001/` — keeps models with the project that defines them
- **Option B:** `AccountManagerObjects7/src/main/resources/models/iso42001/` — keeps all models centralized

**Recommendation:** Option A. Models ship with the ISO42001 JAR and are discovered via classpath when Service7 includes the dependency. This keeps the ISO42001 project self-contained.

### 12.2 Statistical Library
Apache Commons Math 3 provides Mann-Whitney U, Chi-square, and related tests. If more advanced statistics are needed (e.g., bootstrap confidence intervals), consider upgrading to Commons Math 4 or adding a dedicated library.

### 12.3 PDF Library
OpenPDF is LGPL-licensed and adequate for report generation. If advanced features are needed (digital signatures embedded in PDF metadata, PDF/A compliance), iText Community (AGPL) or Apache PDFBox may be alternatives.

### 12.4 Container DB Choice
The design includes PostgreSQL in-container for simplicity. For production deployments, the setup wizard should also support connecting to an external PostgreSQL instance (which it does — the DB host/port fields are configurable).

### 12.5 Ux7 Hodgepodge
Ux7 remains a monolithic SPA. The production build profile carves out only ISO 42001 views, but the development build remains full. No refactoring of Ux7 architecture is planned in this design.
