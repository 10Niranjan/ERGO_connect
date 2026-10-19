package com.ergo.connect.common.security;

import org.springframework.stereotype.Component;

import com.ergo.connect.common.security.ModuleAccess.Module;

/**
 * Layer 1 of access control for {@code @PreAuthorize}: {@code @PreAuthorize("@access.view('RFQ')")}. Reads the
 * caller's one active role from the verified token and asks {@link ModuleAccess}, so the role sheet lives in exactly
 * one place. The optional second argument limits the check to one side ({@code 'ERGO'}, {@code 'CUSTOMER'},
 * {@code 'SUPPLIER'}), for endpoints that are only meant for that side whatever the sheet says about the others.
 */
@Component("access")
public class AccessGuard {

	public boolean view(String module) {
		return check(module, null, ModuleAccess.Level::canView);
	}

	public boolean view(String module, String side) {
		return check(module, side, ModuleAccess.Level::canView);
	}

	public boolean act(String module) {
		return check(module, null, ModuleAccess.Level::canAct);
	}

	public boolean act(String module, String side) {
		return check(module, side, ModuleAccess.Level::canAct);
	}

	public boolean approve(String module) {
		return check(module, null, ModuleAccess.Level::canApprove);
	}

	public boolean approve(String module, String side) {
		return check(module, side, ModuleAccess.Level::canApprove);
	}

	private static boolean check(String module, String side, java.util.function.Predicate<ModuleAccess.Level> test) {
		return CurrentUser.find().map(me -> (side == null || me.companyType() == CompanyType.valueOf(side))
				&& test.test(ModuleAccess.level(me.role(), Module.valueOf(module)))).orElse(false);
	}

}
