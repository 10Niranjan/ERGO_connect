package com.ergo.connect.ofp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;

/**
 * The neighbouring Quality switch against the OFP steps that read Quality's dispatch gate (dispatch-ready, dispatch and
 * release back to dispatch-ready). Hidden, the gate cannot be evaluated and fails closed with a 409 that changes
 * nothing; View-only it is still readable, so the steps work; steps that do not depend on the gate are untouched.
 * (Documents has no switch of its own - POD files follow OFP, covered by OfpShipmentSwitchTests and OfpHiddenSwitchTests.)
 */
class OfpQualityGateSwitchTests extends OfpOrderSupport {

	private static final String BODY = "{\"comment\": \"because\"}";

	private int act(long order, String action) {
		return send(post("/api/ofp/orders/{id}/" + action, order), ergoOfp, BODY);
	}

	@Test
	void aHiddenQualityModuleStopsDispatchReadyAndNothingChanges() throws Exception {
		Order o = packaged();
		cap("QUALITY", "NV");
		assertThat(act(o.id(), "dispatch-ready")).isEqualTo(409);
		assertThat(orderStatus(o.id())).isEqualTo("PACKAGING_IN_PROGRESS");
		cap("QUALITY", "O");
		assertThat(act(o.id(), "dispatch-ready")).as("back at O the same step works").isEqualTo(200);
		assertThat(orderStatus(o.id())).isEqualTo("DISPATCH_READY");
	}

	@Test
	void aHiddenQualityModuleStopsDispatchAndNothingChanges() throws Exception {
		Order o = dispatchReady();
		cap("QUALITY", "NV");
		assertThat(act(o.id(), "dispatch")).isEqualTo(409);
		assertThat(orderStatus(o.id())).isEqualTo("DISPATCH_READY");
		assertThat(jdbc.queryForObject("SELECT actual_dispatch_date FROM orders WHERE order_id = ?", java.sql.Date.class, o.id()))
			.as("no dispatch date stamped").isNull();
		cap("QUALITY", "O");
		assertThat(act(o.id(), "dispatch")).isEqualTo(200);
		assertThat(orderStatus(o.id())).isEqualTo("DISPATCHED");
	}

	@Test
	void aHiddenQualityModuleKeepsAnOrderHeldFromDispatchReadyOnHold() throws Exception {
		Order o = dispatchReady();
		assertThat(act(o.id(), "hold")).isEqualTo(200);
		cap("QUALITY", "NV");
		assertThat(act(o.id(), "release")).isEqualTo(409);
		assertThat(orderStatus(o.id())).isEqualTo("ON_HOLD");
		cap("QUALITY", "O");
		assertThat(act(o.id(), "release")).isEqualTo(200);
		assertThat(orderStatus(o.id())).isEqualTo("DISPATCH_READY");
	}

	@Test
	void stepsThatDoNotReadTheGateIgnoreAHiddenQualityModule() throws Exception {
		Order o = working(); // MATERIAL_RECEIVED
		cap("QUALITY", "NV");
		assertThat(act(o.id(), "advance")).isEqualTo(200);
		assertThat(act(o.id(), "hold")).isEqualTo(200);
		assertThat(act(o.id(), "release")).as("released to a status that is not behind the gate").isEqualTo(200);
		assertThat(orderStatus(o.id())).isEqualTo("PRODUCTION_STARTED");
	}

	@Test
	void aViewOnlyQualityModuleStillLetsTheGateBeReadSoTheStepsWork() throws Exception {
		Order o = packaged();
		cap("QUALITY", "V");
		assertThat(act(o.id(), "dispatch-ready")).isEqualTo(200);
		assertThat(act(o.id(), "hold")).isEqualTo(200);
		assertThat(act(o.id(), "release")).isEqualTo(200);
		assertThat(act(o.id(), "dispatch")).isEqualTo(200);
		assertThat(orderStatus(o.id())).isEqualTo("DISPATCHED");
	}

	@Test
	void aQualityFlipBetweenTwoStepsOnlyBlocksTheStepInsideTheHiddenWindow() throws Exception {
		Order o = packaged();
		assertThat(act(o.id(), "dispatch-ready")).isEqualTo(200);
		cap("QUALITY", "NV");
		assertThat(act(o.id(), "dispatch")).isEqualTo(409);
		cap("QUALITY", "O");
		assertThat(act(o.id(), "dispatch")).isEqualTo(200);
		assertThat(act(o.id(), "in-transit")).isEqualTo(200);
		assertThat(orderStatus(o.id())).isEqualTo("IN_TRANSIT");
	}

}
