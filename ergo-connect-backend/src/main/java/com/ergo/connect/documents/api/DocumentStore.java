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
import com.ergo.connect.documents.domain.DocumentStorage;

/**
 * Stores files attached to records (SRS §13.7.1, basic). This module does not decide access: the module that owns the
 * record checks that the caller may see it, then filters by {@link Visibility}. Every change is audited here.
 * <p>
 * Metadata (this class, raw JDBC) and bytes ({@link DocumentStorage}) are deliberately separate so the byte store can
 * be swapped later (Phase 8: Azure Blob + virus scanning) without touching this class or its 4 callers (RfqService,
 * QuoteService, OrderService, NcrService) - ARCHITECTURE.md §8, 2026-10-02 Phase 6a. The {@code documents} table also
 * grew Phase 6a's category/version/lock/approval columns (V20 migration); this class still only ever INSERTs plain
 * rows (version 1, unlocked, pending) exactly as before - the new columns are read here (so the 4 existing callers
 * and the new {@code documents.domain.DocumentService} agree on one row's state) and written only by
 * {@code DocumentService}.
 */
@Service
@Transactional
public class DocumentStore {

	/** Who besides ERGO may see a file. The uploading company always sees its own uploads. */
	public enum Visibility {
		INTERNAL, CUSTOMER, SUPPLIER, ALL
	}

	public record DocumentInfo(long id, String fileName, String contentType, long size, Visibility visibility,
			long uploadedByUserId, long uploadedByCompanyId, Instant createdAt, LockStatus lockStatus,
			ApprovalStatus approvalStatus, Long supersedesDocumentId) {
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
			rs.getLong("uploaded_by_company_id"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
			LockStatus.valueOf(rs.getString("lock_status")), ApprovalStatus.valueOf(rs.getString("approval_status")),
			rs.getObject("supersedes_document_id", Long.class));

	private final JdbcTemplate jdbc;

	private final AuditLogService audit;

	private final DocumentStorage storage;

	DocumentStore(JdbcTemplate jdbc, AuditLogService audit, DocumentStorage storage) {
		this.jdbc = jdbc;
		this.audit = audit;
		this.storage = storage;
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
		storage.put(id, content);
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
		return storage.get(document.id());
	}

	public void setVisibility(DocumentInfo document, Visibility visibility) {
		// Second security review N2: a locked/approved document is final for every role (client decision M7), and the
		// raw update must still bump row_version so it cannot silently race the Library's optimistic lock (H5).
		if (document.lockStatus() == LockStatus.LOCKED || document.approvalStatus() == ApprovalStatus.APPROVED) {
			throw ApiException.conflict("This document is locked or approved and can no longer be changed");
		}
		jdbc.update("UPDATE documents SET visibility = ?, row_version = row_version + 1 WHERE document_id = ?",
				visibility.name(), document.id());
		audit.record(MODULE, "Document", document.id(), "UPDATE", "visibility", document.visibility().name(),
				visibility.name());
	}

	/**
	 * Hard delete - the "oops, wrong file" self-correction window only (client decision, ARCHITECTURE.md §8
	 * 2026-10-02): blocked once the document is locked, approved, or has a later version, since Phase 6a treats every
	 * document as never-hard-deletable past that point (versioning supersedes instead - see {@code
	 * documents.domain.DocumentService#replace}). The 4 existing callers (RfqService, QuoteService) already only ever
	 * call this on a document their own module's rules already judged deletable (SRS §11.2); this is the second,
	 * module-independent gate on top of that, so a document already shared/approved/superseded can't be erased
	 * through any module's own delete path either.
	 */
	public void delete(DocumentInfo document) {
		if (document.lockStatus() == LockStatus.LOCKED || document.approvalStatus() == ApprovalStatus.APPROVED
				|| document.supersedesDocumentId() != null || hasLaterVersion(document.id())) {
			throw ApiException.conflict("This document can no longer be deleted; it has been locked, approved, or "
					+ "superseded by a newer version - upload a new version instead");
		}
		storage.delete(document.id());
		jdbc.update("DELETE FROM documents WHERE document_id = ?", document.id());
		audit.record(MODULE, "Document", document.id(), "DELETE", "file", document.fileName(), null);
	}

	private boolean hasLaterVersion(long documentId) {
		Integer count = jdbc.queryForObject("SELECT count(*) FROM documents WHERE supersedes_document_id = ?",
				Integer.class, documentId);
		return count != null && count > 0;
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
