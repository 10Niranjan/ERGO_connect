package com.ergo.connect.common.audit;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Objects;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import jakarta.persistence.EntityManagerFactory;

/**
 * Audits every JPA insert, update and delete automatically (ARCHITECTURE.md §1.3), so no module can forget to.
 * Updates are written per changed field with old and new values. Collections are skipped; services audit those
 * as explicit events.
 */
@Component
class AuditEntityListener implements PreInsertEventListener, PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

	private static final String MASK = "***";

	private final AuditLogService audit;

	AuditEntityListener(AuditLogService audit, EntityManagerFactory emf) {
		this.audit = audit;
		EventListenerRegistry registry = emf.unwrap(SessionFactoryImplementor.class)
			.getServiceRegistry()
			.requireService(EventListenerRegistry.class);
		registry.appendListeners(EventType.PRE_INSERT, this);
		registry.appendListeners(EventType.POST_INSERT, this);
		registry.appendListeners(EventType.POST_UPDATE, this);
		registry.appendListeners(EventType.POST_DELETE, this);
	}

	/** Stamps the Super Admin on rows that carry an actingAdminUserId property, when the insert is an act-as. */
	@Override
	public boolean onPreInsert(PreInsertEvent event) {
		Long admin = com.ergo.connect.common.security.CurrentUser.find()
			.map(com.ergo.connect.common.security.CurrentUser::impersonatorId)
			.orElse(null);
		if (admin != null) {
			String[] names = event.getPersister().getPropertyNames();
			for (int i = 0; i < names.length; i++) {
				if ("actingAdminUserId".equals(names[i])) {
					event.getState()[i] = admin;
					event.getPersister().setValue(event.getEntity(), i, admin);
				}
			}
		}
		return false;
	}

	@Override
	public void onPostInsert(PostInsertEvent event) {
		if (audited(event.getEntity())) {
			audit.record(event.getEntity().getClass(), id(event.getId()), "CREATE", null, null, null);
		}
	}

	@Override
	public void onPostDelete(PostDeleteEvent event) {
		if (audited(event.getEntity())) {
			audit.record(event.getEntity().getClass(), id(event.getId()), "DELETE", null, null, null);
		}
	}

	@Override
	public void onPostUpdate(PostUpdateEvent event) {
		Object entity = event.getEntity();
		Object[] before = event.getOldState();
		if (!audited(entity) || before == null) {
			return;
		}
		Object[] after = event.getState();
		String[] names = event.getPersister().getPropertyNames();
		for (int i = 0; i < names.length; i++) {
			if (after[i] instanceof Collection<?> || same(before[i], after[i]) || has(entity, names[i], AuditExclude.class)) {
				continue;
			}
			boolean masked = has(entity, names[i], AuditMasked.class);
			audit.record(entity.getClass(), id(event.getId()), "UPDATE", names[i],
					masked ? MASK : Objects.toString(before[i], null), masked ? MASK : Objects.toString(after[i], null));
		}
	}

	@Override
	public boolean requiresPostCommitHandling(EntityPersister persister) {
		return false;
	}

	private static boolean audited(Object entity) {
		return !entity.getClass().isAnnotationPresent(AuditExclude.class);
	}

	private static boolean same(Object a, Object b) {
		if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
			return x.compareTo(y) == 0; // 1.50 == 1.5 for money
		}
		return Objects.equals(a, b);
	}

	private static boolean has(Object entity, String property, Class<? extends Annotation> annotation) {
		Field field = ReflectionUtils.findField(entity.getClass(), property);
		return field != null && field.isAnnotationPresent(annotation);
	}

	private static long id(Object id) {
		return ((Number) id).longValue();
	}

}
