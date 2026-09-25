package com.ergo.connect.masterdata.lookup;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.masterdata.lookup.MasterList.Field;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Reads and maintains every {@link MasterList} generically. Plain JDBC, so the automatic entity audit does not see
 * these writes: this class audits each change itself (SRS §15.2 "master data changes").
 */
@Service
@Transactional
public class MasterDataService {

	public record ListInfo(String key, String title, List<Field> fields) {
	}

	/** {@code fields} holds the list's extra columns by key, e.g. specification and grade for materials. */
	public record Entry(long id, String code, String name, boolean active, Map<String, String> fields) {
	}

	public record Option(long id, String code, String name) {
	}

	/** The code is fixed once created. {@code active} null means "unchanged" (true on create). */
	public record EntryRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]{0,29}") String code,
			@NotBlank @Size(max = 200) String name, Boolean active, Map<String, String> fields) {
	}

	static final String MODULE = "masterdata";

	private final JdbcTemplate jdbc;

	private final AuditLogService audit;

	MasterDataService(JdbcTemplate jdbc, AuditLogService audit) {
		this.jdbc = jdbc;
		this.audit = audit;
	}

	public List<ListInfo> lists() {
		return Arrays.stream(MasterList.values()).map(l -> new ListInfo(l.key(), l.title(), l.fields())).toList();
	}

	@Transactional(readOnly = true)
	public List<Entry> entries(MasterList list) {
		return jdbc.query(select(list) + " ORDER BY " + list.codeColumn, (rs, n) -> entry(list, rs));
	}

	/** Active values only, for dropdowns. */
	@Transactional(readOnly = true)
	public List<Option> options(MasterList list) {
		return jdbc.query("SELECT %s, %s, %s FROM %s WHERE active ORDER BY %s".formatted(list.idColumn,
				list.codeColumn, list.nameColumn, list.table, list.codeColumn),
				(rs, n) -> new Option(rs.getLong(1), rs.getString(2), rs.getString(3)));
	}

	public boolean isActive(MasterList list, long id) {
		return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM %s WHERE %s = ? AND active)"
			.formatted(list.table, list.idColumn), Boolean.class, id));
	}

	/** Code and name by id, inactive entries included. */
	public Map<Long, Option> describe(MasterList list, Collection<Long> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<Long, Option> result = new LinkedHashMap<>();
		jdbc.query("SELECT %s, %s, %s FROM %s WHERE %s = ANY (?)".formatted(list.idColumn, list.codeColumn,
				list.nameColumn, list.table, list.idColumn), rs -> {
					result.put(rs.getLong(1), new Option(rs.getLong(1), rs.getString(2), rs.getString(3)));
				}, (Object) ids.toArray(Long[]::new));
		return result;
	}

	public Entry create(MasterList list, EntryRequest request) {
		String code = request.code().trim().toUpperCase(Locale.ROOT);
		Map<String, String> fields = checkFields(list, request.fields());
		if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM %s WHERE %s = ?)"
			.formatted(list.table, list.codeColumn), Boolean.class, code))) {
			throw ApiException.conflict("Code " + code + " already exists in " + list.title());
		}
		List<String> columns = new ArrayList<>(List.of(list.codeColumn, list.nameColumn, "active"));
		List<Object> values = new ArrayList<>(List.of(code, request.name().trim(), !Boolean.FALSE.equals(request.active())));
		for (Field field : list.fields()) {
			columns.add(field.column());
			values.add(fields.get(field.key()));
		}
		Long id = jdbc.queryForObject("INSERT INTO %s (%s) VALUES (%s) RETURNING %s".formatted(list.table,
				String.join(", ", columns), String.join(", ", Collections.nCopies(columns.size(), "?")), list.idColumn),
				Long.class, values.toArray());
		audit.record(MODULE, list.entity, id, "CREATE", null, null, null);
		return get(list, id);
	}

	public Entry update(MasterList list, long id, EntryRequest request) {
		Entry before = get(list, id);
		if (!before.code().equals(request.code().trim().toUpperCase(Locale.ROOT))) {
			throw ApiException.unprocessable("Codes cannot be changed");
		}
		Map<String, String> fields = checkFields(list, request.fields());
		String name = request.name().trim();
		boolean active = request.active() == null ? before.active() : request.active();

		List<String> assignments = new ArrayList<>(List.of(list.nameColumn + " = ?", "active = ?"));
		List<Object> values = new ArrayList<>(List.of(name, active));
		for (Field field : list.fields()) {
			assignments.add(field.column() + " = ?");
			values.add(fields.get(field.key()));
		}
		values.add(id);
		jdbc.update("UPDATE %s SET %s WHERE %s = ?".formatted(list.table, String.join(", ", assignments),
				list.idColumn), values.toArray());

		auditChange(list, id, "name", before.name(), name);
		auditChange(list, id, "active", String.valueOf(before.active()), String.valueOf(active));
		for (Field field : list.fields()) {
			auditChange(list, id, field.key(), before.fields().get(field.key()), fields.get(field.key()));
		}
		return get(list, id);
	}

	private Entry get(MasterList list, long id) {
		return jdbc.query(select(list) + " WHERE " + list.idColumn + " = ?", (rs, n) -> entry(list, rs), id)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Entry"));
	}

	/** Blank text becomes null; unknown keys, over-long text and invalid choices are rejected. */
	private static Map<String, String> checkFields(MasterList list, Map<String, String> input) {
		Map<String, String> given = input == null ? Map.of() : input;
		for (String key : given.keySet()) {
			if (list.fields().stream().noneMatch(f -> f.key().equals(key))) {
				throw ApiException.unprocessable("Unknown field: " + key);
			}
		}
		Map<String, String> fields = new LinkedHashMap<>();
		for (Field field : list.fields()) {
			String value = given.get(field.key());
			value = value == null || value.isBlank() ? null : value.trim();
			if (value != null && value.length() > field.maxLength()) {
				throw ApiException.unprocessable(field.label() + " is too long");
			}
			if (!field.choices().isEmpty() && (value == null || !field.choices().contains(value))) {
				throw ApiException.unprocessable(field.label() + " must be one of " + field.choices());
			}
			fields.put(field.key(), value);
		}
		return fields;
	}

	private void auditChange(MasterList list, long id, String field, String before, String after) {
		if (!Objects.equals(before, after)) {
			audit.record(MODULE, list.entity, id, "UPDATE", field, before, after);
		}
	}

	private static String select(MasterList list) {
		List<String> columns = new ArrayList<>(List.of(list.idColumn, list.codeColumn, list.nameColumn, "active"));
		list.fields().forEach(field -> columns.add(field.column()));
		return "SELECT %s FROM %s".formatted(String.join(", ", columns), list.table);
	}

	private static Entry entry(MasterList list, ResultSet rs) throws SQLException {
		Map<String, String> fields = new LinkedHashMap<>();
		for (Field field : list.fields()) {
			fields.put(field.key(), rs.getString(field.column()));
		}
		return new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), fields);
	}

}
