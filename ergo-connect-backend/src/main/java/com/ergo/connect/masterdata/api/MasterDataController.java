package com.ergo.connect.masterdata.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.masterdata.lookup.ExchangeRateService;
import com.ergo.connect.masterdata.lookup.ExchangeRateService.Rate;
import com.ergo.connect.masterdata.lookup.ExchangeRateService.RateRequest;
import com.ergo.connect.masterdata.lookup.MasterDataService;
import com.ergo.connect.masterdata.lookup.MasterDataService.Entry;
import com.ergo.connect.masterdata.lookup.MasterDataService.EntryRequest;
import com.ergo.connect.masterdata.lookup.MasterDataService.ListInfo;
import com.ergo.connect.masterdata.lookup.MasterDataService.Option;
import com.ergo.connect.masterdata.lookup.MasterList;

import jakarta.validation.Valid;

/**
 * SRS §10.12 Admin Controls & Master Data: ERGO Admin maintains, ERGO Management views. Every signed-in role can read
 * the active options, because RFQ and quotation forms need them as dropdowns.
 */
@RestController
@RequestMapping("/api/master-data")
class MasterDataController {

	private static final String VIEW = "hasAnyRole('ERGO_ADMIN', 'ERGO_MANAGEMENT')";

	private static final String MANAGE = "hasRole('ERGO_ADMIN')";

	private final MasterDataService masterData;

	private final ExchangeRateService exchangeRates;

	MasterDataController(MasterDataService masterData, ExchangeRateService exchangeRates) {
		this.masterData = masterData;
		this.exchangeRates = exchangeRates;
	}

	@GetMapping
	@PreAuthorize(VIEW)
	List<ListInfo> lists() {
		return masterData.lists();
	}

	@GetMapping("/{list}")
	@PreAuthorize(VIEW)
	List<Entry> entries(@PathVariable String list) {
		return masterData.entries(list(list));
	}

	@GetMapping("/{list}/options")
	List<Option> options(@PathVariable String list) {
		return masterData.options(list(list));
	}

	@PostMapping("/{list}")
	@PreAuthorize(MANAGE)
	@ResponseStatus(HttpStatus.CREATED)
	Entry create(@PathVariable String list, @Valid @RequestBody EntryRequest body) {
		return masterData.create(list(list), body);
	}

	@PutMapping("/{list}/{id}")
	@PreAuthorize(MANAGE)
	Entry update(@PathVariable String list, @PathVariable long id, @Valid @RequestBody EntryRequest body) {
		return masterData.update(list(list), id, body);
	}

	// SRS §13.2.7. These literal paths take precedence over /{list}.

	@GetMapping("/exchange-rates")
	@PreAuthorize(VIEW)
	List<Rate> exchangeRates() {
		return exchangeRates.list();
	}

	@PostMapping("/exchange-rates")
	@PreAuthorize(MANAGE)
	@ResponseStatus(HttpStatus.CREATED)
	Rate createExchangeRate(@Valid @RequestBody RateRequest body) {
		return exchangeRates.create(body);
	}

	@DeleteMapping("/exchange-rates/{id}")
	@PreAuthorize(MANAGE)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteExchangeRate(@PathVariable long id) {
		exchangeRates.delete(id);
	}

	private static MasterList list(String key) {
		return MasterList.byKey(key).orElseThrow(() -> ApiException.notFound("Master list"));
	}

}
