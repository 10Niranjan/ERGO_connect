package com.ergo.connect.npd;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ergo.connect.IntegrationTest;

/**
 * Two actors, one production order (flow doc step 11). Expected outcome of every race: no 5xx, no double write, the loser
 * a 409. The races on an existing order are made deterministic by holding the order's row lock (the first thing every
 * decision takes), starting the requests, waiting until they are parked behind it and releasing them together. The race
 * on raising is made deterministic by holding an uncommitted conflicting insert, so the request reaches the partial
 * unique index.
 */
@IntegrationTest
class ProductionPoConcurrencyTests extends ProductionPoSupport {

	private static final String PO = "/api/ofp/production-pos";

	@Autowired
	DataSource dataSource;

	interface WhileHeld {

		void run(Connection held) throws Exception;

	}

	@SafeVarargs
	private List<Integer> gated(String holdSql, boolean commit, int waiters, WhileHeld whileHeld, Callable<Integer>... calls) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(calls.length);
		try (Connection held = dataSource.getConnection()) {
			held.setAutoCommit(false);
			try (Statement s = held.createStatement()) {
				s.execute(holdSql);
			}
			List<Future<Integer>> futures = new ArrayList<>();
			for (Callable<Integer> call : calls) {
				futures.add(pool.submit(call));
			}
			try {
				for (int i = 0; i < 100 && count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'") < waiters; i++) {
					Thread.sleep(100);
				}
				Thread.sleep(300);
				if (whileHeld != null) {
					whileHeld.run(held);
				}
				if (commit) {
					held.commit();
				}
				else {
					held.rollback();
				}
			}
			catch (Exception e) {
				held.rollback();
				throw e;
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> future : futures) {
				statuses.add(future.get(60, TimeUnit.SECONDS));
			}
			return statuses;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private String lockOrder(long po) {
		return "SELECT production_po_id FROM production_pos WHERE production_po_id = " + po + " FOR UPDATE";
	}

	private String conflictingInsert(long sample, long creator) {
		return """
				INSERT INTO production_pos (production_po_number, source_project_id, customer_company_id, part_number, customer_po_number,
				                            quantity, required_date, status, created_by_user_id)
				SELECT 'PPO-G-' || (extract(epoch FROM clock_timestamp())::bigint %% 1000000), %d, customer_company_id, part_number, 'GATE', 1,
				       current_date, 'SUBMITTED', %d FROM npd_projects WHERE npd_project_id = %d""".formatted(sample, creator, sample);
	}

	private static void noServerError(List<Integer> statuses, String what) {
		assertThat(statuses).as(what + " " + statuses).noneMatch(s -> s >= 500);
	}

	// ---------------------------------------------------------------- raise

	@Test
	void raiseAgainstAConflictingOrderThatCommitsLaterIsA409NotA500() throws Exception {
		long sample = sampleA();
		List<Integer> statuses = gated(conflictingInsert(sample, ofpA2.userId()), true, 1, null,
				() -> send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PO), ofpA, form(sample)));
		assertThat(statuses).as(statuses.toString()).containsExactly(409);
		assertThat(count("SELECT count(*) FROM production_pos WHERE source_project_id = ?", sample)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM notifications WHERE linked_module = 'production-po' AND recipient_user_id = ?", kamA.userId()))
			.as("the losing request notified nobody").isZero();
	}

	@Test
	void raiseAgainstAConflictingOrderThatRollsBackSucceedsOnce() throws Exception {
		long sample = sampleA();
		List<Integer> statuses = gated(conflictingInsert(sample, ofpA2.userId()), false, 1, null,
				() -> send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PO), ofpA, form(sample)));
		assertThat(statuses).containsExactly(201);
		assertThat(count("SELECT count(*) FROM production_pos WHERE source_project_id = ?", sample)).isEqualTo(1);
	}

	@Test
	void twoCustomerOfpUsersRaisingForOneSampleAtOnceLeaveExactlyOneOrder() throws Exception {
		for (int round = 0; round < 3; round++) {
			long sample = sampleA();
			String a = form(sample);
			String b = form(sample);
			List<Integer> statuses = race(
					() -> send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PO), ofpA, a),
					() -> send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PO), ofpA2, b));
			noServerError(statuses, "double raise");
			assertThat(statuses).as("double raise " + statuses).containsOnlyOnce(201);
			assertThat(statuses).filteredOn(s -> s != 201).allMatch(s -> s == 409);
			assertThat(count("SELECT count(*) FROM production_pos WHERE source_project_id = ?", sample)).isEqualTo(1);
			assertThat(count("""
					SELECT count(*) FROM notifications n JOIN production_pos p ON p.production_po_id = n.linked_record_id
					WHERE n.linked_module = 'production-po' AND n.recipient_user_id = ? AND p.source_project_id = ?""", kamA.userId(), sample))
				.as("one notification for the one order").isEqualTo(1);
		}
	}

	// ---------------------------------------------------------------- decisions

	@Test
	void twoDesksCreatingTheProjectAtOnceMakeOneProject() throws Exception {
		long po = raise(ofpA, sampleA());
		List<Integer> statuses = gated(lockOrder(po), true, 2, null,
				() -> code("POST", PO + "/{id}/create-project", kamA, assign(), po),
				() -> code("POST", PO + "/{id}/create-project", ergoMgmt, assign(), po));
		noServerError(statuses, "double create-project");
		assertThat(statuses).as(statuses.toString()).containsOnlyOnce(201).filteredOn(s -> s != 201).allMatch(s -> s == 409);
		assertThat(projectsOf(po)).isEqualTo(1);
		assertThat(poStatus(po)).isEqualTo("ACCEPTED");
		assertThat(count("SELECT count(*) FROM audit_logs WHERE module_name = 'npd' AND entity_name = 'ProductionPo' AND record_id = ? AND field_name = 'status' AND new_value = 'ACCEPTED'", po))
			.isEqualTo(1);
		assertThat(count("SELECT count(*) FROM notifications WHERE event_kind = 'NPD_PROJECT_ASSIGNED' AND recipient_user_id = ? AND linked_record_id = (SELECT npd_project_id FROM npd_projects WHERE production_po_id = ?)", ergoOfp.userId(), po))
			.isEqualTo(1);
	}

	@Test
	void returnRacingCreateProjectHasOneWinnerAndAConsistentEnd() throws Exception {
		long po = raise(ofpA, sampleA());
		List<Integer> statuses = gated(lockOrder(po), true, 2, null,
				() -> code("POST", PO + "/{id}/return", kamA, comment("Fix it"), po),
				() -> code("POST", PO + "/{id}/create-project", ergoMgmt, assign(), po));
		noServerError(statuses, "return vs create");
		assertThat(statuses).as(statuses.toString()).filteredOn(s -> s < 300).hasSize(1);
		assertThat(statuses).filteredOn(s -> s >= 300).allMatch(s -> s == 409);
		if (statuses.get(0) == 200) {
			assertThat(poStatus(po)).isEqualTo("RETURNED");
			assertThat(projectsOf(po)).isZero();
		}
		else {
			assertThat(poStatus(po)).isEqualTo("ACCEPTED");
			assertThat(projectsOf(po)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT return_comment FROM production_pos WHERE production_po_id = ?", String.class, po)).isNull();
			assertThat(heard(ofpA, "PRODUCTION_PO_RETURNED", po)).as("the customer is not told of a return that did not happen").isZero();
		}
	}

	@Test
	void doubleReturnWritesOneReturnAndNotifiesOnce() throws Exception {
		long po = raise(ofpA, sampleA());
		List<Integer> statuses = gated(lockOrder(po), true, 2, null,
				() -> code("POST", PO + "/{id}/return", kamA, comment("First reason"), po),
				() -> code("POST", PO + "/{id}/return", ergoMgmt, comment("Second reason"), po));
		noServerError(statuses, "double return");
		assertThat(statuses).as(statuses.toString()).containsOnlyOnce(200).filteredOn(s -> s != 200).allMatch(s -> s == 409);
		assertThat(heard(ofpA, "PRODUCTION_PO_RETURNED", po)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_logs WHERE module_name = 'npd' AND entity_name = 'ProductionPo' AND record_id = ? AND field_name = 'status' AND new_value = 'RETURNED'", po))
			.isEqualTo(1);
	}

	@Test
	void returnRacingTheCustomersResubmitEndsConsistently() throws Exception {
		long po = raise(ofpA, sampleA());
		// SUBMITTED: the customer's resubmit is only legal if the return got there first
		List<Integer> statuses = gated(lockOrder(po), true, 2, null,
				() -> code("POST", PO + "/{id}/return", kamA, comment("Fix it"), po),
				() -> code("PUT", PO + "/{id}", ofpA, edit(), po));
		noServerError(statuses, "return vs resubmit");
		int ret = statuses.get(0);
		int resub = statuses.get(1);
		assertThat(ret).as(statuses.toString()).isEqualTo(200);
		assertThat(resub).as(statuses.toString()).isIn(200, 409);
		if (resub == 409) {
			// the resubmit ran first and was refused: the order ended returned with the KAM's reason
			assertThat(poStatus(po)).isEqualTo("RETURNED");
			assertThat(jdbc.queryForObject("SELECT return_comment FROM production_pos WHERE production_po_id = ?", String.class, po)).isEqualTo("Fix it");
		}
		else {
			// return then resubmit: back with the KAM, the old reason cleared, the correction stored
			assertThat(poStatus(po)).isEqualTo("SUBMITTED");
			assertThat(jdbc.queryForObject("SELECT return_comment FROM production_pos WHERE production_po_id = ?", String.class, po)).isNull();
			assertThat(jdbc.queryForObject("SELECT quantity FROM production_pos WHERE production_po_id = ?", Integer.class, po)).isEqualTo(800);
		}
	}

	@Test
	void twoColleaguesCorrectingTheSameReturnedOrderAtOnceWriteOnce() throws Exception {
		long po = raise(ofpA, sampleA());
		call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PO + "/{id}/return", po), kamA, comment("Fix")).andExpect(
				org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
		List<Integer> statuses = gated(lockOrder(po), true, 2, null,
				() -> code("PUT", PO + "/{id}", ofpA, edit(800, java.time.LocalDate.now().plusDays(40).toString()), po),
				() -> code("PUT", PO + "/{id}", ofpA2, edit(900, java.time.LocalDate.now().plusDays(41).toString()), po));
		noServerError(statuses, "double correction");
		assertThat(statuses).as(statuses.toString()).containsOnlyOnce(200).filteredOn(s -> s != 200).allMatch(s -> s == 409);
		assertThat(poStatus(po)).isEqualTo("SUBMITTED");
		assertThat(heard(kamA, "PRODUCTION_PO_SUBMITTED", po)).as("raise + one correction").isEqualTo(2);
		int quantity = jdbc.queryForObject("SELECT quantity FROM production_pos WHERE production_po_id = ?", Integer.class, po);
		assertThat(quantity).isIn(800, 900);
	}

	@Test
	void aCorrectionRacingTheKamsCreateProjectOnAReturnedOrderEndsConsistently() throws Exception {
		long po = raise(ofpA, sampleA());
		call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PO + "/{id}/return", po), kamA, comment("Fix")).andExpect(
				org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
		List<Integer> statuses = gated(lockOrder(po), true, 2, null,
				() -> code("PUT", PO + "/{id}", ofpA, edit(), po),
				() -> code("POST", PO + "/{id}/create-project", kamA, assign(), po));
		noServerError(statuses, "correction vs create-project");
		assertThat(statuses.get(0)).as(statuses.toString()).isEqualTo(200);
		assertThat(statuses.get(1)).as(statuses.toString()).isIn(201, 409);
		if (statuses.get(1) == 201) {
			// correction first, then accepted: the project exists for the corrected order
			assertThat(poStatus(po)).isEqualTo("ACCEPTED");
			assertThat(projectsOf(po)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT quantity FROM production_pos WHERE production_po_id = ?", Integer.class, po)).isEqualTo(800);
		}
		else {
			assertThat(poStatus(po)).isEqualTo("SUBMITTED");
			assertThat(projectsOf(po)).isZero();
		}
	}

}
