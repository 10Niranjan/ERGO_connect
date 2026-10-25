package com.ergo.connect.npd.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import com.ergo.connect.common.validation.NotInvisiblyBlank;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Sample PO request and response shapes (flow doc step 6). A Sample PO holds no supplier data, so one view serves the
 * ERGO and the customer audience; the supplier never reaches Sample POs at all.
 */
public final class SamplePoDtos {

	private SamplePoDtos() {
	}

	/** Buttons the caller may use now: RAISE on an eligible part, the rest on a Sample PO. */
	public enum SamplePoAction {
		RAISE, EDIT, RETURN, CREATE_PROJECT
	}

	public record SamplePoForm(@NotNull Long rfqItemId, @NotBlank @Size(max = 60) String customerPoNumber,
			@NotNull @Positive Integer quantity, @NotNull @FutureOrPresent LocalDate requiredDate, @Size(max = 2000) String remarks) {
	}

	/** The customer's correction of a returned Sample PO; the part never changes. */
	public record SamplePoEditForm(@NotBlank @Size(max = 60) String customerPoNumber,
			@NotNull @Positive Integer quantity, @NotNull @FutureOrPresent LocalDate requiredDate, @Size(max = 2000) String remarks) {
	}

	/** {@code comment} is required: a returned Sample PO says what the customer must fix. */
	public record ReturnForm(@NotInvisiblyBlank @Size(max = 2000) String comment) {
	}

	/** The KAM assigns the project to one ERGO NPD user and one ERGO Quality user. */
	public record CreateProjectForm(@NotNull Long ergoNpdUserId, @NotNull Long ergoQualityUserId,
			@Size(max = 2000) String comment) {
	}

	/** The ERGO users a project can be assigned to (name and id only). */
	public record Assignees(java.util.List<com.ergo.connect.masterdata.api.Directory.UserRef> npd,
			java.util.List<com.ergo.connect.masterdata.api.Directory.UserRef> quality) {
	}

	/** An approved part of the customer that has no Sample PO yet. */
	public record EligiblePart(long rfqId, String rfqNumber, long rfqItemId, String partNumber, Instant approvedAt,
			Set<SamplePoAction> actions) {
	}

	/** {@code revision}/{@code description} are the part's RFQ data and only ERGO reads them; {@code returnComment}
	 * is what the KAM last asked the customer to fix. */
	public record SamplePoView(long id, String samplePoNumber, long rfqId, String rfqNumber, long rfqItemId,
			String customerName, String partNumber, String revision, String description, String customerPoNumber,
			int quantity, LocalDate requiredDate, String remarks, SamplePoStatus status, String returnComment,
			Long npdProjectId, String npdNumber, Instant createdAt, Instant updatedAt, Set<SamplePoAction> actions) {
	}

}
