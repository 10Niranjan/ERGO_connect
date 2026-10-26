package com.ergo.connect.ofp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;

import com.ergo.connect.AdversarialSupport;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Fixture of the OFP order-side switch tests: one customer / supplier pair (customer A, supplier X), their OFP users,
 * ERGO OFP and Quality, and orders built through the real endpoints at each stage a switch can catch an order in.
 * Orders are standalone (the handover path is covered by OfpHandoverAdversarialTests).
 */
abstract class OfpOrderSupport extends AdversarialSupport {

	protected Login ergoOfp;

	protected Login ergoQuality;

	protected Login ofpCust;

	protected Login ofpSup;

	/** An order with the rows a stage can have; ids are 0 where the order has none yet. */
	record Order(long id, long material, long progress, long packaging, long shipment) {
	}

	@BeforeEach
	void ofpSetUp() throws Exception {
		ergoOfp = users.login(ergoCo, "ERGO_OFP");
		ergoQuality = users.login(ergoCo, "ERGO_QUALITY");
		ofpCust = users.login(customer, "CUSTOMER_OFP");
		ofpSup = users.login(supplierX, "SUPPLIER_OFP");
	}

	/** ORDER_CREATED, no rows. */
	protected Order fresh() throws Exception {
		String json = text(post("/api/ofp/orders"), ergoOfp, """
				{"customerCompanyId": %d, "supplierCompanyId": %d, "partNumber": "PN-SW", "revision": "A",
				 "orderedQuantity": 1000, "dueDate": "%s"}""".formatted(customer, supplierX, LocalDate.now().plusDays(30)));
		return new Order(JsonPath.<Number>read(json, "$.id").longValue(), 0, 0, 0, 0);
	}

	/** MATERIAL_RECEIVED with a material-status, a production-progress and a packaging-dispatch row. */
	protected Order working() throws Exception {
		long id = fresh().id();
		step(id, "advance");
		step(id, "advance");
		call(post("/api/ofp/orders/{id}/material-status", id), ergoOfp, "{\"materialStatus\": \"ORDERED\", \"inspectionStatus\": \"PENDING\"}")
			.andExpect(status().isCreated());
		call(post("/api/ofp/orders/{id}/production-progress", id), ergoOfp, "{\"processStepName\": \"Casting\", \"status\": \"IN_PROGRESS\"}")
			.andExpect(status().isCreated());
		call(post("/api/ofp/orders/{id}/packaging-dispatch", id), ergoOfp, "{\"packagingStatus\": \"PACKED\", \"dispatchReadyFlag\": false}")
			.andExpect(status().isCreated());
		return new Order(id, last("order_material_status", "order_material_status_id", id),
				last("order_production_progress", "production_progress_id", id),
				last("order_packaging_dispatch", "packaging_dispatch_id", id), 0);
	}

	/** PACKAGING_IN_PROGRESS with the rows of {@link #working()} and a passed Quality inspection: one step short of the gate. */
	protected Order packaged() throws Exception {
		Order o = working();
		for (int i = 0; i < 3; i++) {
			step(o.id(), "advance");
		}
		String inspection = text(post("/api/quality/inspections"), ergoQuality, """
				{"linkedModule": "OFP", "linkedRecordId": %d, "partNumber": "PN-SW", "customerVisible": false}""".formatted(o.id()));
		long inspectionId = JsonPath.<Number>read(inspection, "$.id").longValue();
		call(post("/api/quality/inspections/{id}/submit", inspectionId), ergoQuality, """
				{"partNumber": "PN-SW", "quantityChecked": 10, "quantityRejected": 0, "inspectionDate": "%s",
				 "customerVisible": false}""".formatted(LocalDate.now())).andExpect(status().isOk());
		call(post("/api/quality/inspections/{id}/pass", inspectionId), ergoMgmt, "{}").andExpect(status().isOk());
		return o;
	}

	/** DISPATCH_READY: {@link #packaged()} and the gate taken. */
	protected Order dispatchReady() throws Exception {
		Order o = packaged();
		step(o.id(), "dispatch-ready");
		return o;
	}

	/** DISPATCHED with one shipment that has a POD file. */
	protected Order dispatched() throws Exception {
		Order o = dispatchReady();
		step(o.id(), "dispatch");
		call(post("/api/ofp/orders/{id}/shipments", o.id()), ergoOfp, """
				{"transporterName": "Acme Logistics", "trackingNumber": "TRACK-1", "status": "DISPATCHED",
				 "receiptConfirmedByCustomerFlag": false}""").andExpect(status().isCreated());
		long shipment = last("shipments", "shipment_id", o.id());
		upload("/api/ofp/shipments/{id}/pod", shipment, ergoOfp, "pod.pdf").andExpect(status().isOk());
		return new Order(o.id(), o.material(), o.progress(), o.packaging(), shipment);
	}

	protected void step(long order, String action) throws Exception {
		call(post("/api/ofp/orders/{id}/" + action, order), ergoOfp, "{}").andExpect(status().isOk());
	}

	protected String orderStatus(long order) {
		return jdbc.queryForObject("SELECT current_status FROM orders WHERE order_id = ?", String.class, order);
	}

	private long last(String table, String key, long order) {
		return jdbc.queryForObject("SELECT max(" + key + ") FROM " + table + " WHERE order_id = ?", Long.class, order);
	}

}
