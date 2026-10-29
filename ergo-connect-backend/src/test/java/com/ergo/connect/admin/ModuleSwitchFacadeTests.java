package com.ergo.connect.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.AccessPolicy;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.npd.api.NpdApi;
import com.ergo.connect.ofp.api.OfpApi;
import com.ergo.connect.quality.api.QualityApi;
import com.ergo.connect.quotation.api.QuotationApi;
import com.ergo.connect.rfq.api.RfqApi;

/**
 * Every method of the five module facades (what other modules - and so every internal call, comment, document,
 * notification and job - reach a module through) is classified here and exercised under V and NV, so a switch cannot be
 * bypassed from inside the backend. The build fails when a facade method is added without being classified: decide
 * how the new method behaves when its module is read-only or hidden, then add it to the table.
 */
@IntegrationTest
class ModuleSwitchFacadeTests {

	/** What a facade method must do when its module is switched off. */
	private enum Rule {

		/** A read: with the module hidden, 404. */
		NOT_FOUND,
		/** A read: with the module hidden, nothing (empty / false / 0). */
		HIDDEN_EMPTY,
		/** A change: refused (409) when the module is read-only or hidden. */
		WRITE,
		/** A change-probe or reminder scan: nothing / false when the module is read-only or hidden. */
		FROZEN_EMPTY

	}

	private static final Map<String, Rule> RULES = new TreeMap<>();

	private static final Map<Class<?>, String> MODULES = Map.of(RfqApi.class, "RFQ", QuotationApi.class, "QUOTATION",
			NpdApi.class, "NPD", OfpApi.class, "OFP", QualityApi.class, "QUALITY");

	static {
		// RFQ
		rules(RfqApi.class, Rule.NOT_FOUND, "forErgo(long)", "forCustomer(long)", "numberOf(long)",
				"forSupplierItem(long)");
		rules(RfqApi.class, Rule.HIDDEN_EMPTY, "visibleToCaller(long)", "partiesVisibleToCaller(long)",
				"forSupplierItems(Collection)", "matchingItems(Collection,String)", "supplierDocuments(long,Collection)", "itemForErgo(long)", "customerParts()",
				"dashboardItems()");
		rules(RfqApi.class, Rule.WRITE, "distributing(long)", "quotationProgress(long,QuotationSummary)");
		rules(RfqApi.class, Rule.FROZEN_EMPTY, "replaceOpenForCaller(long)");
		// Quotation
		rules(QuotationApi.class, Rule.HIDDEN_EMPTY, "approvedParts()", "approvedPart(long)", "customerApprovedParts()",
				"partStatusItems()",
				"visibleToCaller(long)", "partiesVisibleToCaller(long)", "fileVisibleToCaller(long,long)",
				"supplierMayReadRfqDocument(long,long)", "dashboardItems()", "pendingSubmissionCount()");
		rules(QuotationApi.class, Rule.FROZEN_EMPTY, "overdueItems(LocalDate)", "replaceOpenForCaller(long)");
		// NPD
		rules(NpdApi.class, Rule.WRITE, "handover(long)");
		rules(NpdApi.class, Rule.HIDDEN_EMPTY, "projectRef(long)", "partiesVisibleToCaller(long)", "dashboardItems()",
				"samplePosAwaitingCount()", "writableForRecord(long)", "samplePoPartiesVisibleToCaller(long)", "productionPoPartiesVisibleToCaller(long)", "pendingApprovalsCount()");
		rules(NpdApi.class, Rule.FROZEN_EMPTY, "overdueItems(LocalDate)");
		// OFP
		rules(OfpApi.class, Rule.WRITE, "podDocumentReplaced(long,long)");
		rules(OfpApi.class, Rule.HIDDEN_EMPTY, "orderRef(long)", "shipmentVisibleToCaller(long)",
				"orderPartiesVisibleToCaller(long)", "dashboardItems()", "supplierPerformance()");
		rules(OfpApi.class, Rule.FROZEN_EMPTY, "podReplaceOpenForCaller(long)");
		// Quality
		rules(QualityApi.class, Rule.WRITE, "capaEvidenceReplaced(long,long)");
		rules(QualityApi.class, Rule.HIDDEN_EMPTY, "ofpDispatchGateMet(long)", "npdFinalSampleGateMet(long)",
				"capaActionVisibleToCaller(long)", "inspectionPartiesVisibleToCaller(long)",
				"ncrPartiesVisibleToCaller(long)", "deviationPartiesVisibleToCaller(long)", "ncrDashboardItems()",
				"deviationDashboardItems()", "openNcrCountsBySupplier()");
		rules(QualityApi.class, Rule.FROZEN_EMPTY, "overdueItems(LocalDate)", "capaReplaceOpenForCaller(long)");
	}

	@Autowired
	RfqApi rfqApi;

	@Autowired
	QuotationApi quotationApi;

	@Autowired
	NpdApi npdApi;

	@Autowired
	OfpApi ofpApi;

	@Autowired
	QualityApi qualityApi;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AccessPolicy policy;

	@AfterEach
	void restoreShippedSheet() {
		SwitchTestSupport.restore(jdbc, policy);
	}

	@Test
	void everyFacadeMethodIsClassified() {
		TreeSet<String> declared = new TreeSet<>();
		for (Class<?> api : MODULES.keySet()) {
			for (Method m : api.getDeclaredMethods()) {
				declared.add(key(api, m));
			}
		}
		assertThat(declared).as("a facade method was added or removed: classify it in ModuleSwitchFacadeTests.RULES, "
				+ "and make it honour the module switch (docs/PERMISSIONS.md §9)").isEqualTo(new TreeSet<>(RULES.keySet()));
	}

	@Test
	void hiddenModulesShowNothingAndReadOnlyOrHiddenModulesChangeNothingThroughTheirFacades() throws Exception {
		CurrentUser caller = new CurrentUser(1L, 1L, CompanyType.ERGO, Role.ERGO_MANAGEMENT, 0L);
		Map<Class<?>, Object> beans = Map.of(RfqApi.class, rfqApi, QuotationApi.class, quotationApi, NpdApi.class,
				npdApi, OfpApi.class, ofpApi, QualityApi.class, qualityApi);
		List<String> failures = new ArrayList<>();

		for (Map.Entry<Class<?>, String> module : MODULES.entrySet()) {
			Class<?> api = module.getKey();
			for (String cap : List.of("V", "NV")) {
				SwitchTestSupport.cap(jdbc, policy, module.getValue(), cap);
				for (Method m : api.getDeclaredMethods()) {
					Rule rule = RULES.get(key(api, m));
					boolean applies = switch (rule) {
						case NOT_FOUND, HIDDEN_EMPTY -> cap.equals("NV");
						case WRITE, FROZEN_EMPTY -> true;
					};
					if (!applies) {
						continue;
					}
					String outcome = call(caller, beans.get(api), m, rule);
					if (outcome != null) {
						failures.add("%s with %s = %s: %s".formatted(key(api, m), module.getValue(), cap, outcome));
					}
				}
			}
			SwitchTestSupport.cap(jdbc, policy, module.getValue(), "O");
		}
		assertThat(failures).isEmpty();
	}

	/** null = as required. */
	private static String call(CurrentUser caller, Object bean, Method method, Rule rule) throws Exception {
		Object result;
		try {
			result = CurrentUser.runAs(caller, () -> invoke(bean, method));
		}
		catch (RuntimeException e) {
			Throwable cause = e.getCause() instanceof InvocationTargetException ite ? ite.getCause() : e;
			if (cause instanceof ApiException api) {
				int expected = rule == Rule.NOT_FOUND ? 404 : rule == Rule.WRITE ? 409 : -1;
				return api.getStatusCode().value() == expected ? null : "threw " + api.getStatusCode().value();
			}
			return "threw " + cause;
		}
		return switch (rule) {
			case NOT_FOUND, WRITE -> "returned " + result + " instead of refusing";
			case HIDDEN_EMPTY, FROZEN_EMPTY -> isNothing(result) ? null : "returned " + result;
		};
	}

	private static Object invoke(Object bean, Method method) {
		try {
			return method.invoke(bean, arguments(method));
		}
		catch (InvocationTargetException e) {
			throw new RuntimeException(e);
		}
		catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Object[] arguments(Method method) {
		return Arrays.stream(method.getParameterTypes()).map(type -> {
			if (type == long.class) {
				return (Object) 1L;
			}
			if (type == int.class) {
				return 0;
			}
			if (type == LocalDate.class) {
				return LocalDate.now();
			}
			if (Collection.class.isAssignableFrom(type)) {
				return List.of(1L);
			}
			if (type == String.class) {
				return "x";
			}
			if (type.isEnum()) {
				return type.getEnumConstants()[0];
			}
			if (type == RfqApi.QuotationSummary.class) {
				return new RfqApi.QuotationSummary(0, 0, 0, 0, 0, 0, 0, 0);
			}
			throw new IllegalStateException("No test argument for " + type);
		}).toArray();
	}

	private static boolean isNothing(Object result) {
		return result == null || Boolean.FALSE.equals(result) || Integer.valueOf(0).equals(result)
				|| result instanceof Optional<?> o && o.isEmpty() || result instanceof Collection<?> c && c.isEmpty()
				|| result instanceof Map<?, ?> m && m.isEmpty();
	}

	private static String key(Class<?> api, Method method) {
		return api.getSimpleName() + "#" + method.getName() + "("
				+ Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName).collect(Collectors.joining(",")) + ")";
	}

	private static void rules(Class<?> api, Rule rule, String... signatures) {
		for (String signature : signatures) {
			RULES.put(api.getSimpleName() + "#" + signature, rule);
		}
	}

}
