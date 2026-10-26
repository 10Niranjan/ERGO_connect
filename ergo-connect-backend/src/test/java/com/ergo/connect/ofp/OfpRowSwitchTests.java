package com.ergo.connect.ofp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ergo.connect.TestUsers.Login;

/**
 * The OFP module switch against the order's row writes. Chunk 2: View-only. Material status (ERGO OFP and Quality),
 * production progress and packaging/dispatch (ERGO OFP and the assigned supplier) are all refused and nothing is saved;
 * the supplier still reads its rows; back at O every writer works again.
 */
class OfpRowSwitchTests extends OfpOrderSupport {

	private static final String MATERIAL = "{\"materialStatus\": \"RECEIVED\", \"inspectionStatus\": \"PASSED\"}";

	private static final String PROGRESS = "{\"processStepName\": \"Casting\", \"completedQuantity\": 50, \"status\": \"DONE\"}";

	private static final String PACKAGING = "{\"packagingStatus\": \"PACKED\", \"dispatchReadyFlag\": true}";

	private int rows(long order) {
		return count("SELECT (SELECT count(*) FROM order_material_status WHERE order_id = ?)"
				+ " + (SELECT count(*) FROM order_production_progress WHERE order_id = ?)"
				+ " + (SELECT count(*) FROM order_packaging_dispatch WHERE order_id = ?)", order, order, order);
	}

	private int status(String method, String url, Login by, String body, Object... vars) {
		return switch (method) {
			case "POST" -> send(post(url, vars), by, body);
			case "PUT" -> send(put(url, vars), by, body);
			default -> throw new IllegalArgumentException(method);
		};
	}

	private void everyRowWriteIsRefused(Order o) {
		for (Login by : List.of(ergoOfp, ergoQuality)) {
			assertThat(status("POST", "/api/ofp/orders/{id}/material-status", by, MATERIAL, o.id())).as("add material by " + by.userId()).isEqualTo(403);
			assertThat(status("PUT", "/api/ofp/material-status/{id}", by, MATERIAL, o.material())).as("edit material by " + by.userId()).isEqualTo(403);
		}
		for (Login by : List.of(ergoOfp, ofpSup)) {
			assertThat(status("POST", "/api/ofp/orders/{id}/production-progress", by, PROGRESS, o.id())).as("add progress by " + by.userId()).isEqualTo(403);
			assertThat(status("PUT", "/api/ofp/production-progress/{id}", by, PROGRESS, o.progress())).as("edit progress by " + by.userId()).isEqualTo(403);
			assertThat(status("POST", "/api/ofp/orders/{id}/packaging-dispatch", by, PACKAGING, o.id())).as("add packaging by " + by.userId()).isEqualTo(403);
			assertThat(status("PUT", "/api/ofp/packaging-dispatch/{id}", by, PACKAGING, o.packaging())).as("edit packaging by " + by.userId()).isEqualTo(403);
		}
	}

	@Test
	void aReadOnlyOfpModuleRefusesEveryRowWriteFromEveryWriterAndSavesNothing() throws Exception {
		Order o = working();
		int rowsBefore = rows(o.id());
		String material = jdbc.queryForObject("SELECT material_status FROM order_material_status WHERE order_material_status_id = ?", String.class, o.material());
		String progress = jdbc.queryForObject("SELECT status FROM order_production_progress WHERE production_progress_id = ?", String.class, o.progress());
		boolean claim = jdbc.queryForObject("SELECT dispatch_ready_flag FROM order_packaging_dispatch WHERE packaging_dispatch_id = ?", Boolean.class, o.packaging());
		cap("OFP", "V");
		everyRowWriteIsRefused(o);
		assertThat(rows(o.id())).isEqualTo(rowsBefore);
		assertThat(jdbc.queryForObject("SELECT material_status FROM order_material_status WHERE order_material_status_id = ?", String.class, o.material())).isEqualTo(material);
		assertThat(jdbc.queryForObject("SELECT status FROM order_production_progress WHERE production_progress_id = ?", String.class, o.progress())).isEqualTo(progress);
		assertThat(jdbc.queryForObject("SELECT dispatch_ready_flag FROM order_packaging_dispatch WHERE packaging_dispatch_id = ?", Boolean.class, o.packaging())).isEqualTo(claim);
		assertThat(orderStatus(o.id())).isEqualTo("MATERIAL_RECEIVED");
	}

	@Test
	void theAssignedSupplierStillReadsItsRowsWhileOfpIsViewOnly() throws Exception {
		Order o = working();
		cap("OFP", "V");
		String seen = text(get("/api/ofp/orders/{id}", o.id()), ofpSup, null);
		assertThat(seen).contains("Casting");
		assertThat(text(get("/api/ofp/orders/{id}", o.id()), ofpCust, null)).contains("Casting");
	}

	@Test
	void backAtOEveryWriterWorksAgain() throws Exception {
		Order o = working();
		cap("OFP", "V");
		everyRowWriteIsRefused(o);
		cap("OFP", "O");
		assertThat(status("PUT", "/api/ofp/material-status/{id}", ergoQuality, MATERIAL, o.material())).isEqualTo(200);
		assertThat(status("PUT", "/api/ofp/production-progress/{id}", ofpSup, PROGRESS, o.progress())).isEqualTo(200);
		assertThat(status("PUT", "/api/ofp/packaging-dispatch/{id}", ofpSup, PACKAGING, o.packaging())).isEqualTo(200);
		assertThat(status("POST", "/api/ofp/orders/{id}/production-progress", ergoOfp, PROGRESS, o.id())).isEqualTo(201);
	}

}
