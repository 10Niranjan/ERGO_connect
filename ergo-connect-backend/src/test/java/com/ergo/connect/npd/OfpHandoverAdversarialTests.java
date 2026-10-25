package com.ergo.connect.npd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ergo.connect.TestNpd;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Adversarial tests of the OFP order born from a finished production project (flow doc step 11): who may hand over,
 * what a bad request does to the project, the one-time rule under a race, module switches, who sees the order, and the
 * order workflow's refusals. Does not cover shipments (OfpTests) or the full switch matrix (chunk 2c).
 */
class OfpHandoverAdversarialTests extends ProductionPoSupport {

	private static final String HANDOVER = "/api/ofp/orders/handover/{id}";

	private long finishedProject() throws Exception {
		long project = TestNpd.productionProject(mvc, teamA, sampleA());
		TestNpd.completeProject(mvc, teamA, project);
		return project;
	}

	private boolean handedOver(long project) {
		return Boolean.TRUE.equals(jdbc.queryForObject("SELECT handover_ready_flag FROM npd_projects WHERE npd_project_id = ?",
				Boolean.class, project));
	}

	private int orders(long project) {
		return count("SELECT count(*) FROM orders WHERE linked_npd_project_id = ?", project);
	}

	private static final String QTY = "{\"orderedQuantity\": 1000}";

	private long order(long project) throws Exception {
		String json = text(post(HANDOVER, project), ergoOfp, QTY);
		return JsonPath.<Number>read(json, "$.id").longValue();
	}

	private int act(String action, long order, Login by, String comment) {
		return code("POST", "/api/ofp/orders/{id}/" + action, by, comment(comment), order);
	}

	@Test
	void onlyErgoOfpHandsAProjectOver() throws Exception {
		long project = finishedProject();
		for (Login other : List.of(npdA, qualA, ofpA, custKamA, buyer, npdSup, qualSup, ofpSup, sellerX, ergoQuality, ergoNpd, ergoRfq)) {
			ok(403, code("POST", HANDOVER, other, QTY, project), "handover by " + other.userId());
		}
		assertThat(orders(project)).isZero();
		assertThat(handedOver(project)).isFalse();
		ok(404, code("POST", HANDOVER, ergoOfp, QTY, 987654321L), "unknown project");
	}

	@Test
	void aBadFormNeverConsumesTheOneTimeHandover() throws Exception {
		long project = finishedProject();
		for (String bad : List.of("{}", "{\"orderedQuantity\": 0}", "{\"orderedQuantity\": -5}", "{\"orderedQuantity\": null}",
				"{\"orderedQuantity\": \"abc\"}", "{\"orderedQuantity\": 1.234}", "{\"orderedQuantity\": 123456789012345}",
				"{\"orderedQuantity\": 10, \"customerPoNo\": \"" + "x".repeat(101) + "\"}",
				"{\"orderedQuantity\": 10, \"dueDate\": \"not-a-date\"}")) {
			ok(400, code("POST", HANDOVER, ergoOfp, bad, project), bad);
		}
		// an owner who is not ERGO staff is refused as well
		ok(422, code("POST", HANDOVER, ergoOfp, "{\"orderedQuantity\": 10, \"ownerUserId\": %d}".formatted(ofpSup.userId()), project),
				"supplier as owner");
		assertThat(orders(project)).isZero();
		assertThat(handedOver(project)).isFalse();
		ok(201, code("POST", HANDOVER, ergoOfp, QTY, project), "the real handover still works");
		assertThat(handedOver(project)).isTrue();
	}

	@Test
	void theOrderTakesItsDefaultsFromTheProductionPoAndCanBeOverridden() throws Exception {
		long project = finishedProject();
		long po = jdbc.queryForObject("SELECT production_po_id FROM npd_projects WHERE npd_project_id = ?", Long.class, project);
		String poNumber = jdbc.queryForObject("SELECT customer_po_number FROM production_pos WHERE production_po_id = ?", String.class, po);
		String due = jdbc.queryForObject("SELECT required_date::text FROM production_pos WHERE production_po_id = ?", String.class, po);
		long id = order(project);
		call(get("/api/ofp/orders/{id}", id), ergoOfp, null)
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.customerPoNo").value(poNumber))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.dueDate").value(due))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.currentStatus").value("ORDER_CREATED"));
		assertThat(count("SELECT count(*) FROM audit_logs WHERE module_name = 'ofp' AND entity_name = 'Order' AND record_id = ?", id))
			.isPositive();
	}

	@Test
	void twoHandoversAtOnceMakeExactlyOneOrder() throws Exception {
		long project = finishedProject();
		List<Integer> results = race(() -> code("POST", HANDOVER, ergoOfp, QTY, project),
				() -> code("POST", HANDOVER, ergoOfp, QTY, project));
		assertThat(results).containsExactlyInAnyOrder(201, 409);
		assertThat(orders(project)).isOne();
	}

	@Test
	void aHandoverIsRefusedWholeWhileNpdOrOfpIsViewOnly() throws Exception {
		long project = finishedProject();
		for (String module : List.of("NPD", "OFP")) {
			cap(module, "V");
			int status = code("POST", HANDOVER, ergoOfp, QTY, project);
			assertThat(status).as(module + " view-only").isBetween(400, 499);
			assertThat(orders(project)).as(module + " left no order").isZero();
			assertThat(handedOver(project)).as(module + " left the project untouched").isFalse();
			cap(module, "O");
		}
		ok(201, code("POST", HANDOVER, ergoOfp, QTY, project), "back to normal");
	}

	@Test
	void theOrderIsSeenOnlyByThoseWithARightToIt() throws Exception {
		long project = finishedProject();
		long id = order(project);
		for (Login outsider : List.of(ofpB, buyerB, sellerY, sellerZ)) {
			assertThat(code("GET", "/api/ofp/orders/{id}", outsider, null, id)).as("outsider " + outsider.userId()).isBetween(403, 404);
		}
		ok(200, code("GET", "/api/ofp/orders/{id}", ofpA, null, id), "its own customer's OFP");
		ok(200, code("GET", "/api/ofp/orders/{id}", ofpSup, null, id), "its own supplier's OFP");
		ok(403, code("GET", "/api/ofp/orders/{id}/history", ofpA, null, id), "customer audit trail");
		ok(403, code("GET", "/api/ofp/orders/{id}/history", ofpSup, null, id), "supplier audit trail");
		// the two sides never learn of each other
		String seenByCustomer = text(get("/api/ofp/orders/{id}", id), ofpA, null);
		String seenBySupplier = text(get("/api/ofp/orders/{id}", id), ofpSup, null);
		assertThat(seenByCustomer).doesNotContain("\"supplierCompanyId\":" + supplierX);
		assertThat(seenBySupplier).doesNotContain("\"customerCompanyId\":" + customer);
	}

	@Test
	void theOrderWorkflowRefusesWhatIsOutOfOrder() throws Exception {
		long id = order(finishedProject());
		// nothing skips ahead of the next step
		ok(409, act("dispatch-ready", id, ergoOfp, ""), "dispatch ready from the start");
		ok(409, act("dispatch", id, ergoOfp, ""), "dispatch from the start");
		ok(409, act("in-transit", id, ergoOfp, ""), "in transit from the start");
		ok(409, act("delivered", id, ergoOfp, ""), "delivered from the start");
		ok(409, act("close", id, ergoOfp, ""), "close from the start");
		ok(409, act("release", id, ergoOfp, "not held"), "release an order that is not held");
		// only the ERGO owning role advances
		for (Login other : List.of(ofpA, ofpSup, sellerX, npdA, ergoQuality, kamA)) {
			ok(403, act("advance", id, other, ""), "advance by " + other.userId());
		}
		// a hold needs a reason and cannot be repeated
		assertThat(act("hold", id, ergoOfp, "")).isBetween(400, 499);
		assertThat(act("hold", id, ergoOfp, "   ")).isBetween(400, 499);
		ok(403, act("hold", id, ofpA, "customer hold"), "customer holds");
		ok(403, act("hold", id, ofpSup, "supplier hold"), "supplier holds");
		ok(200, act("hold", id, ergoOfp, "Material doubt"), "hold");
		ok(409, act("hold", id, ergoOfp, "again"), "second hold");
		ok(409, act("advance", id, ergoOfp, ""), "advance while held");
		ok(200, act("release", id, ergoOfp, "Cleared"), "release");
		assertThat(jdbc.queryForObject("SELECT current_status FROM orders WHERE order_id = ?", String.class, id))
			.isEqualTo("ORDER_CREATED");
		// the walk stops at packaging, and the dispatch gate needs a passed inspection
		for (int step = 0; step < 5; step++) {
			ok(200, act("advance", id, ergoOfp, ""), "advance " + step);
		}
		ok(409, act("advance", id, ergoOfp, ""), "advance past packaging");
		ok(422, act("dispatch-ready", id, ergoOfp, ""), "dispatch ready with no inspection on record");
		assertThat(jdbc.queryForObject("SELECT current_status FROM orders WHERE order_id = ?", String.class, id))
			.isEqualTo("PACKAGING_IN_PROGRESS");
	}

	@Test
	void aSupplierWritesOnlyItsOwnOrdersProgressAndNothingElse() throws Exception {
		long id = order(finishedProject());
		String progress = "{\"processStepName\": \"Cutting\", \"completedQuantity\": 10, \"status\": \"IN_PROGRESS\"}";
		for (Login stranger : List.of(sellerY, sellerZ, ofpA, ofpB)) {
			assertThat(code("POST", "/api/ofp/orders/{id}/production-progress", stranger, progress, id)).as("stranger " + stranger.userId())
				.isBetween(403, 404);
		}
		// the owning supplier cannot touch what is ERGO's
		ok(403, code("POST", "/api/ofp/orders/{id}/material-status", ofpSup, "{\"materialStatus\": \"RECEIVED\", \"inspectionStatus\": \"PASSED\"}", id), "material status by supplier");
		ok(403, code("PUT", "/api/ofp/orders/{id}", ofpSup, "{\"customerPoNo\": \"X\"}", id), "header edit by supplier");
		ok(403, code("POST", "/api/ofp/orders/{id}/shipments", ofpSup, "{\"trackingNumber\": \"T1\", \"status\": \"DISPATCHED\", \"receiptConfirmedByCustomerFlag\": false}", id), "shipment by supplier");
		assertThat(count("SELECT count(*) FROM order_production_progress WHERE order_id = ?", id)).isZero();
		ok(201, code("POST", "/api/ofp/orders/{id}/production-progress", ofpSup, progress, id), "the assigned supplier");
		assertThat(count("SELECT count(*) FROM order_production_progress WHERE order_id = ?", id)).isOne();
	}

}
