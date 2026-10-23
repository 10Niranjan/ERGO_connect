package com.ergo.connect.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ergo.connect.IntegrationTest;
import com.ergo.connect.TestRfq;
import com.ergo.connect.TestUsers;
import com.ergo.connect.TestUsers.Login;
import com.ergo.connect.documents.domain.EicarOnlyVirusScanner;
import com.jayway.jsonpath.JsonPath;

/**
 * Phase 6a: the Documents module's Library/Detail, edit, versioning and lock/approval surface (SRS §10.9, §12.11,
 * §14 docs states) - record scope across all 4 linked modules (rfq, quotation, OFP_SHIPMENT, QUALITY_CAPA) with 2
 * customers and 2 suppliers, the exact edit-lock boundary, version-chain correctness, hard-delete guards and the
 * admin-only lock/approve/reject workflow (allowed/forbidden/missing-reason/illegal-transition).
 */
@IntegrationTest
class DocumentsTests {

	private static final AtomicInteger SEQ = new AtomicInteger(900_000);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestUsers users;

	long ergo;

	long customerA;

	long customerB;

	long supplierX;

	long supplierY;

	long inr;

	Login ergoAdmin;

	Login ergoRfq;

	Login ergoManagement;

	Login ergoQuality;

	Login ergoOfp;

	Login buyerA;

	Login buyerB;

	Login sellerX;

	Login sellerY;

	Login supplierXQuality;

	Login supplierYQuality;

	Login customerAOfp;

	Login customerBOfp;

	Login supplierXOfp;

	Login customerAQuality;

	@BeforeEach
	void setUp() throws Exception {
		users = new TestUsers(jdbc, mvc);
		ergo = users.company("ERGO");
		customerA = users.company("CUSTOMER");
		customerB = users.company("CUSTOMER");
		supplierX = users.company("SUPPLIER");
		supplierY = users.company("SUPPLIER");
		inr = users.currency("INR");
		ergoAdmin = users.login(ergo, "ERGO_ADMIN");
		ergoRfq = users.login(ergo, "ERGO_RFQ");
		ergoManagement = users.login(ergo, "ERGO_MANAGEMENT");
		ergoQuality = users.login(ergo, "ERGO_QUALITY");
		ergoOfp = users.login(ergo, "ERGO_OFP");
		buyerA = users.login(customerA, "CUSTOMER_RFQ");
		buyerB = users.login(customerB, "CUSTOMER_RFQ");
		sellerX = users.login(supplierX, "SUPPLIER_RFQ");
		sellerY = users.login(supplierY, "SUPPLIER_RFQ");
		supplierXQuality = users.login(supplierX, "SUPPLIER_QUALITY");
		supplierYQuality = users.login(supplierY, "SUPPLIER_QUALITY");
		customerAOfp = users.login(customerA, "CUSTOMER_OFP");
		customerBOfp = users.login(customerB, "CUSTOMER_OFP");
		supplierXOfp = users.login(supplierX, "SUPPLIER_OFP");
		customerAQuality = users.login(customerA, "CUSTOMER_QUALITY");
	}

	// ---------------------------------------------------------------- categories: any authenticated role

	@Test
	void anyAuthenticatedRoleReadsTheCategoryList() throws Exception {
		call(get("/api/documents/categories"), sellerX, null).andExpect(status().isOk())
			.andExpect(jsonPath("$[*].name", hasItem("Drawing")))
			.andExpect(jsonPath("$", hasSize(7)));
	}

	// ---------------------------------------------------------------- rfq-linked: record scope, 2 customers

	@Test
	void customerCannotSeeAnotherCustomersRfqDocument() throws Exception {
		long rfqId = draftRfq(buyerA);
		long docId = idOf(upload("/api/rfqs/{id}/documents", rfqId, buyerA, "drawing.pdf"));

		call(get("/api/documents/{id}", docId), buyerA, null).andExpect(status().isOk());
		call(get("/api/documents/{id}", docId), buyerB, null).andExpect(status().isNotFound());
		call(get("/api/documents/{id}/content", docId), buyerB, null).andExpect(status().isNotFound());
		call(get("/api/documents?linkedModule=rfq&uploaderCompanyId=" + customerA), buyerB, null)
			.andExpect(jsonPath("$.items", hasSize(0)));
		call(get("/api/documents?linkedModule=rfq&uploaderCompanyId=" + customerA), ergoAdmin, null)
			.andExpect(jsonPath("$.items[*].id", hasItem((int) docId)));
	}

	@Test
	void internalVisibilityNeverReachesTheCustomerEvenItsOwnRfq() throws Exception {
		long rfqId = draftRfq(buyerA);
		long docId = idOf(upload("/api/rfqs/{id}/documents", rfqId, ergoRfq, "costing.xlsx")); // no visibility -> INTERNAL

		call(get("/api/documents/{id}", docId), buyerA, null).andExpect(status().isNotFound());
		call(get("/api/documents?linkedModule=rfq&uploaderCompanyId=" + ergo), buyerA, null)
			.andExpect(jsonPath("$.items", hasSize(0)));
		call(get("/api/documents/{id}", docId), ergoAdmin, null).andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- rfq-linked: supplier invitation scope

	@Test
	void onlyAnInvitedSupplierSeesAnRfqDocumentSharedWithSuppliers() throws Exception {
		long rfqId = validatedRfq(buyerA);
		long docId = idOf(upload("/api/rfqs/{id}/documents", rfqId, ergoRfq, "drawing.pdf"));
		call(put("/api/rfqs/{id}/documents/{doc}/visibility", rfqId, docId), ergoRfq, "{\"visibility\": \"ALL\", \"itemIds\": %s}".formatted(com.ergo.connect.TestRfq.itemIds(mvc, ergoRfq, rfqId)))
			.andExpect(status().isOk());
		invite(rfqId, supplierX);

		call(get("/api/documents/{id}", docId), sellerX, null).andExpect(status().isOk());
		call(get("/api/documents/{id}/content", docId), sellerX, null).andExpect(status().isOk());
		call(get("/api/documents/{id}", docId), sellerY, null).andExpect(status().isNotFound());
		call(get("/api/documents?linkedModule=rfq&uploaderCompanyId=" + ergo), sellerY, null)
			.andExpect(jsonPath("$.items", hasSize(0)));
	}

	@Test
	void uploaderIdentityIsHiddenFromTheOppositeExternalParty() throws Exception {
		long rfqId = draftRfq(buyerA);
		long docId = idOf(upload("/api/rfqs/{id}/documents", rfqId, buyerA, "spec.pdf")); // forced CUSTOMER visibility
		validate(rfqId, buyerA);
		// shared with suppliers for the RFQ's parts (a file shared without parts reaches no supplier)
		call(put("/api/rfqs/{id}/documents/{doc}/visibility", rfqId, docId), ergoRfq,
				"{\"visibility\": \"ALL\", \"itemIds\": %s}".formatted(com.ergo.connect.TestRfq.itemIds(mvc, ergoRfq, rfqId)))
			.andExpect(status().isOk());
		invite(rfqId, supplierX);

		call(get("/api/documents/{id}", docId), sellerX, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.uploadedBy").value(nullValue()));
		call(get("/api/documents/{id}", docId), buyerA, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.uploadedBy").exists());
		call(get("/api/documents/{id}", docId), ergoAdmin, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.uploadedBy").exists());
	}

	// ---------------------------------------------------------------- quotation-linked: record scope, 2 suppliers

	@Test
	void supplierCannotSeeAnotherSuppliersQuoteAttachmentAndCustomerNeverSeesIt() throws Exception {
		long rfqId = validatedRfq(buyerA);
		invite(rfqId, supplierX, supplierY);
		long invitationX = invitationOf(sellerX, rfqId);
		long docId = idOf(upload("/api/quotes/{id}/documents", invitationX, sellerX, "x-quote.pdf"));

		call(get("/api/documents/{id}", docId), sellerX, null).andExpect(status().isOk());
		call(get("/api/documents/{id}", docId), sellerY, null).andExpect(status().isNotFound());
		call(get("/api/documents/{id}", docId), buyerA, null).andExpect(status().isNotFound()); // quotation: C never V
		// M2: the supplier's quote is still a draft, so ERGO must not see its file here either (QuoteService.canSee).
		call(get("/api/documents/{id}", docId), ergoRfq, null).andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- OFP_SHIPMENT-linked: record scope

	@Test
	void podIsErgoOnlyRegardlessOfOrderRecordScope() throws Exception {
		long shipmentId = dispatchedShipment(customerA, supplierX);
		String response = uploadPod(shipmentId, ergoOfp).andReturn().getResponse().getContentAsString();
		long docId = ((Number) JsonPath
			.<List<Number>>read(response, "$.shipments[?(@.id == " + shipmentId + ")].podDocumentId").get(0))
			.longValue();

		// C1 fix (ARCHITECTURE.md §8, Phase 6a security review): the POD file is ERGO-only, per
		// OrderController#POD_READERS - customerAOfp genuinely passes the order's own record scope (it is this
		// order's own customer), so this 404 demonstrates the POD-specific visibility layer blocking it, not merely
		// a role/company mismatch. Customers see receipt fields only, never the file itself.
		call(get("/api/documents/{id}", docId), customerAOfp, null).andExpect(status().isNotFound());
		call(get("/api/documents/{id}", docId), customerBOfp, null).andExpect(status().isNotFound());
		// H1 precedent: the supplier is excluded from POD regardless of record scope (visibility = CUSTOMER only) -
		// supplierXOfp genuinely passes the order's own record scope (it is this order's supplier), so this 404
		// demonstrates the document-visibility layer blocking it, not merely a role/company mismatch.
		call(get("/api/documents/{id}", docId), supplierXOfp, null).andExpect(status().isNotFound());
		call(get("/api/documents/{id}", docId), ergoAdmin, null).andExpect(status().isOk());
		call(get("/api/documents/{id}", docId), ergoOfp, null).andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- QUALITY_CAPA-linked: record scope

	@Test
	void onlyTheNcrsOwnSupplierSeesCapaEvidenceAndCustomerNeverDoes() throws Exception {
		long ncrId = createNcr(customerA, supplierX);
		long capaId = createCapaAction(ncrId);
		String response = uploadEvidence(ncrId, capaId, supplierXQuality).andReturn().getResponse()
			.getContentAsString();
		long docId = ((Number) JsonPath.read(response, "$.capaActions[0].evidenceDocumentId")).longValue();

		call(get("/api/documents/{id}", docId), supplierXQuality, null).andExpect(status().isOk());
		// supplierYQuality genuinely has NCR record-scope for its OWN company's NCRs, so this 404 demonstrates
		// cross-company leakage protection, not merely a role that never sees NCRs at all.
		call(get("/api/documents/{id}", docId), supplierYQuality, null).andExpect(status().isNotFound());
		// H7 fix (ARCHITECTURE.md §8, Phase 6a security review): customerAQuality is blocked unconditionally - CAPA
		// evidence is never shown to a customer, full stop, regardless of customerVisible or NCR record scope
		// (NcrService#downloadEvidence's own rule) - not merely because customerVisible defaults to false.
		call(get("/api/documents/{id}", docId), customerAQuality, null).andExpect(status().isNotFound());
		call(get("/api/documents/{id}", docId), ergoQuality, null).andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- edit permission: the exact lock boundary

	@Test
	void customerCanEditItsOwnUploadRightUntilItIsLockedThenNoLonger() throws Exception {
		long rfqId = draftRfq(buyerA);
		long docId = idOf(upload("/api/rfqs/{id}/documents", rfqId, buyerA, "drawing.pdf"));
		long category = drawingCategoryId();

		call(patch("/api/documents/{id}", docId), buyerA, "{\"documentCategoryId\": %d}".formatted(category))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.category").value("Drawing"));

		call(post("/api/documents/{id}/lock", docId), ergoAdmin, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.lockStatus").value("LOCKED"));

		call(patch("/api/documents/{id}", docId), buyerA, "{\"documentCategoryId\": %d}".formatted(category))
			.andExpect(status().isConflict());
		// M7 (client decision): a locked document is final for ERGO too, until Admin unlocks it.
		call(patch("/api/documents/{id}", docId), ergoRfq, "{\"documentCategoryId\": %d}".formatted(category))
			.andExpect(status().isConflict());
		call(patch("/api/documents/{id}", docId), ergoAdmin, "{\"documentCategoryId\": %d}".formatted(category))
			.andExpect(status().isConflict());
		call(post("/api/documents/{id}/unlock", docId), ergoAdmin, null).andExpect(status().isOk());
		call(patch("/api/documents/{id}", docId), ergoRfq, "{\"documentCategoryId\": %d}".formatted(category))
			.andExpect(status().isOk());
	}

	@Test
	void customerCannotEditAnotherCompanysUpload() throws Exception {
		long rfqId = draftRfq(buyerA);
		long docId = idOf(upload("/api/rfqs/{id}/documents", rfqId, buyerA, "drawing.pdf"));
		call(patch("/api/documents/{id}", docId), buyerB, "{\"visibility\": \"ALL\"}")
			.andExpect(status().isNotFound()); // out of record scope entirely for customer B
	}

	@Test
	void customerMayNotMarkItsOwnUploadInternal() throws Exception {
		long rfqId = draftRfq(buyerA);
		long docId = idOf(upload("/api/rfqs/{id}/documents", rfqId, buyerA, "drawing.pdf"));
		call(patch("/api/documents/{id}", docId), buyerA, "{\"visibility\": \"INTERNAL\"}")
			.andExpect(status().isUnprocessableContent());
	}

	// ---------------------------------------------------------------- versioning: replace = supersede

	@Test
	void replaceSupersedesKeepsTheOldVersionAndBlocksHardDeleteOnceSuperseded() throws Exception {
		long rfqId = draftRfq(buyerA);
		long v1 = idOf(upload("/api/rfqs/{id}/documents", rfqId, buyerA, "drawing-v1.pdf"));

		MvcResult replaced = replace(v1, buyerA, "drawing-v2.pdf").andExpect(status().isOk())
			.andExpect(jsonPath("$.versionNo").value(2))
			.andExpect(jsonPath("$.supersedesDocumentId").value((int) v1))
			.andReturn();
		long v2 = idOf(replaced);

		call(get("/api/documents/{id}", v1), buyerA, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.fileName").value("drawing-v1.pdf"))
			.andExpect(jsonPath("$.versionHistory[*].fileName", contains("drawing-v1.pdf", "drawing-v2.pdf")));
		call(get("/api/documents/{id}/content", v1), buyerA, null).andExpect(status().isOk())
			.andExpect(content().bytes("drawing-v1.pdf".getBytes(StandardCharsets.UTF_8)));
		call(get("/api/documents/{id}/content", v2), buyerA, null).andExpect(status().isOk())
			.andExpect(content().bytes("drawing-v2.pdf".getBytes(StandardCharsets.UTF_8)));

		// a document that already has a later version cannot be replaced again, nor hard-deleted
		replace(v1, buyerA, "drawing-v1b.pdf").andExpect(status().isConflict());
		call(delete("/api/rfqs/{id}/documents/{doc}", rfqId, v1), buyerA, null).andExpect(status().isConflict());

		// the "oops, wrong file" window still works for a fresh, never-superseded, unlocked upload
		long oops = idOf(upload("/api/rfqs/{id}/documents", rfqId, buyerA, "wrong-file.pdf"));
		call(delete("/api/rfqs/{id}/documents/{doc}", rfqId, oops), buyerA, null).andExpect(status().isNoContent());
	}

	// ---------------------------------------------------------------- lock / approve / reject: ERGO Admin only

	@Test
	void onlyErgoAdminMayLockUnlockApproveOrReject() throws Exception {
		long rfqId = draftRfq(buyerA);
		long docId = idOf(upload("/api/rfqs/{id}/documents", rfqId, buyerA, "drawing.pdf"));

		for (Login denied : List.of(ergoRfq, buyerA, sellerX)) {
			call(post("/api/documents/{id}/lock", docId), denied, null).andExpect(status().isForbidden());
			call(post("/api/documents/{id}/unlock", docId), denied, null).andExpect(status().isForbidden());
			call(post("/api/documents/{id}/approve", docId), denied, "{}").andExpect(status().isForbidden());
			call(post("/api/documents/{id}/reject", docId), denied, "{\"reason\": \"no\"}")
				.andExpect(status().isForbidden());
		}
	}

	@Test
	void lockAndUnlockAreIdempotentGuardsNotToggles() throws Exception {
		long rfqId = draftRfq(buyerA);
		long docId = idOf(upload("/api/rfqs/{id}/documents", rfqId, buyerA, "drawing.pdf"));

		call(post("/api/documents/{id}/unlock", docId), ergoAdmin, null).andExpect(status().isConflict()); // already unlocked
		call(post("/api/documents/{id}/lock", docId), ergoAdmin, null).andExpect(status().isOk());
		call(post("/api/documents/{id}/lock", docId), ergoAdmin, null).andExpect(status().isConflict()); // already locked
		call(post("/api/documents/{id}/unlock", docId), ergoAdmin, null).andExpect(status().isOk());
	}

	@Test
	void approvingLocksAndIsNotRepeatable() throws Exception {
		long rfqId = draftRfq(buyerA);
		long docId = idOf(upload("/api/rfqs/{id}/documents", rfqId, buyerA, "drawing.pdf"));

		call(post("/api/documents/{id}/approve", docId), ergoAdmin, "{}").andExpect(status().isOk())
			.andExpect(jsonPath("$.approvalStatus").value("APPROVED"))
			.andExpect(jsonPath("$.lockStatus").value("LOCKED"));
		call(post("/api/documents/{id}/approve", docId), ergoAdmin, "{}").andExpect(status().isConflict());
		// approved -> locked, so hard delete is blocked even though it was never superseded
		call(delete("/api/rfqs/{id}/documents/{doc}", rfqId, docId), buyerA, null).andExpect(status().isConflict());
	}

	@Test
	void rejectingRequiresAReasonAndDoesNotLock() throws Exception {
		long rfqId = draftRfq(buyerA);
		long docId = idOf(upload("/api/rfqs/{id}/documents", rfqId, buyerA, "drawing.pdf"));

		call(post("/api/documents/{id}/reject", docId), ergoAdmin, "{}").andExpect(status().isUnprocessableContent());
		call(post("/api/documents/{id}/reject", docId), ergoAdmin, "{\"reason\": \"wrong revision\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.approvalStatus").value("REJECTED"))
			.andExpect(jsonPath("$.lockStatus").value("UNLOCKED"));
		call(post("/api/documents/{id}/reject", docId), ergoAdmin, "{\"reason\": \"again\"}")
			.andExpect(status().isConflict()); // no longer PENDING
		// a rejected, still-unlocked document can be replaced
		replace(docId, buyerA, "drawing-fixed.pdf").andExpect(status().isOk());
	}

	@Test
	void unlockingAnApprovedDocumentReopensItsApprovalState() throws Exception {
		long rfqId = draftRfq(buyerA);
		long docId = idOf(upload("/api/rfqs/{id}/documents", rfqId, buyerA, "drawing.pdf"));

		call(post("/api/documents/{id}/approve", docId), ergoAdmin, "{}").andExpect(status().isOk());
		// M4: reopening an approved document needs a reason.
		call(post("/api/documents/{id}/unlock", docId), ergoAdmin, null).andExpect(status().isUnprocessableContent());
		call(post("/api/documents/{id}/unlock", docId), ergoAdmin, "{\"reason\": \"wrong revision\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lockStatus").value("UNLOCKED"))
			.andExpect(jsonPath("$.approvalStatus").value("PENDING"));
	}

	// ---------------------------------------------------------------- pagination (sanity)

	@Test
	void libraryPaginates() throws Exception {
		long rfqId = draftRfq(buyerA);
		upload("/api/rfqs/{id}/documents", rfqId, ergoRfq, "a.pdf");
		upload("/api/rfqs/{id}/documents", rfqId, ergoRfq, "b.pdf");
		upload("/api/rfqs/{id}/documents", rfqId, ergoRfq, "c.pdf");

		call(get("/api/documents?linkedModule=rfq&uploaderCompanyId=" + ergo + "&page=0&size=2"), ergoAdmin, null)
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.totalElements").value(3));
		call(get("/api/documents?linkedModule=rfq&uploaderCompanyId=" + ergo + "&page=1&size=2"), ergoAdmin, null)
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.totalElements").value(3));
	}

	@Test
	void everyEditingRoleHasEditRightsButOnlyAdminApproves() throws Exception {
		long rfqId = draftRfq(buyerA);
		long docId = idOf(upload("/api/rfqs/{id}/documents", rfqId, ergoRfq, "drawing.pdf"));
		long category = drawingCategoryId();
		// every ERGO functional role, not just Admin, may edit (SRS §10.9) - ERGO RFQ as the representative.
		call(patch("/api/documents/{id}", docId), ergoRfq, "{\"documentCategoryId\": %d}".formatted(category))
			.andExpect(status().isOk());
		call(get("/api/documents/{id}", docId), ergoRfq, null)
			.andExpect(jsonPath("$.actions", hasItem("EDIT")))
			.andExpect(jsonPath("$.actions", not(hasItem("APPROVE"))))
			.andExpect(jsonPath("$.actions", not(hasItem("LOCK"))));
		// H3: SRS §10.9 gives ERGO Management "V X X X A" - no Edit, no Replace, and no EDIT/REPLACE buttons.
		call(patch("/api/documents/{id}", docId), ergoManagement, "{\"documentCategoryId\": %d}".formatted(category))
			.andExpect(status().isConflict());
		call(get("/api/documents/{id}", docId), ergoManagement, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.actions", not(hasItem("EDIT"))))
			.andExpect(jsonPath("$.actions", not(hasItem("REPLACE"))));
		call(get("/api/documents/{id}", docId), ergoAdmin, null).andExpect(jsonPath("$.actions", hasItem("APPROVE")))
			.andExpect(jsonPath("$.actions", hasItem("LOCK")));
	}

	// ---------------------------------------------------------------- security review H1/H2/H6 regressions

	@Test
	void externalEditorsCanOnlySetVisibilityToTheirOwnPartyAndOnlyErgoWidens() throws Exception {
		long rfqId = draftRfq(buyerA);
		long docId = idOf(upload("/api/rfqs/{id}/documents", rfqId, buyerA, "mine.pdf"));
		for (String widening : new String[] { "ALL", "SUPPLIER", "INTERNAL" }) {
			call(patch("/api/documents/{id}", docId), buyerA, "{\"visibility\": \"%s\"}".formatted(widening))
				.andExpect(status().isUnprocessableEntity());
		}
		call(patch("/api/documents/{id}", docId), buyerA, "{\"visibility\": \"CUSTOMER\"}").andExpect(status().isOk());
		call(patch("/api/documents/{id}", docId), ergoRfq, "{\"visibility\": \"ALL\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.visibility").value("ALL"));
	}

	@Test
	void anExternalReplaceResetsVisibilityInsteadOfInheritingWhatErgoGrantedTheReviewedVersion() throws Exception {
		long rfqId = draftRfq(buyerA);
		long v1 = idOf(upload("/api/rfqs/{id}/documents", rfqId, buyerA, "mine.pdf"));
		call(patch("/api/documents/{id}", v1), ergoRfq, "{\"visibility\": \"ALL\"}").andExpect(status().isOk());

		replace(v1, buyerA, "mine-v2.pdf").andExpect(status().isOk())
			.andExpect(jsonPath("$.versionNo").value(2))
			.andExpect(jsonPath("$.visibility").value("CUSTOMER"));
	}

	@Test
	void versionHistoryOmitsVersionsTheCallerCouldNotOpenDirectly() throws Exception {
		long rfqId = draftRfq(buyerA);
		long v1 = idOf(upload("/api/rfqs/{id}/documents", rfqId, ergoRfq, "internal-v1.pdf")); // INTERNAL
		long v2 = idOf(replace(v1, ergoRfq, "shared-v2.pdf").andExpect(status().isOk()));
		call(patch("/api/documents/{id}", v2), ergoRfq, "{\"visibility\": \"CUSTOMER\"}").andExpect(status().isOk());

		call(get("/api/documents/{id}", v2), buyerA, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.versionHistory", hasSize(1)))
			.andExpect(jsonPath("$.versionHistory[0].fileName").value("shared-v2.pdf"));
		call(get("/api/documents/{id}", v2), ergoRfq, null).andExpect(jsonPath("$.versionHistory", hasSize(2)));
	}

	// ---------------------------------------------------------------- security review M1/M3/M5 regressions

	@Test
	void libraryFiltersByApprovalStatus() throws Exception {
		long rfqId = draftRfq(buyerA);
		long approved = idOf(upload("/api/rfqs/{id}/documents", rfqId, buyerA, "a.pdf"));
		upload("/api/rfqs/{id}/documents", rfqId, buyerA, "b.pdf");
		call(post("/api/documents/{id}/approve", approved), ergoAdmin, "{}").andExpect(status().isOk());
		call(get("/api/documents?approvalStatus=APPROVED"), buyerA, null).andExpect(jsonPath("$.totalElements").value(1));
		call(get("/api/documents?approvalStatus=PENDING"), buyerA, null).andExpect(jsonPath("$.totalElements").value(1));
		call(get("/api/documents"), buyerA, null).andExpect(jsonPath("$.totalElements").value(2));
		// the filter only narrows what the caller may already see
		call(get("/api/documents?approvalStatus=APPROVED"), buyerB, null).andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void uploaderFilterIsIgnoredForExternalCallersSoItCannotBeUsedToProbeWhoUploaded() throws Exception {
		long rfqId = draftRfq(buyerA);
		upload("/api/rfqs/{id}/documents", rfqId, buyerA, "mine.pdf");
		// buyerA guessing another company id gets the same answer as guessing nothing: its own visible documents.
		call(get("/api/documents?linkedModule=rfq&uploaderCompanyId=" + customerB), buyerA, null)
			.andExpect(jsonPath("$.totalElements").value(1));
		call(get("/api/documents?linkedModule=rfq"), buyerA, null).andExpect(jsonPath("$.totalElements").value(1));
	}

	// ---------------------------------------------------------------- malware scanning (Phase 8i)

	@Test
	void anInfectedUploadIsRejectedAndNothingIsPersisted() throws Exception {
		long rfqId = draftRfq(buyerA);

		mvc.perform(multipart("/api/rfqs/{id}/documents", rfqId)
				.file(new MockMultipartFile("file", "invoice.pdf", "application/octet-stream",
						EicarOnlyVirusScanner.EICAR.getBytes(StandardCharsets.US_ASCII)))
				.header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(status().isUnprocessableEntity())
			// the engine's detection name must never reach the response body, only logs (security review)
			.andExpect(jsonPath("$.detail").value("This file was rejected by malware scanning and was not saved"));

		assertThat(documentCount(rfqId)).isZero();
	}

	@Test
	void aScannerOutageFailsClosedAndNothingIsPersisted() throws Exception {
		long rfqId = draftRfq(buyerA);

		mvc.perform(multipart("/api/rfqs/{id}/documents", rfqId)
				.file(new MockMultipartFile("file", "drawing.pdf", "application/octet-stream",
						EicarOnlyVirusScanner.SIMULATE_SCANNER_DOWN.getBytes(StandardCharsets.US_ASCII)))
				.header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(status().isServiceUnavailable());

		assertThat(documentCount(rfqId)).isZero();
	}

	@Test
	void anInfectedReplacementIsRejectedAndTheOriginalVersionIsUntouched() throws Exception {
		long rfqId = draftRfq(buyerA);
		long v1 = idOf(upload("/api/rfqs/{id}/documents", rfqId, buyerA, "drawing-v1.pdf"));

		mvc.perform(multipart("/api/documents/{id}/replace", v1)
				.file(new MockMultipartFile("file", "drawing-v2.pdf", "application/octet-stream",
						EicarOnlyVirusScanner.EICAR.getBytes(StandardCharsets.US_ASCII)))
				.header(HttpHeaders.AUTHORIZATION, buyerA.bearer()))
			.andExpect(status().isUnprocessableEntity());

		assertThat(documentCount(rfqId)).isEqualTo(1);
		call(get("/api/documents/{id}/content", v1), buyerA, null).andExpect(status().isOk())
			.andExpect(content().bytes("drawing-v1.pdf".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void aSupplierRoleThatDoesNotWorkQuotesCannotSeeSharedRfqDrawings() throws Exception {
		long rfqId = validatedRfq(buyerA);
		invite(rfqId, supplierX);
		long docId = idOf(upload("/api/rfqs/{id}/documents", rfqId, ergoRfq, "shared.pdf"));
		call(put("/api/rfqs/{id}/documents/{doc}/visibility", rfqId, docId), ergoRfq,
				"{\"visibility\": \"SUPPLIER\", \"itemIds\": %s}".formatted(com.ergo.connect.TestRfq.itemIds(mvc, ergoRfq, rfqId)))
			.andExpect(status().isOk());
		call(get("/api/documents/{id}", docId), sellerX, null).andExpect(status().isOk());
		call(get("/api/documents/{id}", docId), supplierXOfp, null).andExpect(status().isNotFound());
	}

	@Test
	void aVersionTwoDocumentCanNeverBeHardDeletedThroughItsOwningModule() throws Exception {
		long rfqId = draftRfq(buyerA);
		long v1 = idOf(upload("/api/rfqs/{id}/documents", rfqId, buyerA, "d-v1.pdf"));
		long v2 = idOf(replace(v1, buyerA, "d-v2.pdf").andExpect(status().isOk()));
		call(delete("/api/rfqs/{id}/documents/{docId}", rfqId, v2), buyerA, null).andExpect(status().isConflict());
		call(delete("/api/rfqs/{id}/documents/{docId}", rfqId, v1), buyerA, null).andExpect(status().isConflict());
	}

	// ---------------------------------------------------------------- second security review regressions (N1, N2, L6, H4 FK)

	@Test
	void podReplaceFollowsOfpsOwnUploadRuleForErgoAndRepointsTheShipmentsPod() throws Exception {
		long shipmentId = dispatchedShipment(customerA, supplierX);
		String response = uploadPod(shipmentId, ergoOfp).andReturn().getResponse().getContentAsString();
		long v1 = ((Number) JsonPath
			.<List<Number>>read(response, "$.shipments[?(@.id == " + shipmentId + ")].podDocumentId").get(0))
			.longValue();

		// Suppliers never see the POD; ERGO OFP (an "O" role, OFP's own UPDATE_SHIPMENT rule) may replace.
		replace(v1, supplierXOfp, "pod2.pdf").andExpect(status().isNotFound()); // a role with no POD access (suppliers never see it)
		long v2 = idOf(replace(v1, ergoOfp, "pod2.pdf").andExpect(status().isOk()));
		// H4: OFP's own POD download now serves the new version.
		call(get("/api/ofp/shipments/{id}/pod", shipmentId), ergoOfp, null).andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("pod2.pdf")));
		// L6: the superseded version is no longer actionable.
		call(patch("/api/documents/{id}", v1), ergoAdmin, "{\"visibility\": \"ALL\"}").andExpect(status().isConflict());
		call(post("/api/documents/{id}/lock", v1), ergoAdmin, null).andExpect(status().isConflict());
		call(post("/api/documents/{id}/lock", v2), ergoAdmin, null).andExpect(status().isOk());
	}

	@Test
	void rfqVisibilityEndpointCannotWidenALockedDocument() throws Exception {
		long rfqId = draftRfq(buyerA);
		long docId = idOf(upload("/api/rfqs/{id}/documents", rfqId, ergoRfq, "d.pdf"));
		call(post("/api/documents/{id}/lock", docId), ergoAdmin, null).andExpect(status().isOk());
		call(put("/api/rfqs/{id}/documents/{docId}/visibility", rfqId, docId), ergoRfq,
				"{\"visibility\": \"SUPPLIER\"}").andExpect(status().isConflict());
	}

	// ---------------------------------------------------------------- fixtures

	private long draftRfq(Login buyer) throws Exception {
		return idOf(call(post("/api/rfqs"), buyer, rfqForm()).andExpect(status().isCreated()));
	}

	private long validatedRfq(Login buyer) throws Exception {
		long id = draftRfq(buyer);
		validate(id, buyer);
		return id;
	}

	/** Moves an existing DRAFT RFQ to VALIDATED - split out from {@link #validatedRfq} so a test can upload a
	 * customer document first (customer upload is only allowed while the RFQ is still open, SRS §17.1) and validate
	 * afterwards. */
	private void validate(long id, Login buyer) throws Exception {
		call(post("/api/rfqs/{id}/submit", id), buyer, "{}").andExpect(status().isOk());
		call(post("/api/rfqs/{id}/start-review", id), ergoRfq, null).andExpect(status().isOk());
	}

	private void invite(long rfqId, long... supplierIds) throws Exception {
		TestRfq.invite(mvc, ergoRfq, rfqId, supplierIds);
	}

	private long invitationOf(Login supplier, long rfqId) throws Exception {
		String number = JsonPath.read(
				call(get("/api/rfqs/{id}", rfqId), ergoRfq, null).andReturn().getResponse().getContentAsString(),
				"$.number");
		List<Number> ids = JsonPath.read(call(get("/api/quotes"), supplier, null).andReturn().getResponse()
			.getContentAsString(), "$[?(@.rfqNumber == '" + number + "')].id");
		return ids.get(0).longValue();
	}

	private long dispatchedShipment(long customerCompanyId, long supplierCompanyId) {
		long orderId = jdbc.queryForObject("""
				INSERT INTO orders (order_number, customer_company_id, supplier_company_id, part_number,
				                    ordered_quantity, current_status)
				VALUES (?, ?, ?, 'PN-ORDER', 10, 'DISPATCHED') RETURNING order_id""", Long.class,
				"DO" + SEQ.incrementAndGet(), customerCompanyId, supplierCompanyId);
		return jdbc.queryForObject("""
				INSERT INTO shipments (order_id, updated_by_user_id) VALUES (?, ?) RETURNING shipment_id""",
				Long.class, orderId, ergoAdmin.userId());
	}

	private ResultActions uploadPod(long shipmentId, Login user) throws Exception {
		return mvc.perform(multipart("/api/ofp/shipments/{id}/pod", shipmentId)
			.file(new MockMultipartFile("file", "pod.pdf", "application/octet-stream",
					"pod.pdf".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, user.bearer()))
			.andExpect(status().isOk());
	}

	private long createNcr(long customerCompanyId, long supplierCompanyId) throws Exception {
		String body = """
				{"linkedModule": "INCOMING", "linkedRecordId": %d, "issueCategory": "Dimensional",
				 "issueDescription": "part out of tolerance", "targetClosureDate": "%s",
				 "customerCompanyId": %d, "supplierCompanyId": %d, "ownerUserId": null}"""
			.formatted(SEQ.incrementAndGet(), LocalDate.now().plusDays(30), customerCompanyId, supplierCompanyId);
		return idOf(call(post("/api/quality/ncrs"), ergoQuality, body).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
	}

	private long createCapaAction(long ncrId) throws Exception {
		String response = call(post("/api/quality/ncrs/{id}/capa-actions", ncrId), ergoQuality, """
				{"actionType": "CORRECTIVE", "actionDescription": "fix it", "targetDate": "%s"}"""
			.formatted(LocalDate.now().plusDays(10)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(response, "$.capaActions[0].id")).longValue();
	}

	private ResultActions uploadEvidence(long ncrId, long capaActionId, Login user) throws Exception {
		return mvc.perform(multipart("/api/quality/ncrs/{id}/capa-actions/{capaActionId}/evidence", ncrId, capaActionId)
			.file(new MockMultipartFile("file", "evidence.pdf", "application/octet-stream",
					"evidence.pdf".getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, user.bearer()))
			.andExpect(status().isOk());
	}

	private long drawingCategoryId() {
		return jdbc.queryForObject("SELECT category_id FROM document_categories WHERE name = 'Drawing'", Long.class);
	}

	private String rfqForm() {
		return TestRfq.form(inr, "PN-1", "PN-2");
	}

	// ---------------------------------------------------------------- generic helpers

	private int documentCount(long recordId) {
		return jdbc.queryForObject(
				"SELECT count(*) FROM documents WHERE linked_record_id = ? AND linked_module = 'rfq'", Integer.class,
				recordId);
	}

	private ResultActions call(MockHttpServletRequestBuilder request, Login user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, user.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	private ResultActions upload(String url, long id, Login user, String name) throws Exception {
		ResultActions result = mvc.perform(multipart(url, id)
			.file(new MockMultipartFile("file", name, "application/octet-stream", name.getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, user.bearer()));
		result.andExpect(status().isCreated());
		return result;
	}

	private ResultActions replace(long documentId, Login user, String name) throws Exception {
		return mvc.perform(multipart("/api/documents/{id}/replace", documentId)
			.file(new MockMultipartFile("file", name, "application/octet-stream", name.getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, user.bearer()));
	}

	private static long idOf(MvcResult result) throws Exception {
		return idOf(result.getResponse().getContentAsString());
	}

	private static long idOf(ResultActions result) throws Exception {
		return idOf(result.andReturn());
	}

	private static long idOf(String json) {
		return ((Number) JsonPath.read(json, "$.id")).longValue();
	}

}
