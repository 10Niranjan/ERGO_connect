package com.ergo.connect.npd;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;

import com.ergo.connect.AdversarialSupport;
import com.ergo.connect.TestNpd;
import com.ergo.connect.TestRfq;
import com.ergo.connect.TestUsers.Login;

/**
 * Fixture of the production-PO tests (flow doc step 11): customers A and B each with an OFP / NPD / Quality team and
 * their own KAM, a KAM with no account, one supplier with its NPD / Quality / OFP teams, ERGO NPD / OFP / Quality.
 * Customer A is {@code customer}, B is {@code customerB}, the supplier is {@code supplierX} (see AdversarialSupport).
 */
abstract class ProductionPoSupport extends AdversarialSupport {

	private static final AtomicInteger PART = new AtomicInteger();

	private static final AtomicInteger CUSTOMER_PO = new AtomicInteger();

	protected Login ofpA;

	protected Login ofpA2;

	protected Login npdA;

	protected Login qualA;

	protected Login custKamA;

	protected Login ofpB;

	protected Login npdB;

	protected Login qualB;

	protected Login ergoNpd;

	protected Login ergoOfp;

	protected Login ergoQuality;

	protected Login kamA;

	protected Login kamB;

	protected Login kamNone;

	protected Login npdSup;

	protected Login qualSup;

	protected Login qualSup2;

	protected Login ofpSup;

	protected TestNpd.Team teamA;

	protected TestNpd.Team teamB;

	@BeforeEach
	void poSetUp() throws Exception {
		ofpA = users.login(customer, "CUSTOMER_OFP");
		ofpA2 = users.login(customer, "CUSTOMER_OFP");
		npdA = users.login(customer, "CUSTOMER_NPD");
		qualA = users.login(customer, "CUSTOMER_QUALITY");
		custKamA = users.login(customer, "CUSTOMER_KAM");
		ofpB = users.login(customerB, "CUSTOMER_OFP");
		npdB = users.login(customerB, "CUSTOMER_NPD");
		qualB = users.login(customerB, "CUSTOMER_QUALITY");
		ergoNpd = users.login(ergoCo, "ERGO_NPD");
		ergoOfp = users.login(ergoCo, "ERGO_OFP");
		ergoQuality = users.login(ergoCo, "ERGO_QUALITY");
		kamA = users.login(ergoCo, "ERGO_KAM");
		users.assignAccounts(kamA.userId(), customer);
		kamB = users.login(ergoCo, "ERGO_KAM");
		users.assignAccounts(kamB.userId(), customerB);
		kamNone = users.login(ergoCo, "ERGO_KAM");
		npdSup = users.login(supplierX, "SUPPLIER_NPD");
		qualSup = users.login(supplierX, "SUPPLIER_QUALITY");
		qualSup2 = users.login(supplierX, "SUPPLIER_QUALITY");
		ofpSup = users.login(supplierX, "SUPPLIER_OFP");
		teamA = new TestNpd.Team(npdA, qualA, ofpA, kamA, ergoNpd, ergoOfp, ergoQuality, ergoMgmt, npdSup, qualSup, qualSup2,
				ofpSup, supplierX);
		teamB = new TestNpd.Team(npdB, qualB, ofpB, kamB, ergoNpd, ergoOfp, ergoQuality, ergoMgmt, npdSup, qualSup, qualSup2,
				ofpSup, supplierX);
	}

	/** An approved sample project of customer A (the whole NPD path up to final approval). */
	protected long sampleA() throws Exception {
		return TestNpd.approvedSample(mvc, teamA, approvedPart(buyer));
	}

	protected long sampleB() throws Exception {
		return TestNpd.approvedSample(mvc, teamB, approvedPart(buyerB));
	}

	protected long approvedPart(Login owner) throws Exception {
		long rfq = reviewedRfq(owner, "PN-PO-" + PART.incrementAndGet());
		TestRfq.approveWhole(mvc, owner, ergoRfq, sellerX, supplierX, inr, rfq, "90");
		return TestRfq.itemIds(mvc, ergoRfq, rfq).getFirst();
	}

	protected long raise(Login by, long sample) throws Exception {
		return TestNpd.raiseProductionPo(mvc, by, sample);
	}

	protected String form(long sample) {
		return form(sample, 1000, LocalDate.now().plusDays(60).toString());
	}

	protected String form(long sample, Object quantity, String date) {
		return """
				{"sourceProjectId": %d, "customerPoNumber": "CPO-%d", "quantity": %s, "requiredDate": "%s", "remarks": "First lot"}"""
			.formatted(sample, CUSTOMER_PO.incrementAndGet(), quantity, date);
	}

	protected String edit(Object quantity, String date) {
		return """
				{"customerPoNumber": "CPO-FIX", "quantity": %s, "requiredDate": "%s", "remarks": "Corrected"}""".formatted(quantity,
				date);
	}

	protected String edit() {
		return edit(800, LocalDate.now().plusDays(70).toString());
	}

	protected String assign() {
		return assign(ergoOfp.userId(), ergoQuality.userId());
	}

	protected String assign(Object ofpUser, Object qualityUser) {
		return "{\"ergoOfpUserId\": %s, \"ergoQualityUserId\": %s}".formatted(ofpUser, qualityUser);
	}

	protected static String comment(String text) {
		return "{\"comment\": \"%s\"}".formatted(text);
	}

	protected String poStatus(long po) {
		return jdbc.queryForObject("SELECT status FROM production_pos WHERE production_po_id = ?", String.class, po);
	}

	protected int projectsOf(long po) {
		return count("SELECT count(*) FROM npd_projects WHERE production_po_id = ?", po);
	}

	protected int pos() {
		return count("SELECT count(*) FROM production_pos");
	}

	protected int auditRows(long po) {
		return count("SELECT count(*) FROM audit_logs WHERE module_name = 'npd' AND entity_name = 'ProductionPo' AND record_id = ?",
				po);
	}

	protected List<Map<String, Object>> audit(long po) {
		return jdbc.queryForList("""
				SELECT event_type, field_name, old_value, new_value, action_reason, changed_by_user_id, changed_by_company_id,
				       changed_by_role_code
				FROM audit_logs WHERE module_name = 'npd' AND entity_name = 'ProductionPo' AND record_id = ?
				ORDER BY audit_log_id""", po);
	}

	protected int heard(Login user, String kind, long po) {
		return count("""
				SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND event_kind = ? AND linked_module = 'production-po'
				AND linked_record_id = ?""", user.userId(), kind, po);
	}

	protected int heardAnything(Login user, long po) {
		return count("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND linked_module = 'production-po' AND linked_record_id = ?",
				user.userId(), po);
	}

	protected int code(String method, String url, Login user, String body, Object... vars) {
		var request = switch (method) {
			case "GET" -> get(url, vars);
			case "POST" -> post(url, vars);
			case "PUT" -> put(url, vars);
			default -> throw new IllegalArgumentException(method);
		};
		return send(request, user, body);
	}

	protected void ok(int expected, int actual, String what) {
		org.assertj.core.api.Assertions.assertThat(actual).as(what).isEqualTo(expected);
	}

	protected void expect2xx(org.springframework.test.web.servlet.ResultActions r) throws Exception {
		r.andExpect(status().is2xxSuccessful());
	}

}
