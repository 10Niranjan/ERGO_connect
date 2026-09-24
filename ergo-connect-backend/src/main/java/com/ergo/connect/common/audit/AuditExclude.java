package com.ergo.connect.common.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Keeps an entity (type) or field out of the automatic audit trail. Use only for noise such as timestamps and login
 * counters, or for entities whose changes the service audits explicitly with more meaningful events.
 */
@Target({ ElementType.TYPE, ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditExclude {

}
