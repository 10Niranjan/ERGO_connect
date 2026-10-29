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

import com.ergo.connect.quotation.domain.QuoteDtos;
import com.ergo.connect.quotation.domain.QuoteDtos.CommentForm;
import com.ergo.connect.quotation.domain.QuoteDtos.DistributeForm;
import com.ergo.connect.quotation.domain.QuoteDtos.DocumentView;
import com.ergo.connect.quotation.domain.QuoteDtos.PartStatusRow;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteForm;
import com.ergo.connect.quotation.domain.QuoteDtos.QuoteView;
import com.ergo.connect.quotation.domain.QuoteDtos.RfqQuotes;
import com.ergo.connect.quotation.domain.QuoteDtos.VersionView;
import com.ergo.connect.quotation.domain.QuoteService;
import com.ergo.connect.quotation.domain.QuoteService.DocumentFile;

import jakarta.validation.Valid;

/**
 * SRS §10.5 Quotation Management and §18 quote APIs; flow doc §2-4. Layer 1 (which roles may attempt what) is here;
 * layer 2 (whose quotes, in which status) is QuoteService. Customers have no access here: ERGO generates their quotation.
 */
@RestController
class QuoteController {

	// The role sheet (ModuleAccess) decides. Customers never reach these endpoints (CustomerQuoteController is theirs),
	// whatever the sheet grants them on Quotation, hence the explicit sides.
	private static final String ERGO_VIEWERS = "@access.view('QUOTATION', 'ERGO')";

	private static final String ERGO_BUYERS = "@access.act('QUOTATION', 'ERGO')";

	private static final String EVALUATORS = "@access.approve('QUOTATION', 'ERGO')";

	private static final String SUPPLIERS = "@access.act('QUOTATION', 'SUPPLIER')";

	/** The supplier's assigned-RFQ pages belong to the RFQ column; the quote itself to Quotation (QuoteService). */
	private static final String SUPPLIER_RFQ_VIEWERS = "@access.view('RFQ', 'SUPPLIER')";

	private static final String QUOTE_VIEWERS = "@access.view('QUOTATION', 'ERGO') or @access.view('QUOTATION', 'SUPPLIER')";

	private static final String VIEWERS = "@access.view('QUOTATION', 'ERGO') or @access.view('RFQ', 'SUPPLIER')";

	private static final String UPLOADERS = "@access.act('QUOTATION', 'ERGO') or @access.act('QUOTATION', 'SUPPLIER')";

	private final QuoteService quotes;

	QuoteController(QuoteService quotes) {
		this.quotes = quotes;
	}

	// ---- ERGO: the suppliers of an RFQ

	@GetMapping("/api/rfqs/{rfqId}/quotes")
	@PreAuthorize(ERGO_VIEWERS)
	RfqQuotes forRfq(@PathVariable long rfqId) {
		return quotes.forRfq(rfqId);
	}

	@PostMapping("/api/rfqs/{rfqId}/quotes")
	@PreAuthorize(ERGO_BUYERS)
	RfqQuotes distribute(@PathVariable long rfqId, @Valid @RequestBody DistributeForm body) {
		return quotes.distribute(rfqId, body);
	}

	/** Each part's status: ERGO the full one, the RFQ's customer where its quotation stands (flow doc §4). */
	@GetMapping("/api/rfqs/{rfqId}/parts")
	@PreAuthorize("@access.view('QUOTATION', 'ERGO') or @access.view('QUOTATION', 'CUSTOMER')")
	List<PartStatusRow> partStatuses(@PathVariable long rfqId) {
		return quotes.partStatuses(rfqId);
	}

	// ---- quotes

	/** The supplier's "Assigned RFQs" (SRS §12.15). */
	@GetMapping("/api/quotes")
	@PreAuthorize(SUPPLIER_RFQ_VIEWERS)
	QuoteDtos.SupplierPage<?> mine(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
			@RequestParam(required = false) String status, @RequestParam(required = false) String q) {
		return quotes.mine(page, size, status, q);
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

	/** Flow doc §4 "Revision required": back to the supplier with the required changes. */
	@PostMapping("/api/quotes/{id}/revision")
	@PreAuthorize(ERGO_BUYERS)
	QuoteView requestRevision(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return quotes.requestRevision(id, body.comment());
	}

	/** Flow doc §4 "Accepted for customer submission". */
	@PostMapping("/api/quotes/{id}/accept")
	@PreAuthorize(EVALUATORS)
	QuoteView accept(@PathVariable long id) {
		return quotes.accept(id);
	}

	/** ERGO takes a part back from its supplier so it can go to another one. */
	@PostMapping("/api/quotes/{id}/recall")
	@PreAuthorize(ERGO_BUYERS)
	QuoteView recall(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return quotes.recall(id, body.comment());
	}

	@GetMapping("/api/quotes/{id}/versions")
	@PreAuthorize(QUOTE_VIEWERS)
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
