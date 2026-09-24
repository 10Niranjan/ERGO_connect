package com.ergo.connect.quotation.domain;

import java.math.BigDecimal;

import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** One tool in the quote's tooling cost (SRS §5.4 "tool name and cost details", §13.3.5). */
@Embeddable
public record ToolingLine(@NotBlank @Size(max = 200) String toolName,
		@NotNull @PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal toolingCost,
		@Size(max = 500) String remarks) {
}
