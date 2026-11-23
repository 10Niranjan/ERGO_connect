package com.ergo.connect.masterdata.access;

import java.util.List;
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
			@Email @Size(max = 254) String primaryContactEmail, @Size(max = 30) String primaryContactPhone,
			@Pattern(regexp = "[A-Z0-9]{2,6}") String rfqPrefix) {
	}

	/** The company number is given automatically (CUS-00001 / SUP-00001, client question 27). */
	public record CompanyRequest(@NotNull CompanyType type, @NotNull @Valid CompanyProfile profile) {
	}

	/**
	 * Comment is optional to approve and required to reject or ask for more information; it is stored with the audit
	 * trail and as the company's latest review comment.
	 */
	public record ReviewRequest(@Size(max = 1000) String comment) {
	}

	/** What the caller may do with this company now (by role and status), so the UI shows only valid buttons. */
	public enum CompanyAction {
		EDIT, SEND_FOR_APPROVAL, APPROVE, REJECT, REQUEST_INFO, ACTIVATE, DEACTIVATE, ADD_USER
	}

	/** One company, in the shape its audience may see. */
	public sealed interface CompanyView permits CompanyResponse, ErgoCompanyResponse {

		long id();

	}

	/** What a customer or supplier sees of its own company: basic details only (client question 9). */
	public record CompanyResponse(long id, String code, CompanyType type, CompanyStatus status,
			CompanyProfile profile, Set<CompanyAction> actions) implements CompanyView {
	}

	/**
	 * What ERGO sees: also Management's latest review comment and which of the information needed to send the company
	 * for approval is still missing (client questions 15 and 19). Never sent to a customer or supplier.
	 */
	public record ErgoCompanyResponse(long id, String code, CompanyType type, CompanyStatus status,
			CompanyProfile profile, Set<CompanyAction> actions, String reviewComment,
			List<String> missing) implements CompanyView {
	}

}
