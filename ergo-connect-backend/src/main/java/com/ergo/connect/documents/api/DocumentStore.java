package com.ergo.connect.documents.api;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.audit.AuditLogService;
import com.ergo.connect.common.exception.ApiException;
import com.ergo.connect.common.security.CurrentUser;

/**
 * Stores files attached to records (SRS §13.7.1, basic). This module does not decide access: the module that owns the
 * record checks that the caller may see it, then filters by {@link Visibility}. Every change is audited here.
 * ponytail: bytes in Postgres, whole files in memory (50 MB cap); Phase 6 moves them to Azure Blob with virus scanning.
 */
@Service
@Transactional
public class DocumentStore {

	/** Who besides ERGO may see a file. The uploading company always sees its own uploads. */
	public enum Visibility {
		INTERNAL, CUSTOMER, SUPPLIER, ALL
	}

	public record DocumentInfo(long id, String fileName, String contentType, long size, Visibility visibility,
			long uploadedByUserId, long uploadedByCompanyId, Instant createdAt) {
	}

	/** Engineering and office formats only; files are always served as downloads, never rendered inline. */
	private static final Map<String, String> TYPES = Map.ofEntries(Map.entry("pdf", "application/pdf"),
			Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
			Map.entry("tif", "image/tiff"), Map.entry("tiff", "image/tiff"), Map.entry("dwg", "image/vnd.dwg"),
			Map.entry("dxf", "image/vnd.dxf"), Map.entry("step", "model/step"), Map.entry("stp", "model/step"),
			Map.entry("igs", "model/iges"), Map.entry("iges", "model/iges"), Map.entry("stl", "model/stl"),
			Map.entry("x_t", "application/octet-stream"), Map.entry("xlsx",
					"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
			Map.entry("xls", "application/vnd.ms-excel"), Map.entry("csv", "text/csv"),
			Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
			Map.entry("doc", "application/msword"), Map.entry("txt", "text/plain"),
			Map.entry("zip", "application/zip"));

	private static final String MODULE = "documents";

	private static final RowMapper<DocumentInfo> INFO = (rs, n) -> new DocumentInfo(rs.getLong("document_id"),
			rs.getString("file_name"), rs.getString("content_type"), rs.getLong("file_size"),
			Visibility.valueOf(rs.getString("visibility")), rs.getLong("uploaded_by_user_id"),
			rs.getLong("uploaded_by_company_id"), rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private final JdbcTemplate jdbc;

	private final AuditLogService audit;

	DocumentStore(JdbcTemplate jdbc, AuditLogService audit) {
		this.jdbc = jdbc;
		this.audit = audit;
	}

	public DocumentInfo store(String module, long recordId, String fileName, byte[] content, Visibility visibility) {
		CurrentUser me = CurrentUser.get();
		String name = cleanName(fileName);
		String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
		String contentType = TYPES.get(extension);
		if (contentType == null) {
			throw ApiException.unprocessable("File type ." + extension + " is not accepted. Allowed: "
					+ String.join(", ", TYPES.keySet().stream().sorted().toList()));
		}
		if (content.length == 0) {
			throw ApiException.unprocessable("The file is empty");
		}
		Long id = jdbc.queryForObject("""
				INSERT INTO documents (linked_module, linked_record_id, file_name, content_type, file_size, sha256,
				                       visibility, uploaded_by_user_id, uploaded_by_company_id)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING document_id""", Long.class, module, recordId, name,
				contentType, content.length, sha256(content), visibility.name(), me.userId(), me.companyId());
		jdbc.update("INSERT INTO document_contents (document_id, content) VALUES (?, ?)", id, content);
		audit.record(MODULE, "Document", id, "CREATE", "file", null,
				module + " " + recordId + ": " + name + " (" + visibility + ")");
		return find(module, recordId, id).orElseThrow();
	}

	@Transactional(readOnly = true)
	public List<DocumentInfo> list(String module, long recordId) {
		return jdbc.query("SELECT * FROM documents WHERE linked_module = ? AND linked_record_id = ? ORDER BY created_at",
				INFO, module, recordId);
	}

	@Transactional(readOnly = true)
	public Optional<DocumentInfo> find(String module, long recordId, long documentId) {
		return jdbc.query("""
				SELECT * FROM documents WHERE document_id = ? AND linked_module = ? AND linked_record_id = ?""", INFO,
				documentId, module, recordId).stream().findFirst();
	}

	@Transactional(readOnly = true)
	public byte[] content(DocumentInfo document) {
		return jdbc.queryForObject("SELECT content FROM document_contents WHERE document_id = ?", byte[].class,
				document.id());
	}

	public void setVisibility(DocumentInfo document, Visibility visibility) {
		jdbc.update("UPDATE documents SET visibility = ? WHERE document_id = ?", visibility.name(), document.id());
		audit.record(MODULE, "Document", document.id(), "UPDATE", "visibility", document.visibility().name(),
				visibility.name());
	}

	public void delete(DocumentInfo document) {
		jdbc.update("DELETE FROM documents WHERE document_id = ?", document.id());
		audit.record(MODULE, "Document", document.id(), "DELETE", "file", document.fileName(), null);
	}

	/** Keeps only the file name part and characters that are safe in a download header. */
	private static String cleanName(String fileName) {
		String name = fileName == null ? "" : fileName.replace('\\', '/');
		name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").trim();
		if (name.isEmpty() || name.length() > 255) {
			throw ApiException.unprocessable("The file name must be 1 to 255 characters");
		}
		return name;
	}

	private static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
