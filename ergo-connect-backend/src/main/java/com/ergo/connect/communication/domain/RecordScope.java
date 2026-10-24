package com.ergo.connect.communication.domain;

import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.record.RecordParties;
import com.ergo.connect.common.security.ModuleSwitch;
import com.ergo.connect.masterdata.api.Directory;
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

	private final Directory directory;

	RecordScope(RfqApi rfqApi, QuotationApi quotationApi, NpdApi npdApi, OfpApi ofpApi, QualityApi qualityApi,
			Directory directory) {
		this.rfqApi = rfqApi;
		this.quotationApi = quotationApi;
		this.npdApi = npdApi;
		this.ofpApi = ofpApi;
		this.qualityApi = qualityApi;
		this.directory = directory;
	}

	/** Empty = the record doesn't exist for the current caller. */
	Optional<RecordParties> parties(String module, long id) {
		// A hidden module has no records for anyone: comments, the inbox and notification delivery all stop here.
		if (ModuleSwitch.ownerOf(module).map(m -> !ModuleSwitch.readable(m)).orElse(false)) {
			return Optional.empty();
		}
		return switch (module) {
			case "rfq" -> rfqApi.partiesVisibleToCaller(id);
			case "quotation" -> quotationApi.partiesVisibleToCaller(id);
			case "npd" -> npdApi.partiesVisibleToCaller(id);
			case "sample-po" -> npdApi.samplePoPartiesVisibleToCaller(id);
			case "production-po" -> npdApi.productionPoPartiesVisibleToCaller(id);
			case "ofp" -> ofpApi.orderPartiesVisibleToCaller(id);
			case "inspection" -> qualityApi.inspectionPartiesVisibleToCaller(id);
			case "ncr" -> qualityApi.ncrPartiesVisibleToCaller(id);
			case "deviation" -> qualityApi.deviationPartiesVisibleToCaller(id);
			// not a customer/supplier record: only notifications point at a company (onboarding approval)
			case "company" -> directory.companyVisibleToCaller(id) ? Optional.of(new RecordParties(null, null))
					: Optional.empty();
			default -> throw ApiException.unprocessable("Unknown record type: " + module);
		};
	}

	/** True while the record's module accepts changes (switch A or O). Non-throwing, for probes and button state. */
	boolean writable(String module) {
		return ModuleSwitch.ownerOf(module).map(ModuleSwitch::writable).orElse(true);
	}

	/** As {@link #writable(String)}, for one record: an OFP project is also bound by the OFP switch. */
	boolean writable(String module, long id) {
		return writable(module) && (!"npd".equals(module) || npdApi.writableForRecord(id));
	}

	/** The enforcing check for a change to a record of this kind: 409 while its module is read-only or hidden. */
	void requireWritable(String module) {
		ModuleSwitch.ownerOf(module).ifPresent(ModuleSwitch::requireWritable);
	}

	/** As {@link #requireWritable(String)}, for one record: an OFP project is also bound by the OFP switch. */
	void requireWritable(String module, long id) {
		requireWritable(module);
		if ("npd".equals(module) && !npdApi.writableForRecord(id)) {
			ModuleSwitch.requireWritable(com.ergo.connect.common.security.ModuleAccess.Module.OFP);
		}
	}

	/** Where the record lives in the UI: the same routes the frontend uses. */
	static String actionUrl(String module, long id) {
		return switch (module) {
			case "rfq" -> "/rfqs/" + id;
			case "quotation" -> "/quotes/" + id;
			case "npd" -> "/npd/" + id;
			case "sample-po" -> "/npd/sample-pos/" + id;
			case "production-po" -> "/ofp/production-pos/" + id;
			case "ofp" -> "/ofp/" + id;
			case "inspection" -> "/quality/" + id;
			case "ncr" -> "/quality/ncrs/" + id;
			case "deviation" -> "/quality/deviations/" + id;
			case "company" -> "/companies";
			case "document" -> "/documents/" + id;
			default -> "/";
		};
	}

}
