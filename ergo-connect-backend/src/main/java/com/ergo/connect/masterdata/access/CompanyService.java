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
import com.ergo.connect.common.event.NotificationKind;
import com.ergo.connect.common.event.Notifier;
import com.ergo.connect.common.event.Recipients;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.masterdata.access.CompanyDtos.CompanyAction;
import com.ergo.connect.masterdata.access.CompanyDtos.CompanyProfile;
import com.ergo.connect.masterdata.access.CompanyDtos.CompanyRequest;
import com.ergo.connect.masterdata.access.CompanyDtos.CompanyResponse;

/**
 * Customer and supplier management (SRS §10.2, §10.3) with the client's decisions (ARCHITECTURE.md §8): customers
 * added by ERGO RFQ or ERGO KAM and every supplier wait for ERGO Management / ERGO Admin approval (a supplier an
 * Admin added: Management only), a supplier is "approved" (ACTIVE) only after that, ERGO KAMs see only their assigned
 * customers, and customers and suppliers maintain their own profile. Every rule below is used both to enforce an
 * action and to list it in {@link CompanyResponse#actions()}, so the UI can never offer what the server refuses.
 */
@Service
@Transactional
public class CompanyService {

	private final CompanyRepository companies;

	private final UserRepository users;

	private final AuditLogService audit;

	private final Notifier notifier;

	CompanyService(CompanyRepository companies, UserRepository users, AuditLogService audit, Notifier notifier) {
		this.companies = companies;
		this.users = users;
		this.audit = audit;
		this.notifier = notifier;
	}

	/**
	 * The companies the caller may see, optionally narrowed. {@code type} and {@code status} only narrow what record
	 * scope already allows; the approved supplier list is {@code type=SUPPLIER&status=ACTIVE}.
	 */
	@Transactional(readOnly = true)
	public List<CompanyResponse> list(CompanyType type, CompanyStatus status) {
		CurrentUser me = CurrentUser.get();
		List<Company> visible = switch (me.role()) {
			// ERGO_QUALITY also creates/tags inspections directly against customers/suppliers with no backing
			// NPD/OFP record (Quality's own INCOMING/FINAL/IN_PROCESS inspection types, security review F2,
			// ARCHITECTURE.md §8) - it needs the same unrestricted company picker ERGO_RFQ gets, not the
			// record-scoped "only companies on my own records" reading originally given to every ERGO functional
			// role here. ERGO_NPD/ERGO_OFP are left as they were (List.of()): unlike Quality, neither module lets
			// its own ERGO role freely tag an arbitrary company onto a record - flagged, not silently widened too.
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_RFQ -> companies.findAllByOrderByNameAsc();
			// Quality only picks companies it can work with: a pending or rejected company is not one (client 2026-10-21)
			case ERGO_QUALITY -> companies.findAllByOrderByNameAsc()
				.stream()
				.filter(c -> c.status != PENDING_APPROVAL && c.status != REJECTED)
				.toList();
			case ERGO_KAM -> companies.findByIdInOrderByNameAsc(accounts(me));
			// SRS "RV": the companies on their own NPD / OFP records
			case ERGO_NPD, ERGO_OFP -> List.of();
			default -> companies.findById(me.companyId()).stream().toList(); // customers and suppliers: their own
		};
		return visible.stream()
			.filter(company -> type == null || company.type == type)
			.filter(company -> status == null || company.status == status)
			.map(company -> toResponse(company, me))
			.toList();
	}

	@Transactional(readOnly = true)
	public CompanyResponse get(long id) {
		CurrentUser me = CurrentUser.get();
		return toResponse(visible(me, id), me);
	}

	/** Non-throwing record-scope probe for other modules (notification delivery runs it as the recipient). */
	@Transactional(readOnly = true)
	public boolean visibleToCaller(long id) {
		try {
			visible(CurrentUser.get(), id);
			return true;
		}
		catch (ApiException e) {
			return false;
		}
	}

	/**
	 * Every supplier waits for approval, whoever adds it (client decision 2026-10-21); one added by an ERGO Admin can
	 * be approved by ERGO Management only. A customer is active at once when ERGO Admin adds it, and pending when ERGO
	 * RFQ or ERGO KAM does.
	 */
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
		boolean adminAddedCustomer = me.role() == Role.ERGO_ADMIN && request.type() == CompanyType.CUSTOMER;
		company.status = adminAddedCustomer ? ACTIVE : PENDING_APPROVAL;
		company.createdByUserId = me.userId();
		apply(company, request.profile());
		companies.save(company);
		if (company.status == PENDING_APPROVAL) {
			notifyReviewers(company);
		}
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
		notifyReviewers(company);
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
			throw ApiException.unprocessable(company.type == CompanyType.SUPPLIER
					? "Suppliers are approved or rejected by ERGO Management only"
					: "You cannot approve or reject a company you added yourself");
		}
		if (comment != null && !comment.isBlank()) {
			audit.reason(comment.trim()); // stored with the status change: approver, time, old + new status, comment
		}
		company.status = outcome;
		if (company.createdByUserId != null) {
			notifier.send(NotificationKind.COMPANY_APPROVAL_DECIDED, "company", company.id, company.name,
					outcome == ACTIVE ? "approved" : "rejected", Recipients.users(Set.of(company.createdByUserId)));
		}
		return toResponse(company, me);
	}

	/** The people who can review hear that a company awaits it: Management for a supplier, Admin too for a customer. */
	private void notifyReviewers(Company company) {
		Recipients to = company.type == CompanyType.SUPPLIER ? Recipients.ergo(Role.ERGO_MANAGEMENT)
				: Recipients.ergo(Role.ERGO_MANAGEMENT, Role.ERGO_ADMIN);
		notifier.send(NotificationKind.COMPANY_APPROVAL_REQUESTED, "company", company.id, company.name,
				company.type.name().toLowerCase(), to);
	}

	/** Record scope (SRS §10.2/§10.3 view column): out-of-scope companies do not exist for the caller. */
	private Company visible(CurrentUser me, long id) {
		boolean inScope = switch (me.role()) {
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_RFQ, ERGO_QUALITY -> true; // ERGO_QUALITY: see list() above (F2)
			case ERGO_KAM -> accounts(me).contains(id);
			case ERGO_NPD, ERGO_OFP -> false;
			default -> id == me.companyId();
		};
		return (inScope ? companies.findById(id) : Optional.<Company>empty())
			.filter(c -> me.role() != Role.ERGO_QUALITY || (c.status != PENDING_APPROVAL && c.status != REJECTED))
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

	/**
	 * Approve / reject: ERGO Management, and ERGO Admin too - except for a supplier an Admin added, which only
	 * Management may review. Nobody reviews a company they added themselves.
	 */
	private static boolean canReview(CurrentUser me, Company company) {
		boolean reviewer = me.role() == Role.ERGO_MANAGEMENT
				|| (me.role() == Role.ERGO_ADMIN && company.type != CompanyType.SUPPLIER);
		return reviewer && !Objects.equals(company.createdByUserId, me.userId());
	}

	/** ERGO Admin adds accounts to any approved company; ERGO RFQ only to an approved supplier. */
	private static boolean canAddUser(CurrentUser me, Company company) {
		return company.status == ACTIVE && (me.role() == Role.ERGO_ADMIN
				|| (me.role() == Role.ERGO_RFQ && company.type == CompanyType.SUPPLIER));
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
		if (canAddUser(me, c)) {
			actions.add(CompanyAction.ADD_USER);
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
