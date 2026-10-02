package com.ergo.connect.communication.api;

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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ergo.connect.communication.domain.CommentDtos.CommentEditForm;
import com.ergo.connect.communication.domain.CommentDtos.CommentForm;
import com.ergo.connect.communication.domain.CommentDtos.CommentView;
import com.ergo.connect.communication.domain.CommentDtos.InboxPage;
import com.ergo.connect.communication.domain.CommentDtos.MentionOption;
import com.ergo.connect.communication.domain.CommentDtos.ReasonForm;
import com.ergo.connect.communication.domain.CommentService;
import com.ergo.connect.communication.domain.CommentService.AttachmentFile;

import jakarta.validation.Valid;

/**
 * SRS §10.10 Communication, §12.12.1 Communication Center. Layer 1 (which roles may attempt what) is here - SRS
 * §10.10 gives every role view, create and edit - and layer 2 (which records, which audience, whose comment, edit
 * window) is {@code CommentService}. Escalation is ERGO-only and checked in the service, since it is a company-type
 * rule rather than a role list.
 */
@RestController
@RequestMapping("/api/comments")
class CommentController {

	private static final String EVERY_ROLE = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_KAM', 'ERGO_RFQ', "
			+ "'ERGO_NPD', 'ERGO_OFP', 'ERGO_QUALITY', 'CUSTOMER_RFQ', 'CUSTOMER_NPD', 'CUSTOMER_OFP', "
			+ "'CUSTOMER_QUALITY', 'CUSTOMER_KAM', 'CUSTOMER_MANAGEMENT', 'SUPPLIER_RFQ', 'SUPPLIER_NPD', "
			+ "'SUPPLIER_OFP', 'SUPPLIER_QUALITY', 'SUPPLIER_KAM')";

	private final CommentService comments;

	CommentController(CommentService comments) {
		this.comments = comments;
	}

	@GetMapping
	@PreAuthorize(EVERY_ROLE)
	List<CommentView> thread(@RequestParam String linkedModule, @RequestParam long linkedRecordId) {
		return comments.thread(linkedModule, linkedRecordId);
	}

	@GetMapping("/inbox")
	@PreAuthorize(EVERY_ROLE)
	InboxPage inbox(@RequestParam(required = false) String linkedModule,
			@RequestParam(defaultValue = "false") boolean openQueriesOnly,
			@RequestParam(defaultValue = "false") boolean escalatedOnly, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		return comments.inbox(linkedModule, openQueriesOnly, escalatedOnly, page, size);
	}

	@GetMapping("/mentionable")
	@PreAuthorize(EVERY_ROLE)
	List<MentionOption> mentionable(@RequestParam String linkedModule, @RequestParam long linkedRecordId,
			@RequestParam(required = false) CommentType commentType) {
		return comments.mentionable(linkedModule, linkedRecordId, commentType);
	}

	@PostMapping
	@PreAuthorize(EVERY_ROLE)
	@ResponseStatus(HttpStatus.CREATED)
	CommentView create(@Valid @RequestBody CommentForm body) {
		return comments.create(body);
	}

	@PatchMapping("/{id}")
	@PreAuthorize(EVERY_ROLE)
	CommentView edit(@PathVariable long id, @Valid @RequestBody CommentEditForm body) {
		return comments.edit(id, body);
	}

	@PostMapping("/{id}/resolve")
	@PreAuthorize(EVERY_ROLE)
	CommentView resolve(@PathVariable long id, @Valid @RequestBody(required = false) ReasonForm body) {
		return comments.resolve(id, body);
	}

	/** Reason required (CLAUDE.md non-negotiable), enforced in the service so one form shape serves all four. */
	@PostMapping("/{id}/reopen")
	@PreAuthorize(EVERY_ROLE)
	CommentView reopen(@PathVariable long id, @Valid @RequestBody ReasonForm body) {
		return comments.reopen(id, body);
	}

	@PostMapping("/{id}/escalate")
	@PreAuthorize(EVERY_ROLE)
	CommentView escalate(@PathVariable long id, @Valid @RequestBody(required = false) ReasonForm body) {
		return comments.escalate(id, body);
	}

	@PostMapping("/{id}/deescalate")
	@PreAuthorize(EVERY_ROLE)
	CommentView deescalate(@PathVariable long id, @Valid @RequestBody ReasonForm body) {
		return comments.deescalate(id, body);
	}

	@PostMapping(path = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize(EVERY_ROLE)
	@ResponseStatus(HttpStatus.CREATED)
	CommentView attach(@PathVariable long id, @RequestParam("file") MultipartFile file) throws IOException {
		return comments.attach(id, file.getOriginalFilename(), file.getBytes());
	}

	@DeleteMapping("/{id}/attachments/{documentId}")
	@PreAuthorize(EVERY_ROLE)
	CommentView removeAttachment(@PathVariable long id, @PathVariable long documentId) {
		return comments.removeAttachment(id, documentId);
	}

	/** Always a download (never rendered in the browser), not cached - same shape as every other module's own
	 * document download endpoint. */
	@GetMapping("/{id}/attachments/{documentId}/content")
	@PreAuthorize(EVERY_ROLE)
	ResponseEntity<byte[]> download(@PathVariable long id, @PathVariable long documentId) {
		AttachmentFile file = comments.downloadAttachment(id, documentId);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(file.info().contentType()))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(file.info().fileName(), StandardCharsets.UTF_8).build()
						.toString())
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.body(file.content());
	}

}
