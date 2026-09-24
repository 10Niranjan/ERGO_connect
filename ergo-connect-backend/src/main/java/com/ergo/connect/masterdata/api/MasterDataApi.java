package com.ergo.connect.masterdata.api;

import java.util.Collection;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.masterdata.lookup.MasterDataService;
import com.ergo.connect.masterdata.lookup.MasterDataService.Option;
import com.ergo.connect.masterdata.lookup.MasterList;

/** What other modules may ask about master lists: validate a chosen entry, and show its code and name. */
@Service
public class MasterDataApi {

	public enum Ref {

		CURRENCY(MasterList.CURRENCIES), MATERIAL(MasterList.MATERIALS), PROCESS(MasterList.PROCESSES),
		COATING(MasterList.COATINGS), PAYMENT_TERM(MasterList.PAYMENT_TERMS), INCOTERM(MasterList.INCOTERMS);

		private final MasterList list;

		Ref(MasterList list) {
			this.list = list;
		}

	}

	public record Named(long id, String code, String name) {
	}

	private final MasterDataService masterData;

	MasterDataApi(MasterDataService masterData) {
		this.masterData = masterData;
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

	private static Named named(Option option) {
		return new Named(option.id(), option.code(), option.name());
	}

}
