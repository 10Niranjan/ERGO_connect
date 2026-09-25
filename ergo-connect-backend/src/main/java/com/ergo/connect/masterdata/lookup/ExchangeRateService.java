package com.ergo.connect.masterdata.lookup;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CurrentUser;
import com.ergo.connect.masterdata.lookup.MasterDataService.Option;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * SRS §13.2.7 exchange rates. ERGO Admin enters them; they feed only ERGO-internal views (quote comparison and ERGO's
 * margin, SRS §6), never an amount a customer or supplier sees. Plain JDBC, audited here like the other master lists.
 */
@Service
@Transactional
public class ExchangeRateService {

	/** One {@code from} unit costs {@code rate} {@code to} units from {@code rateDate} on. */
	public record Rate(long id, Option from, Option to, LocalDate rateDate, BigDecimal rate, String source,
			Instant createdAt) {
	}

	public record RateRequest(@NotNull Long fromCurrencyId, @NotNull Long toCurrencyId, @NotNull LocalDate rateDate,
			@NotNull @Positive @Digits(integer = 10, fraction = 8) BigDecimal rate, @Size(max = 200) String source) {
	}

	private static final String ENTITY = "ExchangeRate";

	private static final String SELECT = """
			SELECT r.exchange_rate_id, f.currency_id, f.currency_code, f.currency_name, t.currency_id, t.currency_code,
			       t.currency_name, r.rate_date, r.exchange_rate, r.source, r.created_at
			FROM exchange_rates r
			JOIN currencies f ON f.currency_id = r.from_currency_id
			JOIN currencies t ON t.currency_id = r.to_currency_id""";

	private final JdbcTemplate jdbc;

	private final MasterDataService masterData;

	private final AuditLogService audit;

	ExchangeRateService(JdbcTemplate jdbc, MasterDataService masterData, AuditLogService audit) {
		this.jdbc = jdbc;
		this.masterData = masterData;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<Rate> list() {
		return jdbc.query(SELECT + " ORDER BY r.rate_date DESC, f.currency_code, t.currency_code", ExchangeRateService::toRate);
	}

	public Rate create(RateRequest request) {
		if (request.fromCurrencyId().equals(request.toCurrencyId())) {
			throw ApiException.unprocessable("Choose two different currencies");
		}
		for (Long currency : List.of(request.fromCurrencyId(), request.toCurrencyId())) {
			if (!masterData.isActive(MasterList.CURRENCIES, currency)) {
				throw ApiException.unprocessable("Currency is not an active entry of Currencies");
			}
		}
		long id;
		try {
			id = jdbc.queryForObject("""
					INSERT INTO exchange_rates (from_currency_id, to_currency_id, rate_date, exchange_rate, source,
					                            created_by_user_id)
					VALUES (?, ?, ?, ?, ?, ?) RETURNING exchange_rate_id""", Long.class, request.fromCurrencyId(),
					request.toCurrencyId(), request.rateDate(), request.rate(), text(request.source()),
					CurrentUser.get().userId());
		}
		catch (DuplicateKeyException e) {
			throw ApiException.conflict("There is already a rate for this pair on " + request.rateDate()
					+ ". Delete it first to correct it.");
		}
		Rate created = get(id);
		audit.record(MasterDataService.MODULE, ENTITY, id, "CREATE", null, null, describe(created));
		return created;
	}

	/** Corrections: a wrong rate is deleted and entered again. Amounts already computed with it keep their value. */
	public void delete(long id) {
		Rate rate = get(id);
		jdbc.update("DELETE FROM exchange_rates WHERE exchange_rate_id = ?", id);
		audit.record(MasterDataService.MODULE, ENTITY, id, "DELETE", null, describe(rate), null);
	}

	/**
	 * What one unit of {@code from} is worth in {@code to} on {@code day}: 1 for the same currency, otherwise the newest
	 * rate dated on or before the day, entered either way round (a reverse rate is inverted). Empty when none exists.
	 */
	@Transactional(readOnly = true)
	public Optional<BigDecimal> rate(long from, long to, LocalDate day) {
		if (from == to) {
			return Optional.of(BigDecimal.ONE);
		}
		return jdbc.query("""
				SELECT from_currency_id = ? AS direct, exchange_rate FROM exchange_rates
				WHERE rate_date <= ? AND ((from_currency_id = ? AND to_currency_id = ?)
				                       OR (from_currency_id = ? AND to_currency_id = ?))
				ORDER BY rate_date DESC, direct DESC LIMIT 1""",
				(rs, n) -> rs.getBoolean(1) ? rs.getBigDecimal(2) : BigDecimal.ONE.divide(rs.getBigDecimal(2),
						MathContext.DECIMAL64),
				from, day, from, to, to, from).stream().findFirst();
	}

	private Rate get(long id) {
		return jdbc.query(SELECT + " WHERE r.exchange_rate_id = ?", ExchangeRateService::toRate, id).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Exchange rate"));
	}

	private static Rate toRate(ResultSet rs, int n) throws SQLException {
		return new Rate(rs.getLong(1), new Option(rs.getLong(2), rs.getString(3), rs.getString(4)),
				new Option(rs.getLong(5), rs.getString(6), rs.getString(7)), rs.getObject(8, LocalDate.class),
				rs.getBigDecimal(9), rs.getString(10), rs.getObject(11, OffsetDateTime.class).toInstant());
	}

	private static String describe(Rate rate) {
		return "1 %s = %s %s from %s".formatted(rate.from().code(), rate.rate().stripTrailingZeros().toPlainString(),
				rate.to().code(), rate.rateDate());
	}

	private static String text(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

}
