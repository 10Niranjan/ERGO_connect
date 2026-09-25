package com.ergo.connect.quotation.domain;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.ergo.connect.quotation.domain.QuoteDtos.QuoteData;

import tools.jackson.databind.json.JsonMapper;

/** Reads the quotation services share: the quotes of invitations, and what each supplier last submitted. */
@Component
class QuoteReader {

	private final SupplierQuoteRepository quotes;

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	QuoteReader(SupplierQuoteRepository quotes, JdbcTemplate jdbc, JsonMapper json) {
		this.quotes = quotes;
		this.jdbc = jdbc;
		this.json = json;
	}

	/** Each invitation's quote, by invitation id (invitations not yet quoted on are absent). */
	Map<Long, SupplierQuote> byInvitation(Collection<Invitation> invitations) {
		if (invitations.isEmpty()) {
			return Map.of();
		}
		return quotes.findByAssignmentIdIn(invitations.stream().map(i -> i.id).toList()).stream()
			.collect(Collectors.toMap(q -> q.assignmentId, Function.identity()));
	}

	/** The last submitted version of each quote, by quote id (quotes never submitted are absent). */
	Map<Long, QuoteData> lastSubmitted(Collection<SupplierQuote> list) {
		List<Long> ids = list.stream().filter(q -> q.currentVersion > 0).map(q -> q.id).toList();
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<Long, QuoteData> result = new HashMap<>();
		jdbc.query("""
				SELECT DISTINCT ON (supplier_quote_id) supplier_quote_id, snapshot::text FROM supplier_quote_versions
				WHERE supplier_quote_id = ANY (?) ORDER BY supplier_quote_id, version_no DESC""",
				rs -> {
					result.put(rs.getLong(1), json.readValue(rs.getString(2), QuoteData.class));
				}, (Object) ids.toArray(Long[]::new));
		return result;
	}

}
