package com.ergo.connect.masterdata.access;

import com.ergo.connect.common.security.CompanyType;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CompanyDtos {

	private CompanyDtos() {
	}

	/** Code and type are fixed once the company exists. {@code active} null means "unchanged" (true on create). */
	public record CompanyRequest(@NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{1,29}") String code,
			@NotBlank @Size(max = 200) String name, @NotNull CompanyType type, @Size(max = 200) String addressLine1,
			@Size(max = 200) String addressLine2, @Size(max = 100) String city, @Size(max = 100) String state,
			@Size(max = 100) String country, @Size(max = 20) String postalCode, @Size(max = 50) String taxId,
			@Size(max = 200) String website, @Size(max = 200) String primaryContactName,
			@Email @Size(max = 254) String primaryContactEmail, @Size(max = 30) String primaryContactPhone,
			Boolean active) {
	}

	public record CompanyResponse(long id, String code, String name, CompanyType type, String addressLine1,
			String addressLine2, String city, String state, String country, String postalCode, String taxId,
			String website, String primaryContactName, String primaryContactEmail, String primaryContactPhone,
			boolean active) {
	}

}
