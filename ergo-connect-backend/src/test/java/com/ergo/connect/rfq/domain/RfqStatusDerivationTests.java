package com.ergo.connect.rfq.domain;

import static com.ergo.connect.rfq.domain.RfqStatus.CUSTOMER_APPROVED;
import static com.ergo.connect.rfq.domain.RfqStatus.CUSTOMER_DECLINED;
import static com.ergo.connect.rfq.domain.RfqStatus.CUSTOMER_ON_HOLD;
import static com.ergo.connect.rfq.domain.RfqStatus.CUSTOMER_REVISION_REQUESTED;
import static com.ergo.connect.rfq.domain.RfqStatus.QUOTES_COMPLETE;
import static com.ergo.connect.rfq.domain.RfqStatus.QUOTES_PARTIAL;
import static com.ergo.connect.rfq.domain.RfqStatus.SENT_TO_CUSTOMER;
import static com.ergo.connect.rfq.domain.RfqStatus.SENT_TO_SUPPLIERS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.ergo.connect.rfq.api.RfqApi.QuotationSummary;

/**
 * FLOW_ALIGNMENT slice 2: the RFQ status from SENT_TO_SUPPLIERS on summarises its parts. Precedence: every part decided
 * -> approved / declined; else with-customer > on-hold > correction > quotes complete / partial > sent-to-suppliers.
 * Pure function, so every mixed combination is cheap to pin down. The counts are (withSupplier, underErgoReview,
 * accepted, withCustomer, onHold, correction, approved, rejected); parts not counted are unassigned (recalled / not
 * yet sent).
 */
class RfqStatusDerivationTests {

	private static QuotationSummary s(int withSupplier, int underReview, int accepted, int withCustomer, int onHold,
			int correction, int approved, int rejected) {
		return new QuotationSummary(withSupplier, underReview, accepted, withCustomer, onHold, correction, approved,
				rejected);
	}

	static Stream<Arguments> cases() {
		return Stream.of(
				// ---- every part decided
				Arguments.of("all approved", 3, s(0, 0, 0, 0, 0, 0, 3, 0), CUSTOMER_APPROVED),
				Arguments.of("all rejected", 3, s(0, 0, 0, 0, 0, 0, 0, 3), CUSTOMER_DECLINED),
				Arguments.of("approved + rejected, all decided", 3, s(0, 0, 0, 0, 0, 0, 2, 1), CUSTOMER_APPROVED),
				Arguments.of("one approved, two rejected", 3, s(0, 0, 0, 0, 0, 0, 1, 2), CUSTOMER_APPROVED),
				Arguments.of("single part approved", 1, s(0, 0, 0, 0, 0, 0, 1, 0), CUSTOMER_APPROVED),
				Arguments.of("single part rejected", 1, s(0, 0, 0, 0, 0, 0, 0, 1), CUSTOMER_DECLINED),
				// ---- not every part decided: with-customer > on-hold > correction
				Arguments.of("2 approved + 1 with customer", 3, s(0, 0, 0, 1, 0, 0, 2, 0), SENT_TO_CUSTOMER),
				Arguments.of("2 approved + 1 correction", 3, s(0, 0, 0, 0, 0, 1, 2, 0), CUSTOMER_REVISION_REQUESTED),
				Arguments.of("2 approved + 1 on hold", 3, s(0, 0, 0, 0, 1, 0, 2, 0), CUSTOMER_ON_HOLD),
				Arguments.of("on hold + correction + accepted", 3, s(0, 0, 1, 0, 1, 1, 0, 0), CUSTOMER_ON_HOLD),
				Arguments.of("with customer + on hold + correction", 3, s(0, 0, 0, 1, 1, 1, 0, 0), SENT_TO_CUSTOMER),
				Arguments.of("on hold + correction + approved", 3, s(0, 0, 0, 0, 1, 1, 1, 0), CUSTOMER_ON_HOLD),
				Arguments.of("all three in correction", 3, s(0, 0, 0, 0, 0, 3, 0, 0), CUSTOMER_REVISION_REQUESTED),
				Arguments.of("with customer beats a part still at the supplier", 3, s(1, 0, 0, 1, 0, 0, 1, 0),
						SENT_TO_CUSTOMER),
				Arguments.of("correction beats a part still at the supplier", 3, s(1, 0, 0, 0, 0, 1, 0, 0),
						CUSTOMER_REVISION_REQUESTED),
				Arguments.of("with customer beats a recalled (unassigned) part", 3, s(0, 0, 0, 1, 0, 0, 1, 0),
						SENT_TO_CUSTOMER),
				// ---- quotes in
				Arguments.of("all accepted", 3, s(0, 0, 3, 0, 0, 0, 0, 0), QUOTES_COMPLETE),
				Arguments.of("all submitted", 3, s(0, 3, 0, 0, 0, 0, 0, 0), QUOTES_COMPLETE),
				Arguments.of("submitted + accepted + rejected", 3, s(0, 1, 1, 0, 0, 0, 0, 1), QUOTES_COMPLETE),
				Arguments.of("submitted + approved, none open", 3, s(0, 2, 0, 0, 0, 0, 1, 0), QUOTES_COMPLETE),
				Arguments.of("two accepted, one unassigned", 3, s(0, 0, 2, 0, 0, 0, 0, 0), QUOTES_PARTIAL),
				Arguments.of("one submitted, two at supplier", 3, s(2, 1, 0, 0, 0, 0, 0, 0), QUOTES_PARTIAL),
				Arguments.of("one approved, two at supplier", 3, s(2, 0, 0, 0, 0, 0, 1, 0), QUOTES_PARTIAL),
				Arguments.of("one approved, two unassigned", 3, s(0, 0, 0, 0, 0, 0, 1, 0), QUOTES_PARTIAL),
				Arguments.of("one rejected, two unassigned is not 'declined'", 3, s(0, 0, 0, 0, 0, 0, 0, 1),
						QUOTES_PARTIAL),
				Arguments.of("approved + rejected + one back at supplier is not decided", 3, s(1, 0, 0, 0, 0, 0, 1, 1),
						QUOTES_PARTIAL),
				Arguments.of("two approved, one recalled and unassigned is not 'approved'", 3, s(0, 0, 0, 0, 0, 0, 2, 0),
						QUOTES_PARTIAL),
				// ---- nothing in yet
				Arguments.of("all at supplier", 3, s(3, 0, 0, 0, 0, 0, 0, 0), SENT_TO_SUPPLIERS),
				Arguments.of("nothing assigned", 3, s(0, 0, 0, 0, 0, 0, 0, 0), SENT_TO_SUPPLIERS),
				Arguments.of("single part at supplier", 1, s(1, 0, 0, 0, 0, 0, 0, 0), SENT_TO_SUPPLIERS),
				Arguments.of("single part submitted", 1, s(0, 1, 0, 0, 0, 0, 0, 0), QUOTES_COMPLETE),
				// ---- degenerate input never throws
				Arguments.of("no parts at all", 0, s(0, 0, 0, 0, 0, 0, 0, 0), SENT_TO_SUPPLIERS),
				Arguments.of("more counted than parts does not throw", 1, s(2, 2, 2, 0, 0, 0, 0, 0), QUOTES_PARTIAL));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("cases")
	void theStatusSummarisesTheParts(String name, int parts, QuotationSummary summary, RfqStatus expected) {
		assertThat(RfqApiService.derive(parts, summary)).as(name).isEqualTo(expected);
	}

	@Test
	void everyDerivedStatusIsOneTheQuotationModuleMayMoveAnRfqInto() {
		// a derived status must never be a pre-distribution or terminal-by-ERGO status
		for (int w = 0; w <= 2; w++) {
			for (int u = 0; u <= 2; u++) {
				for (int a = 0; a <= 2; a++) {
					for (int c = 0; c <= 1; c++) {
						for (int h = 0; h <= 1; h++) {
							for (int k = 0; k <= 1; k++) {
								for (int ap = 0; ap <= 2; ap++) {
									for (int rj = 0; rj <= 2; rj++) {
										RfqStatus status = RfqApiService.derive(3, s(w, u, a, c, h, k, ap, rj));
										assertThat(status).isIn(SENT_TO_SUPPLIERS, QUOTES_PARTIAL, QUOTES_COMPLETE,
												SENT_TO_CUSTOMER, CUSTOMER_ON_HOLD, CUSTOMER_REVISION_REQUESTED,
												CUSTOMER_APPROVED, CUSTOMER_DECLINED);
									}
								}
							}
						}
					}
				}
			}
		}
	}

	@Test
	void anRfqIsNeverApprovedOrDeclinedWhileAnyPartIsStillUndecided() {
		// exhaustive over 3 parts: approved/declined requires approved + rejected == parts
		for (int w = 0; w <= 3; w++) {
			for (int u = 0; w + u <= 3; u++) {
				for (int a = 0; w + u + a <= 3; a++) {
					for (int c = 0; w + u + a + c <= 3; c++) {
						for (int h = 0; w + u + a + c + h <= 3; h++) {
							for (int k = 0; w + u + a + c + h + k <= 3; k++) {
								for (int ap = 0; w + u + a + c + h + k + ap <= 3; ap++) {
									int rj = 0;
									int undecided = w + u + a + c + h + k;
									RfqStatus status = RfqApiService.derive(3, s(w, u, a, c, h, k, ap, rj));
									if (undecided > 0 || ap < 3) {
										assertThat(status).as("%d/%d/%d/%d/%d/%d/%d", w, u, a, c, h, k, ap)
											.isNotIn(CUSTOMER_APPROVED, CUSTOMER_DECLINED);
									}
								}
							}
						}
					}
				}
			}
		}
	}

}
