package com.ergo.connect.npd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ergo.connect.TestNpd;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Shipments and the POD on an order born from a finished production project (flow doc step 11): the order is driven
 * through the real endpoints from the handover to Dispatched, then the shipment rules, the POD's audience, the record
 * scope (customer, supplier, KAM, NPD) and the notifications are checked against a production-born order. The same
 * rules for standalone orders are covered by OfpTests and OfpShipmentSwitchTests.
 */
class ProductionShipmentTests extends ProductionPoSupport {

	private static final String SHIPMENT = """
			{"transporterName": "Acme Logistics", "trackingNumber": "TRACK-PB", "status": "DISPATCHED",
			 "receiptConfirmedByCustomerFlag": false}""";

	private static final String OTHER = """
			{"transporterName": "Other Carrier", "trackingNumber": "TRACK-X", "status": "DISPATCHED",
			 "receiptConfirmedByCustomerFlag": false}""";

	/** A production-born order and where it stands. */
	record Born(long project, long order, String part) {
	}

	/** Handed over, material received, rows recorded, three more steps: PACKAGING_IN_PROGRESS, gate not yet taken. */
	private Born packaging() throws Exception {
		long project = TestNpd.productionProject(mvc, teamA, sampleA());
		TestNpd.completeProject(mvc, teamA, project);
		String json = text(post("/api/ofp/orders/handover/{id}", project), ergoOfp, "{\"orderedQuantity\": 1000}");
		long order = JsonPath.<Number>read(json, "$.id").longValue();
		String part = jdbc.queryForObject("SELECT part_number FROM orders WHERE order_id = ?", String.class, order);
		step(order, "advance");
		step(order, "advance");
		call(post("/api/ofp/orders/{id}/material-status", order), ergoOfp, "{\"materialStatus\": \"ORDERED\", \"inspectionStatus\": \"PENDING\"}")
			.andExpect(status().isCreated());
		call(post("/api/ofp/orders/{id}/production-progress", order), ergoOfp, "{\"processStepName\": \"Casting\", \"status\": \"IN_PROGRESS\"}")
			.andExpect(status().isCreated());
		call(post("/api/ofp/orders/{id}/packaging-dispatch", order), ergoOfp, "{\"packagingStatus\": \"PACKED\", \"dispatchReadyFlag\": false}")
			.andExpect(status().isCreated());
		for (int i = 0; i < 3; i++) {
			step(order, "advance");
		}
		return new Born(project, order, part);
	}

	private void passInspection(Born o) throws Exception {
		String inspection = text(post("/api/quality/inspections"), ergoQuality, """
				{"linkedModule": "OFP", "linkedRecordId": %d, "partNumber": "%s", "customerVisible": false}""".formatted(o.order(), o.part()));
		long id = JsonPath.<Number>read(inspection, "$.id").longValue();
		call(post("/api/quality/inspections/{id}/submit", id), ergoQuality, """
				{"partNumber": "%s", "quantityChecked": 10, "quantityRejected": 0, "inspectionDate": "%s",
				 "customerVisible": false}""".formatted(o.part(), LocalDate.now())).andExpect(status().isOk());
		call(post("/api/quality/inspections/{id}/pass", id), ergoMgmt, "{}").andExpect(status().isOk());
	}

	/** DISPATCHED, no shipment yet. */
	private Born dispatched() throws Exception {
		Born o = packaging();
		passInspection(o);
		step(o.order(), "dispatch-ready");
		step(o.order(), "dispatch");
		return o;
	}

	private long shipmentOf(Born o) throws Exception {
		call(post("/api/ofp/orders/{id}/shipments", o.order()), ergoOfp, SHIPMENT).andExpect(status().isCreated());
		return jdbc.queryForObject("SELECT max(shipment_id) FROM shipments WHERE order_id = ?", Long.class, o.order());
	}

	private void step(long order, String action) throws Exception {
		call(post("/api/ofp/orders/{id}/" + action, order), ergoOfp, "{}").andExpect(status().isOk());
	}

	private String statusOf(Born o) {
		return jdbc.queryForObject("SELECT current_status FROM orders WHERE order_id = ?", String.class, o.order());
	}

	private int shipments(Born o) {
		return count("SELECT count(*) FROM shipments WHERE order_id = ?", o.order());
	}

	private boolean hasPod(long shipment) {
		return jdbc.queryForObject("SELECT pod_document_id FROM shipments WHERE shipment_id = ?", Long.class, shipment) != null;
	}

	@Test
	void aProductionBornOrderRunsFromHandoverToClosedWithShipmentAndPod() throws Exception {
		Born o = dispatched();
		assertThat(statusOf(o)).isEqualTo("DISPATCHED");
		// no POD yet: the order cannot be delivered, even once in transit
		long shipment = shipmentOf(o);
		step(o.order(), "in-transit");
		ok(422, code("POST", "/api/ofp/orders/{id}/delivered", ergoOfp, "{}", o.order()), "delivered without a POD");
		assertThat(statusOf(o)).isEqualTo("IN_TRANSIT");
		ok(404, code("GET", "/api/ofp/shipments/{id}/pod", ergoOfp, null, shipment), "no POD on file yet");

		upload("/api/ofp/shipments/{id}/pod", shipment, ergoOfp, "pod.pdf").andExpect(status().isOk());
		assertThat(hasPod(shipment)).isTrue();
		step(o.order(), "delivered");
		step(o.order(), "close");
		assertThat(statusOf(o)).isEqualTo("CLOSED");
		ok(200, code("GET", "/api/ofp/shipments/{id}/pod", ergoOfp, null, shipment), "POD after close");
		// the order stays tied to the project it came from
		assertThat(jdbc.queryForObject("SELECT linked_npd_project_id FROM orders WHERE order_id = ?", Long.class, o.order()))
			.isEqualTo(o.project());
	}

	@Test
	void aShipmentCannotBeCreatedBeforeTheOrderIsDispatchedOrClaimFurtherThanTheOrder() throws Exception {
		Born o = packaging();
		ok(409, code("POST", "/api/ofp/orders/{id}/shipments", ergoOfp, SHIPMENT, o.order()), "shipment while packaging");
		assertThat(shipments(o)).isZero();
		passInspection(o);
		step(o.order(), "dispatch-ready");
		ok(409, code("POST", "/api/ofp/orders/{id}/shipments", ergoOfp, SHIPMENT, o.order()), "shipment while dispatch ready");
		step(o.order(), "dispatch");
		ok(422, code("POST", "/api/ofp/orders/{id}/shipments", ergoOfp,
				SHIPMENT.replace("DISPATCHED", "DELIVERED"), o.order()), "delivered shipment on a dispatched order");
		ok(422, code("POST", "/api/ofp/orders/{id}/shipments", ergoOfp,
				SHIPMENT.replace("DISPATCHED", "CLOSED"), o.order()), "closed is not a shipment status");
		assertThat(shipments(o)).isZero();
		ok(201, code("POST", "/api/ofp/orders/{id}/shipments", ergoOfp, SHIPMENT, o.order()), "a fair shipment");
	}

	@Test
	void onlyErgoOfpWritesShipmentsAndPodsAndNothingIsSavedForTheOthers() throws Exception {
		Born o = dispatched();
		long shipment = shipmentOf(o);
		for (Login other : List.of(ofpA, ofpA2, npdA, qualA, custKamA, ofpB, ofpSup, npdSup, qualSup, sellerX, kamA, kamB, kamNone,
				ergoNpd, ergoQuality, ergoRfq)) {
			String who = "by " + other.userId();
			ok(403, code("POST", "/api/ofp/orders/{id}/shipments", other, OTHER, o.order()), "create " + who);
			ok(403, code("PUT", "/api/ofp/shipments/{id}", other, OTHER, shipment), "update " + who);
			ok(403, upload("/api/ofp/shipments/{id}/pod", shipment, other, "evil.pdf").andReturn().getResponse().getStatus(),
					"POD " + who);
		}
		assertThat(shipments(o)).isOne();
		assertThat(hasPod(shipment)).isFalse();
		assertThat(jdbc.queryForObject("SELECT tracking_number FROM shipments WHERE shipment_id = ?", String.class, shipment))
			.isEqualTo("TRACK-PB");
	}

	@Test
	void theOrderAndItsShipmentsAreInvisibleToEveryOtherCompanyAndUnassignedKam() throws Exception {
		Born o = dispatched();
		long shipment = shipmentOf(o);
		upload("/api/ofp/shipments/{id}/pod", shipment, ergoOfp, "pod.pdf").andExpect(status().isOk());
		Login supplierOther = users.login(supplierY, "SUPPLIER_OFP");
		for (Login stranger : List.of(ofpB, supplierOther, kamB, kamNone)) {
			String who = "stranger " + stranger.userId();
			ok(404, code("GET", "/api/ofp/orders/{id}", stranger, null, o.order()), "order " + who);
			// the role check comes first: a stranger who cannot write shipments is told 403, same as for a shipment that does not exist
			ok(403, code("PUT", "/api/ofp/shipments/{id}", stranger, OTHER, shipment), "update " + who);
			ok(403, upload("/api/ofp/shipments/{id}/pod", shipment, stranger, "evil.pdf").andReturn().getResponse().getStatus(),
					"upload " + who);
		}
		for (Login stranger : List.of(ofpB, kamB, kamNone)) {
			ok(stranger == ofpB ? 403 : 404, code("GET", "/api/ofp/shipments/{id}/pod", stranger, null, shipment),
					"POD of stranger " + stranger.userId());
		}
		// the strangers' lists do not carry the order either
		String orderNumber = jdbc.queryForObject("SELECT order_number FROM orders WHERE order_id = ?", String.class, o.order());
		for (Login stranger : List.of(ofpB, supplierOther, kamB, kamNone)) {
			ok(200, code("GET", "/api/ofp/orders", stranger, null), "list for " + stranger.userId());
			assertThat(text(get("/api/ofp/orders"), stranger, null)).as("list of " + stranger.userId()).doesNotContain(orderNumber);
		}
		assertThat(text(get("/api/ofp/orders"), ofpA, null)).as("the own customer's list has it").contains(orderNumber);
		assertThat(shipments(o)).isOne();
	}

	@Test
	void theOwnCustomerAndSupplierSeeTrackingButNeverThePodOrTheOtherSide() throws Exception {
		Born o = dispatched();
		long shipment = shipmentOf(o);
		upload("/api/ofp/shipments/{id}/pod", shipment, ergoOfp, "POD_Acme_Motors_signed.pdf").andExpect(status().isOk());
		String customerSees = text(get("/api/ofp/orders/{id}", o.order()), ofpA, null);
		String supplierSees = text(get("/api/ofp/orders/{id}", o.order()), ofpSup, null);
		assertThat(customerSees).contains("TRACK-PB");
		assertThat(supplierSees).contains("TRACK-PB");
		for (String seen : List.of(customerSees, supplierSees)) {
			assertThat(seen).doesNotContain("POD_Acme").doesNotContain("podDocument").doesNotContain("podFileName");
		}
		// two-way anonymity holds on a production-born order, which carries the project link
		String customerName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?", String.class, customer);
		String supplierName = jdbc.queryForObject("SELECT company_name FROM companies WHERE company_id = ?", String.class, supplierX);
		String customerPo = jdbc.queryForObject("SELECT customer_po_no FROM orders WHERE order_id = ?", String.class, o.order());
		assertThat(customerSees).doesNotContain("linkedNpdProjectId").doesNotContain(supplierName).doesNotContain("\"supplier\"")
			.doesNotContain("supplierPoNo");
		assertThat(supplierSees).doesNotContain("linkedNpdProjectId").doesNotContain(customerName).doesNotContain("\"customer\"")
			.doesNotContain("customerPoNo").doesNotContain(customerPo);
		assertThat(customerSees).as("the customer's own PO is theirs to see").contains(customerPo);
		for (Login external : List.of(ofpA, ofpA2, ofpSup)) {
			ok(403, code("GET", "/api/ofp/shipments/{id}/pod", external, null, shipment), "POD for " + external.userId());
			assertThat(text(get("/api/ofp/orders/{id}", o.order()), external, null)).contains("\"actions\":[]");
		}
	}

	@Test
	void ergoReadsThePodWithinItsScopeAndKamOnlyForItsOwnCustomer() throws Exception {
		Born o = dispatched();
		long shipment = shipmentOf(o);
		upload("/api/ofp/shipments/{id}/pod", shipment, ergoOfp, "pod.pdf").andExpect(status().isOk());
		for (Login ergo : List.of(ergoOfp, ergoMgmt)) {
			ok(200, code("GET", "/api/ofp/shipments/{id}/pod", ergo, null, shipment), "POD for " + ergo.userId());
		}
		// ERGO NPD sees a production-born order (it has a project) but never writes it
		ok(200, code("GET", "/api/ofp/orders/{id}", ergoNpd, null, o.order()), "NPD sees the order");
		ok(200, code("GET", "/api/ofp/orders/{id}", kamA, null, o.order()), "assigned KAM sees the order");
		ok(404, code("GET", "/api/ofp/orders/{id}", kamB, null, o.order()), "other KAM");
		ok(200, code("GET", "/api/ofp/shipments/{id}/pod", kamA, null, shipment), "assigned KAM reads the POD");
		ok(200, code("GET", "/api/ofp/shipments/{id}/pod", ergoNpd, null, shipment), "NPD reads the POD of its own project's order");
	}

	@Test
	void aStandaloneOrderStaysHiddenFromErgoNpdWhileTheProductionBornOneIsNot() throws Exception {
		Born o = dispatched();
		String json = text(post("/api/ofp/orders"), ergoOfp, """
				{"customerCompanyId": %d, "supplierCompanyId": %d, "partNumber": "PN-LONE", "revision": "A",
				 "orderedQuantity": 10, "dueDate": "%s"}""".formatted(customer, supplierX, LocalDate.now().plusDays(30)));
		long lone = JsonPath.<Number>read(json, "$.id").longValue();
		ok(404, code("GET", "/api/ofp/orders/{id}", ergoNpd, null, lone), "standalone order for ERGO NPD");
		ok(200, code("GET", "/api/ofp/orders/{id}", ergoNpd, null, o.order()), "production-born order for ERGO NPD");
	}

	@Test
	void receiptCanOnlyBeConfirmedOnADeliveredShipmentAndOnlyErgoRecordsIt() throws Exception {
		Born o = dispatched();
		long shipment = shipmentOf(o);
		String confirmed = SHIPMENT.replace("\"receiptConfirmedByCustomerFlag\": false", "\"receiptConfirmedByCustomerFlag\": true");
		ok(422, code("PUT", "/api/ofp/shipments/{id}", ergoOfp, confirmed, shipment), "receipt on a dispatched shipment");
		ok(403, code("PUT", "/api/ofp/shipments/{id}", ofpA, confirmed, shipment), "customer confirms receipt");
		assertThat(jdbc.queryForObject("SELECT receipt_confirmed_by_customer_flag FROM shipments WHERE shipment_id = ?", Boolean.class,
				shipment)).isFalse();
		step(o.order(), "in-transit");
		upload("/api/ofp/shipments/{id}/pod", shipment, ergoOfp, "pod.pdf").andExpect(status().isOk());
		step(o.order(), "delivered");
		ok(200, code("PUT", "/api/ofp/shipments/{id}", ergoOfp,
				confirmed.replace("DISPATCHED", "DELIVERED"), shipment), "receipt on a delivered shipment");
		assertThat(jdbc.queryForObject("SELECT receipt_confirmed_at FROM shipments WHERE shipment_id = ?", java.sql.Timestamp.class,
				shipment)).isNotNull();
	}

	@Test
	void theCustomerHearsDeliveryButNeverThePodUploadAndTheWritesAreAudited() throws Exception {
		Born o = dispatched();
		long shipment = shipmentOf(o);
		step(o.order(), "in-transit");
		Login colleague = users.login(ergoCo, "ERGO_OFP");
		upload("/api/ofp/shipments/{id}/pod", shipment, ergoOfp, "pod.pdf").andExpect(status().isOk());
		assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND event_kind = 'DOCUMENT_UPLOADED'",
				colleague.userId())).as("an ERGO OFP colleague is told").isPositive();
		for (Login external : List.of(ofpA, ofpA2, ofpSup, npdA, custKamA)) {
			assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND event_kind = 'DOCUMENT_UPLOADED'",
					external.userId())).as("POD upload news for " + external.userId()).isZero();
		}
		step(o.order(), "delivered");
		assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND event_kind = 'DELIVERY_CONFIRMED'",
				ofpA.userId())).as("delivery news for the customer's OFP desk").isPositive();
		assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND event_kind = 'DELIVERY_CONFIRMED'",
				ofpB.userId())).as("other customer").isZero();
		assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND event_kind = 'DELIVERY_CONFIRMED'",
				ofpSup.userId())).as("supplier").isZero();
		assertThat(count("SELECT count(*) FROM audit_logs WHERE module_name = 'ofp' AND entity_name = 'Shipment' AND record_id = ?",
				shipment)).as("shipment audit rows").isPositive();
		assertThat(count("SELECT count(*) FROM audit_logs WHERE module_name = 'ofp' AND entity_name = 'Order' AND record_id = ?",
				o.order())).as("order audit rows").isPositive();
	}

	@Test
	void anUnknownShipmentIsNotFoundForEveryone() throws Exception {
		// writers get a 404; everybody else a 403 that is the same whether or not the shipment exists
		for (Login user : List.of(ergoOfp, ofpA, ofpSup)) {
			int expected = user == ergoOfp ? 404 : 403;
			ok(expected, code("PUT", "/api/ofp/shipments/{id}", user, OTHER, 987654321L), "update " + user.userId());
			ok(expected, upload("/api/ofp/shipments/{id}/pod", 987654321L, user, "x.pdf").andReturn().getResponse().getStatus(),
					"upload " + user.userId());
		}
		ok(404, code("GET", "/api/ofp/shipments/{id}/pod", ergoOfp, null, 987654321L), "download");
		ok(404, code("POST", "/api/ofp/orders/{id}/shipments", ergoOfp, OTHER, 987654321L), "create");
	}

}
