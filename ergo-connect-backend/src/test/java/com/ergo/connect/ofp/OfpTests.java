package com.ergo.connect.ofp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Phase 4a: ERGO-only order core, both creation paths (NPD handover and direct standalone create), record scope
 * (SRS §10.7, §8.2), the linear workflow up to Packaging in Progress plus Hold/Release (SRS §14.6), and material
 * status / production progress tracking. Phase 4b: the assigned supplier's own scoped edits (production progress,
 * packaging/dispatch), scope isolation between suppliers, and the ERGO-only, inspection-gated Dispatch Ready
 * transition (SRS §10.7 "E**", §14.6, §17.5).
 */
@IntegrationTest
class OfpTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestUsers users;

	long customerA;

	long customerB;

	long supplier;

	long inr;

	Login ergoAdmin;

	Login ergoOfp;

	Login ergoManagement;

	Login ergoKam;

	Login ergoNpd;

	Login ergoRfq;

	Login buyerA;

	Login seller;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		long ergo = users.company("ERGO");
		customerA = users.company("CUSTOMER");
		customerB = users.company("CUSTOMER");
		supplier = users.company("SUPPLIER");
		inr = users.currency("INR");
		ergoAdmin = users.login(ergo, "ERGO_ADMIN");
		ergoOfp = users.login(ergo, "ERGO_OFP");
		ergoManagement = users.login(ergo, "ERGO_MANAGEMENT");
		ergoKam = users.login(ergo, "ERGO_KAM");
		users.assignAccounts(ergoKam.userId(), customerA);
		ergoNpd = users.login(ergo, "ERGO_NPD");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		buyerA = users.login(customerA, "CUSTOMER_RFQ");
		seller = users.login(supplier, "SUPPLIER_RFQ");
	}

	@Test
	void standaloneCreateGeneratesAnOrderNumberAndOnlyEditorsMayCreate() throws Exception {
		call(post("/api/ofp/orders"), ergoRfq, orderForm(customerA, supplier)).andExpect(status().isForbidden());
		call(post("/api/ofp/orders"), ergoManagement, orderForm(customerA, supplier))
			.andExpect(status().isForbidden());

		String created = call(post("/api/ofp/orders"), ergoOfp, orderForm(customerA, supplier))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.currentStatus").value("ORDER_CREATED"))
			.andExpect(jsonPath("$.linkedNpdProjectId").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat((String) JsonPath.read(created, "$.orderNumber")).matches("ORD-\\d{4}-\\d{5}");

		// ERGO Admin may also create (SRS §10.7)
		call(post("/api/ofp/orders"), ergoAdmin, orderForm(customerA, supplier)).andExpect(status().isCreated());
	}

	@Test
	void handoverIsRejectedWhenTheNpdProjectIsNotYetApprovedForProductionHandover() throws Exception {
		long rfqId = approvedRfq(buyerA, "89");
		long projectId = convert(rfqId); // still at PLANNING
		call(post("/api/ofp/orders/handover/{id}", projectId), ergoOfp,
				"{\"orderedQuantity\": 500, \"dueDate\": \"%s\"}".formatted(LocalDate.now().plusDays(45)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("This NPD project is not yet approved for production handover"));
	}

	@Test
	void handoverCopiesTheNpdProjectsOwnDataAndCannotBeRepeated() throws Exception {
		long rfqId = approvedRfq(buyerA, "90");
		long projectId = convert(rfqId);
		advanceToHandoverReady(projectId);

		String order = call(post("/api/ofp/orders/handover/{id}", projectId), ergoOfp,
				"{\"orderedQuantity\": 1000, \"dueDate\": \"%s\"}".formatted(LocalDate.now().plusDays(45)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.linkedNpdProjectId").value((int) projectId))
			.andExpect(jsonPath("$.linkedRfqId").value((int) rfqId))
			.andExpect(jsonPath("$.customer").exists())
			.andExpect(jsonPath("$.supplier").exists())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat((String) JsonPath.read(order, "$.orderNumber")).matches("ORD-\\d{4}-\\d{5}");

		assertThat(jdbc.queryForObject("SELECT handover_ready_flag FROM npd_projects WHERE npd_project_id = ?",
				Boolean.class, projectId)).isTrue();

		// a second handover of the same project is rejected
		call(post("/api/ofp/orders/handover/{id}", projectId), ergoOfp,
				"{\"orderedQuantity\": 500, \"dueDate\": \"%s\"}".formatted(LocalDate.now().plusDays(45)))
			.andExpect(status().isConflict());

		// only ERGO OFP/Admin may hand over
		long rfqId2 = approvedRfq(buyerA, "91");
		long projectId2 = convert(rfqId2);
		call(post("/api/ofp/orders/handover/{id}", projectId2), ergoRfq,
				"{\"orderedQuantity\": 500, \"dueDate\": \"%s\"}".formatted(LocalDate.now().plusDays(45)))
			.andExpect(status().isForbidden());
	}

	@Test
	void keyAccountManagerSeesOnlyItsAssignedCustomersOrdersAndErgoNpdSeesOnlyHandoverOrders() throws Exception {
		long orderA = createStandalone(customerA);
		long orderB = createStandalone(customerB);
		long rfqId = approvedRfq(buyerA, "90");
		long projectId = convert(rfqId);
		advanceToHandoverReady(projectId);
		long handoverOrder = handover(projectId);

		call(get("/api/ofp/orders"), ergoKam, null).andExpect(jsonPath("$[*].id", hasItem((int) orderA)))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) orderB))));
		call(get("/api/ofp/orders/{id}", orderA), ergoKam, null).andExpect(status().isOk());
		call(get("/api/ofp/orders/{id}", orderB), ergoKam, null).andExpect(status().isNotFound());

		// ERGO NPD: only the order that came out of an NPD project, not the standalone ones
		call(get("/api/ofp/orders"), ergoNpd, null).andExpect(jsonPath("$[*].id", hasItem((int) handoverOrder)))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) orderA))))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) orderB))));
		call(get("/api/ofp/orders/{id}", orderA), ergoNpd, null).andExpect(status().isNotFound());
		call(get("/api/ofp/orders/{id}", handoverOrder), ergoNpd, null).andExpect(status().isOk());

		// ERGO RFQ has no access at all (SRS §10.7 X), and nor does any customer/supplier role
		for (Login outsider : List.of(ergoRfq, buyerA, seller)) {
			call(get("/api/ofp/orders"), outsider, null).andExpect(status().isForbidden());
			call(get("/api/ofp/orders/{id}", orderA), outsider, null).andExpect(status().isForbidden());
		}

		// ERGO Management sees everything but cannot write
		call(get("/api/ofp/orders"), ergoManagement, null).andExpect(jsonPath("$[*].id", hasItem((int) orderA)))
			.andExpect(jsonPath("$[*].id", hasItem((int) orderB)));
	}

	@Test
	void advanceMovesOneStepAtATimeAndCannotGoPastPackagingInProgressIn4a() throws Exception {
		long orderId = createStandalone(customerA);

		call(post("/api/ofp/orders/{id}/advance", orderId), ergoOfp, "{}")
			.andExpect(jsonPath("$.currentStatus").value("MATERIAL_ORDERED"));
		call(post("/api/ofp/orders/{id}/advance", orderId), ergoOfp, "{}")
			.andExpect(jsonPath("$.currentStatus").value("MATERIAL_RECEIVED"));
		call(post("/api/ofp/orders/{id}/advance", orderId), ergoOfp, "{}")
			.andExpect(jsonPath("$.currentStatus").value("PRODUCTION_STARTED"));
		call(post("/api/ofp/orders/{id}/advance", orderId), ergoOfp, "{}")
			.andExpect(jsonPath("$.currentStatus").value("IN_INSPECTION"));
		call(post("/api/ofp/orders/{id}/advance", orderId), ergoOfp, "{}")
			.andExpect(jsonPath("$.currentStatus").value("PACKAGING_IN_PROGRESS"));

		// 4a stops here: no dispatch-onward transition exists yet
		call(post("/api/ofp/orders/{id}/advance", orderId), ergoOfp, "{}").andExpect(status().isConflict());

		// only ERGO OFP/Admin may advance
		long other = createStandalone(customerA);
		call(post("/api/ofp/orders/{id}/advance", other), ergoManagement, "{}").andExpect(status().isForbidden());
	}

	@Test
	void holdRequiresAReasonAndReleaseReturnsToWhateverStatusWasInterrupted() throws Exception {
		long orderId = createStandalone(customerA);
		call(post("/api/ofp/orders/{id}/advance", orderId), ergoOfp, "{}").andExpect(status().isOk()); // MATERIAL_ORDERED

		call(post("/api/ofp/orders/{id}/hold", orderId), ergoOfp, "{}").andExpect(status().isUnprocessableContent());
		call(post("/api/ofp/orders/{id}/hold", orderId), ergoOfp, "{\"comment\": \"Awaiting customer PO revision\"}")
			.andExpect(jsonPath("$.currentStatus").value("ON_HOLD"));

		// can't advance while on hold
		call(post("/api/ofp/orders/{id}/advance", orderId), ergoOfp, "{}").andExpect(status().isConflict());
		// can't hold again while already on hold
		call(post("/api/ofp/orders/{id}/hold", orderId), ergoOfp, "{\"comment\": \"again\"}")
			.andExpect(status().isConflict());

		call(post("/api/ofp/orders/{id}/release", orderId), ergoOfp, "{}")
			.andExpect(jsonPath("$.currentStatus").value("MATERIAL_ORDERED"));

		// releasing when not on hold is rejected
		call(post("/api/ofp/orders/{id}/release", orderId), ergoOfp, "{}").andExpect(status().isConflict());

		assertThat(jdbc.queryForObject("""
				SELECT action_reason FROM audit_logs WHERE entity_name = 'Order' AND record_id = ?
				AND field_name = 'currentStatus' AND new_value = 'ON_HOLD'""", String.class, orderId))
			.isEqualTo("Awaiting customer PO revision");
	}

	// Optimistic-lock behavior itself (a write based on an already-stale read is rejected, 409) is covered by
	// OrderConcurrencyTest, which sits in ofp.domain to reach the entity/repository directly: MockMvc can't
	// deterministically force two transactions to interleave through the public HTTP API alone.

	@Test
	void materialStatusAndProductionProgressAreErgoOfpAndAdminOnly() throws Exception {
		long orderId = createStandalone(customerA);

		call(post("/api/ofp/orders/{id}/material-status", orderId), ergoManagement, materialStatusForm("PENDING"))
			.andExpect(status().isForbidden());

		String withMaterial = call(post("/api/ofp/orders/{id}/material-status", orderId), ergoOfp,
				materialStatusForm("ORDERED")).andExpect(status().isCreated())
			.andExpect(jsonPath("$.materialStatus[0].materialStatus").value("ORDERED"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		long statusId = ((Number) JsonPath.read(withMaterial, "$.materialStatus[0].id")).longValue();

		call(put("/api/ofp/material-status/{id}", statusId), ergoOfp, materialStatusForm("RECEIVED"))
			.andExpect(jsonPath("$.materialStatus[0].materialStatus").value("RECEIVED"));

		// production progress: BLOCKED requires a blocker reason
		call(post("/api/ofp/orders/{id}/production-progress", orderId), ergoOfp,
				productionProgressForm("BLOCKED", null)).andExpect(status().isUnprocessableContent());
		String withProgress = call(post("/api/ofp/orders/{id}/production-progress", orderId), ergoOfp,
				productionProgressForm("BLOCKED", "Waiting on tool steel")).andExpect(status().isCreated())
			.andExpect(jsonPath("$.productionProgress[0].status").value("BLOCKED"))
			.andExpect(jsonPath("$.productionProgress[0].blockerReason").value("Waiting on tool steel"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		long progressId = ((Number) JsonPath.read(withProgress, "$.productionProgress[0].id")).longValue();

		call(put("/api/ofp/production-progress/{id}", progressId), ergoOfp, productionProgressForm("IN_PROGRESS", null))
			.andExpect(jsonPath("$.productionProgress[0].status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.productionProgress[0].blockerReason").doesNotExist());

		call(put("/api/ofp/production-progress/{id}", progressId), ergoKam, productionProgressForm("DONE", null))
			.andExpect(status().isForbidden());
	}

	@Test
	void updateSetsAdministrativeFieldsAndOwnerMustBeAnErgoUser() throws Exception {
		long orderId = createStandalone(customerA);

		call(put("/api/ofp/orders/{id}", orderId), ergoOfp,
				"{\"ownerUserId\": %d}".formatted(buyerA.userId())).andExpect(status().isUnprocessableContent());

		call(put("/api/ofp/orders/{id}", orderId), ergoOfp, """
				{"customerPoNo": "PO-1", "supplierPoNo": "SPO-1", "dueDate": "%s", "ownerUserId": %d}"""
			.formatted(LocalDate.now().plusDays(30), ergoOfp.userId()))
			.andExpect(jsonPath("$.customerPoNo").value("PO-1"))
			.andExpect(jsonPath("$.owner").exists());
	}

	// ---------------------------------------------------------------- Phase 4b: supplier participation

	@Test
	void supplierMayEditProductionProgressAndPackagingDispatchOnlyOnItsOwnAssignedOrder() throws Exception {
		long orderId = createStandalone(customerA);
		Login supplierOfp = users.login(supplier, "SUPPLIER_OFP");
		long otherSupplier = users.company("SUPPLIER");
		Login otherSupplierOfp = users.login(otherSupplier, "SUPPLIER_OFP");

		// the assigned supplier sees its own order and may update production progress / packaging & dispatch
		call(get("/api/ofp/orders"), supplierOfp, null).andExpect(jsonPath("$[*].id", hasItem((int) orderId)));
		call(get("/api/ofp/orders/{id}", orderId), supplierOfp, null).andExpect(status().isOk());

		String withProgress = call(post("/api/ofp/orders/{id}/production-progress", orderId), supplierOfp,
				productionProgressForm("IN_PROGRESS", null)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.productionProgress[0].status").value("IN_PROGRESS"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		long progressId = ((Number) JsonPath.read(withProgress, "$.productionProgress[0].id")).longValue();
		call(put("/api/ofp/production-progress/{id}", progressId), supplierOfp, productionProgressForm("DONE", null))
			.andExpect(jsonPath("$.productionProgress[0].status").value("DONE"));

		String withDispatch = call(post("/api/ofp/orders/{id}/packaging-dispatch", orderId), supplierOfp,
				packagingDispatchForm("IN_PROGRESS", false)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.packagingDispatch[0].packagingStatus").value("IN_PROGRESS"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		long dispatchId = ((Number) JsonPath.read(withDispatch, "$.packagingDispatch[0].id")).longValue();
		call(put("/api/ofp/packaging-dispatch/{id}", dispatchId), supplierOfp, packagingDispatchForm("PACKED", true))
			.andExpect(jsonPath("$.packagingDispatch[0].packagingStatus").value("PACKED"))
			.andExpect(jsonPath("$.packagingDispatch[0].dispatchReadyFlag").value(true));

		// a different supplier's order does not exist for this one (404, not 403): scope, not a role gap
		call(get("/api/ofp/orders/{id}", orderId), otherSupplierOfp, null).andExpect(status().isNotFound());
		call(post("/api/ofp/orders/{id}/production-progress", orderId), otherSupplierOfp,
				productionProgressForm("IN_PROGRESS", null)).andExpect(status().isNotFound());
		call(post("/api/ofp/orders/{id}/packaging-dispatch", orderId), otherSupplierOfp,
				packagingDispatchForm("IN_PROGRESS", false)).andExpect(status().isNotFound());
		call(put("/api/ofp/production-progress/{id}", progressId), otherSupplierOfp, productionProgressForm("DONE", null))
			.andExpect(status().isNotFound());
		call(put("/api/ofp/packaging-dispatch/{id}", dispatchId), otherSupplierOfp, packagingDispatchForm("PACKED", true))
			.andExpect(status().isNotFound());
		call(get("/api/ofp/orders"), otherSupplierOfp, null).andExpect(jsonPath("$[*].id", not(hasItem((int) orderId))));
	}

	@Test
	void supplierCannotTouchMaterialStatusOrderHeaderOrWorkflowActions() throws Exception {
		long orderId = createStandalone(customerA);
		Login supplierOfp = users.login(supplier, "SUPPLIER_OFP");
		String withMaterial = call(post("/api/ofp/orders/{id}/material-status", orderId), ergoOfp,
				materialStatusForm("ORDERED")).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long statusId = ((Number) JsonPath.read(withMaterial, "$.materialStatus[0].id")).longValue();

		call(post("/api/ofp/orders"), supplierOfp, orderForm(customerA, supplier)).andExpect(status().isForbidden());
		call(put("/api/ofp/orders/{id}", orderId), supplierOfp,
				"{\"ownerUserId\": %d}".formatted(ergoOfp.userId())).andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/material-status", orderId), supplierOfp, materialStatusForm("ORDERED"))
			.andExpect(status().isForbidden());
		call(put("/api/ofp/material-status/{id}", statusId), supplierOfp, materialStatusForm("RECEIVED"))
			.andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/advance", orderId), supplierOfp, "{}").andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/hold", orderId), supplierOfp, "{\"comment\": \"x\"}")
			.andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/release", orderId), supplierOfp, "{}").andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/dispatch-ready", orderId), supplierOfp, "{}").andExpect(status().isForbidden());
	}

	@Test
	void everySupplierEditorRoleNotJustSupplierOfpCanEditItsOwnOrder() throws Exception {
		long orderId = createStandalone(customerA);
		// SRS §10.7 "E**" names all four supplier roles; confirm the grant isn't accidentally SUPPLIER_OFP-only
		Login supplierNpd = users.login(supplier, "SUPPLIER_NPD");
		call(post("/api/ofp/orders/{id}/production-progress", orderId), supplierNpd,
				productionProgressForm("IN_PROGRESS", null)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.productionProgress[0].status").value("IN_PROGRESS"));
		call(post("/api/ofp/orders/{id}/packaging-dispatch", orderId), supplierNpd,
				packagingDispatchForm("IN_PROGRESS", false)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.packagingDispatch[0].packagingStatus").value("IN_PROGRESS"));
	}

	@Test
	void supplierActionsAreExactlyProductionProgressAndPackagingDispatchNeverDispatchReady() throws Exception {
		long orderId = advanceToPackagingInProgress(customerA);
		call(post("/api/ofp/orders/{id}/material-status", orderId), ergoOfp, materialStatusForm("RECEIVED", "PASSED"))
			.andExpect(status().isCreated()); // gate met: PACKAGING_IN_PROGRESS + one PASSED row
		Login supplierOfp = users.login(supplier, "SUPPLIER_OFP");

		call(get("/api/ofp/orders/{id}", orderId), supplierOfp, null)
			.andExpect(jsonPath("$.actions",
					containsInAnyOrder("UPDATE_PRODUCTION_PROGRESS", "UPDATE_PACKAGING_DISPATCH")));
	}

	@Test
	void supplierSettingDispatchReadyFlagNeverChangesOrderStatus() throws Exception {
		long orderId = createStandalone(customerA);
		Login supplierOfp = users.login(supplier, "SUPPLIER_OFP");
		call(post("/api/ofp/orders/{id}/packaging-dispatch", orderId), supplierOfp,
				packagingDispatchForm("PACKED", true)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.packagingDispatch[0].dispatchReadyFlag").value(true))
			.andExpect(jsonPath("$.currentStatus").value("ORDER_CREATED"));
		assertThat(jdbc.queryForObject("SELECT current_status FROM orders WHERE order_id = ?", String.class, orderId))
			.isEqualTo("ORDER_CREATED");
	}

	@Test
	void packagingDispatchCreateAndUpdateAreAudited() throws Exception {
		long orderId = createStandalone(customerA);
		String withDispatch = call(post("/api/ofp/orders/{id}/packaging-dispatch", orderId), ergoOfp,
				packagingDispatchForm("PENDING", false)).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long dispatchId = ((Number) JsonPath.read(withDispatch, "$.packagingDispatch[0].id")).longValue();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM audit_logs WHERE entity_name = 'OrderPackagingDispatch' AND record_id = ?
				AND event_type = 'CREATE'""", Integer.class, dispatchId)).isEqualTo(1);

		call(put("/api/ofp/packaging-dispatch/{id}", dispatchId), ergoOfp, packagingDispatchForm("PACKED", true))
			.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("""
				SELECT new_value FROM audit_logs WHERE entity_name = 'OrderPackagingDispatch' AND record_id = ?
				AND event_type = 'UPDATE' AND field_name = 'packagingStatus'""", String.class, dispatchId))
			.isEqualTo("PACKED");
	}

	@Test
	void releaseIsRefusedWhenDispatchReadyGateNoLongerHoldsAndSupplierWriteIsBlockedWhileOnHold() throws Exception {
		long orderId = advanceToPackagingInProgress(customerA);
		String withMaterial = call(post("/api/ofp/orders/{id}/material-status", orderId), ergoOfp,
				materialStatusForm("RECEIVED", "PASSED")).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long statusId = ((Number) JsonPath.read(withMaterial, "$.materialStatus[0].id")).longValue();
		call(post("/api/ofp/orders/{id}/dispatch-ready", orderId), ergoOfp, "{}")
			.andExpect(jsonPath("$.currentStatus").value("DISPATCH_READY"));

		call(post("/api/ofp/orders/{id}/hold", orderId), ergoOfp, "{\"comment\": \"pending customer sign-off\"}")
			.andExpect(jsonPath("$.currentStatus").value("ON_HOLD"));

		// L2: a supplier's own write is now blocked while the order is ON_HOLD (ERGO's own writes are unaffected)
		Login supplierOfp = users.login(supplier, "SUPPLIER_OFP");
		call(post("/api/ofp/orders/{id}/production-progress", orderId), supplierOfp,
				productionProgressForm("IN_PROGRESS", null)).andExpect(status().isConflict());
		call(post("/api/ofp/orders/{id}/packaging-dispatch", orderId), supplierOfp,
				packagingDispatchForm("IN_PROGRESS", false)).andExpect(status().isConflict());
		call(post("/api/ofp/orders/{id}/production-progress", orderId), ergoOfp,
				productionProgressForm("IN_PROGRESS", null)).andExpect(status().isCreated());

		// M2: the material inspection that made Dispatch Ready reachable no longer holds
		call(put("/api/ofp/material-status/{id}", statusId), ergoOfp, materialStatusForm("RECEIVED", "FAILED"))
			.andExpect(status().isOk());
		call(post("/api/ofp/orders/{id}/release", orderId), ergoOfp, "{}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail")
				.value("Cannot release back to Dispatch Ready - not every material inspection has passed anymore"));

		// fix it and release succeeds
		call(put("/api/ofp/material-status/{id}", statusId), ergoOfp, materialStatusForm("RECEIVED", "PASSED"))
			.andExpect(status().isOk());
		call(post("/api/ofp/orders/{id}/release", orderId), ergoOfp, "{}")
			.andExpect(jsonPath("$.currentStatus").value("DISPATCH_READY"));
	}

	@Test
	void dispatchReadyIsGatedOnEveryMaterialInspectionPassingAndIsErgoOfpAdminOnly() throws Exception {
		long orderId = advanceToPackagingInProgress(customerA);

		// zero material-status rows: not dispatch-ready (an unchecked order has not "passed")
		call(post("/api/ofp/orders/{id}/dispatch-ready", orderId), ergoOfp, "{}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail")
				.value("All material inspections must pass before the order can be marked dispatch ready."));

		// one FAILED row: still not dispatch-ready
		String withMaterial = call(post("/api/ofp/orders/{id}/material-status", orderId), ergoOfp,
				materialStatusForm("RECEIVED", "FAILED")).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long statusId = ((Number) JsonPath.read(withMaterial, "$.materialStatus[0].id")).longValue();
		call(post("/api/ofp/orders/{id}/dispatch-ready", orderId), ergoOfp, "{}")
			.andExpect(status().isUnprocessableContent());

		// only ERGO OFP/Admin may attempt the transition, gate met or not
		Login supplierOfp = users.login(supplier, "SUPPLIER_OFP");
		call(post("/api/ofp/orders/{id}/dispatch-ready", orderId), supplierOfp, "{}")
			.andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/dispatch-ready", orderId), ergoManagement, "{}")
			.andExpect(status().isForbidden());

		// one PASSED row (the FAILED one corrected): gate now met
		call(put("/api/ofp/material-status/{id}", statusId), ergoOfp, materialStatusForm("RECEIVED", "PASSED"))
			.andExpect(status().isOk());
		call(post("/api/ofp/orders/{id}/dispatch-ready", orderId), ergoOfp, "{}")
			.andExpect(jsonPath("$.currentStatus").value("DISPATCH_READY"));

		// cannot be repeated once already Dispatch Ready
		call(post("/api/ofp/orders/{id}/dispatch-ready", orderId), ergoOfp, "{}").andExpect(status().isConflict());
	}

	@Test
	void supplierListAndDetailNeverIdentifyTheCustomer() throws Exception {
		long orderId = createStandalone(customerA);
		String customerName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?",
				String.class, customerA);
		call(put("/api/ofp/orders/{id}", orderId), ergoOfp, """
				{"customerPoNo": "CUSTPO-SECRET-1", "supplierPoNo": "SPO-7", "ownerUserId": %d}"""
			.formatted(ergoOfp.userId())).andExpect(status().isOk());
		// ERGO-typed free text naming the customer: must not reach the supplier (NPD H1 precedent)
		call(post("/api/ofp/orders/{id}/material-status", orderId), ergoOfp, """
				{"materialStatus": "RECEIVED", "inspectionStatus": "PENDING", "remarks": "for %s"}"""
			.formatted(customerName)).andExpect(status().isCreated());
		Login supplierOfp = users.login(supplier, "SUPPLIER_OFP");

		String list = call(get("/api/ofp/orders"), supplierOfp, null).andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value((int) orderId))
			.andExpect(jsonPath("$[0].supplierPoNo").value("SPO-7"))
			.andExpect(jsonPath("$[0].customer").doesNotExist())
			.andExpect(jsonPath("$[0].customerPoNo").doesNotExist())
			.andExpect(jsonPath("$[0].owner").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(list).doesNotContain(customerName).doesNotContain("CUSTPO-SECRET-1");

		String detail = call(get("/api/ofp/orders/{id}", orderId), supplierOfp, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.supplierPoNo").value("SPO-7"))
			.andExpect(jsonPath("$.materialStatus[0].inspectionStatus").value("PENDING"))
			.andExpect(jsonPath("$.materialStatus[0].remarks").doesNotExist())
			.andExpect(jsonPath("$.customer").doesNotExist())
			.andExpect(jsonPath("$.customerPoNo").doesNotExist())
			.andExpect(jsonPath("$.linkedRfqId").doesNotExist())
			.andExpect(jsonPath("$.linkedNpdProjectId").doesNotExist())
			.andExpect(jsonPath("$.owner").doesNotExist())
			.andExpect(jsonPath("$.ownerUserId").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(detail).doesNotContain(customerName).doesNotContain("CUSTPO-SECRET-1");

		// the supplier's own writes answer with the same supplier-safe shape
		String afterWrite = call(post("/api/ofp/orders/{id}/packaging-dispatch", orderId), supplierOfp,
				packagingDispatchForm("PENDING", false)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.customer").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(afterWrite).doesNotContain(customerName).doesNotContain("CUSTPO-SECRET-1");

		// ERGO keeps the full view
		call(get("/api/ofp/orders/{id}", orderId), ergoOfp, null).andExpect(jsonPath("$.customer").value(customerName))
			.andExpect(jsonPath("$.customerPoNo").value("CUSTPO-SECRET-1"))
			.andExpect(jsonPath("$.materialStatus[0].remarks").value("for " + customerName));
	}

	// ---------------------------------------------------------------- Phase 4c: shipments, dispatch onward, customer

	@Test
	void customerSeesOnlyItsOwnOrdersReadOnlyAndNeverTheSupplier() throws Exception {
		long orderId = createStandalone(customerA);
		long otherOrder = createStandalone(customerB);
		String supplierName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?",
				String.class, supplier);
		call(put("/api/ofp/orders/{id}", orderId), ergoOfp,
				"{\"customerPoNo\": \"PO-4C\", \"supplierPoNo\": \"SPO-SECRET\"}").andExpect(status().isOk());
		Login customerOfp = users.login(customerA, "CUSTOMER_OFP");

		String list = call(get("/api/ofp/orders"), customerOfp, null).andExpect(status().isOk())
			.andExpect(jsonPath("$[*].id", hasItem((int) orderId)))
			.andExpect(jsonPath("$[*].id", not(hasItem((int) otherOrder))))
			.andExpect(jsonPath("$[0].customerPoNo").value("PO-4C"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(list).doesNotContain(supplierName).doesNotContain("SPO-SECRET");

		String detail = call(get("/api/ofp/orders/{id}", orderId), customerOfp, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.actions", empty()))
			.andExpect(jsonPath("$.supplier").doesNotExist())
			.andExpect(jsonPath("$.supplierPoNo").doesNotExist())
			.andExpect(jsonPath("$.linkedRfqId").doesNotExist())
			.andExpect(jsonPath("$.linkedNpdProjectId").doesNotExist())
			.andExpect(jsonPath("$.owner").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(detail).doesNotContain(supplierName).doesNotContain("SPO-SECRET");

		// another customer's order does not exist for this one (404, not 403): scope, not a role gap
		call(get("/api/ofp/orders/{id}", otherOrder), customerOfp, null).andExpect(status().isNotFound());
	}

	@Test
	void customerHasNoWriteAccessAnywhereInOfp() throws Exception {
		long orderId = advanceToPackagingInProgress(customerA);
		call(post("/api/ofp/orders/{id}/material-status", orderId), ergoOfp, materialStatusForm("RECEIVED", "PASSED"))
			.andExpect(status().isCreated());
		call(post("/api/ofp/orders/{id}/dispatch-ready", orderId), ergoOfp, "{}").andExpect(status().isOk());
		Login customerOfp = users.login(customerA, "CUSTOMER_OFP");

		call(post("/api/ofp/orders"), customerOfp, orderForm(customerA, supplier)).andExpect(status().isForbidden());
		call(put("/api/ofp/orders/{id}", orderId), customerOfp, "{}").andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/advance", orderId), customerOfp, "{}").andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/hold", orderId), customerOfp, "{\"comment\": \"x\"}")
			.andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/release", orderId), customerOfp, "{}").andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/material-status", orderId), customerOfp, materialStatusForm("RECEIVED", "PASSED"))
			.andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/production-progress", orderId), customerOfp,
				productionProgressForm("IN_PROGRESS", null)).andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/packaging-dispatch", orderId), customerOfp,
				packagingDispatchForm("PENDING", false)).andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/shipments", orderId), customerOfp, shipmentForm("DISPATCHED"))
			.andExpect(status().isForbidden());
		// L6: customer 403 on shipment update and POD upload, even with a dummy id (role check runs first)
		call(put("/api/ofp/shipments/{id}", 999_999L), customerOfp, shipmentForm("DISPATCHED"))
			.andExpect(status().isForbidden());
		mvc.perform(multipart("/api/ofp/shipments/{id}/pod", 999_999L)
			.file(new MockMultipartFile("file", "pod.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, customerOfp.bearer()))
			.andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/dispatch", orderId), customerOfp, "{}").andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/in-transit", orderId), customerOfp, "{}").andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/delivered", orderId), customerOfp, "{}").andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/close", orderId), customerOfp, "{}").andExpect(status().isForbidden());
	}

	@Test
	void supplierReadsShipmentsOnItsOwnOrderButCannotWriteThemOrTheirPod() throws Exception {
		long orderId = advanceToDispatched(customerA);
		String withShipment = call(post("/api/ofp/orders/{id}/shipments", orderId), ergoOfp,
				shipmentForm("DISPATCHED")).andExpect(status().isCreated())
			.andExpect(jsonPath("$.shipments[0].trackingNumber").value("TRACK-1"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		long shipmentId = ((Number) JsonPath.read(withShipment, "$.shipments[0].id")).longValue();

		Login supplierOfp = users.login(supplier, "SUPPLIER_OFP");
		call(get("/api/ofp/orders/{id}", orderId), supplierOfp, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.shipments[0].id").value((int) shipmentId))
			.andExpect(jsonPath("$.shipments[0].trackingNumber").value("TRACK-1"))
			.andExpect(jsonPath("$.shipments[0].podFileName").doesNotExist())
			.andExpect(jsonPath("$.shipments[0].podDocumentId").doesNotExist())
			.andExpect(jsonPath("$.shipments[0].updatedBy").doesNotExist());

		call(post("/api/ofp/orders/{id}/shipments", orderId), supplierOfp, shipmentForm("DISPATCHED"))
			.andExpect(status().isForbidden());
		call(put("/api/ofp/shipments/{id}", shipmentId), supplierOfp, shipmentForm("IN_TRANSIT"))
			.andExpect(status().isForbidden());
		mvc.perform(multipart("/api/ofp/shipments/{id}/pod", shipmentId)
			.file(new MockMultipartFile("file", "pod.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, supplierOfp.bearer()))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/ofp/shipments/{id}/pod", shipmentId).header(HttpHeaders.AUTHORIZATION, supplierOfp.bearer()))
			.andExpect(status().isForbidden());
	}

	@Test
	void dispatchReChecksTheInspectionGateAndIsErgoOfpAdminOnly() throws Exception {
		long orderId = advanceToPackagingInProgress(customerA);
		String withMaterial = call(post("/api/ofp/orders/{id}/material-status", orderId), ergoOfp,
				materialStatusForm("RECEIVED", "PASSED")).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long statusId = ((Number) JsonPath.read(withMaterial, "$.materialStatus[0].id")).longValue();
		call(post("/api/ofp/orders/{id}/dispatch-ready", orderId), ergoOfp, "{}")
			.andExpect(jsonPath("$.currentStatus").value("DISPATCH_READY"));

		// the inspection that made Dispatch Ready reachable no longer holds: Dispatch re-checks the same gate
		call(put("/api/ofp/material-status/{id}", statusId), ergoOfp, materialStatusForm("RECEIVED", "FAILED"))
			.andExpect(status().isOk());
		call(post("/api/ofp/orders/{id}/dispatch", orderId), ergoOfp, "{}")
			.andExpect(status().isUnprocessableContent());

		// only ERGO OFP/Admin may attempt it
		Login supplierOfp = users.login(supplier, "SUPPLIER_OFP");
		call(post("/api/ofp/orders/{id}/dispatch", orderId), supplierOfp, "{}").andExpect(status().isForbidden());
		call(post("/api/ofp/orders/{id}/dispatch", orderId), ergoManagement, "{}").andExpect(status().isForbidden());

		// fix it and dispatch succeeds
		call(put("/api/ofp/material-status/{id}", statusId), ergoOfp, materialStatusForm("RECEIVED", "PASSED"))
			.andExpect(status().isOk());
		call(post("/api/ofp/orders/{id}/dispatch", orderId), ergoOfp, "{}")
			.andExpect(jsonPath("$.currentStatus").value("DISPATCHED"));

		// cannot be repeated once already dispatched
		call(post("/api/ofp/orders/{id}/dispatch", orderId), ergoOfp, "{}").andExpect(status().isConflict());
	}

	@Test
	void fullHappyPathFromDispatchReadyToClosedRequiresPodBeforeDelivered() throws Exception {
		long orderId = advanceToPackagingInProgress(customerA);
		call(post("/api/ofp/orders/{id}/material-status", orderId), ergoOfp, materialStatusForm("RECEIVED", "PASSED"))
			.andExpect(status().isCreated());
		call(post("/api/ofp/orders/{id}/dispatch-ready", orderId), ergoOfp, "{}")
			.andExpect(jsonPath("$.currentStatus").value("DISPATCH_READY"));

		call(post("/api/ofp/orders/{id}/dispatch", orderId), ergoOfp, "{}")
			.andExpect(jsonPath("$.currentStatus").value("DISPATCHED"))
			.andExpect(jsonPath("$.actualDispatchDate").value(LocalDate.now().toString())); // L5 fix

		// Close Order is refused before Delivered, ERGO-only
		call(post("/api/ofp/orders/{id}/close", orderId), ergoOfp, "{}").andExpect(status().isConflict());
		call(post("/api/ofp/orders/{id}/close", orderId), ergoManagement, "{}").andExpect(status().isForbidden());

		String withShipment = call(post("/api/ofp/orders/{id}/shipments", orderId), ergoOfp,
				shipmentForm("DISPATCHED")).andExpect(status().isCreated())
			.andExpect(jsonPath("$.shipments[0].status").value("DISPATCHED"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		long shipmentId = ((Number) JsonPath.read(withShipment, "$.shipments[0].id")).longValue();

		call(post("/api/ofp/orders/{id}/in-transit", orderId), ergoOfp, "{}")
			.andExpect(jsonPath("$.currentStatus").value("IN_TRANSIT"));

		// Delivered is refused without a POD on file
		call(post("/api/ofp/orders/{id}/delivered", orderId), ergoOfp, "{}")
			.andExpect(status().isUnprocessableContent());

		mvc.perform(multipart("/api/ofp/shipments/{id}/pod", shipmentId)
			.file(new MockMultipartFile("file", "pod.pdf", "application/pdf",
					"pod content".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, ergoOfp.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.shipments[0].podFileName").value("pod.pdf"));

		call(post("/api/ofp/orders/{id}/delivered", orderId), ergoOfp, "{}")
			.andExpect(jsonPath("$.currentStatus").value("DELIVERED"));

		call(post("/api/ofp/orders/{id}/close", orderId), ergoOfp, "{}")
			.andExpect(jsonPath("$.currentStatus").value("CLOSED"));
	}

	@Test
	void podFileNameNeverLeaksToSupplierOrCustomerButErgoAndTheCustomerCanDownloadIt() throws Exception {
		long orderId = advanceToDispatched(customerA);
		String customerName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?",
				String.class, customerA);
		String distinctiveName = "POD_" + customerName.replace(' ', '_') + "_signed.pdf";
		String withShipment = call(post("/api/ofp/orders/{id}/shipments", orderId), ergoOfp,
				shipmentForm("DISPATCHED")).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long shipmentId = ((Number) JsonPath.read(withShipment, "$.shipments[0].id")).longValue();

		mvc.perform(multipart("/api/ofp/shipments/{id}/pod", shipmentId)
			.file(new MockMultipartFile("file", distinctiveName, "application/pdf",
					"pod content".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, ergoOfp.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.shipments[0].podFileName").value(distinctiveName))
			.andExpect(jsonPath("$.shipments[0].podDocumentId").exists());

		// H1: the supplier never sees the POD reference at all, in list or detail
		Login supplierOfp = users.login(supplier, "SUPPLIER_OFP");
		String supplierDetail = call(get("/api/ofp/orders/{id}", orderId), supplierOfp, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.shipments[0].podFileName").doesNotExist())
			.andExpect(jsonPath("$.shipments[0].podDocumentId").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(supplierDetail).doesNotContain(distinctiveName).doesNotContain(customerName);

		// the customer also never sees the raw POD reference, but may download the file via its own endpoint
		Login customerOfp = users.login(customerA, "CUSTOMER_OFP");
		String customerDetail = call(get("/api/ofp/orders/{id}", orderId), customerOfp, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.shipments[0].podFileName").doesNotExist())
			.andExpect(jsonPath("$.shipments[0].podDocumentId").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(customerDetail).doesNotContain(distinctiveName);

		mvc.perform(get("/api/ofp/shipments/{id}/pod", shipmentId).header(HttpHeaders.AUTHORIZATION, customerOfp.bearer()))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(distinctiveName)));

		// ERGO's own view keeps the full POD reference
		call(get("/api/ofp/orders/{id}", orderId), ergoOfp, null)
			.andExpect(jsonPath("$.shipments[0].podFileName").value(distinctiveName));

		// the supplier is refused a download outright (role layer, H1/L5)
		mvc.perform(get("/api/ofp/shipments/{id}/pod", shipmentId).header(HttpHeaders.AUTHORIZATION, supplierOfp.bearer()))
			.andExpect(status().isForbidden());
	}

	@Test
	void shipmentCannotBeMarkedFurtherAlongThanTheOrderItself() throws Exception {
		long orderId = advanceToDispatched(customerA); // order is DISPATCHED (rank 0)
		call(post("/api/ofp/orders/{id}/shipments", orderId), ergoOfp, shipmentForm("IN_TRANSIT"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail")
				.value("A shipment cannot be marked further along than the order itself (order is currently DISPATCHED)"));
		call(post("/api/ofp/orders/{id}/shipments", orderId), ergoOfp, shipmentForm("DELIVERED"))
			.andExpect(status().isUnprocessableContent());
		// at the order's own reached status, or behind it, is fine
		call(post("/api/ofp/orders/{id}/shipments", orderId), ergoOfp, shipmentForm("DISPATCHED"))
			.andExpect(status().isCreated());
	}

	@Test
	void receiptConfirmationOnlySettableOnceTheShipmentItselfIsDelivered() throws Exception {
		long orderId = advanceToDispatched(customerA);
		String withShipment = call(post("/api/ofp/orders/{id}/shipments", orderId), ergoOfp,
				shipmentForm("DISPATCHED")).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long shipmentId = ((Number) JsonPath.read(withShipment, "$.shipments[0].id")).longValue();

		// L2: refused while the shipment itself hasn't reached Delivered yet
		call(put("/api/ofp/shipments/{id}", shipmentId), ergoOfp, """
				{"transporterName": "Acme Logistics", "trackingNumber": "TRACK-1", "status": "DISPATCHED",
				 "receiptConfirmedByCustomerFlag": true}""").andExpect(status().isUnprocessableContent());

		// advance the order itself to Delivered (M2: a shipment can't be marked further along than its order)
		mvc.perform(multipart("/api/ofp/shipments/{id}/pod", shipmentId)
			.file(new MockMultipartFile("file", "pod.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, ergoOfp.bearer())).andExpect(status().isOk());
		call(post("/api/ofp/orders/{id}/in-transit", orderId), ergoOfp, "{}").andExpect(status().isOk());
		call(post("/api/ofp/orders/{id}/delivered", orderId), ergoOfp, "{}").andExpect(status().isOk());

		call(put("/api/ofp/shipments/{id}", shipmentId), ergoOfp, """
				{"transporterName": "Acme Logistics", "trackingNumber": "TRACK-1", "status": "DELIVERED",
				 "receiptConfirmedByCustomerFlag": true}""")
			.andExpect(jsonPath("$.shipments[0].receiptConfirmedByCustomerFlag").value(true))
			.andExpect(jsonPath("$.shipments[0].receiptConfirmedAt").exists());
	}

	@Test
	void customerDetailNeverLeaksInternalFreeTextOrOwner() throws Exception {
		long orderId = advanceToPackagingInProgress(customerA);
		String distinctive = "BLOCKED_ON_SUPPLIER_XYZ_TOOLING_ISSUE";
		call(post("/api/ofp/orders/{id}/production-progress", orderId), ergoOfp,
				productionProgressForm("BLOCKED", distinctive)).andExpect(status().isCreated());
		call(post("/api/ofp/orders/{id}/material-status", orderId), ergoOfp, """
				{"materialStatus": "RECEIVED", "inspectionStatus": "PENDING", "remarks": "internal note xyz"}""")
			.andExpect(status().isCreated());

		Login customerOfp = users.login(customerA, "CUSTOMER_OFP");
		String detail = call(get("/api/ofp/orders/{id}", orderId), customerOfp, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.productionProgress[0].blockerReason").doesNotExist())
			.andExpect(jsonPath("$.productionProgress[0].updatedBy").doesNotExist())
			.andExpect(jsonPath("$.materialStatus[0].remarks").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(detail).doesNotContain(distinctive).doesNotContain("internal note xyz");
	}

	@Test
	void materialProductionAndPackagingWritesAreFrozenOnceDispatched() throws Exception {
		long orderId = advanceToDispatched(customerA);
		call(post("/api/ofp/orders/{id}/material-status", orderId), ergoOfp, materialStatusForm("RECEIVED", "PASSED"))
			.andExpect(status().isConflict());
		call(post("/api/ofp/orders/{id}/production-progress", orderId), ergoOfp,
				productionProgressForm("IN_PROGRESS", null)).andExpect(status().isConflict());
		call(post("/api/ofp/orders/{id}/packaging-dispatch", orderId), ergoOfp, packagingDispatchForm("PACKED", true))
			.andExpect(status().isConflict());
		Login supplierOfp = users.login(supplier, "SUPPLIER_OFP");
		call(post("/api/ofp/orders/{id}/production-progress", orderId), supplierOfp,
				productionProgressForm("IN_PROGRESS", null)).andExpect(status().isConflict());
		call(post("/api/ofp/orders/{id}/packaging-dispatch", orderId), supplierOfp,
				packagingDispatchForm("PACKED", true)).andExpect(status().isConflict());
	}

	@Test
	void closedOrderFreezesShipmentsHoldAndEdit() throws Exception {
		long orderId = advanceToDispatched(customerA);
		String withShipment = call(post("/api/ofp/orders/{id}/shipments", orderId), ergoOfp,
				shipmentForm("DISPATCHED")).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long shipmentId = ((Number) JsonPath.read(withShipment, "$.shipments[0].id")).longValue();
		mvc.perform(multipart("/api/ofp/shipments/{id}/pod", shipmentId)
			.file(new MockMultipartFile("file", "pod.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, ergoOfp.bearer())).andExpect(status().isOk());
		call(post("/api/ofp/orders/{id}/in-transit", orderId), ergoOfp, "{}").andExpect(status().isOk());
		call(post("/api/ofp/orders/{id}/delivered", orderId), ergoOfp, "{}").andExpect(status().isOk());
		call(post("/api/ofp/orders/{id}/close", orderId), ergoOfp, "{}")
			.andExpect(jsonPath("$.currentStatus").value("CLOSED"));

		call(put("/api/ofp/shipments/{id}", shipmentId), ergoOfp, shipmentForm("DELIVERED"))
			.andExpect(status().isConflict());
		mvc.perform(multipart("/api/ofp/shipments/{id}/pod", shipmentId)
			.file(new MockMultipartFile("file", "pod2.pdf", "application/pdf", "y".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, ergoOfp.bearer()))
			.andExpect(status().isConflict());
		call(post("/api/ofp/orders/{id}/hold", orderId), ergoOfp, "{\"comment\": \"x\"}")
			.andExpect(status().isConflict());
		call(put("/api/ofp/orders/{id}", orderId), ergoOfp, "{\"customerPoNo\": \"X\"}")
			.andExpect(status().isConflict());
	}

	@Test
	void statusTransitionsAndShipmentWritesAreAudited() throws Exception {
		long orderId = advanceToDispatched(customerA);
		assertAuditedStatus(orderId, "DISPATCHED");

		String withShipment = call(post("/api/ofp/orders/{id}/shipments", orderId), ergoOfp,
				shipmentForm("DISPATCHED")).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long shipmentId = ((Number) JsonPath.read(withShipment, "$.shipments[0].id")).longValue();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM audit_logs WHERE entity_name = 'Shipment' AND record_id = ?
				AND event_type = 'CREATE'""", Integer.class, shipmentId)).isEqualTo(1);

		call(put("/api/ofp/shipments/{id}", shipmentId), ergoOfp, """
				{"transporterName": "Acme Logistics", "trackingNumber": "TRACK-2", "status": "DISPATCHED",
				 "receiptConfirmedByCustomerFlag": false}""").andExpect(status().isOk());
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM audit_logs WHERE entity_name = 'Shipment' AND record_id = ?
				AND event_type = 'UPDATE' AND field_name = 'trackingNumber'""", Integer.class, shipmentId))
			.isEqualTo(1);

		mvc.perform(multipart("/api/ofp/shipments/{id}/pod", shipmentId)
			.file(new MockMultipartFile("file", "pod.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, ergoOfp.bearer())).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM audit_logs WHERE entity_name = 'Shipment' AND record_id = ?
				AND field_name = 'podDocumentId'""", Integer.class, shipmentId)).isEqualTo(1);

		call(post("/api/ofp/orders/{id}/in-transit", orderId), ergoOfp, "{}").andExpect(status().isOk());
		assertAuditedStatus(orderId, "IN_TRANSIT");
		call(post("/api/ofp/orders/{id}/delivered", orderId), ergoOfp, "{}").andExpect(status().isOk());
		assertAuditedStatus(orderId, "DELIVERED");
		call(post("/api/ofp/orders/{id}/close", orderId), ergoOfp, "{}").andExpect(status().isOk());
		assertAuditedStatus(orderId, "CLOSED");
	}

	private void assertAuditedStatus(long orderId, String newValue) {
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM audit_logs WHERE entity_name = 'Order' AND record_id = ? AND field_name = 'currentStatus'
				AND new_value = ?""", Integer.class, orderId, newValue)).isGreaterThanOrEqualTo(1);
	}

	// ---------------------------------------------------------------- helpers

	private long advanceToPackagingInProgress(long customerCompanyId) throws Exception {
		long orderId = createStandalone(customerCompanyId);
		for (int i = 0; i < 5; i++) {
			call(post("/api/ofp/orders/{id}/advance", orderId), ergoOfp, "{}").andExpect(status().isOk());
		}
		return orderId;
	}

	/** Advances an order all the way to Dispatched (M2 fix: shipment writes require at least this status), one
	 * PASSED material-status row along the way so Dispatch Ready's own gate is met. */
	private long advanceToDispatched(long customerCompanyId) throws Exception {
		long orderId = advanceToPackagingInProgress(customerCompanyId);
		call(post("/api/ofp/orders/{id}/material-status", orderId), ergoOfp, materialStatusForm("RECEIVED", "PASSED"))
			.andExpect(status().isCreated());
		call(post("/api/ofp/orders/{id}/dispatch-ready", orderId), ergoOfp, "{}").andExpect(status().isOk());
		call(post("/api/ofp/orders/{id}/dispatch", orderId), ergoOfp, "{}").andExpect(status().isOk());
		return orderId;
	}

	private long createStandalone(long customerCompanyId) throws Exception {
		return JsonPath
			.<Number>read(call(post("/api/ofp/orders"), ergoOfp, orderForm(customerCompanyId, supplier))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString(), "$.id")
			.longValue();
	}

	private long handover(long projectId) throws Exception {
		return JsonPath
			.<Number>read(
					call(post("/api/ofp/orders/handover/{id}", projectId), ergoOfp,
							"{\"orderedQuantity\": 1000, \"dueDate\": \"%s\"}".formatted(LocalDate.now().plusDays(45)))
						.andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString(),
					"$.id")
			.longValue();
	}

	private long convert(long rfqId) throws Exception {
		return JsonPath
			.<Number>read(call(post("/api/npd/convert/{id}", rfqId), ergoNpd, "{}").andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.id")
			.longValue();
	}

	/** Closes every remaining NPD stage (no mandatory activities were added, so each Close Stage succeeds
	 * immediately) from PLANNING to APPROVED_FOR_PRODUCTION_HANDOVER, the stage the security review's M1 fix now
	 * requires before {@code NpdApi#handover} will hand a project to OFP. */
	private void advanceToHandoverReady(long projectId) throws Exception {
		for (int i = 0; i < 7; i++) {
			call(post("/api/npd/{id}/close-stage", projectId), ergoNpd, "{}").andExpect(status().isOk());
		}
	}

	/** Full RFQ->quotation flow up to CUSTOMER_APPROVED with one supplier's quote approved (SRS §14.2-14.4),
	 * matching NpdTests's own helper exactly (needed here to reach a convertible, then handover-able, project). */
	private long approvedRfq(Login buyer, String price) throws Exception {
		long id = JsonPath
			.<Number>read(call(post("/api/rfqs"), buyer, """
					{"partNumber": "PN-OFP", "description": "Bracket", "materialSpec": "EN8",
					 "targetQuoteDate": "%s", "currencyId": %d, "targetProductionPiecePrice": 99.5,
					 "processSteps": [{"stepName": "Casting"}]}""".formatted(LocalDate.now().plusDays(10), inr))
				.andReturn().getResponse().getContentAsString(), "$.id")
			.longValue();
		call(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
		call(post("/api/rfqs/{id}/validate", id), ergoRfq, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/quotes", id), ergoRfq,
				"{\"supplierIds\": [%d], \"responseDueDate\": \"%s\"}".formatted(supplier, LocalDate.now().plusDays(7)))
			.andExpect(status().isOk());
		long invitationId = invitationOf(id, seller);
		call(put("/api/quotes/{id}", invitationId), seller, """
				{"validityDate": "%s", "currencyId": %d, "productionPiecePrice": %s, "toolingCostTotal": 50000,
				 "rawMaterialCost": 40, "processingCost": 20, "overheadCost": 8, "packagingCost": 2, "profit": 6,
				 "moq": 500, "productionLeadTimeDays": 45}""".formatted(LocalDate.now().plusDays(60), inr, price))
			.andExpect(status().isOk());
		call(post("/api/quotes/{id}/submit", invitationId), seller, null).andExpect(status().isOk());
		call(post("/api/quotes/{id}/decision", invitationId), ergoRfq, "{\"decision\": \"SELECT_FOR_CUSTOMER\"}")
			.andExpect(status().isOk());
		call(post("/api/rfqs/{id}/customer-quotes", id), ergoRfq,
				"{\"quoteId\": %d, \"type\": \"SUPPLIER_QUOTE\", \"remarks\": \"ok\"}".formatted(invitationId))
			.andExpect(status().isOk());
		long sent = JsonPath
			.<Number>read(
					call(get("/api/rfqs/{id}/customer-quotes", id), buyer, null).andReturn().getResponse()
						.getContentAsString(),
					"$.quotes[0].id")
			.longValue();
		call(post("/api/customer-quotes/{id}/respond", sent), buyer, "{\"response\": \"APPROVED\"}")
			.andExpect(status().isOk());
		return id;
	}

	private long invitationOf(long rfqId, Login supplierLogin) throws Exception {
		String number = JsonPath.read(call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andReturn().getResponse()
			.getContentAsString(), "$.number");
		List<Number> ids = JsonPath.read(call(get("/api/quotes"), supplierLogin, null).andReturn().getResponse()
			.getContentAsString(), "$[?(@.rfqNumber == '" + number + "')].id");
		return ids.get(0).longValue();
	}

	private static String orderForm(long customerCompanyId, long supplierCompanyId) {
		return """
				{"customerCompanyId": %d, "supplierCompanyId": %d, "partNumber": "PN-STD", "revision": "A",
				 "orderedQuantity": 1000, "dueDate": "%s"}"""
			.formatted(customerCompanyId, supplierCompanyId, LocalDate.now().plusDays(30));
	}

	private static String materialStatusForm(String materialStatus) {
		return materialStatusForm(materialStatus, "PENDING");
	}

	private static String materialStatusForm(String materialStatus, String inspectionStatus) {
		return """
				{"materialStatus": "%s", "inspectionStatus": "%s"}""".formatted(materialStatus, inspectionStatus);
	}

	private static String shipmentForm(String status) {
		return """
				{"transporterName": "Acme Logistics", "trackingNumber": "TRACK-1", "status": "%s",
				 "receiptConfirmedByCustomerFlag": false}""".formatted(status);
	}

	private static String packagingDispatchForm(String packagingStatus, boolean dispatchReadyFlag) {
		return """
				{"packagingStatus": "%s", "dispatchReadyFlag": %s}""".formatted(packagingStatus, dispatchReadyFlag);
	}

	private static String productionProgressForm(String status, String blockerReason) {
		return """
				{"processStepName": "Casting", "status": "%s", "blockerReason": %s}"""
			.formatted(status, blockerReason == null ? "null" : "\"" + blockerReason + "\"");
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

}
