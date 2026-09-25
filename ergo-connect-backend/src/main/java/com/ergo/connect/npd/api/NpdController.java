package com.ergo.connect.npd.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ergo.connect.npd.domain.NpdDtos.ActivityForm;
import com.ergo.connect.npd.domain.NpdDtos.CommentForm;
import com.ergo.connect.npd.domain.NpdDtos.ConversionQueueRow;
import com.ergo.connect.npd.domain.NpdDtos.NpdDetailView;
import com.ergo.connect.npd.domain.NpdDtos.NpdListRow;
import com.ergo.connect.npd.domain.NpdDtos.NpdProjectForm;
import com.ergo.connect.npd.domain.NpdService;

import jakarta.validation.Valid;

/**
 * SRS §10.6 NPD Management, §18 NPD APIs, §14.5 workflow. Layer 1 (which roles may attempt what) is here; layer 2
 * (which projects, in which stage) is NpdService. Customer and supplier NPD roles get nothing in 3a (3b).
 */
@RestController
@RequestMapping("/api/npd")
class NpdController {

	private static final String VIEWERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_RFQ', 'ERGO_KAM', "
			+ "'ERGO_NPD')";

	private static final String EDITORS = "hasAnyRole('ERGO_ADMIN', 'ERGO_NPD')";

	private final NpdService npd;

	NpdController(NpdService npd) {
		this.npd = npd;
	}

	@GetMapping("/conversion-queue")
	@PreAuthorize(VIEWERS)
	List<ConversionQueueRow> conversionQueue() {
		return npd.conversionQueue();
	}

	@PostMapping("/convert/{rfqId}")
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	NpdDetailView convert(@PathVariable long rfqId, @Valid @RequestBody CommentForm body) {
		return npd.convert(rfqId, body.comment());
	}

	@GetMapping
	@PreAuthorize(VIEWERS)
	List<NpdListRow> list() {
		return npd.list();
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEWERS)
	NpdDetailView get(@PathVariable long id) {
		return npd.get(id);
	}

	@PutMapping("/{id}")
	@PreAuthorize(EDITORS)
	NpdDetailView updateProject(@PathVariable long id, @Valid @RequestBody NpdProjectForm body) {
		return npd.updateProject(id, body);
	}

	@PostMapping("/{id}/close-stage")
	@PreAuthorize(EDITORS)
	NpdDetailView closeStage(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return npd.closeStage(id, body.comment());
	}

	@PostMapping("/{id}/activities")
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	NpdDetailView addActivity(@PathVariable long id, @Valid @RequestBody ActivityForm body) {
		return npd.addActivity(id, body);
	}

	@PutMapping("/activities/{activityId}")
	@PreAuthorize(EDITORS)
	NpdDetailView updateActivity(@PathVariable long activityId, @Valid @RequestBody ActivityForm body) {
		return npd.updateActivity(activityId, body);
	}

	@PostMapping("/activities/{activityId}/block")
	@PreAuthorize(EDITORS)
	NpdDetailView markBlocked(@PathVariable long activityId, @Valid @RequestBody CommentForm body) {
		return npd.markBlocked(activityId, body.comment());
	}

	@PostMapping("/activities/{activityId}/unblock")
	@PreAuthorize(EDITORS)
	NpdDetailView unblock(@PathVariable long activityId, @Valid @RequestBody CommentForm body) {
		return npd.unblock(activityId, body.comment());
	}

}
