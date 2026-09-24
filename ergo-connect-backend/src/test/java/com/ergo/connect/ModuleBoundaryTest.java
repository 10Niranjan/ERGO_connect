package com.ergo.connect;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import java.util.Set;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/** ARCHITECTURE.md §1.1: a business module may touch another module only through that module's {@code api} package. */
@AnalyzeClasses(packages = "com.ergo.connect", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

	private static final String BASE = "com.ergo.connect.";

	// common/ is the shared kernel and is open to everyone, so it is not listed.
	private static final Set<String> MODULES = Set.of("masterdata", "rfq", "quotation", "npd", "ofp", "quality",
			"documents", "communication", "dashboard", "admin");

	@ArchTest
	static final ArchRule modulesTalkOnlyThroughApi = classes()
		.should(new ArchCondition<>("depend on other modules only through their api package") {
			@Override
			public void check(JavaClass clazz, ConditionEvents events) {
				String from = moduleOf(clazz.getPackageName());
				if (from == null) {
					return;
				}
				for (Dependency dep : clazz.getDirectDependenciesFromSelf()) {
					String pkg = dep.getTargetClass().getPackageName();
					String to = moduleOf(pkg);
					String api = BASE + to + ".api";
					if (to != null && !to.equals(from) && !pkg.equals(api) && !pkg.startsWith(api + ".")) {
						events.add(SimpleConditionEvent.violated(dep, dep.getDescription()));
					}
				}
			}
		})
		.allowEmptyShould(true);

	private static String moduleOf(String pkg) {
		if (!pkg.startsWith(BASE)) {
			return null;
		}
		String top = pkg.substring(BASE.length()).split("\\.")[0];
		return MODULES.contains(top) ? top : null;
	}

}
