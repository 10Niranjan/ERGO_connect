package com.ergo.connect.communication.domain;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.communication.api.CommunicationApi;

/** Implements {@link CommunicationApi} for the documents module's comment attachments (Phase 6c). */
@Service
@Transactional(readOnly = true)
class CommunicationApiService implements CommunicationApi {

	private final CommentService comments;

	CommunicationApiService(CommentService comments) {
		this.comments = comments;
	}

	@Override
	public boolean commentVisibleToCaller(long commentId) {
		return comments.visibleToCaller(commentId);
	}

	@Override
	public boolean attachmentWindowOpenForCaller(long commentId) {
		return comments.attachmentWindowOpen(commentId);
	}

}
