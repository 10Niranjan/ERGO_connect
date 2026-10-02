package com.ergo.connect.documents.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import jakarta.persistence.LockModeType;

/** Package-private; DocumentService applies the record scope (cross-module) and visibility rules for every read. */
interface DocumentRepository extends Repository<Document, Long> {

	Document save(Document document);

	Optional<Document> findById(Long id);

	/** Bumps the row's version at commit so two concurrent replaces of the same version collide as a 409 (optimistic
	 * lock) instead of a 500 from the unique supersedes index (second security review N5). */
	@Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
	@Query("select d from Document d where d.id = :id")
	Optional<Document> findByIdForReplace(Long id);

	List<Document> findByIdIn(Collection<Long> ids);

	/** The whole version chain a document belongs to: itself plus every row that supersedes it, transitively, found
	 * by walking from the chain's root - {@link DocumentService} walks {@code supersedesDocumentId} one hop at a
	 * time instead of a recursive query, which Spring Data's derived/@Query methods can't express. */
	Optional<Document> findBySupersedesDocumentId(Long supersedesDocumentId);

	/** Library list (SRS §12.11.1): the SQL-expressible filters, nullable to mean "don't filter on this" (date range
	 * is filtered afterwards in Java - {@code DocumentService.library} - a null {@code Instant} bind value in an
	 * {@code (:x is null or ...)} clause leaves Postgres unable to infer that parameter's type, unlike the
	 * String/Long filters here). Cross-module record scope and document-level visibility are also resolved
	 * afterwards in Java (ponytail: in-memory filter + pagination in {@code DocumentService} - the table isn't large
	 * enough yet to need a DB-side join against every linked module's own scope tables, which would also mean
	 * reaching past each module's api boundary into its repositories; revisit if this list query becomes a hot path
	 * on a much larger document count). */
	@Query("""
			select d from Document d
			where (:linkedModule is null or d.linkedModule = :linkedModule)
			  and (:categoryId is null or d.documentCategoryId = :categoryId)
			  and (:uploaderCompanyId is null or d.uploadedByCompanyId = :uploaderCompanyId)
			order by d.createdAt desc""")
	List<Document> findFiltered(String linkedModule, Long categoryId, Long uploaderCompanyId);

}

interface DocumentCategoryRepository extends Repository<DocumentCategory, Long> {

	Optional<DocumentCategory> findById(Long id);

	List<DocumentCategory> findByActiveFlagTrueOrderByName();

	List<DocumentCategory> findByIdIn(Collection<Long> ids);

}

interface DocumentLinkRepository extends Repository<DocumentLink, Long> {

	DocumentLink save(DocumentLink link);

	List<DocumentLink> findByDocumentId(Long documentId);

}
