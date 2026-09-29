package com.ergo.connect.masterdata.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.masterdata.lookup.ExchangeRateService;
import com.ergo.connect.masterdata.lookup.MasterDataService;
import com.ergo.connect.masterdata.lookup.MasterDataService.Option;
import com.ergo.connect.masterdata.lookup.MasterList;

/** What other modules may ask about master lists: validate a chosen entry, show its code and name, convert currency. */
@Service
public class MasterDataApi {

	public enum Ref {

		CURRENCY(MasterList.CURRENCIES), MATERIAL(MasterList.MATERIALS), PROCESS(MasterList.PROCESSES),
		COATING(MasterList.COATINGS), PAYMENT_TERM(MasterList.PAYMENT_TERMS), INCOTERM(MasterList.INCOTERMS),
		SHIPMENT_MODE(MasterList.SHIPMENT_MODES);

		private final MasterList list;

		Ref(MasterList list) {
			this.list = list;
		}

	}

	public record Named(long id, String code, String name) {
	}

	private final MasterDataService masterData;

	private final ExchangeRateService exchangeRates;

	MasterDataApi(MasterDataService masterData, ExchangeRateService exchangeRates) {
		this.masterData = masterData;
		this.exchangeRates = exchangeRates;
	}

	/** Accepts null (nothing chosen); otherwise the entry must exist and be active. */
	public void requireActive(Ref ref, Long id, String label) {
		if (id != null && !masterData.isActive(ref.list, id)) {
			throw ApiException.unprocessable(label + " is not an active entry of " + ref.list.title());
		}
	}

	/** Code and name by id, including inactive entries (existing records keep showing them). */
	public Map<Long, Named> describe(Ref ref, Collection<Long> ids) {
		return masterData.describe(ref.list, ids).entrySet().stream().collect(java.util.stream.Collectors.toMap(
				Map.Entry::getKey, e -> named(e.getValue())));
	}

	/** One unit of {@code fromCurrencyId} in {@code toCurrencyId} on {@code day}; empty when ERGO has entered no rate. */
	public Optional<BigDecimal> rate(long fromCurrencyId, long toCurrencyId, LocalDate day) {
		return exchangeRates.rate(fromCurrencyId, toCurrencyId, day);
	}

	private static Named named(Option option) {
		return new Named(option.id(), option.code(), option.name());
	}

}
