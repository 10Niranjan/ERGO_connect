package com.ergo.connect.rfq.domain;

import java.math.BigDecimal;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** One row of the process flow table (SRS §5.5): planned on the RFQ, filled per supplier in its quote. */
@Embeddable
public record ProcessStep(Long processId, @NotBlank @Size(max = 200) String stepName,
		@Positive @Digits(integer = 8, fraction = 2) BigDecimal cycleTimeSeconds,
		@Enumerated(EnumType.STRING) SourcingType sourcingType, @Size(max = 200) String outsourcedSupplierName,
		@Size(max = 500) String remarks) {

	public enum SourcingType {
		INHOUSE, OUTSOURCED
	}

}
