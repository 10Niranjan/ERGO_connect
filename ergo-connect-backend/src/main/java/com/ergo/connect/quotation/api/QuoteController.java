package com.ergo.connect.quotation.api;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ergo.connect.quotation.domain.QuoteDtos.CommentForm;
import com.ergo.connect.quotation.domain.QuoteDtos.DecisionForm;
import com.ergo.connect.quotation.domain.QuoteDtos.DocumentView;
import com.ergo.connect.quotation.domain.QuoteDtos.InviteForm;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteForm;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteView;
import com.ergo.connect.quotation.domain.QuoteDtos.RfqSuppliers;
import com.ergo.connect.quotation.domain.QuoteDtos.SupplierQuoteRow;
import com.ergo.connect.quotation.domain.QuoteDtos.VersionView;
import com.ergo.connect.quotation.domain.QuoteService;
import com.ergo.connect.quotation.domain.QuoteService.DocumentFile;

import jakarta.validation.Valid;

/**
 * SRS §10.5 Quotation Management and §18 quote APIs. Layer 1 (which roles may attempt what) is here; layer 2 (whose
 * quotes, in which status) is QuoteService. Customers have no access: ERGO sends them quotes in Phase 2c.
 */
@RestController
class QuoteController {

	private static final String ERGO_VIEWERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_RFQ', 'ERGO_KAM', "
			+ "'ERGO_NPD')";

	private static final String ERGO_BUYERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_RFQ')";

	private static final String EVALUATORS = "hasAnyRole('ERGO_ADMIN', 'ERGO_RFQ')";

	private static final String SUPPLIERS = "hasAnyRole('SUPPLIER_RFQ', 'SUPPLIER_KAM')";

	private static final String VIEWERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_RFQ', 'ERGO_KAM', "
			+ "'ERGO_NPD', 'SUPPLIER_RFQ', 'SUPPLIER_KAM')";

	private static final String UPLOADERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_RFQ', "
			+ "'SUPPLIER_RFQ', 'SUPPLIER_KAM')";

	private final QuoteService quotes;

	QuoteController(QuoteService quotes) {
		this.quotes = quotes;
	}

	// ---- ERGO: the suppliers of an RFQ

	@GetMapping("/api/rfqs/{rfqId}/quotes")
	@PreAuthorize(ERGO_VIEWERS)
	RfqSuppliers forRfq(@PathVariable long rfqId) {
		return quotes.forRfq(rfqId);
	}

	@PostMapping("/api/rfqs/{rfqId}/quotes")
	@PreAuthorize(ERGO_BUYERS)
	RfqSuppliers invite(@PathVariable long rfqId, @Valid @RequestBody InviteForm body) {
		return quotes.invite(rfqId, body.supplierIds(), body.responseDueDate());
	}

	// ---- quotes

	/** The supplier's "Assigned RFQs" (SRS §12.15). */
	@GetMapping("/api/quotes")
	@PreAuthorize(SUPPLIERS)
	List<SupplierQuoteRow> mine() {
		return quotes.mine();
	}

	@GetMapping("/api/quotes/{id}")
	@PreAuthorize(VIEWERS)
	QuoteView get(@PathVariable long id) {
		return quotes.get(id);
	}

	@PutMapping("/api/quotes/{id}")
	@PreAuthorize(SUPPLIERS)
	QuoteView saveDraft(@PathVariable long id, @Valid @RequestBody QuoteForm body) {
		return quotes.saveDraft(id, body);
	}

	@PostMapping("/api/quotes/{id}/submit")
	@PreAuthorize(SUPPLIERS)
	QuoteView submit(@PathVariable long id) {
		return quotes.submit(id);
	}

	@PostMapping("/api/quotes/{id}/regret")
	@PreAuthorize(SUPPLIERS)
	QuoteView regret(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return quotes.regret(id, body.comment());
	}

	@PostMapping("/api/quotes/{id}/request-clarification")
	@PreAuthorize(SUPPLIERS)
	QuoteView requestClarification(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return quotes.requestClarification(id, body.comment());
	}

	@PostMapping("/api/quotes/{id}/answer-clarification")
	@PreAuthorize(ERGO_BUYERS)
	QuoteView answerClarification(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return quotes.answerClarification(id, body.comment());
	}

	@PostMapping("/api/quotes/{id}/reopen")
	@PreAuthorize(ERGO_BUYERS)
	QuoteView reopen(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return quotes.reopen(id, body.comment());
	}

	/** SRS §17.3 shortlist / select for customer / not selected. */
	@PostMapping("/api/quotes/{id}/decision")
	@PreAuthorize(EVALUATORS)
	QuoteView decide(@PathVariable long id, @Valid @RequestBody DecisionForm body) {
		return quotes.decide(id, body.decision(), body.comment());
	}

	@GetMapping("/api/quotes/{id}/versions")
	@PreAuthorize(VIEWERS)
	List<VersionView> versions(@PathVariable long id) {
		return quotes.versions(id);
	}

	// ---- files

	@GetMapping("/api/quotes/{id}/documents")
	@PreAuthorize(VIEWERS)
	List<DocumentView> documents(@PathVariable long id) {
		return quotes.documents(id);
	}

	@PostMapping(path = "/api/quotes/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize(UPLOADERS)
	@ResponseStatus(HttpStatus.CREATED)
	DocumentView upload(@PathVariable long id, @RequestParam("file") MultipartFile file) throws IOException {
		return quotes.upload(id, file.getOriginalFilename(), file.getBytes());
	}

	/** {@code source} RFQ = a file ERGO shared from the RFQ; QUOTE = a file attached to this quote. */
	@GetMapping("/api/quotes/{id}/documents/{documentId}/content")
	@PreAuthorize(VIEWERS)
	ResponseEntity<byte[]> download(@PathVariable long id, @PathVariable long documentId,
			@RequestParam(defaultValue = "QUOTE") String source) {
		DocumentFile file = quotes.download(id, source, documentId);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(file.info().contentType()))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(file.info().fileName(), StandardCharsets.UTF_8).build()
						.toString())
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.body(file.content());
	}

	@DeleteMapping("/api/quotes/{id}/documents/{documentId}")
	@PreAuthorize(UPLOADERS)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteDocument(@PathVariable long id, @PathVariable long documentId) {
		quotes.deleteDocument(id, documentId);
	}

}
