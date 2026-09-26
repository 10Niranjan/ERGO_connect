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

	List<NpdProject> findAllByOrderByIdDesc();

	List<NpdProject> findByCustomerCompanyIdInOrderByIdDesc(Collection<Long> customerCompanyIds);

	/** A supplier's own company's projects (SRS §12.15 "NPD Tasks" nav entry point). */
	List<NpdProject> findBySupplierCompanyIdOrderByIdDesc(Long supplierCompanyId);

	/** Defense in depth: the DB's UNIQUE constraint is the real guarantee. */
	boolean existsBySourceRfqId(Long sourceRfqId);

}

interface NpdActivityRepository extends Repository<NpdActivity, Long> {

	NpdActivity save(NpdActivity activity);

	Optional<NpdActivity> findById(Long id);

	List<NpdActivity> findByNpdProjectIdOrderById(Long npdProjectId);

	/** A customer's or supplier's own activities of one project (record scope, query-based per CLAUDE.md). */
	List<NpdActivity> findByNpdProjectIdAndOwnerOrganizationTypeAndOwnerCompanyIdOrderById(Long npdProjectId,
			CompanyType ownerOrganizationType, Long ownerCompanyId);

	/** The activities that gate Close Stage for one stage of one project. */
	List<NpdActivity> findByNpdProjectIdAndPhaseNameAndMandatoryFlagTrue(Long npdProjectId, NpdStage phaseName);

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

	/** A customer's own view: only the customer-facing approval types (SRS §10.6; suppliers get none at all). */
	List<NpdApproval> findByNpdProjectIdAndApprovalTypeInOrderById(Long npdProjectId,
			Collection<ApprovalType> approvalTypes);

	/** Feeds the Close Stage gate: which activities already have an APPROVED approval linked to them. */
	List<NpdApproval> findByNpdProjectIdAndApprovalStatusOrderById(Long npdProjectId, ApprovalStatus approvalStatus);

}
