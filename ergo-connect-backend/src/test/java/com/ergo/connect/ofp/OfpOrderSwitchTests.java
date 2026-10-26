package com.ergo.connect.ofp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ergo.connect.TestUsers.Login;

/**
 * The OFP module switch (PERMISSIONS.md §9) against the order header and workflow, action by action. Chunk 1: View-only.
 * Every change is refused whoever asks and nothing moves; every read stays; back at O everything works again.
 */
class OfpOrderSwitchTests extends OfpOrderSupport {

	private static final String ORDERS = "/api/ofp/orders/{id}";

	/** Orders at the stages the actions need: one to edit/advance, one held, one ready to dispatch, one dispatched. */
	record Fx(Order working, Order held, Order ready, Order dispatched, int orders) {
	}

	private Fx fixture() throws Exception {
		Order working = working();
		Order held = fresh();
		call(post(ORDERS + "/hold", held.id()), ergoOfp, "{\"comment\": \"Material doubt\"}").andExpect(status().isOk());
		Order ready = dispatchReady();
		Order dispatched = dispatched();
		return new Fx(working, held, ready, dispatched, count("SELECT count(*) FROM orders"));
	}

	private int act(String action, long order, Login by, String body) {
		return send(post("/api/ofp/orders/{id}/" + action, order), by, body);
	}

	private void everyChangeIsRefused(Fx f) {
		String comment = "{\"comment\": \"go\"}";
		String header = "{\"customerPoNo\": \"X\", \"dueDate\": \"%s\"}".formatted(LocalDate.now().plusDays(40));
		assertThat(send(post("/api/ofp/orders"), ergoOfp, """
				{"customerCompanyId": %d, "supplierCompanyId": %d, "partNumber": "PN-NEW", "orderedQuantity": 5}"""
			.formatted(customer, supplierX))).as("create").isEqualTo(403);
		assertThat(send(put(ORDERS, f.working.id()), ergoOfp, header)).as("update").isEqualTo(403);
		for (Login by : List.of(ergoOfp, ergoMgmt)) {
			assertThat(act("advance", f.working.id(), by, comment)).as("advance by " + by.userId()).isEqualTo(403);
		}
		assertThat(act("hold", f.working.id(), ergoOfp, comment)).as("hold by OFP").isEqualTo(403);
		assertThat(act("hold", f.working.id(), ergoQuality, comment)).as("hold by Quality").isEqualTo(403);
		assertThat(act("release", f.held.id(), ergoOfp, comment)).as("release by OFP").isEqualTo(403);
		assertThat(act("release", f.held.id(), ergoQuality, comment)).as("release by Quality").isEqualTo(403);
		assertThat(act("dispatch-ready", f.working.id(), ergoOfp, comment)).as("dispatch ready").isEqualTo(403);
		assertThat(act("dispatch", f.ready.id(), ergoOfp, comment)).as("dispatch").isEqualTo(403);
		assertThat(act("in-transit", f.dispatched.id(), ergoOfp, comment)).as("in transit").isEqualTo(403);
		assertThat(act("delivered", f.dispatched.id(), ergoOfp, comment)).as("delivered").isEqualTo(403);
		assertThat(act("close", f.dispatched.id(), ergoOfp, comment)).as("close").isEqualTo(403);
	}

	private void nothingMoved(Fx f) {
		assertThat(orderStatus(f.working.id())).isEqualTo("MATERIAL_RECEIVED");
		assertThat(orderStatus(f.held.id())).isEqualTo("ON_HOLD");
		assertThat(orderStatus(f.ready.id())).isEqualTo("DISPATCH_READY");
		assertThat(orderStatus(f.dispatched.id())).isEqualTo("DISPATCHED");
		assertThat(count("SELECT count(*) FROM orders")).isEqualTo(f.orders);
		assertThat(count("SELECT count(*) FROM orders WHERE order_id = ? AND customer_po_no = 'X'", f.working.id())).isZero();
	}

	@Test
	void aReadOnlyOfpModuleRefusesEveryOrderChangeAndMovesNothing() throws Exception {
		Fx f = fixture();
		long auditBefore = count("SELECT count(*) FROM audit_logs WHERE module_name = 'ofp'");
		cap("OFP", "V");
		everyChangeIsRefused(f);
		nothingMoved(f);
		assertThat(count("SELECT count(*) FROM audit_logs WHERE module_name = 'ofp'")).as("no audit rows for refused writes")
			.isEqualTo(auditBefore);
	}

	@Test
	void aReadOnlyOfpModuleStillShowsEveryOrderWithoutButtons() throws Exception {
		Fx f = fixture();
		cap("OFP", "V");
		for (Login reader : List.of(ergoOfp, ergoMgmt, ergoQuality)) {
			call(get(ORDERS, f.working.id()), reader, null).andExpect(status().isOk()).andExpect(jsonPath("$.actions", hasSize(0)));
			call(get("/api/ofp/orders"), reader, null).andExpect(status().isOk());
		}
		call(get(ORDERS + "/history", f.working.id()), ergoOfp, null).andExpect(status().isOk());
		call(get(ORDERS, f.working.id()), ofpCust, null).andExpect(status().isOk()).andExpect(jsonPath("$.actions", hasSize(0)));
		call(get(ORDERS, f.working.id()), ofpSup, null).andExpect(status().isOk()).andExpect(jsonPath("$.actions", hasSize(0)));
		call(get("/api/ofp/orders"), ofpCust, null).andExpect(status().isOk());
		call(get("/api/ofp/orders"), ofpSup, null).andExpect(status().isOk());
	}

	@Test
	void backAtOAllTheRefusedActionsWorkAgain() throws Exception {
		Fx f = fixture();
		cap("OFP", "V");
		everyChangeIsRefused(f);
		cap("OFP", "O");
		String comment = "{\"comment\": \"go\"}";
		assertThat(act("advance", f.working.id(), ergoOfp, comment)).isEqualTo(200);
		assertThat(act("release", f.held.id(), ergoQuality, comment)).isEqualTo(200);
		assertThat(act("dispatch", f.ready.id(), ergoOfp, comment)).isEqualTo(200);
		assertThat(act("in-transit", f.dispatched.id(), ergoOfp, comment)).isEqualTo(200);
		assertThat(send(put(ORDERS, f.working.id()), ergoOfp, "{\"customerPoNo\": \"X\"}")).isEqualTo(200);
	}

}
