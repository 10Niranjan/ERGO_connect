package com.ergo.connect.masterdata.access;

import java.util.EnumSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.documents.api.DocumentStore;
import com.ergo.connect.documents.api.DocumentStore.DocumentInfo;
import com.ergo.connect.documents.api.DocumentStore.Visibility;
import com.ergo.connect.masterdata.access.CompanyAssessmentDtos.AssessmentFile;
import com.ergo.connect.masterdata.access.CompanyAssessmentDtos.AssessmentKind;
import com.ergo.connect.masterdata.access.CompanyAssessmentDtos.AssessmentList;
import com.ergo.connect.masterdata.access.CompanyAssessmentDtos.AssessmentRequest;
import com.ergo.connect.masterdata.access.CompanyAssessmentDtos.AssessmentView;

/**
 * Supplier audit reports and customer assessments (client questions 6, 14, 28, 29; ARCHITECTURE.md §8 2026-11-23).
 * ERGO-internal: no customer or supplier ever reaches them (question 9). Layer 1 is the controller's role list, layer
 * 2 is CompanyService's record scope (KAMs: their own customers; Quality: suppliers only). Report files are stored
 * through the documents module under {@link #DOCUMENT_MODULE}, which its Library does not know and therefore never
 * lists, so they are reachable only through this service.
 */
@Service
@Transactional
public class CompanyAssessmentService {

	/** documents.linked_module of report files; linked_record_id is the company id. */
	static final String DOCUMENT_MODULE = "company_assessment";

	private final CompanyService companies;

	private final CompanyAssessmentRepository assessments;

	private final UserRepository users;

	private final DocumentStore documents;

	CompanyAssessmentService(CompanyService companies, CompanyAssessmentRepository assessments, UserRepository users,
			DocumentStore documents) {
		this.companies = companies;
		this.assessments = assessments;
		this.users = users;
		this.documents = documents;
	}

	public record ReportFile(DocumentInfo info, byte[] content) {
	}

	@Transactional(readOnly = true)
	public AssessmentList list(long companyId) {
		CurrentUser me = CurrentUser.get();
		Company company = viewable(me, companyId);
		List<AssessmentView> items = assessments.findByCompanyIdOrderByAssessmentDateDescIdDesc(companyId)
			.stream()
			.map(a -> view(a, companyId))
			.toList();
		return new AssessmentList(kindOf(company), canAdd(me, company), items);
	}

	/**
	 * Supplier audit: ERGO Quality, with audit type, auditor, findings, recommendation and the report file. Customer
	 * assessment: whoever in ERGO may register that customer, with notes and a risk rating, file optional. Fields that
	 * do not belong to the kind are not stored.
	 */
	public AssessmentView add(long companyId, AssessmentRequest request, String fileName, byte[] content) {
		CurrentUser me = CurrentUser.get();
		Company company = viewable(me, companyId);
		if (!canAdd(me, company)) {
			throw ApiException.forbidden(company.type == CompanyType.SUPPLIER
					? "Supplier audit reports are added by ERGO Quality"
					: "Customer assessments are added by the ERGO team that registers customers");
		}
		boolean hasFile = content != null && content.length > 0;
		CompanyAssessment a = new CompanyAssessment();
		a.companyId = companyId;
		a.kind = kindOf(company);
		a.assessmentDate = request.assessmentDate();
		a.findings = request.findings().trim();
		if (a.kind == AssessmentKind.SUPPLIER_AUDIT) {
			if (request.auditType() == null || request.recommendation() == null || request.auditor() == null
					|| request.auditor().isBlank()) {
				throw ApiException.unprocessable("A supplier audit needs the audit type, the auditor and a recommendation");
			}
			if (!hasFile) {
				throw ApiException.unprocessable("Attach the audit report file");
			}
			a.auditType = request.auditType();
			a.auditor = request.auditor().trim();
			a.recommendation = request.recommendation();
		}
		else {
			if (request.riskRating() == null) {
				throw ApiException.unprocessable("A customer assessment needs a risk rating");
			}
			a.riskRating = request.riskRating();
		}
		if (hasFile) {
			a.documentId = documents.store(DOCUMENT_MODULE, companyId, fileName, content, Visibility.INTERNAL).id();
		}
		a.createdByUserId = me.userId();
		return view(assessments.save(a), companyId);
	}

	@Transactional(readOnly = true)
	public ReportFile file(long companyId, long assessmentId) {
		CurrentUser me = CurrentUser.get();
		viewable(me, companyId);
		CompanyAssessment a = assessments.findByIdAndCompanyId(assessmentId, companyId)
			.filter(found -> found.documentId != null)
			.orElseThrow(() -> ApiException.notFound("Assessment file"));
		DocumentInfo info = documents.find(DOCUMENT_MODULE, companyId, a.documentId)
			.orElseThrow(() -> ApiException.notFound("Assessment file"));
		return new ReportFile(info, documents.content(info));
	}

	/** Record scope first (404 when out of scope), then: ERGO only, and ERGO Quality only for suppliers. */
	private Company viewable(CurrentUser me, long companyId) {
		Company company = companies.scoped(me, companyId);
		if (!CompanyService.canViewAssessments(me, company)) {
			throw ApiException.notFound("Company");
		}
		return company;
	}

	private static boolean canAdd(CurrentUser me, Company company) {
		return company.type == CompanyType.SUPPLIER ? me.role() == Role.ERGO_QUALITY
				: EnumSet.of(Role.ERGO_ADMIN, Role.ERGO_RFQ, Role.ERGO_KAM).contains(me.role());
	}

	private static AssessmentKind kindOf(Company company) {
		return company.type == CompanyType.SUPPLIER ? AssessmentKind.SUPPLIER_AUDIT : AssessmentKind.CUSTOMER_ASSESSMENT;
	}

	private String name(Long userId) {
		return users.findById(userId).map(u -> u.firstName + " " + u.lastName).orElse(null);
	}

	private AssessmentView view(CompanyAssessment a, long companyId) {
		AssessmentFile file = a.documentId == null ? null
				: documents.find(DOCUMENT_MODULE, companyId, a.documentId)
					.map(d -> new AssessmentFile(d.id(), d.fileName(), d.size()))
					.orElse(null);
		String by = name(a.createdByUserId);
		if (a.actingAdminUserId != null) { // added during Login as: name the Super Admin too, as the audit trail does
			by = by + " (by Super Admin " + name(a.actingAdminUserId) + ")";
		}
		return new AssessmentView(a.id, a.kind, a.assessmentDate, a.auditType, a.auditor, a.findings, a.recommendation,
				a.riskRating, file, by, a.createdAt);
	}

}
