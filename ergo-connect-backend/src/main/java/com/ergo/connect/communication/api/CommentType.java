package com.ergo.connect.communication.api;

/**
 * SRS §13.7.3 {@code comment_type}: who may read a comment. INTERNAL = ERGO only; EXTERNAL_CUSTOMER = ERGO plus the
 * record's customer; EXTERNAL_SUPPLIER = ERGO plus the record's supplier. Customers and suppliers never share a
 * thread (they never see each other), so there is deliberately no "both external" value.
 */
public enum CommentType {
	INTERNAL, EXTERNAL_CUSTOMER, EXTERNAL_SUPPLIER
}
