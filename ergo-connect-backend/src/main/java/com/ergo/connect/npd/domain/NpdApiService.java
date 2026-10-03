package com.ergo.connect.npd.domain;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.npd.api.NpdApi;

/**
 * Implements {@link NpdApi} for OFP's "Handover to OFP" action. Deliberately narrower than {@link NpdService}'s own
 * record scope (which restricts most ERGO roles' NPD visibility): any ERGO caller may read a specific project's own
 * handover data by id (the OFP controller's own {@code @PreAuthorize} already confines who actually reaches this),
 * the same "narrow, purpose-built cross-module read" shape as {@code QuotationApiService.selectedQuoteFor}.
 */
@Service
@Transactional
class NpdApiService implements NpdApi {

	private final NpdProjectRepository projects;

	private final NpdService npdService;

	NpdApiService(NpdProjectRepository projects, NpdService npdService) {
		this.projects = projects;
		this.npdService = npdService;
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.List<com.ergo.connect.common.event.OverdueItem> overdueItems(java.time.LocalDate today) {
		return npdService.overdue(today);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<com.ergo.connect.common.record.RecordParties> partiesVisibleToCaller(long npdProjectId) {
		if (!npdService.inScope(CurrentUser.get(), npdProjectId)) {
			return Optional.empty();
		}
		return projects.findById(npdProjectId).map(p -> new com.ergo.connect.common.record.RecordParties(p.customerCompanyId, p.supplierCompanyId));
	}

	@Override
	public HandoverProject handover(long npdProjectId) {
		CurrentUser me = CurrentUser.get();
		if (me.companyType() != CompanyType.ERGO) {
			throw ApiException.notFound("NPD project");
		}
		NpdProject project = projects.findById(npdProjectId).orElseThrow(() -> ApiException.notFound("NPD project"));
		// Security review M1 (ARCHITECTURE.md §8 2026-09-29): a project must actually have reached the stage that
		// gates production handover before OFP can create an order from it, not just "any ERGO-visible project".
		if (project.stage != NpdStage.APPROVED_FOR_PRODUCTION_HANDOVER) {
			throw ApiException.conflict("This NPD project is not yet approved for production handover");
		}
		if (project.handoverReadyFlag) {
			throw ApiException.conflict("This NPD project has already been handed over to OFP");
		}
		project.handoverReadyFlag = true;
		return new HandoverProject(project.id, project.customerCompanyId, project.supplierCompanyId,
				project.sourceRfqId, project.partNumber, project.revision);
	}

	@Override
	public Optional<ProjectRef> projectRef(long npdProjectId) {
		return projects.findById(npdProjectId)
			.map(p -> new ProjectRef(p.id, p.customerCompanyId, p.supplierCompanyId));
	}

}
