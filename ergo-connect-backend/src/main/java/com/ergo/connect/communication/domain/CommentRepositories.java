package com.ergo.connect.communication.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import jakarta.persistence.LockModeType;

/** Package-private; CommentService applies the record scope (cross-module) and audience rules for every read. */
interface CommentRepository extends Repository<Comment, Long> {

	Comment save(Comment comment);

	Optional<Comment> findById(Long id);

	/** Bumps the parent's version at commit, so a reply racing the author's edit collides as a 409 instead of the edit
	 * silently landing on a comment that has just been answered (security review L1). */
	@Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
	@Query("select c from Comment c where c.id = :id")
	Optional<Comment> findByIdForReply(Long id);

	/** Top-level comments of one record, oldest first. Audience filtering happens in CommentService. */
	List<Comment> findByLinkedModuleAndLinkedRecordIdAndParentCommentIdIsNullOrderById(String linkedModule,
			Long linkedRecordId);

	List<Comment> findByParentCommentIdInOrderById(Collection<Long> parentIds);

	boolean existsByParentCommentId(Long parentCommentId);

	/** Communication Center list: top-level comments across records, newest first. Record scope and audience are
	 * resolved afterwards in Java (ponytail: in-memory filter over a capped candidate window, same shape as the
	 * Document Library - revisit if this becomes a hot path on a much larger comment count). */
	@Query("""
			select c from Comment c
			where c.parentCommentId is null
			  and (:linkedModule is null or c.linkedModule = :linkedModule)
			  and (:openQueriesOnly = false or (c.queryFlag = true and c.resolvedAt is null))
			  and (:escalatedOnly = false or c.escalatedFlag = true)
			  and (:audienceCompanyId is null or c.audienceCompanyId = :audienceCompanyId)
			order by c.id desc""")
	List<Comment> findInbox(String linkedModule, boolean openQueriesOnly, boolean escalatedOnly,
			Long audienceCompanyId, Pageable window);

}
