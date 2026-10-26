package com.ergo.connect.ofp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The fixture itself: each stage builder lands in the state the switch tests rely on. */
class OfpOrderFixtureTests extends OfpOrderSupport {

	@Test
	void everyStageBuilderLandsInItsState() throws Exception {
		Order fresh = fresh();
		assertThat(orderStatus(fresh.id())).isEqualTo("ORDER_CREATED");

		Order working = working();
		assertThat(orderStatus(working.id())).isEqualTo("MATERIAL_RECEIVED");
		assertThat(working.material()).isPositive();
		assertThat(working.progress()).isPositive();
		assertThat(working.packaging()).isPositive();

		Order ready = dispatchReady();
		assertThat(orderStatus(ready.id())).isEqualTo("DISPATCH_READY");

		Order out = dispatched();
		assertThat(orderStatus(out.id())).isEqualTo("DISPATCHED");
		assertThat(out.shipment()).isPositive();
		assertThat(count("SELECT count(*) FROM shipments WHERE shipment_id = ? AND pod_document_id IS NOT NULL", out.shipment()))
			.as("the shipment has a POD on file").isOne();
	}

}
