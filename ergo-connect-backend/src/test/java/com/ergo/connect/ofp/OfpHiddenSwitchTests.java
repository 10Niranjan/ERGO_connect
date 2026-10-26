package com.ergo.connect.ofp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * The OFP module switch at NV (PERMISSIONS.md §9) against every audience and every path that can reach an order:
 * the module's own endpoints, dashboards, notifications, comments and the documents library. Nothing is shown, listed,
 * counted or notified, every change is refused, and the data is kept: back at O everything is there again.
 * (OFP has no overdue-reminder scan - {@code OverdueReminders} reads NPD, Quotation and Quality only - so there is
 * no scheduled path to test here.)
 */
class OfpHiddenSwitchTests extends OfpOrderSupport {

	private static final String ORDER = "/api/ofp/orders/{id}";

	private Login ergoAdmin;

	private Login watcher;

	private Order o;

	private long pod;

	@BeforeEach
	void hiddenSetUp() throws Exception {
		ergoAdmin = users.login(ergoCo, "ERGO_ADMIN");
		watcher = users.login(ergoCo, "ERGO_OFP"); // exists before the events: the acting user is not notified of their own steps
		o = dispatched();
		pod = jdbc.queryForObject("SELECT pod_document_id FROM shipments WHERE shipment_id = ?", Long.class, o.shipment());
		call(post("/api/comments"), ergoOfp, commentOn(o.id(), "Truck leaves tomorrow")).andExpect(status().isCreated());
	}

	private static String commentOn(long order, String text) {
		return """
				{"linkedModule": "ofp", "linkedRecordId": %d, "commentType": null, "text": "%s",
				 "parentCommentId": null, "query": false, "mentionedUserIds": []}""".formatted(order, text);
	}

	private List<Login> everyAudience() {
		return List.of(ergoOfp, ergoMgmt, ergoQuality, ergoAdmin, ofpCust, ofpSup);
	}

	private void closed(int got, String what) {
		assertThat(got).as(what).isBetween(403, 404);
	}

	private int comments() {
		return count("SELECT count(*) FROM comments WHERE linked_module = 'ofp'");
	}

	@Test
	void aHiddenOfpModuleRefusesEveryOrderEndpointToEveryAudienceAndMovesNothing() throws Exception {
		int orders = count("SELECT count(*) FROM orders");
		long audit = count("SELECT count(*) FROM audit_logs WHERE module_name = 'ofp'");
		cap("OFP", "NV");
		String comment = "{\"comment\": \"go\"}";
		for (Login by : everyAudience()) {
			String who = "by " + by.userId();
			closed(send(get("/api/ofp/orders"), by, null), "list " + who);
			closed(send(get(ORDER, o.id()), by, null), "get " + who);
			closed(send(get(ORDER + "/history", o.id()), by, null), "history " + who);
			closed(send(post("/api/ofp/orders"), by, """
					{"customerCompanyId": %d, "supplierCompanyId": %d, "partNumber": "PN-NV", "orderedQuantity": 5}"""
				.formatted(customer, supplierX)), "create " + who);
			closed(send(put(ORDER, o.id()), by, "{\"customerPoNo\": \"X\"}"), "update " + who);
			for (String action : List.of("advance", "hold", "release", "dispatch-ready", "dispatch", "in-transit", "delivered", "close")) {
				closed(send(post(ORDER + "/" + action, o.id()), by, comment), action + " " + who);
			}
			closed(send(post("/api/ofp/orders/{id}/material-status", o.id()), by,
					"{\"materialStatus\": \"ORDERED\", \"inspectionStatus\": \"PENDING\"}"), "material " + who);
			closed(send(put("/api/ofp/material-status/{id}", o.material()), by,
					"{\"materialStatus\": \"RECEIVED\", \"inspectionStatus\": \"PENDING\"}"), "material update " + who);
			closed(send(post("/api/ofp/orders/{id}/production-progress", o.id()), by,
					"{\"processStepName\": \"Casting\", \"status\": \"IN_PROGRESS\"}"), "progress " + who);
			closed(send(put("/api/ofp/production-progress/{id}", o.progress()), by,
					"{\"processStepName\": \"Casting\", \"status\": \"DONE\"}"), "progress update " + who);
			closed(send(post("/api/ofp/orders/{id}/packaging-dispatch", o.id()), by,
					"{\"packagingStatus\": \"PACKED\", \"dispatchReadyFlag\": false}"), "packaging " + who);
			closed(send(put("/api/ofp/packaging-dispatch/{id}", o.packaging()), by,
					"{\"packagingStatus\": \"PACKED\", \"dispatchReadyFlag\": true}"), "packaging update " + who);
			closed(send(post("/api/ofp/orders/{id}/shipments", o.id()), by,
					"{\"transporterName\": \"T\", \"trackingNumber\": \"TRACK-NV\", \"status\": \"DISPATCHED\", \"receiptConfirmedByCustomerFlag\": false}"),
					"shipment create " + who);
			closed(send(put("/api/ofp/shipments/{id}", o.shipment()), by,
					"{\"transporterName\": \"T\", \"trackingNumber\": \"TRACK-NV\", \"status\": \"DISPATCHED\", \"receiptConfirmedByCustomerFlag\": false}"),
					"shipment update " + who);
			closed(upload("/api/ofp/shipments/{id}/pod", o.shipment(), by, "nv-pod.pdf").andReturn().getResponse().getStatus(), "POD upload " + who);
			closed(send(get("/api/ofp/shipments/{id}/pod", o.shipment()), by, null), "POD download " + who);
		}
		assertThat(orderStatus(o.id())).isEqualTo("DISPATCHED");
		assertThat(count("SELECT count(*) FROM orders")).isEqualTo(orders);
		assertThat(count("SELECT count(*) FROM shipments WHERE order_id = ?", o.id())).isOne();
		assertThat(count("SELECT count(*) FROM shipments WHERE tracking_number = 'TRACK-NV'")).isZero();
		assertThat(count("SELECT count(*) FROM order_material_status WHERE order_id = ?", o.id())).isOne();
		assertThat(count("SELECT count(*) FROM order_production_progress WHERE order_id = ?", o.id())).isOne();
		assertThat(count("SELECT count(*) FROM order_packaging_dispatch WHERE order_id = ?", o.id())).isOne();
		assertThat(count("SELECT count(*) FROM audit_logs WHERE module_name = 'ofp'")).as("no audit rows for refused calls")
			.isEqualTo((int) audit);
	}

	@Test
	void aHiddenOfpModuleEmptiesTheOrderPartOfEveryDashboard() throws Exception {
		for (Login reader : List.of(ergoOfp, ofpCust, ofpSup)) {
			call(get("/api/dashboard"), reader, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.orderItems", not(hasSize(0))));
		}
		call(get("/api/dashboard"), ergoMgmt, null).andExpect(jsonPath("$.supplierPerformance", not(hasSize(0))));
		cap("OFP", "NV");
		for (Login reader : List.of(ergoOfp, ergoQuality, ofpCust, ofpSup)) {
			call(get("/api/dashboard"), reader, null).andExpect(status().isOk()).andExpect(jsonPath("$.orderItems", hasSize(0)));
		}
		for (Login reader : List.of(ergoMgmt, ergoAdmin)) {
			call(get("/api/dashboard"), reader, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.ordersAtRisk", hasSize(0)))
				.andExpect(jsonPath("$.supplierPerformance", hasSize(0)));
		}
		cap("OFP", "O");
		call(get("/api/dashboard"), ofpCust, null).andExpect(jsonPath("$.orderItems", not(hasSize(0))));
	}

	@Test
	void aHiddenOfpModuleNotifiesNobodyAndHidesWhatWasAlreadySent() throws Exception {
		List<Login> recipients = List.of(watcher, ofpCust);
		String mine = "$.items[?(@.linkedModule=='ofp' || @.linkedModule=='document')]";
		for (Login reader : recipients) {
			call(get("/api/notifications?size=100"), reader, null).andExpect(jsonPath(mine, not(hasSize(0))));
		}
		long unread = JsonPath.<Number>read(text(get("/api/notifications/unread-count"), ofpCust, null), "$.count").longValue();
		cap("OFP", "NV");
		for (Login reader : recipients) {
			call(get("/api/notifications?size=100"), reader, null).andExpect(jsonPath(mine, hasSize(0)));
		}
		assertThat(JsonPath.<Number>read(text(get("/api/notifications/unread-count"), ofpCust, null), "$.count").longValue())
			.as("hidden notifications are not counted").isLessThan(unread);
		int before = count("SELECT count(*) FROM notifications");
		// a new event while hidden: nothing is written for anyone (the write is refused, so nothing even tries)
		send(post(ORDER + "/in-transit", o.id()), ergoOfp, "{\"comment\": \"go\"}");
		assertThat(count("SELECT count(*) FROM notifications")).isEqualTo(before);
		cap("OFP", "O");
		call(get("/api/notifications?size=100"), ofpCust, null).andExpect(jsonPath(mine, not(hasSize(0))));
	}

	@Test
	void aHiddenOfpModuleClosesTheCommentsAndTheDocumentsOfItsRecords() throws Exception {
		int comments = comments();
		call(get("/api/documents?size=100"), ergoOfp, null).andExpect(jsonPath("$.items[?(@.linkedModule=='OFP_SHIPMENT')]", not(hasSize(0))));
		cap("OFP", "NV");
		for (Login by : everyAudience()) {
			String who = "by " + by.userId();
			closed(send(get("/api/comments?linkedModule=ofp&linkedRecordId=" + o.id()), by, null), "thread " + who);
			closed(send(post("/api/comments"), by, commentOn(o.id(), "hello")), "comment " + who);
			closed(send(get("/api/documents/{id}", pod), by, null), "document " + who);
			closed(send(get("/api/documents/{id}/content", pod), by, null), "document content " + who);
			call(get("/api/documents?size=100"), by, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.linkedModule=='OFP_SHIPMENT')]", hasSize(0)));
			call(get("/api/comments/inbox"), by, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.linkedModule=='ofp')]", hasSize(0)));
		}
		assertThat(comments()).as("no comment written while hidden").isEqualTo(comments);
		cap("OFP", "O");
		call(get("/api/comments?linkedModule=ofp&linkedRecordId=" + o.id()), ergoOfp, null).andExpect(status().isOk())
			.andExpect(jsonPath("$", not(hasSize(0))));
		call(get("/api/documents/{id}", pod), ergoOfp, null).andExpect(status().isOk());
	}

	@Test
	void backAtOEverythingHiddenIsThereUnchanged() throws Exception {
		cap("OFP", "NV");
		closed(send(get(ORDER, o.id()), ergoOfp, null), "hidden");
		cap("OFP", "O");
		for (Login reader : List.of(ergoOfp, ergoMgmt, ergoQuality, ofpCust, ofpSup)) {
			call(get(ORDER, o.id()), reader, null).andExpect(status().isOk());
		}
		call(get(ORDER + "/history", o.id()), ergoOfp, null).andExpect(status().isOk());
		call(get("/api/ofp/shipments/{id}/pod", o.shipment()), ergoOfp, null).andExpect(status().isOk());
		assertThat(orderStatus(o.id())).isEqualTo("DISPATCHED");
		assertThat(send(post(ORDER + "/in-transit", o.id()), ergoOfp, "{\"comment\": \"go\"}")).isEqualTo(200);
	}

}
