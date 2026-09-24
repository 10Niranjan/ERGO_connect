package com.ergo.connect.rfq.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.ergo.connect.documents.api.DocumentStore.Visibility;
import com.ergo.connect.rfq.domain.RfqDtos.CommentForm;
import com.ergo.connect.rfq.domain.RfqDtos.DocumentView;
import com.ergo.connect.rfq.domain.RfqDtos.NotesForm;
import com.ergo.connect.rfq.domain.RfqDtos.RfqForm;
import com.ergo.connect.rfq.domain.RfqDtos.RfqRow;
import com.ergo.connect.rfq.domain.RfqDtos.RfqView;
import com.ergo.connect.rfq.domain.RfqDtos.VisibilityForm;
import com.ergo.connect.rfq.domain.RfqService;
import com.ergo.connect.rfq.domain.RfqService.DocumentFile;

import jakarta.validation.Valid;

/**
 * SRS §10.4 RFQ Management, §18 RFQ APIs. Layer 1 (which roles may attempt what) is here; layer 2 (which RFQs, in
 * which status) is RfqService. Suppliers join in Phase 2b.
 */
@RestController
@RequestMapping("/api/rfqs")
class RfqController {

	private static final String VIEWERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_RFQ', 'ERGO_KAM', "
			+ "'ERGO_NPD', 'CUSTOMER_RFQ', 'CUSTOMER_KAM', 'CUSTOMER_MANAGEMENT')";

	private static final String CREATORS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_RFQ', 'ERGO_KAM', "
			+ "'CUSTOMER_RFQ', 'CUSTOMER_KAM', 'CUSTOMER_MANAGEMENT')";

	private static final String ERGO_EDITORS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_RFQ', 'ERGO_KAM')";

	private static final String REVIEWERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_RFQ')";

	private final RfqService rfqs;

	RfqController(RfqService rfqs) {
		this.rfqs = rfqs;
	}

	@GetMapping
	@PreAuthorize(VIEWERS)
	List<RfqRow> list() {
		return rfqs.list();
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEWERS)
	RfqView get(@PathVariable long id) {
		return rfqs.get(id);
	}

	@GetMapping("/{id}/history")
	@PreAuthorize(VIEWERS)
	List<AuditEntry> history(@PathVariable long id) {
		return rfqs.history(id);
	}

	@PostMapping
	@PreAuthorize(CREATORS)
	@ResponseStatus(HttpStatus.CREATED)
	RfqView create(@Valid @RequestBody RfqForm body) {
		return rfqs.create(body);
	}

	@PutMapping("/{id}")
	@PreAuthorize(CREATORS)
	RfqView update(@PathVariable long id, @Valid @RequestBody RfqForm body) {
		return rfqs.update(id, body);
	}

	@PutMapping("/{id}/internal-notes")
	@PreAuthorize(ERGO_EDITORS)
	RfqView updateNotes(@PathVariable long id, @Valid @RequestBody NotesForm body) {
		return rfqs.updateNotes(id, body.internalNotes());
	}

	@PostMapping("/{id}/clone")
	@PreAuthorize(CREATORS)
	@ResponseStatus(HttpStatus.CREATED)
	RfqView cloneRfq(@PathVariable long id) {
		return rfqs.cloneRfq(id);
	}

	@PostMapping("/{id}/submit")
	@PreAuthorize(CREATORS)
	RfqView submit(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return rfqs.submit(id, body.comment());
	}

	@PostMapping("/{id}/start-review")
	@PreAuthorize(REVIEWERS)
	RfqView startReview(@PathVariable long id) {
		return rfqs.startReview(id);
	}

	@PostMapping("/{id}/request-clarification")
	@PreAuthorize(ERGO_EDITORS)
	RfqView requestClarification(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return rfqs.requestClarification(id, body.comment());
	}

	@PostMapping("/{id}/validate")
	@PreAuthorize(REVIEWERS)
	RfqView validate(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return rfqs.validate(id, body.comment());
	}

	@PostMapping("/{id}/reopen")
	@PreAuthorize("hasAnyRole('ERGO_ADMIN', 'ERGO_RFQ')")
	RfqView reopen(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return rfqs.reopen(id, body.comment());
	}

	@PostMapping("/{id}/close")
	@PreAuthorize(REVIEWERS)
	RfqView close(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return rfqs.close(id, body.comment());
	}

	@PostMapping("/{id}/cancel")
	@PreAuthorize(CREATORS)
	RfqView cancel(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return rfqs.cancel(id, body.comment());
	}

	@GetMapping("/{id}/documents")
	@PreAuthorize(VIEWERS)
	List<DocumentView> documents(@PathVariable long id) {
		return rfqs.documents(id);
	}

	@PostMapping(path = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize(CREATORS)
	@ResponseStatus(HttpStatus.CREATED)
	DocumentView upload(@PathVariable long id, @RequestParam("file") MultipartFile file,
			@RequestParam(required = false) Visibility visibility) throws IOException {
		return rfqs.upload(id, file.getOriginalFilename(), file.getBytes(), visibility);
	}

	/** Always a download (never rendered in the browser), not cached. */
	@GetMapping("/{id}/documents/{documentId}/content")
	@PreAuthorize(VIEWERS)
	ResponseEntity<byte[]> download(@PathVariable long id, @PathVariable long documentId) {
		DocumentFile file = rfqs.download(id, documentId);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(file.info().contentType()))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(file.info().fileName(), StandardCharsets.UTF_8).build()
						.toString())
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.body(file.content());
	}

	@PutMapping("/{id}/documents/{documentId}/visibility")
	@PreAuthorize(ERGO_EDITORS)
	DocumentView setVisibility(@PathVariable long id, @PathVariable long documentId,
			@Valid @RequestBody VisibilityForm body) {
		return rfqs.setVisibility(id, documentId, body.visibility());
	}

	@DeleteMapping("/{id}/documents/{documentId}")
	@PreAuthorize(CREATORS)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteDocument(@PathVariable long id, @PathVariable long documentId) {
		rfqs.deleteDocument(id, documentId);
	}

}
