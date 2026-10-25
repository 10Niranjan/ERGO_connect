package com.ergo.connect.npd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ergo.connect.TestUsers.Login;

/**
 * The module switches (PERMISSIONS.md §9) against the production order, entry point by entry point: OFP at View refuses
 * every change and keeps every read, OFP hidden shows nothing from any path, NPD not accepting changes blocks the
 * accept (it creates the NPD-side project) and hidden NPD blocks raising (it continues an NPD sample).
 */
class ProductionPoSwitchTests extends ProductionPoSupport {

	private static final String PO = "/api/ofp/production-pos";

	/** A submitted order, a returned one and an approved sample with no order yet. */
	record Fx(long submitted, long returned, long freeSample) {
	}

	private Fx fixture() throws Exception {
		long submitted = raise(ofpA, sampleA());
		long returned = raise(ofpA, sampleA());
		ok(200, code("POST", PO + "/{id}/return", kamA, comment("Check the quantity"), returned), "return");
		return new Fx(submitted, returned, sampleA());
	}

	private void nothingChanged(Fx f, int orders) {
		assertThat(poStatus(f.submitted)).isEqualTo("SUBMITTED");
		assertThat(poStatus(f.returned)).isEqualTo("RETURNED");
		assertThat(projectsOf(f.submitted)).isZero();
		assertThat(pos()).isEqualTo(orders);
	}

	private void everyChangeIsRefused(Fx f, int orders) throws Exception {
		assertThat(code("POST", PO, ofpA, form(f.freeSample), new Object[0])).as("raise").isBetween(400, 499);
		assertThat(code("PUT", PO + "/{id}", ofpA, edit(), f.returned)).as("correct").isBetween(400, 499);
		assertThat(code("POST", PO + "/{id}/return", kamA, comment("late"), f.submitted)).as("return").isBetween(400, 499);
		assertThat(code("POST", PO + "/{id}/create-project", kamA, assign(), f.submitted)).as("accept").isBetween(400, 499);
		assertThat(code("POST", PO + "/{id}/create-project", ergoMgmt, assign(), f.submitted)).as("accept by management").isBetween(400, 499);
		nothingChanged(f, orders);
	}

	@Test
	void aReadOnlyOfpModuleRefusesEveryChangeButShowsEverythingWithNoButtons() throws Exception {
		Fx f = fixture();
		int orders = pos();
		cap("OFP", "V");
		everyChangeIsRefused(f, orders);
		for (Login reader : List.of(ofpA, kamA, ergoMgmt, ergoOfp)) {
			ok(200, code("GET", PO + "/{id}", reader, null, f.submitted), "read by " + reader.userId());
			call(get(PO + "/{id}", f.submitted), reader, null).andExpect(jsonPath("$.actions", hasSize(0)));
		}
		cap("OFP", "O");
		ok(200, code("POST", PO + "/{id}/return", kamA, comment("now fine"), f.submitted), "back at O");
	}

	/** F3, settled as intended: with OFP View-only nobody can raise, so Customer OFP has no eligible list (403), never a list with a button. */
	@Test
	void withOfpViewOnlyCustomerOfpHasNoEligibleListToRaiseFrom() throws Exception {
		sampleA();
		cap("OFP", "V");
		ok(403, code("GET", PO + "/eligible-projects", ofpA, null), "eligible list");
	}

	@Test
	void aHiddenOfpModuleShowsNothingAndRefusesEverythingFromEveryPath() throws Exception {
		Fx f = fixture();
		int orders = pos();
		call(get("/api/notifications?size=100"), kamA, null).andExpect(jsonPath("$.items[?(@.linkedModule=='production-po')]", not(hasSize(0))));
		cap("OFP", "NV");
		everyChangeIsRefused(f, orders);
		for (Login reader : List.of(ofpA, kamA, ergoMgmt, ergoOfp)) {
			assertThat(code("GET", PO + "/{id}", reader, null, f.submitted)).as("get by " + reader.userId()).isBetween(403, 404);
			assertThat(code("GET", PO + "/{id}/history", reader, null, f.submitted)).as("history by " + reader.userId()).isBetween(403, 404);
			assertThat(text(get(PO), reader, null)).as("list for " + reader.userId()).doesNotContain("PPO-");
		}
		call(get("/api/notifications?size=100"), kamA, null).andExpect(jsonPath("$.items[?(@.linkedModule=='production-po')]", hasSize(0)));
		call(get("/api/notifications?size=100"), ofpA, null).andExpect(jsonPath("$.items[?(@.linkedModule=='production-po')]", hasSize(0)));
		cap("OFP", "O");
		call(get(PO + "/{id}", f.submitted), kamA, null).andExpect(status().isOk());
		call(get("/api/notifications?size=100"), kamA, null).andExpect(jsonPath("$.items[?(@.linkedModule=='production-po')]", not(hasSize(0))));
	}

	@Test
	void anNpdModuleThatIsNotAcceptingChangesBlocksTheAcceptWholeButNotTheReturn() throws Exception {
		Fx f = fixture();
		for (String cap : List.of("V", "NV")) {
			cap("NPD", cap);
			assertThat(code("POST", PO + "/{id}/create-project", kamA, assign(), f.submitted)).as("accept at " + cap).isBetween(400, 499);
			assertThat(poStatus(f.submitted)).isEqualTo("SUBMITTED");
			assertThat(projectsOf(f.submitted)).isZero();
			assertThat(count("SELECT count(*) FROM npd_projects WHERE production_po_id = ?", f.submitted)).isZero();
		}
		cap("NPD", "V");
		ok(200, code("POST", PO + "/{id}/return", kamA, comment("Not yet"), f.submitted), "a return needs no NPD change");
		cap("NPD", "O");
		ok(200, code("PUT", PO + "/{id}", ofpA, edit(), f.submitted), "correct the returned order");
		ok(201, code("POST", PO + "/{id}/create-project", kamA, assign(), f.submitted), "accept once NPD is back");
		assertThat(projectsOf(f.submitted)).isOne();
	}

	@Test
	void aHiddenNpdModuleStopsRaisingAndEmptiesTheEligibleList() throws Exception {
		Fx f = fixture();
		cap("NPD", "NV");
		assertThat(code("POST", PO, ofpA, form(f.freeSample), new Object[0])).as("raise").isBetween(400, 499);
		assertThat(count("SELECT count(*) FROM production_pos WHERE source_project_id = ?", f.freeSample)).isZero();
		call(get(PO + "/eligible-projects"), ofpA, null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
		// raising only reads the sample, so view-only NPD still lets the customer release the order
		cap("NPD", "V");
		ok(201, code("POST", PO, ofpA, form(f.freeSample), new Object[0]), "raise with NPD at View");
	}

}
