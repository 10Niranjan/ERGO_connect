package com.ergo.connect.masterdata.access;

import static com.ergo.connect.masterdata.access.CompanyStatus.ACTIVE;
import static com.ergo.connect.masterdata.access.CompanyStatus.DRAFT;
import static com.ergo.connect.masterdata.access.CompanyStatus.INACTIVE;
import static com.ergo.connect.masterdata.access.CompanyStatus.INFO_REQUESTED;
import static com.ergo.connect.masterdata.access.CompanyStatus.PENDING_APPROVAL;
import static com.ergo.connect.masterdata.access.CompanyStatus.REJECTED;

import java.util.ArrayList;
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
import com.ergo.connect.masterdata.access.CompanyDtos.CompanyView;
import com.ergo.connect.masterdata.access.CompanyDtos.ErgoCompanyResponse;

/**
 * Customer and supplier management (SRS §10.2, §10.3) with the client's registration decisions (ARCHITECTURE.md §8,
 * 2026-11-23): every new company starts as a DRAFT that an ERGO editor sends for approval once the minimum
 * information is in; ERGO Management alone approves, rejects or asks for more information; ERGO KAMs see only their
 * assigned customers, and customers and suppliers maintain their own basic profile. Every rule below is used both to
 * enforce an action and to list it in the response's {@code actions}, so the UI can never offer what the server
 * refuses.
 */
@Service
@Transactional
public class CompanyService {

	/** Statuses ERGO Quality works with: a company still being registered or reviewed is not one (client 2026-10-21). */
	private static final Set<CompanyStatus> QUALITY_VISIBLE = EnumSet.of(ACTIVE, INACTIVE);

	/** A registration can be sent for approval from these. */
	private static final Set<CompanyStatus> SENDABLE = EnumSet.of(DRAFT, INFO_REQUESTED, REJECTED);

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
	public List<CompanyView> list(CompanyType type, CompanyStatus status) {
		CurrentUser me = CurrentUser.get();
		List<Company> visible = switch (me.role()) {
			// ERGO_QUALITY also creates/tags inspections directly against customers/suppliers with no backing
			// NPD/OFP record (Quality's own INCOMING/FINAL/IN_PROCESS inspection types, security review F2,
			// ARCHITECTURE.md §8) - it needs the same unrestricted company picker ERGO_RFQ gets, not the
			// record-scoped "only companies on my own records" reading originally given to every ERGO functional
			// role here. ERGO_NPD/ERGO_OFP are left as they were (List.of()): unlike Quality, neither module lets
			// its own ERGO role freely tag an arbitrary company onto a record - flagged, not silently widened too.
			case ERGO_ADMIN, ERGO_MANAGEMENT, ERGO_RFQ -> companies.findAllByOrderByNameAsc();
			// Quality only picks companies it can work with (client 2026-10-21)
			case ERGO_QUALITY -> companies.findAllByOrderByNameAsc()
				.stream()
				.filter(c -> QUALITY_VISIBLE.contains(c.status))
				.toList();
			case ERGO_KAM -> companies.findByIdInOrderByNameAsc(accounts(me));
			// SRS "RV": the companies on their own NPD / OFP records
			case ERGO_NPD, ERGO_OFP -> List.of();
			default -> companies.findById(me.companyId()).stream().toList(); // customers and suppliers: their own
		};
		return visible.stream()
			.filter(company -> type == null || company.type == type)
			.filter(company -> status == null || company.status == status)
			.map(company -> toView(company, me))
			.toList();
	}

	@Transactional(readOnly = true)
	public CompanyView get(long id) {
		CurrentUser me = CurrentUser.get();
		return toView(visible(me, id), me);
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
	 * Every new company starts as a DRAFT, whoever adds it (client question 10: also a customer the ERGO Admin adds),
	 * and gets the next automatic number (question 27). Nobody is notified until it is sent for approval.
	 */
	public CompanyView create(CompanyRequest request) {
		CurrentUser me = CurrentUser.get();
		if (request.type() == CompanyType.ERGO) {
			throw ApiException.unprocessable("The ERGO company is created at system setup and cannot be added here");
		}
		if (me.role() == Role.ERGO_KAM && request.type() != CompanyType.CUSTOMER) {
			throw ApiException.forbidden("Key Account Managers can add customers only");
		}
		Company company = new Company();
		company.code = nextCode(request.type());
		company.type = request.type();
		company.status = DRAFT;
		company.createdByUserId = me.userId();
		apply(company, request.profile(), me);
		companies.save(company);
		if (me.role() == Role.ERGO_KAM) { // otherwise the KAM could not see what they just added
			User kam = users.findById(me.userId()).orElseThrow();
			kam.accountIds.add(company.id);
			audit.record(User.class, kam.id, "ACCOUNT_ASSIGNED", "accounts", null, String.valueOf(company.id));
		}
		return toView(company, me);
	}

	public CompanyView update(long id, CompanyProfile profile) {
		CurrentUser me = CurrentUser.get();
		Company company = visible(me, id);
		if (!canEdit(me, company)) {
			throw ApiException.forbidden("You cannot edit this company");
		}
		// Management decides on exactly what was sent: no changes while it waits (security review L1)
		if (company.status == PENDING_APPROVAL) {
			throw ApiException.conflict("The company is waiting for ERGO Management's decision and cannot be changed now");
		}
		apply(company, profile, me);
		return toView(company, me);
	}

	/**
	 * A draft, a company sent back for more information or a rejected one goes (again) to ERGO Management, once the
	 * minimum information is filled in (client questions 15 and 16).
	 */
	public CompanyView sendForApproval(long id) {
		CurrentUser me = CurrentUser.get();
		Company company = visible(me, id);
		if (!SENDABLE.contains(company.status) || !canSend(me, company)) {
			throw ApiException.conflict(
					"Only a draft, a company sent back for more information or a rejected one can be sent for approval, "
							+ "by someone in ERGO who can edit it");
		}
		List<String> missing = missing(company);
		if (!missing.isEmpty()) {
			throw ApiException.unprocessable("Fill in before sending for approval: " + String.join(", ", missing));
		}
		company.status = PENDING_APPROVAL;
		company.submittedByUserId = me.userId();
		notifier.send(NotificationKind.COMPANY_APPROVAL_REQUESTED, "company", company.id, company.name,
				company.type.name().toLowerCase(), Recipients.ergo(Role.ERGO_MANAGEMENT));
		return toView(company, me);
	}

	public CompanyView approve(long id, String comment) {
		return review(id, comment, ACTIVE);
	}

	public CompanyView reject(long id, String comment) {
		if (comment == null || comment.isBlank()) {
			throw ApiException.unprocessable("A comment is required to reject a company");
		}
		return review(id, comment, REJECTED);
	}

	/** Management sends the company back to the person who registered it with a question (client question 13). */
	public CompanyView requestInfo(long id, String comment) {
		if (comment == null || comment.isBlank()) {
			throw ApiException.unprocessable("Write what information is needed");
		}
		return review(id, comment, INFO_REQUESTED);
	}

	/** ERGO Admin only (enforced on the controller). Users of an inactive company can no longer sign in. */
	public CompanyView setActive(long id, boolean active) {
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
		return toView(company, me);
	}

	private CompanyView review(long id, String comment, CompanyStatus outcome) {
		CurrentUser me = CurrentUser.get();
		Company company = visible(me, id);
		if (company.status != PENDING_APPROVAL) {
			throw ApiException.conflict("Only companies pending approval can be reviewed");
		}
		if (!canReview(me, company)) {
			throw ApiException.unprocessable("You cannot review a company you registered or sent for approval yourself");
		}
		String trimmed = comment == null || comment.isBlank() ? null : comment.trim();
		if (trimmed != null) {
			audit.reason(trimmed); // stored with the status change: approver, time, old + new status, comment
		}
		company.status = outcome;
		company.reviewComment = trimmed;
		if (company.createdByUserId != null) {
			String decision = switch (outcome) {
				case ACTIVE -> "approved";
				case REJECTED -> "rejected";
				default -> "sent back for more information";
			};
			notifier.send(NotificationKind.COMPANY_APPROVAL_DECIDED, "company", company.id, company.name, decision,
					Recipients.users(Set.of(company.createdByUserId)));
		}
		return toView(company, me);
	}

	/**
	 * The minimum information agreed with the client (question 15) that a company still lacks. Business type, a
	 * supplier's processes and its audit report join this list with the registration tabs (steps 2 and 3).
	 */
	static List<String> missing(Company c) {
		List<String> missing = new ArrayList<>();
		if (blank(c.country)) {
			missing.add("country");
		}
		if (blank(c.addressLine1) || blank(c.city)) {
			missing.add("address");
		}
		if (blank(c.taxId)) {
			missing.add("registration or tax number");
		}
		if (blank(c.primaryContactName) || blank(c.primaryContactEmail)) {
			missing.add("main contact name and email");
		}
		return missing;
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	/** CUS-00001 / SUP-00001; skips a number some older company already uses as its typed-in code. */
	private String nextCode(CompanyType type) {
		String code;
		do {
			code = type == CompanyType.CUSTOMER ? "CUS-%05d".formatted(companies.nextCustomerNumber())
					: "SUP-%05d".formatted(companies.nextSupplierNumber());
		}
		while (companies.existsByCodeIgnoreCase(code));
		return code;
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
			.filter(c -> me.role() != Role.ERGO_QUALITY || QUALITY_VISIBLE.contains(c.status))
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

	/** Sending for approval: anyone in ERGO who may edit the company (who registers it, client questions 4-6). */
	private static boolean canSend(CurrentUser me, Company company) {
		return me.companyType() == CompanyType.ERGO && canEdit(me, company);
	}

	/**
	 * Approve / reject / ask for more information: ERGO Management alone (client question 11), never by the person who
	 * registered the company or sent it for approval (one user may hold both ERGO RFQ and ERGO Management).
	 */
	private static boolean canReview(CurrentUser me, Company company) {
		return me.role() == Role.ERGO_MANAGEMENT && !Objects.equals(company.createdByUserId, me.userId())
				&& !Objects.equals(company.submittedByUserId, me.userId());
	}

	/** ERGO Admin adds accounts to any approved company; ERGO RFQ only to an approved supplier. */
	private static boolean canAddUser(CurrentUser me, Company company) {
		return company.status == ACTIVE && (me.role() == Role.ERGO_ADMIN
				|| (me.role() == Role.ERGO_RFQ && company.type == CompanyType.SUPPLIER));
	}

	private Set<Long> accounts(CurrentUser me) {
		return users.findById(me.userId()).map(user -> Set.copyOf(user.accountIds)).orElse(Set.of());
	}

	private void apply(Company company, CompanyProfile profile, CurrentUser me) {
		// The RFQ prefix shapes ERGO's RFQ numbers, so only ERGO sets it; a customer's own edit leaves it as it is.
		if (me.companyType() == CompanyType.ERGO) {
			String prefix = profile.rfqPrefix();
			if (prefix != null && companies.findByRfqPrefix(prefix).filter(other -> !other.id.equals(company.id)).isPresent()) {
				throw ApiException.conflict("Another company already uses the RFQ prefix " + prefix);
			}
			company.rfqPrefix = prefix;
		}
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

	/** The audience decides the shape (CLAUDE.md: separate DTOs per audience, chosen by company type, not by role). */
	private static CompanyView toView(Company c, CurrentUser me) {
		Set<CompanyAction> actions = EnumSet.noneOf(CompanyAction.class);
		if (canEdit(me, c) && c.status != PENDING_APPROVAL) {
			actions.add(CompanyAction.EDIT);
		}
		if (SENDABLE.contains(c.status) && canSend(me, c)) {
			actions.add(CompanyAction.SEND_FOR_APPROVAL);
		}
		if (c.status == PENDING_APPROVAL && canReview(me, c)) {
			actions.add(CompanyAction.APPROVE);
			actions.add(CompanyAction.REJECT);
			actions.add(CompanyAction.REQUEST_INFO);
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
				c.postalCode, c.taxId, c.website, c.primaryContactName, c.primaryContactEmail, c.primaryContactPhone,
				c.rfqPrefix);
		if (me.companyType() != CompanyType.ERGO) {
			return new CompanyResponse(c.id, c.code, c.type, c.status, profile, actions);
		}
		// Management's comment: always to Management; to the ERGO people who must act on it only while the company is
		// sent back or rejected (an approval comment can be an internal judgement, security review L2)
		boolean commentShown = me.role() == Role.ERGO_MANAGEMENT
				|| ((c.status == INFO_REQUESTED || c.status == REJECTED) && canSend(me, c));
		return new ErgoCompanyResponse(c.id, c.code, c.type, c.status, profile, actions,
				commentShown ? c.reviewComment : null, c.type == CompanyType.ERGO ? List.of() : missing(c));
	}

}
