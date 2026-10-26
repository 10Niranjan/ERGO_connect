package com.ergo.connect.ofp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ergo.connect.TestUsers.Login;

/**
 * The OFP module switch against shipments and the POD. Chunk 3: View-only. Shipment create/update and POD upload are
 * refused and nothing is saved; the POD still downloads for ERGO, stays closed to the customer and supplier, and the
 * customer still sees the shipment's tracking; back at O the writes work again.
 */
class OfpShipmentSwitchTests extends OfpOrderSupport {

	private static final String SHIPMENT = """
			{"transporterName": "Other Carrier", "trackingNumber": "TRACK-2", "status": "DISPATCHED",
			 "receiptConfirmedByCustomerFlag": false}""";

	private int shipments(long order) {
		return count("SELECT count(*) FROM shipments WHERE order_id = ?", order);
	}

	private long pod(long shipment) {
		return jdbc.queryForObject("SELECT pod_document_id FROM shipments WHERE shipment_id = ?", Long.class, shipment);
	}

	private void everyShipmentWriteIsRefused(Order o) throws Exception {
		assertThat(send(post("/api/ofp/orders/{id}/shipments", o.id()), ergoOfp, SHIPMENT)).as("create").isEqualTo(403);
		assertThat(send(put("/api/ofp/shipments/{id}", o.shipment()), ergoOfp, SHIPMENT)).as("update").isEqualTo(403);
		assertThat(upload("/api/ofp/shipments/{id}/pod", o.shipment(), ergoOfp, "new-pod.pdf").andReturn().getResponse().getStatus())
			.as("POD upload").isEqualTo(403);
		for (Login other : List.of(ofpCust, ofpSup)) {
			assertThat(send(post("/api/ofp/orders/{id}/shipments", o.id()), other, SHIPMENT)).as("create by " + other.userId()).isEqualTo(403);
		}
	}

	@Test
	void aReadOnlyOfpModuleRefusesEveryShipmentWriteAndSavesNothing() throws Exception {
		Order o = dispatched();
		long podBefore = pod(o.shipment());
		cap("OFP", "V");
		everyShipmentWriteIsRefused(o);
		assertThat(shipments(o.id())).isOne();
		assertThat(pod(o.shipment())).as("the POD on file is untouched").isEqualTo(podBefore);
		assertThat(jdbc.queryForObject("SELECT tracking_number FROM shipments WHERE shipment_id = ?", String.class, o.shipment()))
			.isEqualTo("TRACK-1");
		assertThat(orderStatus(o.id())).isEqualTo("DISPATCHED");
	}

	@Test
	void thePodStillDownloadsForErgoAndStaysClosedToTheOtherSidesWhileOfpIsViewOnly() throws Exception {
		Order o = dispatched();
		cap("OFP", "V");
		for (Login ergo : List.of(ergoOfp, ergoMgmt)) {
			assertThat(send(get("/api/ofp/shipments/{id}/pod", o.shipment()), ergo, null)).as("POD for " + ergo.userId()).isEqualTo(200);
		}
		assertThat(send(get("/api/ofp/shipments/{id}/pod", o.shipment()), ofpCust, null)).isEqualTo(403);
		assertThat(send(get("/api/ofp/shipments/{id}/pod", o.shipment()), ofpSup, null)).isEqualTo(403);
	}

	@Test
	void bothSidesStillSeeTheShipmentTrackingButNeverThePodFile() throws Exception {
		Order o = dispatched();
		cap("OFP", "V");
		String seen = text(get("/api/ofp/orders/{id}", o.id()), ofpCust, null);
		assertThat(seen).contains("TRACK-1");
		assertThat(seen).doesNotContain("pod.pdf").doesNotContain("podDocument");
		assertThat(text(get("/api/ofp/orders/{id}", o.id()), ofpSup, null)).doesNotContain("pod.pdf").doesNotContain("podDocument");
	}

	@Test
	void backAtOTheShipmentWritesWorkAgain() throws Exception {
		Order o = dispatched();
		cap("OFP", "V");
		everyShipmentWriteIsRefused(o);
		cap("OFP", "O");
		assertThat(send(put("/api/ofp/shipments/{id}", o.shipment()), ergoOfp, SHIPMENT)).isEqualTo(200);
		assertThat(upload("/api/ofp/shipments/{id}/pod", o.shipment(), ergoOfp, "new-pod.pdf").andReturn().getResponse().getStatus())
			.isEqualTo(200);
		assertThat(send(post("/api/ofp/orders/{id}/shipments", o.id()), ergoOfp, SHIPMENT)).isEqualTo(201);
		assertThat(shipments(o.id())).isEqualTo(2);
	}

}
