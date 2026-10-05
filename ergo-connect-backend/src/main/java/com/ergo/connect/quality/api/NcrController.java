package com.ergo.connect.quality.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ergo.connect.common.audit.AuditLogService.AuditEntry;
import com.ergo.connect.quality.domain.NcrService;
import com.ergo.connect.quality.domain.NcrService.EvidenceFile;
import com.ergo.connect.quality.domain.QualityDtos.CapaActionForm;
import com.ergo.connect.quality.domain.QualityDtos.NcrDetail;
import com.ergo.connect.quality.domain.QualityDtos.NcrForm;
import com.ergo.connect.quality.domain.QualityDtos.NcrSummary;
import com.ergo.connect.quality.domain.QualityDtos.NcrUpdateForm;
import com.ergo.connect.quality.domain.QualityDtos.ReasonForm;

import jakarta.validation.Valid;

/**
 * SRS §10.8 Quality Management (NCR/CAPA), §14.7 workflow, Phase 5b. Layer 1 (which roles may attempt what) is here;
 * layer 2 (which NCRs, in which status, and whose own company) is {@code NcrService} - same split as {@link
 * InspectionController}.
 */
@RestController
@RequestMapping("/api/quality/ncrs")
class NcrController {

	/** SRS §10.8 View: every role except the three *_RFQ roles (X) - same set as {@link InspectionController}. */
	private static final String VIEWERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_KAM', 'ERGO_NPD', "
			+ "'ERGO_OFP', 'ERGO_QUALITY', 'CUSTOMER_NPD', 'CUSTOMER_OFP', 'CUSTOMER_QUALITY', 'CUSTOMER_KAM', "
			+ "'CUSTOMER_MANAGEMENT', 'SUPPLIER_NPD', 'SUPPLIER_OFP', 'SUPPLIER_QUALITY', 'SUPPLIER_KAM')";

	/** SRS §10.8 Create/Edit/Submit: ERGO Admin/Quality and Supplier Quality only (Customer Quality view-only, same
	 * judgment call as {@link InspectionController}). */
	private static final String EDITORS = "hasAnyRole('ERGO_ADMIN', 'ERGO_QUALITY', 'SUPPLIER_QUALITY')";

	/** SRS §10.8 Approve, its own authority separate from edit: includes ERGO Management, who has no create/edit/
	 * submit right at all. Supplier Quality has no approve/close rights. */
	private static final String APPROVERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_QUALITY')";

	private final NcrService ncrs;

	NcrController(NcrService ncrs) {
		this.ncrs = ncrs;
	}

	@GetMapping
	@PreAuthorize(VIEWERS)
	List<? extends NcrSummary> list() {
		return ncrs.list();
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEWERS)
	NcrDetail get(@PathVariable long id) {
		return ncrs.get(id);
	}

	@GetMapping("/{id}/history")
	@PreAuthorize(VIEWERS)
	List<AuditEntry> history(@PathVariable long id) {
		return ncrs.history(id);
	}

	@PostMapping
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	NcrDetail create(@Valid @RequestBody NcrForm body) {
		return ncrs.create(body);
	}

	@PutMapping("/{id}")
	@PreAuthorize(EDITORS)
	NcrDetail edit(@PathVariable long id, @Valid @RequestBody NcrUpdateForm body) {
		return ncrs.edit(id, body);
	}

	/** SRS §14.7: OPEN -> CONTAINMENT_IN_PROGRESS. */
	@PostMapping("/{id}/move-to-containment")
	@PreAuthorize(EDITORS)
	NcrDetail moveToContainment(@PathVariable long id, @Valid @RequestBody NcrUpdateForm body) {
		return ncrs.moveToContainment(id, body);
	}

	/** SRS §14.7: CONTAINMENT_IN_PROGRESS -> ROOT_CAUSE_IN_PROGRESS. */
	@PostMapping("/{id}/move-to-root-cause")
	@PreAuthorize(EDITORS)
	NcrDetail moveToRootCause(@PathVariable long id, @Valid @RequestBody NcrUpdateForm body) {
		return ncrs.moveToRootCause(id, body);
	}

	/** SRS §14.7/§17.6: ROOT_CAUSE_IN_PROGRESS -> CAPA_IN_PROGRESS. Root cause required. */
	@PostMapping("/{id}/move-to-capa")
	@PreAuthorize(EDITORS)
	NcrDetail moveToCapa(@PathVariable long id, @Valid @RequestBody NcrUpdateForm body) {
		return ncrs.moveToCapa(id, body);
	}

	/** SRS §14.7: CAPA_IN_PROGRESS -> PENDING_CLOSURE_APPROVAL. */
	@PostMapping("/{id}/submit-for-closure")
	@PreAuthorize(EDITORS)
	NcrDetail submitForClosure(@PathVariable long id, @Valid @RequestBody NcrUpdateForm body) {
		return ncrs.submitForClosure(id, body);
	}

	/** SRS §14.7/§17.6: PENDING_CLOSURE_APPROVAL -> CLOSED. Evidence and root cause required. */
	@PostMapping("/{id}/close")
	@PreAuthorize(APPROVERS)
	NcrDetail close(@PathVariable long id, @Valid @RequestBody ReasonForm body) {
		return ncrs.close(id, body);
	}

	/** SRS §14.7: PENDING_CLOSURE_APPROVAL -> CAPA_IN_PROGRESS. Reason required. */
	@PostMapping("/{id}/send-back")
	@PreAuthorize(APPROVERS)
	NcrDetail sendBack(@PathVariable long id, @Valid @RequestBody ReasonForm body) {
		return ncrs.sendBack(id, body);
	}

	/** CLOSED -> OPEN, the only reopen path. Reason required. */
	@PostMapping("/{id}/reopen")
	@PreAuthorize(APPROVERS)
	NcrDetail reopen(@PathVariable long id, @Valid @RequestBody ReasonForm body) {
		return ncrs.reopen(id, body);
	}

	// ---------------------------------------------------------------- CAPA actions

	@PostMapping("/{id}/capa-actions")
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	NcrDetail createCapaAction(@PathVariable long id, @Valid @RequestBody CapaActionForm body) {
		return ncrs.createCapaAction(id, body);
	}

	@PutMapping("/{id}/capa-actions/{capaActionId}")
	@PreAuthorize(EDITORS)
	NcrDetail editCapaAction(@PathVariable long id, @PathVariable long capaActionId,
			@Valid @RequestBody CapaActionForm body) {
		return ncrs.editCapaAction(id, capaActionId, body);
	}

	/** Hard gate (SRS §17.6): root cause must already be on record. */
	@PostMapping("/{id}/capa-actions/{capaActionId}/close")
	@PreAuthorize(EDITORS)
	NcrDetail closeCapaAction(@PathVariable long id, @PathVariable long capaActionId) {
		return ncrs.closeCapaAction(id, capaActionId);
	}

	/** Evidence upload (SRS §17.6): reuses the Documents module, same shape as {@code OrderController.uploadPod}. */
	@PostMapping(path = "/{id}/capa-actions/{capaActionId}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize(EDITORS)
	NcrDetail uploadEvidence(@PathVariable long id, @PathVariable long capaActionId,
			@RequestParam("file") MultipartFile file) throws IOException {
		return ncrs.uploadEvidence(id, capaActionId, file.getOriginalFilename(), file.getBytes());
	}

	@GetMapping("/{id}/capa-actions/{capaActionId}/evidence")
	@PreAuthorize(VIEWERS)
	ResponseEntity<byte[]> downloadEvidence(@PathVariable long id, @PathVariable long capaActionId) {
		EvidenceFile file = ncrs.downloadEvidence(id, capaActionId);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(file.info().contentType()))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(file.info().fileName(), StandardCharsets.UTF_8).build()
						.toString())
			.body(file.content());
	}

}
