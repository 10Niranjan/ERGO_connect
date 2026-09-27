package com.ergo.connect.ofp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * status / production progress tracking.
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
	void handoverCopiesTheNpdProjectsOwnDataAndCannotBeRepeated() throws Exception {
		long rfqId = approvedRfq(buyerA, "90");
		long projectId = convert(rfqId);

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

	// ---------------------------------------------------------------- helpers

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
		return """
				{"materialStatus": "%s", "inspectionStatus": "PENDING"}""".formatted(materialStatus);
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
