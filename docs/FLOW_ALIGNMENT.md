# ERGO Connect — flow-document alignment: handoff for the next Claude

**Read this file first.** It is self-contained: after reading it (and `CLAUDE.md`) you can continue without asking
the user what happened. Last updated 2026-10-24 (**all six slices done**), branch `sprint-3`.

## 1. Why this work exists

The client sent a new business flow ("ERGO Platform — End-to-End Business Workflow", `Varad_ERGO flow (1).docx`, kept
outside the repo; its full text is in §2 below). The user's instruction: **"I want completely the same flow as the doc.
Do not take any assumption on the basis of the old code."** So:

- **The flow document wins** over `docs/SRS.md`, over earlier client decisions in `ARCHITECTURE.md` §8, and over what
  the old code does. When they differ, change the code to follow the doc, and log the change in `ARCHITECTURE.md` §8.
- Do not keep an old feature just because it exists. If the doc has no such step (validate, comparison, supplier
  clarification, regret, "supplier quote as-is"), it was removed.
- If the doc is genuinely silent or ambiguous, **ask the user** (`AskUserQuestion`) — don't guess. Record the answer
  in `ARCHITECTURE.md` §8.
- The work is split into slices (§4). Finish one slice completely (backend + migration + tests + frontend + security
  review + `ARCHITECTURE.md` entry + commit) before the next.

## 2. The flow document (verbatim, condensed only where marked)

1. **Customer RFQ creation.** The Customer RFQ team creates an RFQ. One RFQ may contain **multiple RFQ items**, all
   listed under the same RFQ.
2. **ERGO RFQ review and supplier distribution.** ERGO RFQ reviews and analyses the complete customer RFQ, may
   **modify RFQ details** where required, then selects suppliers from the existing Supplier List and distributes to
   their Supplier RFQ teams. ERGO may send the **complete RFQ to one supplier**, or **split it into sub-RFQs** and send
   them to different suppliers (example: 7 parts = 3 machined + 2 plastic + 2 castings → 3 sub-RFQs, 3 suppliers).
3. **Supplier quotation submission.** The Supplier RFQ team reviews, evaluates, prepares a quotation and submits it
   with relevant remarks to ERGO RFQ.
4. **ERGO quotation review and customer quotation generation.** ERGO RFQ reviews the supplier quotation and remarks.
   *Revision required:* it returns to the Supplier RFQ team with required changes/comments. *Accepted for customer
   submission:* ERGO RFQ runs **Generate Quotation for Customer**; the system automatically generates a customer-facing
   quotation in the customer's required format and sends it to the Customer RFQ team. The original supplier quotation
   and the generated customer quotation are both retained for ERGO reference/traceability/history. The supplier
   quotation is **never forwarded as-is**, and the customer has **no visibility of supplier quotations at all**.
   **Quotations are submitted per individual part number, and the dashboard reflects the status at each part.**
5. **Customer quotation review and decision.** The Customer RFQ team selects: **Approve** (proceeds to Customer NPD) ·
   **Correction Required** (back to ERGO RFQ for revisions through the RFQ workflow) · **Reject** · **On hold**
   (received, decision about moving to NPD on hold). A comment field is available for every decision.
6. **Customer NPD — Sample Purchase Order.** After approval, **Customer NPD generates the Sample PO** and submits it to
   the **ERGO Key Account Manager (KAM)**.
7. **Sample project creation and team assignment.** The ERGO KAM creates the **sample development project** from the
   approved Sample PO and assigns it to **ERGO NPD** and **ERGO Quality**. ERGO NPD reviews the project, discusses it
   with ERGO Quality, then creates the **New Project Development plan template** with what is needed to execute it
   (e.g. raw-material availability timeline, tooling readiness timeline, sample manufacturing plan date, packaging
   design, other steps depending on complexity) and assigns it to **Supplier NPD, Supplier RFQ, Supplier Quality**. The
   project and its relevant information are available to the assigned teams according to role and permission.
8. **NPD project execution.** Supplier NPD submits the plan in ERGO NPD's template; **ERGO NPD agrees**; then it goes
   to execution. The NPD teams work the steps and track completion; each step is marked completed once its activities
   are finished. The system maintains project progress and step status.
9. **ERGO and Supplier quality validation.** After the applicable NPD steps are completed, **Supplier Quality inspects
   and approves internally**, then the project is submitted to **both ERGO Quality and Supplier Quality** for
   inspection/testing/validation. Both approve → next step. **If ERGO Quality rejects → the project returns to the
   relevant project development step (steps 7/8) for corrective action and rework**, based on the failure comments.
   Quality decisions and remarks are recorded in the project history.
10. **Customer final quality approval.** After both ERGO Quality and Supplier Quality approve, the project is submitted
    to **Customer NPD and Customer Quality** for final review. Approved → next stage. Rejected → returned to the
    relevant quality stage with comments for corrective action and resubmission. **Both** Customer NPD and Customer
    Quality approvals are required.
11. **ERGO OFP — final production development.** After sample development and customer approvals, ERGO OFP begins.
    The **customer releases a production order** (to the supplier or to ERGO) and **submits it in the software under
    OFP**; the **ERGO KAM gets a notification** and **creates an OFP project** for that production PO. ERGO OFP then
    creates a project like NPD but **only with the relevant steps** (tool development and sample approval steps are
    not required). Final production development follows the same execution / step tracking / quality validation /
    approval workflow as steps 7–10, **with Customer OFP and Supplier OFP teams added**. The system coordinates NPD,
    OFP and Quality teams and keeps status, approvals, comments and history.

**Key business rules from the doc** (apply to every slice):
- *Quotation traceability:* keep both the supplier quotation and the system-generated customer quotation.
- *Controlled customer quotation generation:* only via "Generate Quotation for Customer".
- *Decision comments:* approval, correction, rejection must include comments.
- *Quality gate:* ERGO Quality **and** Supplier Quality must both approve before customer final approval.
- *Customer final approval:* Customer NPD **and** Customer Quality both mandatory.
- *Rework management:* rejected quotations, failed quality tests, rejected final approvals return to the appropriate
  stage **with comments**, and there must be a trail of past activities.
- *Role-based access:* each team acts per its role and project responsibilities.
- *Data visibility control:* **no supplier data goes to a customer, and no customer data to a supplier, without ERGO's
  approval.** Customers and suppliers see only data related to them — never another customer's or supplier's.
- *Audit history:* RFQs, quotations, revisions, project steps, quality results, decisions and comments stay traceable.

## 3. What is done

### Slice 1 — customer decision "On Hold" (commit `d5b1734`, V31) ✅
- `On hold` is a non-final customer decision (`ResponseStatus.ON_HOLD`, RFQ status `CUSTOMER_ON_HOLD`; customers see
  `ON_HOLD`). The quotation stays open (one open quotation per RFQ), the customer answers once more; the DB guard
  trigger allows that single final answer. Only the customer's approving role may hold/approve/reject; lower customer
  roles may only request a correction.
- **Every customer decision needs a comment** (approve included).
- A supplier quotation is **never forwarded**; "Generate quotation for customer" always creates ERGO's own quote.
- UI wording: "Correction required" (= `REVISION_REQUESTED`), "Reject" (= `DECLINED`).

### Slice 2 — multi-item RFQ, sub-RFQs, quotes per part, per-part customer quotation (commit `c1195be`, V32–V34) ✅
Full decisions are in `ARCHITECTURE.md` §8, entries dated 2026-10-23 (three entries). Summary of the model:

**RFQ module (`rfq`)**
- `rfqs` = header only (customer, status, currency, payment term, target date, priority, remarks, clarification).
  `rfq_items` = the parts (`RfqItem` entity: technical data, customer targets, process steps in
  `rfq_item_process_steps`). Existing RFQs were migrated to one-item RFQs.
- ERGO may edit everything while `UNDER_REVIEW`; the customer while it is theirs. Items keep their id across edits
  (`ItemForm.id`); items missing from an edit are removed (only possible before distribution). `RfqService.update`
  takes the RFQ row lock (`FOR UPDATE`).
- **No validate / reopen** (removed). RFQ statuses now: `DRAFT, SUBMITTED, UNDER_REVIEW, CLARIFICATION_REQUIRED,
  SENT_TO_SUPPLIERS, QUOTES_PARTIAL, QUOTES_COMPLETE, SENT_TO_CUSTOMER, CUSTOMER_ON_HOLD, CUSTOMER_REVISION_REQUESTED,
  CUSTOMER_APPROVED, CUSTOMER_DECLINED, CLOSED, CANCELLED`.
- **The RFQ status from `SENT_TO_SUPPLIERS` on is a summary of its parts**: quotation module reports counts
  (`RfqApi.quotationProgress(rfqId, QuotationSummary)`), `RfqApiService.derive(parts, summary)` computes the status
  (all parts decided → approved/declined; else with-customer > on-hold > correction > quotes complete/partial >
  sent-to-suppliers).
- Files ERGO shares with suppliers are shared **per part** (`rfq_document_parts`); a supplier sees a file only if it
  holds one of its parts. A file made visible via the Document Library without parts reaches no supplier (fail closed).
  The RFQ endpoints take `itemIds` (required when visibility is SUPPLIER/ALL).

**Quotation module (`quotation`)** — the old comparison module is gone.
- `rfq_supplier_assignments` (entity `Invitation`) = **one part sent to one supplier**. Its `status` is the part's
  quote status (`QuoteStatus`): `NOT_STARTED, DRAFT_SAVED, SUBMITTED, REVISION_REQUIRED, RESUBMITTED, ACCEPTED,
  WITH_CUSTOMER, CUSTOMER_ON_HOLD, CUSTOMER_CORRECTION, CUSTOMER_APPROVED, CUSTOMER_REJECTED, RECALLED`.
  `QuoteStatus.forSupplier()` collapses everything from ACCEPTED on to ACCEPTED; `forCustomer()` collapses to
  `CustomerPartStatus`.
- `sub_rfqs` groups the parts sent to one supplier in one go. Partial unique index
  `ux_assignments_item_active`: a part is with one supplier at a time. Suppliers see their own sub-RFQ number
  (counted among their own sub-RFQs), never the global one.
- Endpoints (`QuoteController`): `GET/POST /api/rfqs/{id}/quotes` (parts view / distribute), `GET /api/rfqs/{id}/parts`
  (part status per audience), `GET /api/quotes`, `GET/PUT /api/quotes/{id}`, `POST /api/quotes/{id}/submit | accept |
  revision | recall`, versions, documents. `CustomerQuoteController`: `GET/POST /api/rfqs/{id}/customer-quotes`
  (list / **generate**), `POST /api/customer-quotes/{id}/respond`.
- Roles: distribute/revision/recall = ERGO "A and up" (`ERGO_BUYERS`); accept/generate = ERGO "O" (`EVALUATORS`);
  undoing an acceptance (revision/recall on an ACCEPTED or CUSTOMER_CORRECTION part) needs an "O" role.
- **Customer quotation**: `customer_quote_submissions` (frozen `customer_view` JSON = what the customer received,
  numbered per RFQ `version_no`) + `customer_quote_lines` (append-only; supplier quote version + ERGO's hidden margin
  per part). One open quotation per RFQ (unique index). The customer's answer updates every part's status and calls
  `QuoteService.progress(rfqId)`.
- Supplier quote versions (`supplier_quote_versions`) are unchanged and append-only.

**Other modules touched**
- NPD: `npd_projects.source_rfq_item_id` (unique): conversion is now **per approved part** (`POST /api/npd/convert/{rfqItemId}`,
  conversion queue rows have `rfqItemId`). `QuotationApi.approvedParts()/approvedPart(itemId)` feed it.
- Dashboard: `partItems` (`QuotationApi.partStatusItems()`) on the Management, Functional and Customer dashboards;
  `RfqDashboardItem.parts`.
- Notifications: `QUOTE_REOPENED` title is now "Revision required on your quote"; clarification/regret kinds are unused.

**Frontend** (`ergo-connect-frontend/src/features`)
- `rfq`: `RfqEditPage` (header + several part cards), `RfqDetailPage` (parts overview, per-part status table, tabs
  "Parts & suppliers", "Customer quotation"), `DocumentsPanel` (choose parts when sharing with suppliers), list shows parts.
- `quotation`: `PartsPanel` (send selected parts to a supplier, accept / revision required / take back, generate
  quotation), `GenerateQuotationDialog`, `CustomerQuotesPanel` (+ `ErgoQuotationsPanel`), `QuotePage`
  (per part), `services/quoteApi.ts`, `services/customerQuoteApi.ts`, `utils/labels.ts` (`partStatusLabel`).
- `npd` ConversionQueuePage converts per part; dashboard sections show part status.
- **Not yet clicked through in a browser** — only `npm run build` + `npm run lint` verified. A browser smoke test of
  the RFQ → distribute → quote → accept → generate → decide path is worth doing early in the next session
  (`docker compose -f ergo-connect-backend/docker/docker-compose.local.yml up -d`, then see `CLAUDE.md` Commands).

**Security review** was run on slice 2 (read-only `security-reviewer` agent): 1 High + several Low, all fixed
(logged in `ARCHITECTURE.md` §8). Run it again for every slice.

### Slice 3 — Sample PO, KAM creates the project, plan release (V35) ✅
Decisions in `ARCHITECTURE.md` §8 (2026-10-23, two entries: decisions + security review) and `docs/PERMISSIONS.md` §11.
- **Backend (`npd` module):** `SamplePo` entity + `SamplePoService` + `SamplePoController` (`/api/npd/sample-pos`: list, `/eligible-parts`, `/assignees`, `/{id}`, `/{id}/history`, raise, `PUT /{id}` resubmit, `/{id}/return`, `/{id}/create-project`). `NpdService.createFromSamplePo` (package-private, called only by `SamplePoService`) creates the project; `NpdService.assignPlan` (`POST /api/npd/{id}/plan/assign`) releases it. `PlanStatus` = `AWAITING_PLAN` -> `ASSIGNED_TO_SUPPLIER` -> `AGREED` (slice 4 sets `AGREED`; old projects are `AGREED`). `QuotationApi.customerApprovedParts()` feeds the customer's eligible parts (no supplier data). New notification kinds `SAMPLE_PO_SUBMITTED`, `SAMPLE_PO_RETURNED`, `NPD_PROJECT_ASSIGNED`, `NPD_PLAN_ASSIGNED`; new record kind `sample-po` (`RecordScope`, `ModuleSwitch.ownerOf`). `NpdApi.unconvertedApprovedRfqCount` became `samplePosAwaitingCount`.
- **Rules to keep:** the supplier cannot see a project (any path, incl. `NpdApi.projectRef`) or close stages while `AWAITING_PLAN`; `NpdService.EDITORS` excludes the KAM; only Customer NPD raises, only KAM/Management decide, only ERGO_NPD/Management assign the plan.
- **Frontend:** `features/npd` has the Sample PO list/detail pages and dialogs, plan status, owners, "Assign plan to supplier", the activity Stage selector; the conversion queue page is gone.
- **Not built / known gaps:** the optional PO file attachment (needs the Documents module); deleting a plan step; the plan *template* is just activities (no saved reusable template); a browser smoke test of the whole path has not been done.

### Slice 4 — plan submission, ERGO NPD agreement, execution (V36) ✅
Decisions in `ARCHITECTURE.md` §8 (2026-10-24) and `docs/PERMISSIONS.md` §11. Questions put to the client first: the supplier only fills in steps (no adding), a sent-back plan returns to the supplier with a mandatory comment, steps stay the 9 stages with progress added.
- **State machine (`PlanStatus`):** `AWAITING_PLAN` -> `ASSIGNED_TO_SUPPLIER` -> `SUBMITTED` -> `AGREED`; `CHANGES_REQUESTED` = sent back (the supplier edits and submits again). `npd_plan_history` (append-only, V36) keeps every release / submit / send-back / agree with the comment.
- **Backend (`NpdService`):** `updatePlanStep` (`PUT /api/npd/activities/{id}/plan`: planned date, responsible person of the supplier's company, remarks; own steps only, while the plan is with the supplier), `submitPlan` (Supplier NPD only; every supplier step needs a planned date), `agreePlan` / `sendBackPlan` (ERGO NPD / Management; send-back comment mandatory). New actions `EDIT_PLAN_STEP`, `SUBMIT_PLAN`, `AGREE_PLAN`, `SEND_BACK_PLAN`; notifications `NPD_PLAN_SUBMITTED`, `NPD_PLAN_RETURNED`, `NPD_PLAN_AGREED`.
- **Rules to keep:** while `SUBMITTED` nobody changes the steps (add / edit blocked for ERGO too); progress, block / unblock, checklist answers and **close stage need `AGREED`** (`NpdService.executing`); projects from before the plan flow are `AGREED`. The customer's view has no plan status or history; the supplier's view has them (ERGO's send-back comment is meant for the supplier).
- **Progress:** every view carries `progress` (done / total / percent and per stage completed-current-upcoming) over the activities the caller can see.
- **Frontend:** `features/npd/components/PlanPanel.tsx` (plan status, submit / agree / send back with comment dialogs, plan history, `ProgressSummary`, `PlanStepDialog`), wired into the Overview and Activities tabs of `NpdDetailPage`.
- **Tests:** `PlanAgreementTests` (9) plus `TestNpd.agreed(...)`, the helper that drives a project to execution (`TestNpd.release` stops at "assigned": supplier steps get a planned date there). Tests that work steps or close stages now use it.
- **Not built / known gaps:** the responsible-person picker on the supplier's step dialog (date and remarks only), a reusable saved plan template, a browser smoke test of the whole path.

### Slice 5 — dual quality gate and customer final approval (V37) ✅
Decisions (defaults to confirm with the client) in `ARCHITECTURE.md` §8, two entries of 2026-10-24.
- **Loop (`NpdValidationService`, `ValidationStatus`):** `NOT_STARTED` -> `IN_VALIDATION` (Supplier Quality's internal approval = submission) -> `VALIDATED` (ERGO Quality and Supplier Quality both approved) -> `IN_CUSTOMER_REVIEW` (closing Final Sample Readiness submits) -> `CUSTOMER_APPROVED` (Customer NPD and Customer Quality both approved). Endpoints `POST /api/npd/{id}/validation/submit` and `/validation/decide`. `npd_validation_decisions` (append-only) is the quality history.
- **Rejections:** reason mandatory. ERGO Quality / Supplier Quality send the project to a development stage (Planning..Trial Execution), reopening that stage's finished non-customer steps; the customer sends it back to Final Sample Readiness. Each starts a new round; Supplier Quality must submit again (a colleague must validate, not the submitter).
- **Gates:** `closeStage` at Final Sample Readiness = real inspection (`QualityApi`) AND `VALIDATED`; at Customer Feedback = `CUSTOMER_APPROVED`. Final Sample Readiness work is frozen from submission until the customer decides. Projects already at Customer Feedback or later were backfilled as approved.
- **Visibility:** ERGO sees all; the supplier sees its side + ERGO Quality's rejection reasons + one customer outcome per round; the customer sees only its own decisions, a coarse status and its own round count. External stage history shows names only for ERGO and the caller's own company.
- **Frontend:** `features/npd/components/ValidationPanel.tsx` (status, submit / approve / reject dialogs, quality history) on the Overview tab.
- **Tests:** `ValidationFlowTests` (12); `TestNpd.validate(...)` / `customerApprove(...)` drive the loop; the helpers that close every stage (NpdTests, OfpTests) use them.
- **Known gaps:** approvals linked to a reopened step stay approved; Supplier Quality chooses the return stage; the older `ApprovalType` approvals still exist beside this loop.

### Slice 6 — production PO and OFP project (V38) ✅
Decisions (defaults to confirm with the client) in `ARCHITECTURE.md` §8, two entries of 2026-10-24.
- **Production PO (`ProductionPo`, `ProductionPoStatus`, `ProductionPoService`; `/api/ofp/production-pos`):** Customer OFP (the customer's OFP approver) releases one for an approved sample project (`SUBMITTED`); the ERGO KAM of that account either returns it with a comment (`RETURNED`, Customer OFP corrects and resubmits) or accepts it by creating the OFP project (`ACCEPTED`). Number `PPO-yyyy-nnnn` from `production_po_number_seq`. One open production PO per sample project (partial unique index). History is audited; notifications `PRODUCTION_PO_SUBMITTED` / `PRODUCTION_PO_RETURNED`.
- **OFP project = an `npd_projects` row with `kind = PRODUCTION`** (`ProjectKind`; `production_po_id`, `source_project_id`; a CHECK ties the three together). The KAM assigns one ERGO OFP and one ERGO Quality user; the supplier and customer teams are the same companies as the sample project. It reuses the whole execution engine of slices 4 and 5 (plan, agreement, step tracking, dual quality gate, customer approval). `NpdStage.skippedBy(kind)` drops *Tooling & Material Release* and *Trial Preparation* for production projects (no tool development, no sample approval).
- **Per-kind roles (`NpdRights.of(project)`):** replaces the static role sets in `NpdService`. A sample project is worked by the NPD columns as before; a production project is worked by the **OFP** columns (ERGO OFP / Customer OFP / Supplier OFP) unioned with the NPD roles that already have access. Customer OFP is not an approver (the customer's final approval stays with Customer NPD).
- **Handover is rebased:** `OrderService.handover` is available only for production projects and defaults customer PO number, quantity and required date from the production PO. A sample project can no longer be handed over to an order; an order always comes from a production PO.
- **Module switches:** an OFP project is bound by both the NPD and the OFP switch (`NpdRights.writable()`, `NpdApi.writableForRecord`, comments via `RecordScope.writable(module, id)`).
- **KAM A on OFP (V38, was V):** limited to production-order decisions. `OrderService.ERGO_ACTORS` excludes `ERGO_KAM`, so the KAM cannot hold, release or clear orders.
- **Frontend:** `features/ofp` — `ProductionPoListPage`, `ProductionPoDetailPage`, `ProductionPoDialogs`, `productionPoApi`; sidebar items `production-pos` and `ofp-projects` (migration), `?kind=` filter on `MyNpdProjectsPage`, an OFP chip on the NPD pages, handover button only for production projects.
- **Tests:** `ProductionFlowTests`; `OfpTests` re-based (an order is created only from a production project); `TestNpd` helpers `raiseProductionPo`, `createOfpProject`, `productionProject`, `approvedSample`, `completeProject`.
- **Defaults to confirm with the client:** the KAM (not ERGO OFP) creates the OFP project and assigns the ERGO OFP; the customer's final approval is kept for production projects; old sample projects at handover now need a production PO; Customer OFP is not an approver; Supplier Quality chooses the return stage on a rejection; the KAM's A on OFP means production-order decisions only.
- **Known gaps:** approvals linked to a reopened step stay approved; no browser smoke test of any flow; `pendingApprovalsCount` counts OFP projects even when OFP is hidden; no saved reusable plan template; the responsible-person picker on the supplier's step dialog.

## 4. What is left

All six slices of the flow document are built. Open items are the "defaults to confirm" and "known gaps" lists above and
the backlog below. For any further slice: write migration `V39+` (next free number is **V39**); backend (entities →
service/workflow → `<module>.api` facade → controller → tests); frontend; then `security-reviewer`; `./mvnw verify` +
`npm run build && npm run lint` green; log in `ARCHITECTURE.md` §8; commit.

(Original plan for slice 6, kept for context.)

### Flow step 11 as planned
- **Customer OFP releases a production order** and submits it under OFP; the **ERGO KAM is notified** and creates an
  **OFP project** for that production PO. ERGO OFP creates a NPD-like project with **only the relevant steps** (no tool
  development, no sample approval) and the **Customer OFP and Supplier OFP teams** are added. Same execution, step
  tracking, quality validation and approval workflow as slices 4–5. Existing `ofp` module (orders, production tracking,
  shipments) and `OrderService.handover` from NPD need to be re-based on this.

### Cross-cutting backlog (do when it fits)
- `docs/SRS.md`, `docs/PERMISSIONS.md` and the CLAUDE.md build-order list are **not updated** for the flow doc; treat
  this file + `ARCHITECTURE.md` §8 as the source of truth until they are.
- Unused leftovers: `NotificationKind.QUOTE_CLARIFICATION_*`, `QUOTE_REGRETTED`; check the role/permission sheet
  (`docs/PERMISSIONS.md`) still makes sense for the new actions (accept, generate, recall).
- ERGO_NPD can see an RFQ only when the RFQ as a whole is `CUSTOMER_APPROVED` (partial approvals are reachable through
  `itemForErgo`/`approvedParts`); revisit when Sample PO / KAM project replaces the NPD conversion queue.

## 5. Working notes for this repo (learned the hard way)

- **Build/test:** backend `cd ergo-connect-backend && ./mvnw -q verify` (Docker Desktop must be running; ~5 min).
  On this machine `java` on PATH is JDK 21 but the shell's `JAVA_HOME` points to JDK 17, which `mvnw` uses — so
  prefix with `JAVA_HOME="C:/Program Files/Java/jdk-21.0.10"` (that path exists). Use `clean verify` after switching
  branches/stashing (stale `target/classes` migrations break every context). Frontend: `cd ergo-connect-frontend && npm run build && npm run lint`.
- **Windows + CRLF:** `core.autocrlf=true`; many files are CRLF on disk. Multi-line regex edits need `\r?\n`. The
  `Edit` tool needs a fresh `Read` first. Python is not installed; use perl/bash/PowerShell.
- **Never bulk-overwrite files from a script without checking the output is non-empty** (a perl error once wiped ~20
  test files; they were restored from git). Pattern that worked: write the perl script with the Write tool, run it into
  a temp file, `[ -s tmp ]` and `cmp`, then `cp`.
- **Tests:** shared helper `src/test/java/com/ergo/connect/TestRfq.java` builds multi-part RFQs, distributes
  (`invite`, `distribution`, `wholeTo`), finds a part's quote id, and drives an RFQ to customer approval
  (`approveWhole`). `ModuleSwitchFacadeTests` forces every new `<module>.api` method to be classified (and
  `QuotationSummary` etc. need an argument in its `arguments()`). `ModuleBoundaryTest` forbids cross-module imports
  except via `<module>.api`.
- **Flyway:** migrations are immutable once committed (V31–V38 are committed now); add V39+. Raw SQL in tests that
  inserts `rfqs`/`npd_projects` must also create an `rfq_items` row and set `source_rfq_item_id`.
- **Module switches / audit / DTO-per-audience** rules in `CLAUDE.md` still apply to everything above.
- **Git:** work on `sprint-3`. Commit messages end with the `Co-Authored-By` trailer given in the session reminder.

## 6. Test campaign status (QA, branch sprint-3, working tree not committed)

- **Step 1 (uncommitted fixes, do not revert):** `V39__quotation_optimistic_lock.sql`, `@Version` on `CustomerQuote` / `SupplierQuote`, `DataIntegrityViolation` -> 409 and `AccessDenied` -> 403 ProblemDetail handlers in `ValidationErrorHandler`, cancel/derive change in `QuoteService`.
- **Known red tests, pending a business decision:** `QuoteWorkflowAdversarialTests.aQuotationOfZeroPricedPartsIsNotAValidOfferToACustomer` and `zeroOrExpiredQuotesCannotBeSubmitted` (zero price accepted).
- **Chunk 2a done (production PO lifecycle):** `npd/ProductionPoSupport`, `ProductionPoAdversarialTests`, `ProductionPoConcurrencyTests`. Two open Low findings, left unfixed on purpose (no production changes in the test chunks): **F1** invisible-character return comment accepted; **F2** fractional quantity (1.5) accepted and truncated. Both have red tests (`aCommentOfOnlyInvisibleCharactersDoesNotCountAsAReason`, `aFractionalQuantityIsRefusedNotSilentlyTruncated`). Fixes: strip Unicode whitespace/format chars before the blank check; disable float-to-int coercion (`ACCEPT_FLOAT_AS_INT`) on the quantity DTOs.
- **Order handover done (chunk 2b, `npd/OfpHandoverAdversarialTests`, 8 tests, all green):** roles, bad forms not burning the one-time handover, PO defaults, race (one 201 + one 409), NPD/OFP at View, visibility, workflow refusals, supplier write scope. Note: bean validation (400) runs before the role check (403) on bad bodies; no data leaks.
- **Chunk 2c done (production PO switches, `npd/ProductionPoSwitchTests`, 5 tests, 4 green):** OFP View-only and hidden, NPD View-only and hidden, against every PO entry point. Open Low finding **F3** (red test `theEligibleListStillOpens...`): `GET /production-pos/eligible-projects` is guarded by `approve(OFP, CUSTOMER)`, which a View-only OFP module removes, so Customer OFP gets 403 instead of the list with no RAISE button that `ProductionPoService.eligibleProjects` is written to return. Fix: guard the endpoint with view-level (the service already filters by RAISERS).
- **Still to do:** roles-per-project-kind matrix, shipments/POD for production-born orders, switch matrix for the OFP project/order side, frontend checks (no test runner: buttons only from `actions`, form validation parity). Untestable today: Customer Management (inactive role), customer user with an ERGO role.
- **Test tips:** extend `AdversarialSupport` (call/send/race/cap/level); Customer Management cannot log in; ERGO_ADMIN login needs 2FA (TestUsers handles it); full run is about 10 minutes, run it in the background and check the exit code.
