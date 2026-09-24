package com.ergo.connect.masterdata.access;

import static com.ergo.connect.masterdata.access.CompanyStatus.ACTIVE;
import static com.ergo.connect.masterdata.access.CompanyStatus.INACTIVE;
import static com.ergo.connect.masterdata.access.CompanyStatus.PENDING_APPROVAL;
import static com.ergo.connect.masterdata.access.CompanyStatus.REJECTED;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.masterdata.access.CompanyDtos.CompanyAction;
import com.ergo.connect.masterdata.access.CompanyDtos.CompanyProfile;
import com.ergo.connect.masterdata.access.CompanyDtos.CompanyRequest;
import com.ergo.connect.masterdata.access.CompanyDtos.CompanyResponse;

/**
 * Customer and supplier management (SRS §10.2, §10.3) with the client's decisions (ARCHITECTURE.md §8): companies
 * added by ERGO RFQ or ERGO KAM wait for ERGO Admin / ERGO Management approval, ERGO KAMs see only their assigned
 * customers, and customers and suppliers maintain their own profile. Every rule below is used both to enforce an
 * action and to list it in {@link CompanyResponse#actions()}, so the UI can never offer what the server refuses.
 */
@Service
@Transactional
public class CompanyService {

	private final CompanyRepository companies;

	private final UserRepository users;

	private final AuditLogService audit;

	CompanyService(CompanyRepository companies, UserRepository users, AuditLogService audit) {
		this.companies = companies;
		this.users = users;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<CompanyResponse> list() {
		CurrentUser me = CurrentUser.get();
		List<Company> visible = switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_RFQ -> companies.findAllByOrderByNameAsc();
			case ERGO_KAM -> companies.findByIdInOrderByNameAsc(accounts(me));
			// SRS "RV": the companies on their own NPD / OFP / quality records, which arrive in Phases 3-5
			case ERGO_NPD, ERGO_OFP, ERGO_QUALITY -> List.of();
			default -> companies.findById(me.companyId()).stream().toList(); // customers and suppliers: their own
		};
		return visible.stream().map(company -> toResponse(company, me)).toList();
	}

	@Transactional(readOnly = true)
	public CompanyResponse get(long id) {
		CurrentUser me = CurrentUser.get();
		return toResponse(visible(me, id), me);
	}

	/** ERGO Admin: active at once. ERGO RFQ (customers, suppliers) and ERGO KAM (customers): pending approval. */
	public CompanyResponse create(CompanyRequest request) {
		CurrentUser me = CurrentUser.get();
		if (request.type() == CompanyType.ERGO) {
			throw ApiException.unprocessable("The ERGO company is created at system setup and cannot be added here");
		}
		if (me.role() == Role.ERGO_KAM && request.type() != CompanyType.CUSTOMER) {
			throw ApiException.forbidden("Key Account Managers can add customers only");
		}
		if (companies.existsByCodeIgnoreCase(request.code())) {
			throw ApiException.conflict("A company with this code already exists");
		}
		Company company = new Company();
		company.code = request.code();
		company.type = request.type();
		company.status = me.role() == Role.ERGO_ADMIN ? ACTIVE : PENDING_APPROVAL;
		company.createdByUserId = me.userId();
		apply(company, request.profile());
		companies.save(company);
		if (me.role() == Role.ERGO_KAM) { // otherwise the KAM could not see what they just added
			User kam = users.findById(me.userId()).orElseThrow();
			kam.accountIds.add(company.id);
			audit.record(User.class, kam.id, "ACCOUNT_ASSIGNED", "accounts", null, String.valueOf(company.id));
		}
		return toResponse(company, me);
	}

	public CompanyResponse update(long id, CompanyProfile profile) {
		CurrentUser me = CurrentUser.get();
		Company company = visible(me, id);
		if (!canEdit(me, company)) {
			throw ApiException.forbidden("You cannot edit this company");
		}
		apply(company, profile);
		return toResponse(company, me);
	}

	public CompanyResponse approve(long id, String comment) {
		return review(id, comment, ACTIVE);
	}

	public CompanyResponse reject(long id, String comment) {
		if (comment == null || comment.isBlank()) {
			throw ApiException.unprocessable("A comment is required to reject a company");
		}
		return review(id, comment, REJECTED);
	}

	/** After fixing what the reviewer rejected, an ERGO editor sends the company back for approval. */
	public CompanyResponse resubmit(long id) {
		CurrentUser me = CurrentUser.get();
		Company company = visible(me, id);
		if (!canResubmit(me, company)) {
			throw ApiException.conflict("Only rejected companies can be resubmitted, by someone who can edit them");
		}
		company.status = PENDING_APPROVAL;
		return toResponse(company, me);
	}

	/** ERGO Admin only (enforced on the controller). Users of an inactive company can no longer sign in. */
	public CompanyResponse setActive(long id, boolean active) {
		CurrentUser me = CurrentUser.get();
		Company company = visible(me, id);
		if (company.type == CompanyType.ERGO) {
			throw ApiException.unprocessable("The ERGO company cannot be deactivated");
		}
		if (company.status != (active ? INACTIVE : ACTIVE)) {
			throw ApiException.conflict(active ? "Only inactive companies can be activated"
					: "Only active companies can be deactivated");
		}
		company.status = active ? ACTIVE : INACTIVE;
		return toResponse(company, me);
	}

	private CompanyResponse review(long id, String comment, CompanyStatus outcome) {
		CurrentUser me = CurrentUser.get();
		Company company = visible(me, id);
		if (company.status != PENDING_APPROVAL) {
			throw ApiException.conflict("Only companies pending approval can be approved or rejected");
		}
		if (!canReview(me, company)) {
			throw ApiException.unprocessable("You cannot approve or reject a company you added yourself");
		}
		if (comment != null && !comment.isBlank()) {
			audit.reason(comment.trim()); // stored with the status change: approver, time, old + new status, comment
		}
		company.status = outcome;
		return toResponse(company, me);
	}

	/** Record scope (SRS §10.2/§10.3 view column): out-of-scope companies do not exist for the caller. */
	private Company visible(CurrentUser me, long id) {
		boolean inScope = switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_RFQ -> true;
			case ERGO_KAM -> accounts(me).contains(id);
			case ERGO_NPD, ERGO_OFP, ERGO_QUALITY -> false;
			default -> id == me.companyId();
		};
		return (inScope ? companies.findById(id) : Optional.<Company>empty())
			.orElseThrow(() -> ApiException.notFound("Company"));
	}

	/** SRS §10.2/§10.3 edit column. Visibility is checked first, which already limits KAMs and external users. */
	private static boolean canEdit(CurrentUser me, Company company) {
		return switch (me.role()) {
			case ERGO_ADMIN -> true;
			case ERGO_RFQ -> company.type != CompanyType.ERGO;
			case ERGO_KAM -> company.type == CompanyType.CUSTOMER;
			case CUSTOMER_RFQ, CUSTOMER_KAM, CUSTOMER_MANAGEMENT, SUPPLIER_RFQ, SUPPLIER_KAM -> true;
			default -> false;
		};
	}

	/** Approve / reject: ERGO Admin and ERGO Management, never on a company they added themselves. */
	private static boolean canReview(CurrentUser me, Company company) {
		return (me.role() == Role.ERGO_ADMIN || me.role() == Role.ERGO_MANAGEMENT)
				&& !Objects.equals(company.createdByUserId, me.userId());
	}

	private static boolean canResubmit(CurrentUser me, Company company) {
		return company.status == REJECTED && me.companyType() == CompanyType.ERGO && canEdit(me, company);
	}

	private Set<Long> accounts(CurrentUser me) {
		return users.findById(me.userId()).map(user -> Set.copyOf(user.accountIds)).orElse(Set.of());
	}

	private static void apply(Company company, CompanyProfile profile) {
		company.name = profile.name().trim();
		company.addressLine1 = profile.addressLine1();
		company.addressLine2 = profile.addressLine2();
		company.city = profile.city();
		company.state = profile.state();
		company.country = profile.country();
		company.postalCode = profile.postalCode();
		company.taxId = profile.taxId();
		company.website = profile.website();
		company.primaryContactName = profile.primaryContactName();
		company.primaryContactEmail = profile.primaryContactEmail();
		company.primaryContactPhone = profile.primaryContactPhone();
	}

	private static CompanyResponse toResponse(Company c, CurrentUser me) {
		Set<CompanyAction> actions = EnumSet.noneOf(CompanyAction.class);
		if (canEdit(me, c)) {
			actions.add(CompanyAction.EDIT);
		}
		if (c.status == PENDING_APPROVAL && canReview(me, c)) {
			actions.add(CompanyAction.APPROVE);
			actions.add(CompanyAction.REJECT);
		}
		if (canResubmit(me, c)) {
			actions.add(CompanyAction.RESUBMIT);
		}
		if (me.role() == Role.ERGO_ADMIN && c.type != CompanyType.ERGO) {
			if (c.status == ACTIVE) {
				actions.add(CompanyAction.DEACTIVATE);
			}
			if (c.status == INACTIVE) {
				actions.add(CompanyAction.ACTIVATE);
			}
		}
		CompanyProfile profile = new CompanyProfile(c.name, c.addressLine1, c.addressLine2, c.city, c.state, c.country,
				c.postalCode, c.taxId, c.website, c.primaryContactName, c.primaryContactEmail, c.primaryContactPhone);
		return new CompanyResponse(c.id, c.code, c.type, c.status, profile, actions);
	}

}
