package com.ergo.connect.documents.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ergo.connect.documents.domain.DocumentDtos.CategoryOption;
import com.ergo.connect.documents.domain.DocumentDtos.DocumentDetail;
import com.ergo.connect.documents.domain.DocumentDtos.DocumentEditForm;
import com.ergo.connect.documents.domain.DocumentDtos.LibraryPage;
import com.ergo.connect.documents.domain.DocumentDtos.ReasonForm;
import com.ergo.connect.documents.domain.DocumentService;
import com.ergo.connect.documents.domain.DocumentService.DocumentFile;

import jakarta.validation.Valid;

/**
 * SRS §10.9 Document Management, §12.11 Library/Detail, §18 Documents APIs. Layer 1 (which roles may attempt what) is
 * here; layer 2 (which documents - cross-module record scope, this document's own visibility, lock state) is {@code
 * DocumentService}. Upload/create stays on each of the 4 owning modules' own endpoints (client decision,
 * ARCHITECTURE.md §8 2026-10-02) - this controller is read, edit, versioning and lock/approval only.
 */
@RestController
@RequestMapping("/api/documents")
class DocumentController {

	/** SRS §10.9: every role has at least view+create+edit+submit on documents (ERGO unrestricted within its own
	 * per-module record scope; Customer/Supplier on their own company's own uploads while unlocked) - record scope
	 * and the edit/lock gate are {@code DocumentService}'s job, not a narrower role list here. */
	private static final String EVERY_ROLE = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_KAM', 'ERGO_RFQ', "
			+ "'ERGO_NPD', 'ERGO_OFP', 'ERGO_QUALITY', 'CUSTOMER_RFQ', 'CUSTOMER_NPD', 'CUSTOMER_OFP', "
			+ "'CUSTOMER_QUALITY', 'CUSTOMER_KAM', 'CUSTOMER_MANAGEMENT', 'SUPPLIER_RFQ', 'SUPPLIER_NPD', "
			+ "'SUPPLIER_OFP', 'SUPPLIER_QUALITY', 'SUPPLIER_KAM')";

	/** SRS §10.9 Approve (and, by the same client decision, lock/unlock/reject): ERGO Admin only, its own authority
	 * separate from edit (CLAUDE.md non-negotiable). */
	private static final String ADMIN_ONLY = "hasRole('ERGO_ADMIN')";

	private final DocumentService documents;

	DocumentController(DocumentService documents) {
		this.documents = documents;
	}

	@GetMapping("/categories")
	@PreAuthorize(EVERY_ROLE)
	List<CategoryOption> categories() {
		return documents.categories();
	}

	@GetMapping
	@PreAuthorize(EVERY_ROLE)
	LibraryPage library(@RequestParam(required = false) String linkedModule,
			@RequestParam(required = false) Long categoryId, @RequestParam(required = false) Long uploaderCompanyId,
			@RequestParam(required = false) ApprovalStatus approvalStatus, @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		return documents.library(linkedModule, categoryId, uploaderCompanyId, approvalStatus, from, to, page, size);
	}

	@GetMapping("/{id}")
	@PreAuthorize(EVERY_ROLE)
	DocumentDetail get(@PathVariable long id) {
		return documents.get(id);
	}

	/** Always a download (never rendered in the browser), not cached - same shape as every other module's own
	 * document download endpoint. */
	@GetMapping("/{id}/content")
	@PreAuthorize(EVERY_ROLE)
	ResponseEntity<byte[]> content(@PathVariable long id) {
		DocumentFile file = documents.content(id);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(file.contentType()))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build()
						.toString())
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.body(file.content());
	}

	@PatchMapping("/{id}")
	@PreAuthorize(EVERY_ROLE)
	DocumentDetail edit(@PathVariable long id, @Valid @RequestBody DocumentEditForm body) {
		return documents.edit(id, body);
	}

	@PostMapping("/{id}/replace")
	@PreAuthorize(EVERY_ROLE)
	DocumentDetail replace(@PathVariable long id, @RequestParam("file") MultipartFile file,
			@RequestParam(required = false) Long documentCategoryId) throws IOException {
		return documents.replace(id, file.getOriginalFilename(), file.getBytes(), documentCategoryId);
	}

	@PostMapping("/{id}/lock")
	@PreAuthorize(ADMIN_ONLY)
	DocumentDetail lock(@PathVariable long id) {
		return documents.lock(id);
	}

	@PostMapping("/{id}/unlock")
	@PreAuthorize(ADMIN_ONLY)
	DocumentDetail unlock(@PathVariable long id, @RequestBody(required = false) ReasonForm body) {
		return documents.unlock(id, body);
	}

	@PostMapping("/{id}/approve")
	@PreAuthorize(ADMIN_ONLY)
	DocumentDetail approve(@PathVariable long id, @Valid @RequestBody ReasonForm body) {
		return documents.approve(id, body);
	}

	/** Reason required (CLAUDE.md non-negotiable), enforced in {@code DocumentService}, not by Bean Validation -
	 * same shape as every other module's reject/reopen/send-back ({@code ReasonForm.reason} is optional at the DTO
	 * level so the same form serves both approve's optional comment and reject's mandatory reason). */
	@PostMapping("/{id}/reject")
	@PreAuthorize(ADMIN_ONLY)
	DocumentDetail reject(@PathVariable long id, @Valid @RequestBody ReasonForm body) {
		return documents.reject(id, body);
	}

}
