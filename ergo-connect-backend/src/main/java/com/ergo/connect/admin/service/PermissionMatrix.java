package com.ergo.connect.admin.service;

import java.util.List;

import com.ergo.connect.common.security.ModuleAccess;
import com.ergo.connect.common.security.Role;

import static com.ergo.connect.admin.service.PermissionMatrix.Grant.A;
import static com.ergo.connect.admin.service.PermissionMatrix.Grant.C;
import static com.ergo.connect.admin.service.PermissionMatrix.Grant.E;
import static com.ergo.connect.admin.service.PermissionMatrix.Grant.RV;
import static com.ergo.connect.admin.service.PermissionMatrix.Grant.S;
import static com.ergo.connect.admin.service.PermissionMatrix.Grant.V;
import static com.ergo.connect.admin.service.PermissionMatrix.Grant.X;
import static com.ergo.connect.common.security.Role.CUSTOMER_KAM;
import static com.ergo.connect.common.security.Role.CUSTOMER_MANAGEMENT;
import static com.ergo.connect.common.security.Role.CUSTOMER_NPD;
import static com.ergo.connect.common.security.Role.CUSTOMER_OFP;
import static com.ergo.connect.common.security.Role.CUSTOMER_QUALITY;
import static com.ergo.connect.common.security.Role.CUSTOMER_RFQ;
import static com.ergo.connect.common.security.Role.ERGO_ADMIN;
import static com.ergo.connect.common.security.Role.ERGO_KAM;
import static com.ergo.connect.common.security.Role.ERGO_MANAGEMENT;
import static com.ergo.connect.common.security.Role.ERGO_NPD;
import static com.ergo.connect.common.security.Role.ERGO_OFP;
import static com.ergo.connect.common.security.Role.ERGO_QUALITY;
import static com.ergo.connect.common.security.Role.ERGO_RFQ;
import static com.ergo.connect.common.security.Role.SUPPLIER_KAM;
import static com.ergo.connect.common.security.Role.SUPPLIER_NPD;
import static com.ergo.connect.common.security.Role.SUPPLIER_OFP;
import static com.ergo.connect.common.security.Role.SUPPLIER_QUALITY;
import static com.ergo.connect.common.security.Role.SUPPLIER_RFQ;

/**
 * SRS §10.1-§10.12's module-wise permission tables, read-only reference data for the ERGO-Admin-only Permission
 * Mapping screen (§12.13.2). Not a rules engine: every actual permission check in the system is a hardcoded
 * {@code @PreAuthorize}/record-scope check in its own module (ARCHITECTURE.md §4); this is a static transcription of
 * the SRS tables so an admin can see them without opening the SRS. Client decision 2026-10-07 (ARCHITECTURE.md §8).
 * Each footnote is attached to the one grant cell it actually limits (security review, 2026-10-07), not the whole
 * row - several rows carry two different markers on two different columns (e.g. §10.5's RV** and S***, §10.6 and
 * §10.7's E* and A* pairs).
 */
public final class PermissionMatrix {

	public enum Grant {
		V, C, E, S, A, X, RV;
	}

	public record Cell(Grant value, String footnote) {

		public Cell(Grant value) {
			this(value, null);
		}
	}

	public record RoleGrant(Role role, Cell view, Cell create, Cell edit, Cell submit, Cell approve) {
	}

	public record Module(String section, String title, List<RoleGrant> roles, List<String> notes) {
	}

	public record Legend(Grant code, String meaning) {
	}

	public static final List<Legend> LEGEND = List.of(new Legend(Grant.V, "View"), new Legend(Grant.C, "Create"),
			new Legend(Grant.E, "Edit"), new Legend(Grant.S, "Submit / update own transactional response"),
			new Legend(Grant.A, "Approve / validate / final authorize"), new Legend(Grant.X, "No access"),
			new Legend(Grant.RV, "Restricted view (own company / assigned records only)"));

	public static final List<Module> MODULES = List.of(
			new Module("10.1", "User & Access Management",
					List.of(g(ERGO_ADMIN, V, C, E, S, A), g(ERGO_MANAGEMENT, V, X, E, S, A), g(ERGO_KAM, V, X, X, X, X),
							g(ERGO_RFQ, V, C, E, S, X), g(ERGO_NPD, V, C, E, S, A), g(ERGO_OFP, V, C, E, S, A),
							g(ERGO_QUALITY, V, C, E, S, A), g(CUSTOMER_RFQ, RV, C, E, S, A), g(CUSTOMER_NPD, RV, C, E, S, A),
							g(CUSTOMER_OFP, RV, C, E, S, A), g(CUSTOMER_QUALITY, RV, C, E, S, A), g(CUSTOMER_KAM, RV, C, E, S, A),
							g(CUSTOMER_MANAGEMENT, RV, C, E, S, A), g(SUPPLIER_RFQ, RV, X, X, S, X), g(SUPPLIER_NPD, RV, X, E, S, X),
							g(SUPPLIER_OFP, RV, X, E, S, X), g(SUPPLIER_QUALITY, RV, C, E, S, X), g(SUPPLIER_KAM, RV, X, X, X, X)),
					List.of("The Create/Edit/Approve marks on this table for every role other than ERGO Admin are the "
							+ "SRS's generic per-module template applied mechanically; they are not read as a real grant to "
							+ "manage ERGO's own users and roles - in the running system, creating, editing, activating and "
							+ "role-mapping a user is ERGO Admin only (client decision, ARCHITECTURE.md §8, 2026-09-24). This "
							+ "Permission Mapping screen itself is governed by §10.12 below (ERGO Admin only), not by this "
							+ "table.")),
			new Module("10.2", "Customer Management",
					List.of(g(ERGO_ADMIN, V, C, E, S, A), g(ERGO_MANAGEMENT, V, X, X, X, A), g(ERGO_KAM, V, C, E, S, X),
							g(ERGO_RFQ, V, C, E, S, X), g(ERGO_NPD, RV, X, X, X, X), g(ERGO_OFP, RV, X, X, X, X),
							g(ERGO_QUALITY, RV, X, X, X, X), g(CUSTOMER_RFQ, RV, X, E, S, X), g(CUSTOMER_NPD, RV, X, X, X, X),
							g(CUSTOMER_OFP, RV, X, X, X, X), g(CUSTOMER_QUALITY, RV, X, X, X, X), g(CUSTOMER_KAM, RV, X, E, S, X),
							g(CUSTOMER_MANAGEMENT, RV, X, E, S, X), g(SUPPLIER_RFQ, X, X, X, X, X), g(SUPPLIER_NPD, X, X, X, X, X),
							g(SUPPLIER_OFP, X, X, X, X, X), g(SUPPLIER_QUALITY, X, X, X, X, X), g(SUPPLIER_KAM, X, X, X, X, X)),
					List.of()),
			new Module("10.3", "Supplier Management",
					List.of(g(ERGO_ADMIN, V, C, E, S, A), g(ERGO_MANAGEMENT, V, X, X, X, A), g(ERGO_KAM, RV, X, X, X, X),
							g(ERGO_RFQ, V, C, E, S, X), g(ERGO_NPD, RV, X, X, X, X), g(ERGO_OFP, RV, X, X, X, X),
							g(ERGO_QUALITY, RV, X, X, X, X), g(CUSTOMER_RFQ, X, X, X, X, X), g(CUSTOMER_NPD, X, X, X, X, X),
							g(CUSTOMER_OFP, X, X, X, X, X), g(CUSTOMER_QUALITY, X, X, X, X, X), g(CUSTOMER_KAM, X, X, X, X, X),
							g(CUSTOMER_MANAGEMENT, X, X, X, X, X), g(SUPPLIER_RFQ, RV, X, E, S, X), g(SUPPLIER_NPD, RV, X, X, X, X),
							g(SUPPLIER_OFP, RV, X, X, X, X), g(SUPPLIER_QUALITY, RV, X, X, X, X), g(SUPPLIER_KAM, RV, X, E, S, X)),
					List.of()),
				// 10.4-10.8: the five workflow modules come straight from the role sheet (ModuleAccess), the one place
				// the enforced access lives (client decision 2026-10-19, ARCHITECTURE.md §8), so this screen cannot drift.
				sheetModule("10.4", "RFQ Management", ModuleAccess.Module.RFQ,
						List.of("Source: the role access sheet. V = view only; A = create, edit, submit without final approval; "
								+ "O = owns and gives the final approval (validate, reopen, close); CO = the customer's final approval.",
								"Suppliers reach RFQs only as the quotes ERGO asks them for (Assigned RFQs): A = respond, V = read the "
										+ "assigned RFQ.")),
				sheetModule("10.5", "Quotation Management", ModuleAccess.Module.QUOTATION,
						List.of("Source: the role access sheet. O (ERGO) decides on the comparison and sends quotes to the customer.",
								"Customer: CO approves, requests a revision or declines an ERGO quote; A may only request a revision. "
										+ "Customers see only quotes ERGO has sent to their own company.",
								"Supplier: A creates, edits and submits its own quote (while ERGO has it open).")),
				sheetModule("10.6", "NPD Management", ModuleAccess.Module.NPD,
						List.of("Source: the role access sheet. O (ERGO NPD, Management) converts RFQs, closes stages and decides "
								+ "ERGO-owned stage and quality gates; ERGO Quality (A) prepares and requests them.",
								"Customer NPD (CO) decides the customer-facing approvals (sample, milestone, quality acceptance); "
										+ "other customer roles act or view on their own company's projects.",
								"Supplier roles (A) edit only supplier-owned activities, dates, attachments and issue responses.")),
				sheetModule("10.7", "OFP / Production Tracking", ModuleAccess.Module.OFP,
						List.of("Source: the role access sheet. O (ERGO OFP, Management) creates orders and runs the workflow and "
								+ "shipments; ERGO Quality (A) holds and releases orders and records material inspection status.",
								"Suppliers (A) update production progress and packaging/dispatch of their own assigned orders only.",
								"Customers see their own company's orders read-only; no customer-side OFP action exists yet, so CO/A "
										+ "customers have view access today.")),
				sheetModule("10.8", "Quality Management", ModuleAccess.Module.QUALITY,
						List.of("Source: the role access sheet. O (ERGO Management, Quality) passes, rejects, closes and approves; "
								+ "A creates, edits and submits inspections, NCRs and deviations within the role's own records.",
								"Deviations: the customer's CO roles decide the customer-approval step; every acting role may raise one.",
								"Customers see inspections and NCRs only where ERGO has made them customer-visible; customers and "
										+ "suppliers never see each other.")),
			new Module("10.9", "Document Management System",
					List.of(g(ERGO_ADMIN, V, C, E, S, A), g(ERGO_MANAGEMENT, V, X, X, X, A), g(ERGO_KAM, RV, C, E, S, X),
							g(ERGO_RFQ, RV, C, E, S, X), g(ERGO_NPD, RV, C, E, S, X), g(ERGO_OFP, RV, C, E, S, X),
							g(ERGO_QUALITY, RV, C, E, S, X),
							new RoleGrant(CUSTOMER_RFQ, c(RV), c(C), c(E, "E*"), c(S), c(X)),
							new RoleGrant(CUSTOMER_NPD, c(RV), c(C), c(E, "E*"), c(S), c(X)),
							new RoleGrant(CUSTOMER_OFP, c(RV), c(C), c(E, "E*"), c(S), c(X)),
							new RoleGrant(CUSTOMER_QUALITY, c(RV), c(C), c(E, "E*"), c(S), c(X)),
							new RoleGrant(CUSTOMER_KAM, c(RV), c(C), c(E, "E*"), c(S), c(X)),
							new RoleGrant(CUSTOMER_MANAGEMENT, c(RV), c(C), c(E, "E*"), c(S), c(X)),
							new RoleGrant(SUPPLIER_RFQ, c(RV), c(C), c(E, "E**"), c(S), c(X)),
							new RoleGrant(SUPPLIER_NPD, c(RV), c(C), c(E, "E**"), c(S), c(X)),
							new RoleGrant(SUPPLIER_OFP, c(RV), c(C), c(E, "E**"), c(S), c(X)),
							new RoleGrant(SUPPLIER_QUALITY, c(RV), c(C), c(E, "E**"), c(S), c(X)),
							new RoleGrant(SUPPLIER_KAM, c(RV), c(C), c(E, "E**"), c(S), c(X))),
					List.of("E*: Customer roles can edit or replace only their own uploaded documents, until locked/approved.",
							"E**: Supplier roles can edit or replace only their own uploaded documents, until locked/approved.")),
			new Module("10.10", "Communication & Alerts",
					List.of(g(ERGO_ADMIN, V, C, E, S, A), g(ERGO_MANAGEMENT, V, C, E, S, X), g(ERGO_KAM, RV, C, E, S, X),
							g(ERGO_RFQ, RV, C, E, S, X), g(ERGO_NPD, RV, C, E, S, X), g(ERGO_OFP, RV, C, E, S, X),
							g(ERGO_QUALITY, RV, C, E, S, X),
							new RoleGrant(CUSTOMER_RFQ, c(RV), c(C), c(E, "E*"), c(S), c(X)),
							new RoleGrant(CUSTOMER_NPD, c(RV), c(C), c(E, "E*"), c(S), c(X)),
							new RoleGrant(CUSTOMER_OFP, c(RV), c(C), c(E, "E*"), c(S), c(X)),
							new RoleGrant(CUSTOMER_QUALITY, c(RV), c(C), c(E, "E*"), c(S), c(X)),
							new RoleGrant(CUSTOMER_KAM, c(RV), c(C), c(E, "E*"), c(S), c(X)),
							new RoleGrant(CUSTOMER_MANAGEMENT, c(RV), c(C), c(E, "E*"), c(S), c(X)),
							new RoleGrant(SUPPLIER_RFQ, c(RV), c(C), c(E, "E**"), c(S), c(X)),
							new RoleGrant(SUPPLIER_NPD, c(RV), c(C), c(E, "E**"), c(S), c(X)),
							new RoleGrant(SUPPLIER_OFP, c(RV), c(C), c(E, "E**"), c(S), c(X)),
							new RoleGrant(SUPPLIER_QUALITY, c(RV), c(C), c(E, "E**"), c(S), c(X)),
							new RoleGrant(SUPPLIER_KAM, c(RV), c(C), c(E, "E**"), c(S), c(X))),
					List.of("E*/E**: edit limited to own comments/messages until the conversation item is locked or "
							+ "responded to per workflow.")),
			new Module("10.11", "Dashboard & Analytics",
					List.of(g(ERGO_ADMIN, V, X, X, X, X), g(ERGO_MANAGEMENT, V, X, X, X, X), g(ERGO_KAM, RV, X, X, X, X),
							g(ERGO_RFQ, RV, X, X, X, X), g(ERGO_NPD, RV, X, X, X, X), g(ERGO_OFP, RV, X, X, X, X),
							g(ERGO_QUALITY, RV, X, X, X, X), g(CUSTOMER_RFQ, RV, X, X, X, X), g(CUSTOMER_NPD, RV, X, X, X, X),
							g(CUSTOMER_OFP, RV, X, X, X, X), g(CUSTOMER_QUALITY, RV, X, X, X, X), g(CUSTOMER_KAM, RV, X, X, X, X),
							g(CUSTOMER_MANAGEMENT, RV, X, X, X, X), g(SUPPLIER_RFQ, RV, X, X, X, X), g(SUPPLIER_NPD, RV, X, X, X, X),
							g(SUPPLIER_OFP, RV, X, X, X, X), g(SUPPLIER_QUALITY, RV, X, X, X, X), g(SUPPLIER_KAM, RV, X, X, X, X)),
					List.of()),
			new Module("10.12", "Admin Controls & Master Data",
					List.of(g(ERGO_ADMIN, V, C, E, S, A),
							new RoleGrant(ERGO_MANAGEMENT, c(V), c(X), c(X), c(X), c(A, "A*")),
							g(ERGO_KAM, X, X, X, X, X), g(ERGO_RFQ, RV, X, X, X, X), g(ERGO_NPD, RV, X, X, X, X),
							g(ERGO_OFP, RV, X, X, X, X), g(ERGO_QUALITY, RV, X, X, X, X), g(CUSTOMER_RFQ, X, X, X, X, X),
							g(CUSTOMER_NPD, X, X, X, X, X), g(CUSTOMER_OFP, X, X, X, X, X), g(CUSTOMER_QUALITY, X, X, X, X, X),
							g(CUSTOMER_KAM, X, X, X, X, X), g(CUSTOMER_MANAGEMENT, X, X, X, X, X), g(SUPPLIER_RFQ, X, X, X, X, X),
							g(SUPPLIER_NPD, X, X, X, X, X), g(SUPPLIER_OFP, X, X, X, X, X), g(SUPPLIER_QUALITY, X, X, X, X, X),
							g(SUPPLIER_KAM, X, X, X, X, X)),
					List.of("A* (SRS text, the system's one master-data dual-approval marker - the §12.13.2/§14.8 \"dual "
							+ "approval rules\" this Permission Mapping screen was scoped to, per ARCHITECTURE.md §8 "
							+ "2026-10-07): ERGO Management approval MAY apply to selected master-data changes - workflow "
							+ "policy, commercial policy, or strategic configuration - if the company wishes dual control. Not "
							+ "implemented: every master-data change today is ERGO Admin only with no second approval step "
							+ "(MasterDataController); a configurable dual-approval matrix is deferred to Phase-2 (SRS §16.2). "
							+ "Each business module's own approval workflow (RFQ validation, NPD stage/quality gates, "
							+ "deviation approval, etc.) is separate and already built into that module.")));

	private PermissionMatrix() {
	}

	/** One module's table, generated from {@link ModuleAccess}: V = view; A adds create / edit / submit; O and CO add
	 * the final approval. View is restricted (RV) wherever record scope narrows it. */
	private static Module sheetModule(String section, String title, ModuleAccess.Module module, List<String> notes) {
		List<RoleGrant> grants = java.util.Arrays.stream(Role.values()).map(role -> {
			ModuleAccess.Level level = ModuleAccess.level(role, module);
			if (!level.canView()) {
				return g(role, X, X, X, X, X);
			}
			boolean restricted = role.companyType() != com.ergo.connect.common.security.CompanyType.ERGO
					|| role == ERGO_KAM || role == ERGO_NPD || role == ERGO_OFP && module == ModuleAccess.Module.QUALITY;
			Cell view = c(restricted ? RV : V);
			Cell act = level.canAct() ? null : c(X);
			Cell approve = level.canApprove() ? (level == ModuleAccess.Level.CO ? c(A, "CO") : c(A)) : c(X);
			return new RoleGrant(role, view, act == null ? c(C) : act, act == null ? c(E) : act, act == null ? c(S) : act,
					approve);
		}).toList();
		return new Module(section, title, grants, notes);
	}

	private static Cell c(Grant value) {
		return new Cell(value);
	}

	private static Cell c(Grant value, String footnote) {
		return new Cell(value, footnote);
	}

	private static RoleGrant g(Role role, Grant view, Grant create, Grant edit, Grant submit, Grant approve) {
		return new RoleGrant(role, c(view), c(create), c(edit), c(submit), c(approve));
	}

}
