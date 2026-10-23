package com.ergo.connect.npd.domain;

/**
 * Where a project is in the quality validation and customer final approval loop (flow doc steps 9-10).
 * <pre>
 * NOT_STARTED -> IN_VALIDATION -> VALIDATED -> IN_CUSTOMER_REVIEW -> CUSTOMER_APPROVED
 *      ^              | rejected by ERGO / Supplier Quality: back to a development stage
 *      +--------------+ rejected by the customer: back to Final Sample Readiness
 * </pre>
 */
public enum ValidationStatus {

	NOT_STARTED, IN_VALIDATION, VALIDATED, IN_CUSTOMER_REVIEW, CUSTOMER_APPROVED

}
