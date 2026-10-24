package com.ergo.connect.common.security;

import static com.ergo.connect.common.security.ModuleAccess.Level.A;
import static com.ergo.connect.common.security.ModuleAccess.Level.CO;
import static com.ergo.connect.common.security.ModuleAccess.Level.NONE;
import static com.ergo.connect.common.security.ModuleAccess.Level.O;
import static com.ergo.connect.common.security.ModuleAccess.Level.V;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.admin.service.PermissionMatrix;
import com.ergo.connect.admin.service.PermissionMatrix.Grant;
import com.ergo.connect.common.security.ModuleAccess.Level;
import com.ergo.connect.common.security.ModuleAccess.Module;

/** The seeded role access sheet (V25) must equal the signed-off sheet (client's "role access.xlsx", 2026-10-19), transcribed independently of {@link ModuleAccess}. */
@IntegrationTest
class ModuleAccessTests {

	/** RFQ, QUOTATION, NPD, OFP, QUALITY - read straight off the spreadsheet, row by row. */
	private static final Map<Role, Level[]> SHEET = new EnumMap<>(Role.class);

	static {
		SHEET.put(Role.ERGO_ADMIN, new Level[] { V, V, V, V, V });
		SHEET.put(Role.ERGO_MANAGEMENT, new Level[] { O, O, O, O, O });
		SHEET.put(Role.ERGO_KAM, new Level[] { A, V, A, A, V }); // NPD: A to decide Sample POs (flow doc step 7); OFP: A to decide production POs (step 11)
		SHEET.put(Role.ERGO_RFQ, new Level[] { O, O, V, V, V });
		SHEET.put(Role.ERGO_NPD, new Level[] { V, V, O, V, V });
		SHEET.put(Role.ERGO_OFP, new Level[] { NONE, NONE, V, O, A });
		SHEET.put(Role.ERGO_QUALITY, new Level[] { NONE, NONE, A, A, O });
		SHEET.put(Role.CUSTOMER_RFQ, new Level[] { CO, CO, V, NONE, V });
		SHEET.put(Role.CUSTOMER_NPD, new Level[] { V, NONE, CO, NONE, CO });
		SHEET.put(Role.CUSTOMER_OFP, new Level[] { NONE, NONE, NONE, CO, V });
		SHEET.put(Role.CUSTOMER_QUALITY, new Level[] { V, NONE, A, V, CO });
		SHEET.put(Role.CUSTOMER_KAM, new Level[] { A, A, V, V, V });
		SHEET.put(Role.CUSTOMER_MANAGEMENT, new Level[] { A, A, A, A, A });
		SHEET.put(Role.SUPPLIER_RFQ, new Level[] { A, A, V, NONE, NONE }); // NPD: V, the plan is assigned to it too (flow doc step 7)
		SHEET.put(Role.SUPPLIER_NPD, new Level[] { NONE, NONE, A, NONE, V });
		SHEET.put(Role.SUPPLIER_OFP, new Level[] { NONE, NONE, NONE, A, V });
		SHEET.put(Role.SUPPLIER_QUALITY, new Level[] { V, NONE, A, A, A });
		SHEET.put(Role.SUPPLIER_KAM, new Level[] { A, A, A, A, A });
	}

	@Test
	void everyRoleAndModuleMatchesTheSheet() {
		assertThat(SHEET.keySet()).containsExactlyInAnyOrder(Role.values());
		for (Role role : Role.values()) {
			for (Module module : Module.values()) {
				assertThat(ModuleAccess.level(role, module)).as("%s on %s", role, module)
					.isEqualTo(SHEET.get(role)[module.ordinal()]);
			}
		}
	}

	@Test
	void legendMeaning() {
		assertThat(NONE.canView()).isFalse();
		assertThat(V.canView()).isTrue();
		assertThat(V.canAct()).isFalse();
		assertThat(A.canAct()).isTrue();
		assertThat(A.canApprove()).isFalse();
		assertThat(O.canAct()).isTrue();
		assertThat(O.canApprove()).isTrue();
		assertThat(CO.canApprove()).isTrue();
	}

	@Test
	void derivedRoleSets() {
		// ERGO Admin is view-only on the business modules: it can approve nothing and create nothing there
		for (Module module : Module.values()) {
			assertThat(ModuleAccess.canAct(Role.ERGO_ADMIN, module)).isFalse();
			assertThat(ModuleAccess.canApprove(Role.ERGO_ADMIN, module)).isFalse();
		}
		assertThat(ModuleAccess.approvers(Module.RFQ, CompanyType.ERGO)).containsExactlyInAnyOrder(Role.ERGO_MANAGEMENT,
				Role.ERGO_RFQ);
		assertThat(ModuleAccess.approvers(Module.NPD)).containsExactlyInAnyOrder(Role.ERGO_MANAGEMENT, Role.ERGO_NPD,
				Role.CUSTOMER_NPD);
		assertThat(ModuleAccess.actors(Module.QUOTATION, CompanyType.SUPPLIER)).containsExactlyInAnyOrder(
				Role.SUPPLIER_RFQ, Role.SUPPLIER_KAM);
		assertThat(ModuleAccess.viewers(Module.RFQ, CompanyType.SUPPLIER)).containsExactlyInAnyOrder(Role.SUPPLIER_RFQ,
				Role.SUPPLIER_KAM, Role.SUPPLIER_QUALITY);
		assertThat(ModuleAccess.viewers(Module.OFP, CompanyType.CUSTOMER)).containsExactlyInAnyOrder(Role.CUSTOMER_OFP,
				Role.CUSTOMER_QUALITY, Role.CUSTOMER_KAM, Role.CUSTOMER_MANAGEMENT);
		assertThat(ModuleAccess.viewers(Module.QUALITY)).doesNotContain(Role.SUPPLIER_RFQ);
	}

	@Test
	void permissionMappingScreenShowsTheSheet() {
		Map<Module, String> sections = new EnumMap<>(Module.class);
		sections.put(Module.RFQ, "10.4");
		sections.put(Module.QUOTATION, "10.5");
		sections.put(Module.NPD, "10.6");
		sections.put(Module.OFP, "10.7");
		sections.put(Module.QUALITY, "10.8");
		sections.forEach((module, section) -> {
			PermissionMatrix.Module table = PermissionMatrix.MODULES.stream()
				.filter(m -> m.section().equals(section))
				.findFirst()
				.orElseThrow();
			assertThat(table.roles()).hasSize(Role.values().length);
			for (PermissionMatrix.RoleGrant grant : table.roles()) {
				Level level = ModuleAccess.level(grant.role(), module);
				assertThat(grant.view().value() != Grant.X).as("%s view on %s", grant.role(), module)
					.isEqualTo(level.canView());
				assertThat(grant.create().value() == Grant.C).as("%s create on %s", grant.role(), module)
					.isEqualTo(level.canAct());
				assertThat(grant.approve().value() == Grant.A).as("%s approve on %s", grant.role(), module)
					.isEqualTo(level.canApprove());
			}
		});
	}

}
