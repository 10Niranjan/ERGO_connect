package com.ergo.connect.npd.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.ergo.connect.masterdata.api.Directory.UserRef;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Production PO request and response shapes (flow doc step 11). A production order holds no supplier data, so one view
 * serves the ERGO and the customer audience; the supplier never reaches a production order at all.
 */
public final class ProductionPoDtos {

	private ProductionPoDtos() {
	}

	/** Buttons the caller may use now: RAISE on an eligible project, the rest on a production order. */
	public enum ProductionPoAction {
		RAISE, EDIT, RETURN, CREATE_PROJECT
	}

	public record ProductionPoForm(@NotNull Long sourceProjectId, @NotBlank @Size(max = 60) String customerPoNumber,
			@NotNull @Positive Integer quantity, @NotNull @FutureOrPresent LocalDate requiredDate, @Size(max = 2000) String remarks) {
	}

	/** The customer's correction of a returned production order; the part never changes. */
	public record ProductionPoEditForm(@NotBlank @Size(max = 60) String customerPoNumber,
			@NotNull @Positive Integer quantity, @NotNull @FutureOrPresent LocalDate requiredDate, @Size(max = 2000) String remarks) {
	}

	/** {@code comment} is required: a returned production order says what the customer must fix. */
	public record ReturnForm(@NotBlank @Size(max = 2000) String comment) {
	}

	/** The KAM assigns the OFP project to one ERGO OFP user and one ERGO Quality user. */
	public record CreateProjectForm(@NotNull Long ergoOfpUserId, @NotNull Long ergoQualityUserId,
			@Size(max = 2000) String comment) {
	}

	/** The ERGO users an OFP project can be assigned to (name and id only). */
	public record Assignees(List<UserRef> ofp, List<UserRef> quality) {
	}

	/** An approved sample project of the customer that has no open production order: where Customer OFP starts. */
	public record EligibleProject(long projectId, String npdNumber, String partNumber, String revision,
			Set<ProductionPoAction> actions) {
	}

	/** {@code revision}/{@code description} are the part's data and only ERGO reads them; {@code returnComment} is what
	 * the KAM last asked the customer to fix. */
	public record ProductionPoView(long id, String productionPoNumber, long sourceProjectId, String sourceNpdNumber,
			String customerName, String partNumber, String revision, String description, String customerPoNumber,
			int quantity, LocalDate requiredDate, String remarks, ProductionPoStatus status, String returnComment,
			Long projectId, String projectNumber, Instant createdAt, Instant updatedAt, Set<ProductionPoAction> actions) {
	}

}
