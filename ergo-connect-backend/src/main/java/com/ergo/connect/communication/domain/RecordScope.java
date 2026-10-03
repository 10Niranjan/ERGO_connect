package com.ergo.connect.communication.domain;

import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.record.RecordParties;
import com.ergo.connect.npd.api.NpdApi;
import com.ergo.connect.ofp.api.OfpApi;
import com.ergo.connect.quality.api.QualityApi;
import com.ergo.connect.quotation.api.QuotationApi;
import com.ergo.connect.rfq.api.RfqApi;

/**
 * Whether the current caller may see a record another module owns, asked of that module's own {@code api} facade (the
 * owning module's real record scope, never a copy of it). Shared by comments and notifications. Run under {@code
 * CurrentUser.runAs} to ask the same question about someone else, which is how a notification is checked against its
 * recipient before it is stored.
 */
@Component
class RecordScope {

	static final Set<String> MODULES = Set.of("rfq", "quotation", "npd", "ofp", "inspection", "ncr", "deviation");

	private final RfqApi rfqApi;

	private final QuotationApi quotationApi;

	private final NpdApi npdApi;

	private final OfpApi ofpApi;

	private final QualityApi qualityApi;

	RecordScope(RfqApi rfqApi, QuotationApi quotationApi, NpdApi npdApi, OfpApi ofpApi, QualityApi qualityApi) {
		this.rfqApi = rfqApi;
		this.quotationApi = quotationApi;
		this.npdApi = npdApi;
		this.ofpApi = ofpApi;
		this.qualityApi = qualityApi;
	}

	/** Empty = the record doesn't exist for the current caller. */
	Optional<RecordParties> parties(String module, long id) {
		return switch (module) {
			case "rfq" -> rfqApi.partiesVisibleToCaller(id);
			case "quotation" -> quotationApi.partiesVisibleToCaller(id);
			case "npd" -> npdApi.partiesVisibleToCaller(id);
			case "ofp" -> ofpApi.orderPartiesVisibleToCaller(id);
			case "inspection" -> qualityApi.inspectionPartiesVisibleToCaller(id);
			case "ncr" -> qualityApi.ncrPartiesVisibleToCaller(id);
			case "deviation" -> qualityApi.deviationPartiesVisibleToCaller(id);
			default -> throw ApiException.unprocessable("Unknown record type: " + module);
		};
	}

	/** Where the record lives in the UI: the same routes the frontend uses. */
	static String actionUrl(String module, long id) {
		return switch (module) {
			case "rfq" -> "/rfqs/" + id;
			case "quotation" -> "/quotes/" + id;
			case "npd" -> "/npd/" + id;
			case "ofp" -> "/ofp/" + id;
			case "inspection" -> "/quality/" + id;
			case "ncr" -> "/quality/ncrs/" + id;
			case "deviation" -> "/quality/deviations/" + id;
			case "document" -> "/documents/" + id;
			default -> "/";
		};
	}

}
