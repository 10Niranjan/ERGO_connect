package com.ergo.connect.masterdata.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ergo.connect.masterdata.access.CompanyAssessmentDtos.AssessmentList;
import com.ergo.connect.masterdata.access.CompanyAssessmentDtos.AssessmentRequest;
import com.ergo.connect.masterdata.access.CompanyAssessmentDtos.AssessmentView;
import com.ergo.connect.masterdata.access.CompanyAssessmentService;
import com.ergo.connect.masterdata.access.CompanyAssessmentService.ReportFile;

import jakarta.validation.Valid;

/**
 * Supplier audit reports and customer assessments (client questions 28, 29). ERGO roles only (layer 1); which
 * companies and who may add is decided in CompanyAssessmentService (layer 2).
 */
@RestController
@RequestMapping("/api/companies/{companyId}/assessments")
class CompanyAssessmentController {

	private static final String VIEWERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_RFQ', 'ERGO_KAM', 'ERGO_QUALITY')";

	private static final String ADDERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_RFQ', 'ERGO_KAM', 'ERGO_QUALITY')";

	private final CompanyAssessmentService assessments;

	CompanyAssessmentController(CompanyAssessmentService assessments) {
		this.assessments = assessments;
	}

	@GetMapping
	@PreAuthorize(VIEWERS)
	AssessmentList list(@PathVariable long companyId) {
		return assessments.list(companyId);
	}

	/** Multipart: an {@code assessment} JSON part and, for a supplier audit, the report {@code file}. */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize(ADDERS)
	@ResponseStatus(HttpStatus.CREATED)
	AssessmentView add(@PathVariable long companyId, @Valid @RequestPart("assessment") AssessmentRequest assessment,
			@RequestPart(name = "file", required = false) MultipartFile file) throws IOException {
		return assessments.add(companyId, assessment, file == null ? null : file.getOriginalFilename(),
				file == null ? null : file.getBytes());
	}

	@GetMapping("/{assessmentId}/file")
	@PreAuthorize(VIEWERS)
	ResponseEntity<byte[]> file(@PathVariable long companyId, @PathVariable long assessmentId) {
		ReportFile file = assessments.file(companyId, assessmentId);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(file.info().contentType()))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(file.info().fileName(), StandardCharsets.UTF_8).build()
						.toString())
			.body(file.content());
	}

}
