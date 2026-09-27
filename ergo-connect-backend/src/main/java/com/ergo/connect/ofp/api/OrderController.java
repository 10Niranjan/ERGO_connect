package com.ergo.connect.ofp.api;

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

import com.ergo.connect.ofp.domain.OfpDtos.CommentForm;
import com.ergo.connect.ofp.domain.OfpDtos.MaterialStatusForm;
import com.ergo.connect.ofp.domain.OfpDtos.OrderForm;
import com.ergo.connect.ofp.domain.OfpDtos.OrderHandoverForm;
import com.ergo.connect.ofp.domain.OfpDtos.OrderRow;
import com.ergo.connect.ofp.domain.OfpDtos.OrderUpdateForm;
import com.ergo.connect.ofp.domain.OfpDtos.OrderView;
import com.ergo.connect.ofp.domain.OfpDtos.ProductionProgressForm;
import com.ergo.connect.ofp.domain.OrderService;

import jakarta.validation.Valid;

/**
 * SRS §10.7 OFP order core, §14.6 workflow. Layer 1 (which roles may attempt what) is here; layer 2 (which orders,
 * in which status) is {@code OrderService}. 4a scope only: order creation (standalone or NPD handover) and
 * production tracking up to Packaging in Progress. Supplier/customer participation, packaging/dispatch and
 * shipments are 4b/4c.
 */
@RestController
@RequestMapping("/api/ofp")
class OrderController {

	/** SRS §10.7: full View/Create/Edit/Submit/Approve. ERGO Management has no create/edit/submit right in 4a. */
	private static final String EDITORS = "hasAnyRole('ERGO_ADMIN', 'ERGO_OFP')";

	/** EDITORS plus the view-only roles (ERGO Management, ERGO KAM within their assigned accounts, ERGO NPD within
	 * their own NPD-scoped orders); the service confines each to its own record scope. */
	private static final String VIEWERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_OFP', 'ERGO_KAM', "
			+ "'ERGO_NPD')";

	private final OrderService orders;

	OrderController(OrderService orders) {
		this.orders = orders;
	}

	@GetMapping("/orders")
	@PreAuthorize(VIEWERS)
	List<OrderRow> list() {
		return orders.list();
	}

	@GetMapping("/orders/{id}")
	@PreAuthorize(VIEWERS)
	OrderView get(@PathVariable long id) {
		return orders.get(id);
	}

	@PostMapping("/orders")
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	OrderView create(@Valid @RequestBody OrderForm body) {
		return orders.create(body);
	}

	/** NPD's "Handover to OFP" action (SRS §14.5/§14.6): {@code npdProjectId} names the source project, not the
	 * order itself. */
	@PostMapping("/orders/handover/{npdProjectId}")
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	OrderView handover(@PathVariable long npdProjectId, @Valid @RequestBody OrderHandoverForm body) {
		return orders.handover(npdProjectId, body);
	}

	@PutMapping("/orders/{id}")
	@PreAuthorize(EDITORS)
	OrderView update(@PathVariable long id, @Valid @RequestBody OrderUpdateForm body) {
		return orders.update(id, body);
	}

	@PostMapping("/orders/{id}/advance")
	@PreAuthorize(EDITORS)
	OrderView advance(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return orders.advance(id, body.comment());
	}

	@PostMapping("/orders/{id}/hold")
	@PreAuthorize(EDITORS)
	OrderView hold(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return orders.hold(id, body.comment());
	}

	@PostMapping("/orders/{id}/release")
	@PreAuthorize(EDITORS)
	OrderView release(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return orders.release(id, body.comment());
	}

	@PostMapping("/orders/{id}/material-status")
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	OrderView addMaterialStatus(@PathVariable long id, @Valid @RequestBody MaterialStatusForm body) {
		return orders.addMaterialStatus(id, body);
	}

	@PutMapping("/material-status/{statusId}")
	@PreAuthorize(EDITORS)
	OrderView updateMaterialStatus(@PathVariable long statusId, @Valid @RequestBody MaterialStatusForm body) {
		return orders.updateMaterialStatus(statusId, body);
	}

	@PostMapping("/orders/{id}/production-progress")
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	OrderView addProductionProgress(@PathVariable long id, @Valid @RequestBody ProductionProgressForm body) {
		return orders.addProductionProgress(id, body);
	}

	@PutMapping("/production-progress/{progressId}")
	@PreAuthorize(EDITORS)
	OrderView updateProductionProgress(@PathVariable long progressId, @Valid @RequestBody ProductionProgressForm body) {
		return orders.updateProductionProgress(progressId, body);
	}

}
