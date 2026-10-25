package com.ergo.connect.quotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ergo.connect.AdversarialSupport;
import com.ergo.connect.common.validation.Blank;

/** A "reason required" rule is not met by invisible characters, in the shared check and on a service-level reason. */
class InvisibleReasonTests extends AdversarialSupport {

	@Test
	void theSharedCheckSeesThroughInvisibleCharacters() {
		for (String blank : List.of("", "  ", "\u00a0", "\u200b", "\u3000", "\u00a0\u200b \t\n", "\ufeff")) {
			assertThat(Blank.is(blank)).as("blank " + blank.length()).isTrue();
		}
		assertThat(Blank.is(null)).isTrue();
		for (String real : List.of("a", " \u200bx", "Qty wrong", "\u00e9")) {
			assertThat(Blank.is(real)).as(real).isFalse();
		}
	}

	@Test
	void aRevisionRequestNeedsARealReason() throws Exception {
		long quote = singlePartQuote(sellerX, supplierX, "PN-INV");
		driveTo(quote, sellerX, "SUBMITTED");
		for (String blank : List.of("\u200b", "\u00a0", "\u3000\u200b")) {
			assertThat(send(post("/api/quotes/{id}/revision", quote), ergoRfq, "{\"comment\": \"" + blank + "\"}"))
				.as("revision with " + blank).isBetween(400, 499);
		}
		assertThat(statusOf(quote)).isEqualTo("SUBMITTED");
		assertThat(send(post("/api/quotes/{id}/revision", quote), ergoRfq, "{\"comment\": \"Price breakdown missing\"}")).isEqualTo(200);
	}

}
