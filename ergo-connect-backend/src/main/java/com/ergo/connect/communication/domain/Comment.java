package com.ergo.connect.communication.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.ergo.connect.common.audit.AuditExclude;
import com.ergo.connect.communication.api.CommentType;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** SRS §13.7.3 (V21 migration). Package-private: only {@code communication.domain} touches it. The author may edit
 * until someone replies or the query is resolved; every change is audited automatically like any JPA entity. */
@Entity
@Table(name = "comments")
class Comment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "comment_id")
	Long id;

	@Column(updatable = false)
	String linkedModule;

	@Column(updatable = false)
	Long linkedRecordId;

	@Enumerated(EnumType.STRING)
	@Column(updatable = false)
	CommentType commentType;

	/** The one external company allowed to read this comment (null for INTERNAL); see V21. */
	@Column(updatable = false)
	Long audienceCompanyId;

	@Column(updatable = false)
	Long parentCommentId;

	String commentText;

	@Column(updatable = false)
	Long createdByUserId;

	@Column(updatable = false)
	Long createdByCompanyId;

	boolean queryFlag;

	Instant resolvedAt;

	Long resolvedByUserId;

	boolean escalatedFlag;

	Instant escalatedAt;

	Long escalatedByUserId;

	@ElementCollection
	@CollectionTable(name = "comment_mentions", joinColumns = @JoinColumn(name = "comment_id"))
	@Column(name = "mentioned_user_id")
	Set<Long> mentionedUserIds = new HashSet<>();

	@CreationTimestamp
	@Column(updatable = false)
	Instant createdAt;

	@UpdateTimestamp
	@AuditExclude
	Instant updatedAt;

	/** Optimistic lock: of two concurrent changes (an author's edit racing a reply, or two resolves) the later one
	 * fails with 409 instead of silently overwriting the other. */
	@Version
	@AuditExclude
	long rowVersion;

}
