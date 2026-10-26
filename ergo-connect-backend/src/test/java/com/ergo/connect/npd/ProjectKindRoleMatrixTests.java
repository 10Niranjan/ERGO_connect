package com.ergo.connect.npd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ergo.connect.TestNpd;
import com.ergo.connect.TestUsers.Login;
import com.jayway.jsonpath.JsonPath;

/**
 * Roles per project kind: the same project endpoints against a sample project (the NPD column of the role sheet) and a
 * production / OFP project (NPD and OFP columns together, NpdRights). The expected sets below are written from the
 * default role sheet (DefaultAccessSheet), not from NpdRights, so a wrong union shows up as a red cell. Every cell is
 * collected before asserting, so one run lists them all.
 * <p>
 * Sheet, NPD / OFP column: ERGO Management O/O, ERGO NPD O/V, ERGO OFP V/O, ERGO Quality A/A, ERGO Admin V/V, KAM A/A
 * (the KAM never edits a project), Customer NPD O/NV, Customer OFP NV/O, Customer Quality A/V, Customer KAM V/V,
 * Supplier NPD A/NV, Supplier OFP NV/A, Supplier Quality A/A.
 */
class ProjectKindRoleMatrixTests extends ProductionPoSupport {

	private static final Set<String> SAMPLE_READERS = Set.of("admin", "mgmt", "ergoNpd", "ergoOfp", "ergoQuality", "kamA", "npdA",
			"qualA", "custKamA", "npdSup", "qualSup");

	private static final Set<String> PRODUCTION_READERS = union(SAMPLE_READERS, Set.of("ofpA", "ofpSup"));

	/** ERGO roles at A and up on the project's modules, except the KAM. */
	private static final Set<String> SAMPLE_EDITORS = Set.of("mgmt", "ergoNpd", "ergoQuality");

	private static final Set<String> PRODUCTION_EDITORS = union(SAMPLE_EDITORS, Set.of("ergoOfp"));

	/** ERGO owners (O). */
	private static final Set<String> SAMPLE_OWNERS = Set.of("mgmt", "ergoNpd");

	private static final Set<String> PRODUCTION_OWNERS = union(SAMPLE_OWNERS, Set.of("ergoOfp"));

	private long sample;

	private long production;

	private final Map<String, Login> who = new LinkedHashMap<>();

	private static Set<String> union(Set<String> a, Set<String> b) {
		var all = new java.util.HashSet<>(a);
		all.addAll(b);
		return Set.copyOf(all);
	}

	@BeforeEach
	void projects() throws Exception {
		sample = TestNpd.agreed(mvc, npdA, kamA, ergoNpd, ergoQuality, npdSup, supplierX, approvedPart(buyer));
		production = TestNpd.productionProject(mvc, teamA, sampleA());
		who.put("admin", users.login(ergoCo, "ERGO_ADMIN"));
		who.put("mgmt", ergoMgmt);
		who.put("ergoNpd", ergoNpd);
		who.put("ergoOfp", ergoOfp);
		who.put("ergoQuality", ergoQuality);
		who.put("kamA", kamA);
		who.put("kamB", kamB);
		who.put("kamNone", kamNone);
		who.put("npdA", npdA);
		who.put("qualA", qualA);
		who.put("ofpA", ofpA);
		who.put("custKamA", custKamA);
		who.put("npdB", npdB);
		who.put("ofpB", ofpB);
		who.put("npdSup", npdSup);
		who.put("qualSup", qualSup);
		who.put("ofpSup", ofpSup);
	}

	private static boolean refused(int status) {
		return status == 403 || status == 404;
	}

	/** A write the role sheet lets through the controller is refused by the service with a 409; nothing may change either way. */
	private static boolean notDone(int status) {
		return refused(status) || status == 409;
	}

	private int detail(Login by, long project) {
		return send(get("/api/npd/{id}", project), by, null);
	}

	private void cells(String title, List<String> bad) {
		assertThat(bad).as(title).isEmpty();
	}

	@Test
	void whoCanOpenAProjectDependsOnItsKind() {
		List<String> bad = new ArrayList<>();
		for (var kind : Map.of("sample", sample, "production", production).entrySet()) {
			Set<String> readers = kind.getKey().equals("sample") ? SAMPLE_READERS : PRODUCTION_READERS;
			for (var person : who.entrySet()) {
				int got = detail(person.getValue(), kind.getValue());
				boolean ok = readers.contains(person.getKey()) ? got == 200 : refused(got);
				if (!ok) {
					bad.add(kind.getKey() + " / " + person.getKey() + ": got " + got + ", readers=" + readers.contains(person.getKey()));
				}
			}
		}
		cells("read matrix", bad);
	}

	@Test
	void theActionsEachRoleIsOfferedFollowTheKind() throws Exception {
		List<String> bad = new ArrayList<>();
		for (var kind : Map.of("sample", sample, "production", production).entrySet()) {
			boolean prod = kind.getKey().equals("production");
			for (var person : who.entrySet()) {
				if (detail(person.getValue(), kind.getValue()) != 200) {
					continue;
				}
				String json = text(get("/api/npd/{id}", kind.getValue()), person.getValue(), null);
				List<String> actions = JsonPath.read(json, "$.actions");
				check(bad, kind.getKey(), person.getKey(), "ADD_ACTIVITY", actions, (prod ? PRODUCTION_EDITORS : SAMPLE_EDITORS));
				check(bad, kind.getKey(), person.getKey(), "UPDATE_PROJECT", actions, (prod ? PRODUCTION_EDITORS : SAMPLE_EDITORS));
				check(bad, kind.getKey(), person.getKey(), "CLOSE_STAGE", actions, (prod ? PRODUCTION_OWNERS : SAMPLE_OWNERS));
			}
		}
		cells("action matrix", bad);
	}

	private static void check(List<String> bad, String kind, String person, String action, List<String> actions, Set<String> expected) {
		if (actions.contains(action) != expected.contains(person)) {
			bad.add(kind + " / " + person + ": " + action + (actions.contains(action) ? " offered" : " missing"));
		}
	}

	private String activity(String name) {
		return """
				{"activityName": "%s", "status": "NOT_STARTED", "mandatory": false, "riskFlag": false, "issueFlag": false,
				 "ownerOrganizationType": "SUPPLIER", "ownerCompanyId": %d, "approvalRequired": false, "plannedDate": "%s"}"""
			.formatted(name, supplierX, LocalDate.now().plusDays(14));
	}

	private int activities(long project) {
		return count("SELECT count(*) FROM npd_activities WHERE npd_project_id = ?", project);
	}

	private String stage(long project) {
		return jdbc.queryForObject("SELECT stage FROM npd_projects WHERE npd_project_id = ?", String.class, project);
	}

	@Test
	void everyRoleOutsideTheKindsEditorsIsRefusedAndNothingMoves() {
		List<String> bad = new ArrayList<>();
		for (var kind : Map.of("sample", sample, "production", production).entrySet()) {
			boolean prod = kind.getKey().equals("production");
			long project = kind.getValue();
			int rows = activities(project);
			String stage = stage(project);
			for (var person : who.entrySet()) {
				if ((prod ? PRODUCTION_EDITORS : SAMPLE_EDITORS).contains(person.getKey())) {
					continue;
				}
				int add = send(post("/api/npd/{id}/activities", project), person.getValue(), activity("Intruder " + person.getKey()));
				if (!notDone(add)) {
					bad.add(kind.getKey() + " / " + person.getKey() + ": add activity got " + add);
				}
			}
			for (var person : who.entrySet()) {
				if ((prod ? PRODUCTION_OWNERS : SAMPLE_OWNERS).contains(person.getKey())) {
					continue;
				}
				int close = send(post("/api/npd/{id}/close-stage", project), person.getValue(), "{}");
				if (close >= 200 && close < 300 || close >= 500) {
					bad.add(kind.getKey() + " / " + person.getKey() + ": close stage got " + close);
				}
			}
			if (activities(project) != rows || !stage(project).equals(stage)) {
				bad.add(kind.getKey() + ": a refused call changed the project (" + rows + " -> " + activities(project) + ", " + stage + " -> "
						+ stage(project) + ")");
			}
		}
		cells("refused writes", bad);
	}

	@Test
	void everyEditorOfTheKindCanAddAStepAndEveryOtherKindOnlyRoleCannot() {
		List<String> bad = new ArrayList<>();
		for (String person : PRODUCTION_EDITORS) {
			int onProduction = send(post("/api/npd/{id}/activities", production), who.get(person), activity("Step by " + person));
			if (onProduction != 201) {
				bad.add("production / " + person + ": add activity got " + onProduction);
			}
			int onSample = send(post("/api/npd/{id}/activities", sample), who.get(person), activity("Step by " + person));
			boolean mayOnSample = SAMPLE_EDITORS.contains(person);
			if (mayOnSample ? onSample != 201 : !notDone(onSample)) {
				bad.add("sample / " + person + ": add activity got " + onSample);
			}
		}
		cells("editors", bad);
	}

	@Test
	void ergoOfpClosesAProductionStageButNeverASampleStage() {
		String sampleStage = stage(sample);
		int onSample = send(post("/api/npd/{id}/close-stage", sample), ergoOfp, "{}");
		assertThat(onSample).as("ERGO OFP closing a sample stage").isIn(403, 404, 409);
		assertThat(stage(sample)).isEqualTo(sampleStage);
		String productionStage = stage(production);
		int onProduction = send(post("/api/npd/{id}/close-stage", production), ergoOfp, "{}");
		assertThat(onProduction).as("ERGO OFP closing a production stage").isEqualTo(200);
		assertThat(stage(production)).isNotEqualTo(productionStage);
	}

	@Test
	void theOwnProjectListsShowProductionProjectsToTheOfpTeamsAndSampleProjectsOnlyToTheNpdOnes() throws Exception {
		String ofpCustomer = text(get("/api/npd/mine"), ofpA, null);
		assertThat(ids(ofpCustomer)).contains(production).doesNotContain(sample);
		String ofpSupplier = text(get("/api/npd/mine"), ofpSup, null);
		assertThat(ids(ofpSupplier)).contains(production).doesNotContain(sample);
		assertThat(ids(text(get("/api/npd/mine"), npdA, null))).contains(sample);
		assertThat(ids(text(get("/api/npd/mine"), npdSup, null))).contains(sample);
		assertThat(ids(text(get("/api/npd/mine"), ofpB, null))).doesNotContain(sample, production);
		assertThat(ids(text(get("/api/npd/mine"), npdB, null))).doesNotContain(sample, production);
	}

	private static List<Long> ids(String json) {
		return JsonPath.<List<Number>>read(json, "$[*].id").stream().map(Number::longValue).toList();
	}

}
