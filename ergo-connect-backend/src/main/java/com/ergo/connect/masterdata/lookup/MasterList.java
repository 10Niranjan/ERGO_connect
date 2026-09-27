package com.ergo.connect.masterdata.lookup;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The simple code + name master lists (SRS §13.2.6–13.2.12, §12.13.3). This registry is the only description of each
 * list: table and columns, extra fields, validation and the admin screen all come from it. Table and column names are
 * constants here, never user input.
 */
public enum MasterList {

	CURRENCIES("currencies", "currency", "Currency", "Currencies"),
	MATERIALS("materials", "material", "Material", "Materials",
			Field.text("specification", "specification", "Specification", 500),
			Field.text("grade", "grade", "Grade", 100)),
	PROCESSES("processes", "process", "Process", "Processes",
			Field.choice("processType", "process_type", "Type", "INHOUSE", "OUTSOURCED", "MIXED")),
	COATINGS("coatings", "coating", "Coating", "Coatings / surface treatments",
			Field.text("specification", "specification", "Specification", 500)),
	PAYMENT_TERMS("payment_terms", "payment_term", "PaymentTerm", "Payment terms",
			Field.text("description", "description", "Description", 500)),
	INCOTERMS("incoterms", "incoterm", "Incoterm", "Incoterms"),
	SHIPMENT_MODES("shipment_modes", "shipment_mode", "ShipmentMode", "Shipment modes");

	/** An extra column. Choice fields are required; text fields are optional. */
	public record Field(String key, String column, String label, int maxLength, List<String> choices) {

		static Field text(String key, String column, String label, int maxLength) {
			return new Field(key, column, label, maxLength, List.of());
		}

		static Field choice(String key, String column, String label, String... choices) {
			return new Field(key, column, label, 20, List.of(choices));
		}

	}

	final String table;

	final String idColumn;

	final String codeColumn;

	final String nameColumn;

	/** Entity name in the audit trail. */
	final String entity;

	private final String title;

	private final List<Field> fields;

	MasterList(String table, String columnPrefix, String entity, String title, Field... fields) {
		this.table = table;
		this.idColumn = columnPrefix + "_id";
		this.codeColumn = columnPrefix + "_code";
		this.nameColumn = columnPrefix + "_name";
		this.entity = entity;
		this.title = title;
		this.fields = List.of(fields);
	}

	/** URL key, e.g. "payment-terms". */
	public String key() {
		return name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

	public String title() {
		return title;
	}

	public List<Field> fields() {
		return fields;
	}

	public static Optional<MasterList> byKey(String key) {
		return Arrays.stream(values()).filter(list -> list.key().equals(key)).findFirst();
	}

}
