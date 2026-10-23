package com.ergo.connect;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Shared test plumbing for the flow doc's steps 6-7: Customer NPD raises a Sample PO for an approved part, the ERGO KAM
 * (or Management) creates the sample project and assigns ERGO NPD and ERGO Quality, ERGO NPD builds the plan and
 * releases it to the supplier, the supplier submits it and ERGO NPD agrees it (step 8). {@link #start} stops with the plan still being built (the supplier cannot see the
 * project); {@link #release} goes on until the supplier's NPD / RFQ / Quality teams can.
 */
public final class TestNpd {

	private static final AtomicInteger PO_SEQ = new AtomicInteger(5000);

	private TestNpd() {
	}

	/** A Sample PO form for one part: 25 pieces due in 30 days. */
	public static String samplePoForm(long rfqItemId) {
		return samplePoForm(rfqItemId, "PO-" + PO_SEQ.incrementAndGet(), 25, LocalDate.now().plusDays(30));
	}

	public static String samplePoForm(long rfqItemId, String customerPoNumber, int quantity, LocalDate requiredDate) {
		return """
				{"rfqItemId": %d, "customerPoNumber": "%s", "quantity": %d, "requiredDate": "%s", "remarks": "For trials"}"""
			.formatted(rfqItemId, customerPoNumber, quantity, requiredDate);
	}

	/** Customer NPD raises the Sample PO for a part its company has had approved; returns the Sample PO id. */
	public static long raise(MockMvc mvc, Login customerNpd, long rfqItemId) throws Exception {
		String json = send(mvc, post("/api/npd/sample-pos"), customerNpd, samplePoForm(rfqItemId), 201);
		return JsonPath.<Number>read(json, "$.id").longValue();
	}

	/** The KAM / Management accepts the Sample PO by creating the project; returns the project id. */
	public static long createProject(MockMvc mvc, Login desk, long samplePoId, Login ergoNpd, Login ergoQuality,
			String comment) throws Exception {
		String json = send(mvc, post("/api/npd/sample-pos/{id}/create-project", samplePoId), desk,
				"{\"ergoNpdUserId\": %d, \"ergoQualityUserId\": %d, \"comment\": %s}".formatted(ergoNpd.userId(),
						ergoQuality.userId(), comment == null ? "null" : "\"" + comment + "\""),
				201);
		return JsonPath.<Number>read(json, "$.id").longValue();
	}

	/** Sample PO raised and accepted: a project in PLANNING whose plan is still being built (AWAITING_PLAN). */
	public static long start(MockMvc mvc, Login customerNpd, Login desk, Login ergoNpd, Login ergoQuality,
			long rfqItemId) throws Exception {
		return createProject(mvc, desk, raise(mvc, customerNpd, rfqItemId), ergoNpd, ergoQuality, null);
	}

	/** A step owned by the supplier company, added to the plan by ERGO NPD; returns the activity id. */
	public static long addSupplierStep(MockMvc mvc, Login ergoNpd, long projectId, long supplierCompanyId,
			String name, boolean mandatory) throws Exception {
		String json = send(mvc, post("/api/npd/{id}/activities", projectId), ergoNpd, """
				{"activityName": "%s", "status": "NOT_STARTED", "mandatory": %s, "riskFlag": false, "issueFlag": false,
				 "ownerOrganizationType": "SUPPLIER", "ownerCompanyId": %d, "approvalRequired": false, "plannedDate": "%s"}"""
			.formatted(name, mandatory, supplierCompanyId, LocalDate.now().plusDays(14)), 201);
		return JsonPath.<java.util.List<Number>>read(json, "$.activities[?(@.activityName == '" + name + "')].id")
			.getFirst()
			.longValue();
	}

	/** ERGO NPD releases the plan to the supplier (needs at least one supplier-owned step). */
	public static void assignPlan(MockMvc mvc, Login ergoNpd, long projectId) throws Exception {
		send(mvc, post("/api/npd/{id}/plan/assign", projectId), ergoNpd, "{}", 200);
	}

	/**
	 * {@link #start} plus one optional (non-mandatory, so it never blocks Close Stage) supplier step and the plan
	 * release: the supplier's teams can now open the project.
	 */
	public static long release(MockMvc mvc, Login customerNpd, Login desk, Login ergoNpd, Login ergoQuality,
			long supplierCompanyId, long rfqItemId) throws Exception {
		long project = start(mvc, customerNpd, desk, ergoNpd, ergoQuality, rfqItemId);
		addSupplierStep(mvc, ergoNpd, project, supplierCompanyId, "Supplier plan step", false);
		assignPlan(mvc, ergoNpd, project);
		return project;
	}

	/** Supplier NPD submits the plan (its steps carry a planned date already, see {@link #addSupplierStep}). */
	public static void submitPlan(MockMvc mvc, Login supplierNpd, long projectId) throws Exception {
		send(mvc, post("/api/npd/{id}/plan/submit", projectId), supplierNpd, "{}", 200);
	}

	/** ERGO NPD agrees the submitted plan: execution starts. */
	public static void agreePlan(MockMvc mvc, Login ergoNpd, long projectId) throws Exception {
		send(mvc, post("/api/npd/{id}/plan/agree", projectId), ergoNpd, "{}", 200);
	}

	/** {@link #release} plus the supplier's submission and ERGO NPD's agreement: the project is in execution. */
	public static long agreed(MockMvc mvc, Login customerNpd, Login desk, Login ergoNpd, Login ergoQuality,
			Login supplierNpd, long supplierCompanyId, long rfqItemId) throws Exception {
		long project = release(mvc, customerNpd, desk, ergoNpd, ergoQuality, supplierCompanyId, rfqItemId);
		submitPlan(mvc, supplierNpd, project);
		agreePlan(mvc, ergoNpd, project);
		return project;
	}

	/** Supplier Quality approves internally and submits; ERGO Quality and a Supplier Quality colleague both approve (flow doc step 9). */
	public static void validate(MockMvc mvc, Login supplierQuality, Login supplierQualityColleague, Login ergoQuality, long projectId)
			throws Exception {
		send(mvc, post("/api/npd/{id}/validation/submit", projectId), supplierQuality, "{}", 200);
		send(mvc, post("/api/npd/{id}/validation/decide", projectId), ergoQuality, "{\"approved\": true}", 200);
		// a colleague validates: nobody approves their own submission
		send(mvc, post("/api/npd/{id}/validation/decide", projectId), supplierQualityColleague, "{\"approved\": true}", 200);
	}

	/** Customer NPD and Customer Quality both approve the project's final review (flow doc step 10). */
	public static void customerApprove(MockMvc mvc, Login customerNpd, Login customerQuality, long projectId) throws Exception {
		send(mvc, post("/api/npd/{id}/validation/decide", projectId), customerNpd, "{\"approved\": true}", 200);
		send(mvc, post("/api/npd/{id}/validation/decide", projectId), customerQuality, "{\"approved\": true}", 200);
	}

	private static String send(MockMvc mvc, MockHttpServletRequestBuilder request, Login user, String body,
			int expectedStatus) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer()).contentType(MediaType.APPLICATION_JSON).content(body);
		return mvc.perform(request)
			.andExpect(status().is(expectedStatus))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

}
