package com.ergo.connect.common.security;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.ModuleAccess.Module;

/**
 * The module switch (ERGO Admin &rarr; Permission Management: O on, A no final approvals, V read-only, NV hidden) as
 * the rest of the backend must honour it, wherever the work comes from: a controller, another module's service, a
 * comment, a document, a notification or a scheduled job. {@link ModuleAccess} already caps what each <i>role</i> may
 * do; this class is for the code paths that have no role check of their own on the module they touch (a quotation
 * moving its RFQ, a quality record linking to an order, the deviation-expiry job, ...).
 * <ul>
 * <li><b>NV</b>: nothing in the module is read, written, listed, counted or notified. Data is kept untouched.
 * <li><b>V</b>: reading is unchanged; every change is refused, whoever asks and whatever triggered it.
 * <li><b>A, O</b>: changes allowed (A only removes the final approvals, which the role levels already enforce).
 * </ul>
 * Reads ({@link #readable}) use the request's access snapshot, like every other read check. Writes
 * ({@link #requireWritable}, {@link #writableNow}) ask the database, so a just-changed switch is honoured by every
 * instance and thread at once, and inside a transaction the switch rows stay locked until it ends (see
 * {@link AccessPolicy#capsNow}): an operation either completes under the switch it checked or does not start.
 * The role-by-module grid is never touched.
 */
public final class ModuleSwitch {

	private ModuleSwitch() {
	}

	/** True unless the module is switched to NV. */
	public static boolean readable(Module module) {
		return !"NV".equals(AccessPolicy.cap(module.name()));
	}

	/** True when the module accepts changes (switch A or O), judged by this request's snapshot. For probes and
	 * "which buttons to show"; the enforcing checks are {@link #requireWritable} / {@link #writableNow}. */
	public static boolean writable(Module module) {
		return changesAllowed(AccessPolicy.cap(module.name()));
	}

	/** The enforcing check for an <i>operation that reads</i> the module's data on behalf of another module: 404 as
	 * if the record did not exist, so a hidden module reveals nothing. */
	public static void requireReadable(Module module) {
		if (!readable(module)) {
			throw ApiException.notFound(label(module));
		}
	}

	/**
	 * The enforcing check for an operation that changes (or whose commit changes) data in each of the modules: 409
	 * unless every one of them accepts changes right now. Authoritative (database, locked for the transaction).
	 * Call it before the first write, with <i>all</i> modules the operation touches, so a refused operation leaves
	 * nothing half done; the surrounding transaction rolls back anything done before the call anyway.
	 */
	public static void requireWritable(Module... modules) {
		Map<String, String> caps = AccessPolicy.capsNow(Arrays.stream(modules).map(Enum::name).toList());
		for (Module module : modules) {
			String cap = caps.get(module.name());
			if (cap == null || "NV".equals(cap)) {
				throw ApiException.conflict("The " + label(module) + " module is not available right now, so this "
						+ "cannot be done. Nothing was changed; it can be repeated once the module is switched back on.");
			}
			if (!changesAllowed(cap)) {
				throw ApiException.conflict("The " + label(module) + " module is read-only right now, so this cannot "
						+ "be done. Nothing was changed; it can be repeated once the module is switched back on.");
			}
		}
	}

	/** Non-throwing {@link #requireWritable} for background jobs: true only if every module accepts changes now. */
	public static boolean writableNow(Module... modules) {
		Map<String, String> caps = AccessPolicy.capsNow(Arrays.stream(modules).map(Enum::name).toList());
		return Arrays.stream(modules).allMatch(m -> caps.get(m.name()) != null && changesAllowed(caps.get(m.name())));
	}

	/** The workflow module that owns a record or file kind, by the strings the comment, document and notification
	 * tables use. Empty for kinds that belong to no switchable module (a plain {@code document} reference, the
	 * {@code comment} attachments, whose module is the comment's record's). */
	public static Optional<Module> ownerOf(String linkedModule) {
		if (linkedModule == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(switch (linkedModule) {
			case "rfq" -> Module.RFQ;
			case "quotation" -> Module.QUOTATION;
			case "npd", "NPD" -> Module.NPD;
			case "ofp", "OFP", "OFP_SHIPMENT" -> Module.OFP;
			case "inspection", "ncr", "deviation", "QUALITY_CAPA" -> Module.QUALITY;
			default -> null;
		});
	}

	/** The modules currently switched to NV (this request's snapshot). */
	public static List<Module> hidden() {
		return Arrays.stream(Module.values()).filter(m -> !readable(m)).toList();
	}

	private static boolean changesAllowed(String cap) {
		return "A".equals(cap) || "O".equals(cap);
	}

	private static String label(Module module) {
		return switch (module) {
			case RFQ -> "RFQ";
			case QUOTATION -> "Quotation";
			case NPD -> "NPD";
			case OFP -> "OFP";
			case QUALITY -> "Quality";
		};
	}

}
