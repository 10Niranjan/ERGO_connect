package com.ergo.connect.npd.domain;

import static com.ergo.connect.common.security.Role.ERGO_KAM;
import static com.ergo.connect.common.security.Role.SUPPLIER_NPD;
import static com.ergo.connect.common.security.Role.SUPPLIER_OFP;

import java.util.Set;

import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.ModuleAccess;
import com.ergo.connect.common.security.ModuleAccess.Module;
import com.ergo.connect.common.security.ModuleSwitch;
import com.ergo.connect.common.security.Role;

/**
 * Who may do what on a project, from the role sheet (live): the NPD column for a sample project; for a production (OFP)
 * project the NPD column and the OFP column together, because the flow adds the OFP teams to the same workflow. Which
 * rows a role reaches (its own company, assigned accounts) stays NpdService's record scope.
 */
final class NpdRights {

	static final NpdRights SAMPLE = new NpdRights(ProjectKind.SAMPLE, Module.NPD);

	static final NpdRights PRODUCTION = new NpdRights(ProjectKind.PRODUCTION, Module.NPD, Module.OFP);

	private final ProjectKind kind;

	private final Module[] modules;

	/** ERGO roles that create / edit / submit ("A" and up), except the KAM: its "A" is for deciding POs and creating the
	 * project, not for editing a project or working a customer's or supplier's steps on their behalf. */
	final Set<Role> editors;

	/** ERGO's owning roles ("O"): release the plan, agree it, close a stage, decide the ERGO-owned approvals. */
	final Set<Role> owners;

	final Set<Role> customerEditors;

	final Set<Role> supplierEditors;

	final Set<Role> customerViewers;

	final Set<Role> supplierViewers;

	/** Every ERGO role with access except the account-scoped KAM. */
	final Set<Role> fullViewers;

	/** Roles that give a final approval on the project (an ERGO owner, the customer's approving role). */
	final Set<Role> approvers;

	/** Supplier roles that submit the plan: Supplier NPD, and on a production project Supplier OFP as well. */
	final Set<Role> planSubmitters;

	private NpdRights(ProjectKind kind, Module... modules) {
		this.kind = kind;
		this.modules = modules;
		this.editors = union(m -> ModuleAccess.actorsExcept(m, CompanyType.ERGO, ERGO_KAM));
		this.owners = union(m -> ModuleAccess.approvers(m, CompanyType.ERGO));
		this.customerEditors = union(m -> ModuleAccess.actors(m, CompanyType.CUSTOMER));
		this.supplierEditors = union(m -> ModuleAccess.actors(m, CompanyType.SUPPLIER));
		this.customerViewers = union(m -> ModuleAccess.viewers(m, CompanyType.CUSTOMER));
		this.supplierViewers = union(m -> ModuleAccess.viewers(m, CompanyType.SUPPLIER));
		this.fullViewers = union(m -> ModuleAccess.viewersExcept(m, CompanyType.ERGO, ERGO_KAM));
		this.approvers = union(m -> m == Module.NPD ? ModuleAccess.approvers(m) : ModuleAccess.approvers(m, CompanyType.ERGO));
		this.planSubmitters = kind == ProjectKind.PRODUCTION ? Set.of(SUPPLIER_NPD, SUPPLIER_OFP) : Set.of(SUPPLIER_NPD);
	}

	static NpdRights of(NpdProject project) {
		return project.kind == ProjectKind.PRODUCTION ? PRODUCTION : SAMPLE;
	}

	/** The role has any view access to the project's modules. */
	boolean canView(Role role) {
		return anyModule(m -> ModuleAccess.canView(role, m));
	}

	/** The role acts (A and up) in one of the project's modules. */
	boolean canAct(Role role) {
		return anyModule(m -> ModuleAccess.canAct(role, m));
	}

	/** Every module the project belongs to accepts changes: NPD, and OFP too for a production project. */
	boolean writable() {
		for (Module m : modules) {
			if (!ModuleSwitch.writable(m)) {
				return false;
			}
		}
		return true;
	}

	ProjectKind kind() {
		return kind;
	}

	private interface PerModule {

		Set<Role> roles(Module module);

	}

	private boolean anyModule(java.util.function.Predicate<Module> test) {
		for (Module m : modules) {
			if (test.test(m)) {
				return true;
			}
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	private Set<Role> union(PerModule perModule) {
		Set<Role>[] sets = new Set[modules.length];
		for (int i = 0; i < modules.length; i++) {
			sets[i] = perModule.roles(modules[i]);
		}
		return ModuleAccess.union(sets);
	}

}
