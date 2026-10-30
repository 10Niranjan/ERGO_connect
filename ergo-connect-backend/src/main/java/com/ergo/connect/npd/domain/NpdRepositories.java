package com.ergo.connect.npd.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.ergo.connect.common.security.CompanyType;

/** Package-private; NpdService applies the record scope for every read. */
interface NpdProjectRepository extends Repository<NpdProject, Long> {

	NpdProject save(NpdProject project);

	Optional<NpdProject> findById(Long id);

	List<NpdProject> findByIdIn(Collection<Long> ids);

	/** The list page. The only Specification method on purpose (JpaSpecificationExecutor would bring bulk update/delete that skip @Version and audit). */
	org.springframework.data.domain.Page<NpdProject> findAll(org.springframework.data.jpa.domain.Specification<NpdProject> spec,
			org.springframework.data.domain.Pageable pageable);

	/** Ids only, to ask the RFQ module which of them match a search text. */
	@org.springframework.data.jpa.repository.Query("select p.sourceRfqItemId from NpdProject p where p.sourceRfqItemId is not null")
	List<Long> sourceRfqItemIds();

	List<NpdProject> findAllByOrderByIdDesc();

	List<NpdProject> findByCustomerCompanyIdInOrderByIdDesc(Collection<Long> customerCompanyIds);

	/** A supplier's own company's projects (SRS §12.15 "NPD Tasks" nav entry point), once the plan is released to it. */
	List<NpdProject> findBySupplierCompanyIdAndPlanStatusNotOrderByIdDesc(Long supplierCompanyId, PlanStatus excluded);

	Optional<NpdProject> findBySamplePoId(Long samplePoId);

	Optional<NpdProject> findByProductionPoId(Long productionPoId);

	List<NpdProject> findByProductionPoIdIn(Collection<Long> productionPoIds);

	/** Parts that already have a project (also those created before Sample POs existed). */
	List<NpdProject> findBySourceRfqItemIdIn(Collection<Long> rfqItemIds);

	List<NpdProject> findBySamplePoIdIn(Collection<Long> samplePoIds);

}

interface SamplePoRepository extends Repository<SamplePo, Long> {

	SamplePo save(SamplePo samplePo);

	Optional<SamplePo> findById(Long id);

	/** The list page. The only Specification method on purpose (JpaSpecificationExecutor would bring bulk update/delete that skip @Version and audit). */
	org.springframework.data.domain.Page<SamplePo> findAll(org.springframework.data.jpa.domain.Specification<SamplePo> spec,
			org.springframework.data.domain.Pageable pageable);

	List<SamplePo> findByCustomerCompanyIdInOrderByIdDesc(Collection<Long> customerCompanyIds);

	List<SamplePo> findByRfqItemIdIn(Collection<Long> rfqItemIds);

	long countByStatus(SamplePoStatus status);

	long countByStatusAndCustomerCompanyIdIn(SamplePoStatus status, Collection<Long> customerCompanyIds);

}

interface NpdActivityRepository extends Repository<NpdActivity, Long> {

	NpdActivity save(NpdActivity activity);

	Optional<NpdActivity> findById(Long id);

	List<NpdActivity> findByNpdProjectIdOrderById(Long npdProjectId);

	/** The overdue scan: planned for before {@code date} and not done (blocked work is still late). */
	List<NpdActivity> findByPlannedDateBeforeAndStatusNot(java.time.LocalDate date, ActivityStatus status);

	/** A customer's or supplier's own activities of one project (record scope, query-based per CLAUDE.md). */
	List<NpdActivity> findByNpdProjectIdAndOwnerOrganizationTypeAndOwnerCompanyIdOrderById(Long npdProjectId,
			CompanyType ownerOrganizationType, Long ownerCompanyId);

	/** The activities that gate Close Stage for one stage of one project. */
	List<NpdActivity> findByNpdProjectIdAndPhaseNameAndMandatoryFlagTrue(Long npdProjectId, NpdStage phaseName);

	/** Every activity of one stage of one project (a rework sends the project back to it). */
	List<NpdActivity> findByNpdProjectIdAndPhaseName(Long npdProjectId, NpdStage phaseName);

}

interface NpdStageHistoryRepository extends Repository<NpdStageHistoryEntry, Long> {

	NpdStageHistoryEntry save(NpdStageHistoryEntry entry);

	List<NpdStageHistoryEntry> findByNpdProjectIdOrderById(Long npdProjectId);

}

/** Question bank (SRS §13.4.4); authored only by ERGO Admin/NPD. */
interface NpdPromptChecklistRepository extends Repository<NpdPromptChecklist, Long> {

	NpdPromptChecklist save(NpdPromptChecklist checklist);

	Optional<NpdPromptChecklist> findById(Long id);

	List<NpdPromptChecklist> findByNpdActivityIdInOrderBySequenceNo(Collection<Long> npdActivityIds);

}

/** One current answer per question (SRS §13.4.5): re-answering upserts by checklist id. */
interface NpdPromptResponseRepository extends Repository<NpdPromptResponse, Long> {

	NpdPromptResponse save(NpdPromptResponse response);

	Optional<NpdPromptResponse> findByChecklistId(Long checklistId);

	List<NpdPromptResponse> findByChecklistIdIn(Collection<Long> checklistIds);

	/** Reassigning an activity's owner (NpdService.updateActivity) discards the previous owner's answers along with
	 * its remarks: the questions themselves (NpdPromptChecklist) are ERGO's authored template and stay put, only the
	 * answers are the other company's own words. */
	void deleteByChecklistIdIn(Collection<Long> checklistIds);

}

interface NpdIssueRiskRepository extends Repository<NpdIssueRisk, Long> {

	NpdIssueRisk save(NpdIssueRisk issue);

	Optional<NpdIssueRisk> findById(Long id);

	/** ERGO's unrestricted view of one project's issues/risks. */
	List<NpdIssueRisk> findByNpdProjectIdOrderById(Long npdProjectId);

	/** A customer's or supplier's own-owned issues/risks of one project (record scope, query-based). Deliberately not
	 * unioned with issues merely linked to their own activity: that would let an issue owned by the other external
	 * party leak through (NpdService.issuesFor). */
	List<NpdIssueRisk> findByNpdProjectIdAndOwnerCompanyIdOrderById(Long npdProjectId, Long ownerCompanyId);

}

interface NpdApprovalRepository extends Repository<NpdApproval, Long> {

	NpdApproval save(NpdApproval approval);

	Optional<NpdApproval> findById(Long id);

	/** ERGO's unrestricted view of one project's approvals. */
	List<NpdApproval> findByNpdProjectIdOrderById(Long npdProjectId);

	/** Dashboard "Pending approvals" widget (ERGO Management, unrestricted-view roles only). */
	long countByApprovalStatus(ApprovalStatus approvalStatus);

	/** A customer's own view: only the customer-facing approval types (SRS §10.6; suppliers get none at all). */
	List<NpdApproval> findByNpdProjectIdAndApprovalTypeInOrderById(Long npdProjectId,
			Collection<ApprovalType> approvalTypes);

	/** Feeds the Close Stage gate: which activities already have an APPROVED approval linked to them. */
	List<NpdApproval> findByNpdProjectIdAndApprovalStatusOrderById(Long npdProjectId, ApprovalStatus approvalStatus);

}

interface NpdPlanHistoryRepository extends Repository<NpdPlanHistoryEntry, Long> {

	NpdPlanHistoryEntry save(NpdPlanHistoryEntry entry);

	List<NpdPlanHistoryEntry> findByNpdProjectIdOrderById(Long npdProjectId);

}

interface NpdValidationDecisionRepository extends Repository<NpdValidationDecision, Long> {

	NpdValidationDecision save(NpdValidationDecision decision);

	List<NpdValidationDecision> findByNpdProjectIdOrderById(Long npdProjectId);

	List<NpdValidationDecision> findByNpdProjectIdAndRoundNoOrderById(Long npdProjectId, int roundNo);

}

interface ProductionPoRepository extends Repository<ProductionPo, Long> {

	ProductionPo save(ProductionPo productionPo);

	Optional<ProductionPo> findById(Long id);

	/** The list page. The only Specification method on purpose (JpaSpecificationExecutor would bring bulk update/delete that skip @Version and audit). */
	org.springframework.data.domain.Page<ProductionPo> findAll(org.springframework.data.jpa.domain.Specification<ProductionPo> spec,
			org.springframework.data.domain.Pageable pageable);

	List<ProductionPo> findByCustomerCompanyIdInOrderByIdDesc(Collection<Long> customerCompanyIds);

	/** Production orders still open (submitted or returned) for these sample projects. */
	List<ProductionPo> findBySourceProjectIdInAndStatusIn(Collection<Long> sourceProjectIds, Collection<ProductionPoStatus> statuses);

}
