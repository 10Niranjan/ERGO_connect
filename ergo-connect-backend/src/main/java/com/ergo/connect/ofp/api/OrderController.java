package com.ergo.connect.ofp.api;

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

import com.ergo.connect.ofp.domain.OfpDtos.CommentForm;
import com.ergo.connect.ofp.domain.OfpDtos.MaterialStatusForm;
import com.ergo.connect.ofp.domain.OfpDtos.OrderForm;
import com.ergo.connect.ofp.domain.OfpDtos.OrderHandoverForm;
import com.ergo.connect.ofp.domain.OfpDtos.OrderDetail;
import com.ergo.connect.ofp.domain.OfpDtos.OrderSummary;
import com.ergo.connect.ofp.domain.OfpDtos.OrderUpdateForm;
import com.ergo.connect.ofp.domain.OfpDtos.PackagingDispatchForm;
import com.ergo.connect.ofp.domain.OfpDtos.ProductionProgressForm;
import com.ergo.connect.ofp.domain.OfpDtos.ShipmentForm;
import com.ergo.connect.common.audit.AuditLogService.AuditEntry;
import com.ergo.connect.ofp.domain.OrderService;
import com.ergo.connect.ofp.domain.OrderService.PodFile;

import jakarta.validation.Valid;

/**
 * SRS §10.7 OFP order core, §14.6 workflow. Layer 1 (which roles may attempt what) is here; layer 2 (which orders,
 * in which status, and — from 4b — whose own assigned order) is {@code OrderService}. 4a: order creation (standalone
 * or NPD handover) and production tracking up to Packaging in Progress, ERGO-only. 4b: the assigned supplier's own
 * scoped edits (production progress, packaging/dispatch) and the ERGO-only, inspection-gated Dispatch Ready
 * transition. 4c: shipments/tracking (ERGO-only writes, ERGO+customer reads — the supplier is excluded from POD
 * access entirely, security review H1/L5), Dispatch/In Transit/Delivered/Close Order (ERGO-only), and Customer OFP's
 * read-only access.
 */
@RestController
@RequestMapping("/api/ofp")
class OrderController {

	/** SRS §10.7: full View/Create/Edit/Submit/Approve. ERGO Management has no create/edit/submit right in 4a, and
	 * shipments/status transitions (4c) never gain a wider write grant than this. */
	private static final String EDITORS = "hasAnyRole('ERGO_ADMIN', 'ERGO_OFP')";

	/** EDITORS plus the four supplier roles' own assigned-order writes (SRS §10.7 "E**"): production progress and
	 * packaging/dispatch only — {@code OrderService} confines each to its own supplier company and these two write
	 * actions specifically (never material status, header fields, hold/release/advance, or shipments). */
	private static final String EDITORS_AND_SUPPLIER = "hasAnyRole('ERGO_ADMIN', 'ERGO_OFP', 'SUPPLIER_NPD', "
			+ "'SUPPLIER_OFP', 'SUPPLIER_QUALITY', 'SUPPLIER_KAM')";

	/** EDITORS plus the view-only roles (ERGO Management, ERGO KAM within their assigned accounts, ERGO NPD within
	 * their own NPD-scoped orders), the four supplier roles reaching their own assigned orders (SRS §10.7 "RV",
	 * 4b), and, from 4c, Customer OFP reaching its own company's orders (also "RV"); the service confines each to
	 * its own record scope. */
	private static final String VIEWERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_OFP', 'ERGO_KAM', "
			+ "'ERGO_NPD', 'SUPPLIER_NPD', 'SUPPLIER_OFP', 'SUPPLIER_QUALITY', 'SUPPLIER_KAM', 'CUSTOMER_OFP')";

	/** ERGO roles only. Suppliers and customers get no POD file: it carries the supplier's letterhead/file name, which
	 * would break the customer-never-sees-supplier rule. Customers see receipt fields only (ARCHITECTURE.md §8). */
	private static final String POD_READERS = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT', 'ERGO_OFP', 'ERGO_KAM', "
			+ "'ERGO_NPD')";

	private final OrderService orders;

	OrderController(OrderService orders) {
		this.orders = orders;
	}

	@GetMapping("/orders")
	@PreAuthorize(VIEWERS)
	List<? extends OrderSummary> list() {
		return orders.list();
	}

	@GetMapping("/orders/{id}")
	@PreAuthorize(VIEWERS)
	OrderDetail get(@PathVariable long id) {
		return orders.get(id);
	}

	@GetMapping("/orders/{id}/history")
	@PreAuthorize(VIEWERS)
	List<AuditEntry> history(@PathVariable long id) {
		return orders.history(id);
	}

	@PostMapping("/orders")
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	OrderDetail create(@Valid @RequestBody OrderForm body) {
		return orders.create(body);
	}

	/** NPD's "Handover to OFP" action (SRS §14.5/§14.6): {@code npdProjectId} names the source project, not the
	 * order itself. */
	@PostMapping("/orders/handover/{npdProjectId}")
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	OrderDetail handover(@PathVariable long npdProjectId, @Valid @RequestBody OrderHandoverForm body) {
		return orders.handover(npdProjectId, body);
	}

	@PutMapping("/orders/{id}")
	@PreAuthorize(EDITORS)
	OrderDetail update(@PathVariable long id, @Valid @RequestBody OrderUpdateForm body) {
		return orders.update(id, body);
	}

	@PostMapping("/orders/{id}/advance")
	@PreAuthorize(EDITORS)
	OrderDetail advance(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return orders.advance(id, body.comment());
	}

	@PostMapping("/orders/{id}/hold")
	@PreAuthorize(EDITORS)
	OrderDetail hold(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return orders.hold(id, body.comment());
	}

	@PostMapping("/orders/{id}/release")
	@PreAuthorize(EDITORS)
	OrderDetail release(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return orders.release(id, body.comment());
	}

	@PostMapping("/orders/{id}/material-status")
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	OrderDetail addMaterialStatus(@PathVariable long id, @Valid @RequestBody MaterialStatusForm body) {
		return orders.addMaterialStatus(id, body);
	}

	@PutMapping("/material-status/{statusId}")
	@PreAuthorize(EDITORS)
	OrderDetail updateMaterialStatus(@PathVariable long statusId, @Valid @RequestBody MaterialStatusForm body) {
		return orders.updateMaterialStatus(statusId, body);
	}

	@PostMapping("/orders/{id}/production-progress")
	@PreAuthorize(EDITORS_AND_SUPPLIER)
	@ResponseStatus(HttpStatus.CREATED)
	OrderDetail addProductionProgress(@PathVariable long id, @Valid @RequestBody ProductionProgressForm body) {
		return orders.addProductionProgress(id, body);
	}

	@PutMapping("/production-progress/{progressId}")
	@PreAuthorize(EDITORS_AND_SUPPLIER)
	OrderDetail updateProductionProgress(@PathVariable long progressId, @Valid @RequestBody ProductionProgressForm body) {
		return orders.updateProductionProgress(progressId, body);
	}

	@PostMapping("/orders/{id}/packaging-dispatch")
	@PreAuthorize(EDITORS_AND_SUPPLIER)
	@ResponseStatus(HttpStatus.CREATED)
	OrderDetail addPackagingDispatch(@PathVariable long id, @Valid @RequestBody PackagingDispatchForm body) {
		return orders.addPackagingDispatch(id, body);
	}

	@PutMapping("/packaging-dispatch/{dispatchId}")
	@PreAuthorize(EDITORS_AND_SUPPLIER)
	OrderDetail updatePackagingDispatch(@PathVariable long dispatchId, @Valid @RequestBody PackagingDispatchForm body) {
		return orders.updatePackagingDispatch(dispatchId, body);
	}

	/** SRS §14.6/§17.5: Packaging in Progress -> Dispatch Ready, ERGO OFP/Admin only, gated on every material
	 * inspection having passed. A supplier's own {@code dispatchReadyFlag} claim (packaging-dispatch endpoints above)
	 * never triggers this by itself. */
	@PostMapping("/orders/{id}/dispatch-ready")
	@PreAuthorize(EDITORS)
	OrderDetail dispatchReady(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return orders.dispatchReady(id, body.comment());
	}

	// ---------------------------------------------------------------- Phase 4c: shipments and dispatch onward

	@PostMapping("/orders/{id}/shipments")
	@PreAuthorize(EDITORS)
	@ResponseStatus(HttpStatus.CREATED)
	OrderDetail createShipment(@PathVariable long id, @Valid @RequestBody ShipmentForm body) {
		return orders.createShipment(id, body);
	}

	@PutMapping("/shipments/{shipmentId}")
	@PreAuthorize(EDITORS)
	OrderDetail updateShipment(@PathVariable long shipmentId, @Valid @RequestBody ShipmentForm body) {
		return orders.updateShipment(shipmentId, body);
	}

	/** POD upload (SRS §13.5.5): reuses the Documents module, ERGO OFP/Admin only. */
	@PostMapping(path = "/shipments/{shipmentId}/pod", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize(EDITORS)
	OrderDetail uploadPod(@PathVariable long shipmentId, @RequestParam("file") MultipartFile file)
			throws IOException {
		return orders.uploadPod(shipmentId, file.getOriginalFilename(), file.getBytes());
	}

	/** L5 fix (ARCHITECTURE.md §8 2026-09-29): ERGO (any order in its scope) or the order's own customer only — the
	 * supplier is excluded at this role layer, matching H1's removal of the POD reference from its shipment view.
	 * Always a download (never rendered in the browser), not cached, same shape as the Documents/RFQ precedent. */
	@GetMapping("/shipments/{shipmentId}/pod")
	@PreAuthorize(POD_READERS)
	ResponseEntity<byte[]> downloadPod(@PathVariable long shipmentId) {
		PodFile file = orders.downloadPod(shipmentId);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(file.info().contentType()))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(file.info().fileName(), StandardCharsets.UTF_8).build()
						.toString())
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.body(file.content());
	}

	/** SRS §14.6/§17.5: Dispatch Ready -> Dispatched, ERGO OFP/Admin only, re-checks the inspection gate. */
	@PostMapping("/orders/{id}/dispatch")
	@PreAuthorize(EDITORS)
	OrderDetail dispatch(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return orders.dispatch(id, body.comment());
	}

	@PostMapping("/orders/{id}/in-transit")
	@PreAuthorize(EDITORS)
	OrderDetail markInTransit(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return orders.markInTransit(id, body.comment());
	}

	/** SRS §14.6: In Transit -> Delivered, ERGO OFP/Admin only, requires a POD on file. */
	@PostMapping("/orders/{id}/delivered")
	@PreAuthorize(EDITORS)
	OrderDetail markDelivered(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return orders.markDelivered(id, body.comment());
	}

	/** SRS §14.6: Delivered -> Closed, ERGO OFP/Admin only, no automatic transition. */
	@PostMapping("/orders/{id}/close")
	@PreAuthorize(EDITORS)
	OrderDetail closeOrder(@PathVariable long id, @Valid @RequestBody CommentForm body) {
		return orders.closeOrder(id, body.comment());
	}

}
