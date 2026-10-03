package com.ergo.connect.documents.domain;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.documents.api.DocumentsApi;

/** Implements {@link DocumentsApi}: {@link DocumentService}'s own record scope and visibility, as a boolean. */
@Service
@Transactional(readOnly = true)
class DocumentsApiService implements DocumentsApi {

	private final DocumentService documents;

	DocumentsApiService(DocumentService documents) {
		this.documents = documents;
	}

	@Override
	public boolean documentVisibleToCaller(long documentId) {
		return documents.visibleToCaller(documentId);
	}

}
