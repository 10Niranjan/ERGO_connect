package com.ergo.connect.masterdata.access;

import java.util.Set;

import com.ergo.connect.common.security.CompanyType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CompanyDtos {

	private CompanyDtos() {
	}

	/** The editable part of a company (SRS §13.2.1). Code, type and status are never edited through it. */
	public record CompanyProfile(@NotBlank @Size(max = 200) String name, @Size(max = 200) String addressLine1,
			@Size(max = 200) String addressLine2, @Size(max = 100) String city, @Size(max = 100) String state,
			@Size(max = 100) String country, @Size(max = 20) String postalCode, @Size(max = 50) String taxId,
			@Size(max = 200) String website, @Size(max = 200) String primaryContactName,
			@Email @Size(max = 254) String primaryContactEmail, @Size(max = 30) String primaryContactPhone) {
	}

	public record CompanyRequest(@NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{1,29}") String code,
			@NotNull CompanyType type, @NotNull @Valid CompanyProfile profile) {
	}

	/** Comment is required to reject and optional to approve; it is stored with the audit trail. */
	public record ReviewRequest(@Size(max = 1000) String comment) {
	}

	/** What the caller may do with this company now (by role and status), so the UI shows only valid buttons. */
	public enum CompanyAction {
		EDIT, APPROVE, REJECT, RESUBMIT, ACTIVATE, DEACTIVATE, ADD_USER
	}

	public record CompanyResponse(long id, String code, CompanyType type, CompanyStatus status,
			CompanyProfile profile, Set<CompanyAction> actions) {
	}

}
