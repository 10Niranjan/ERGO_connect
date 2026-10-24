package com.ergo.connect.common.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ergo.connect.common.security.ModuleAccess.Level;

import jakarta.annotation.PostConstruct;

/**
 * The live role-by-module access sheet and sidebar, read from {@code role_module_permissions} / {@code nav_items}
 * (V25) and held as one immutable snapshot that is swapped atomically. {@link ModuleAccess} and every
 * {@code @access.*} check read it, so an ERGO Admin edit applies on the next request: immediately on the instance that
 * saved, within {@code ergo.access.refresh-ms} (5 s) on any other.
 * <p>
 * Fails closed: the app does not start if a configured role has no row for a module, and a failed refresh keeps the
 * last good snapshot instead of opening anything up.
 */
@Component
public class AccessPolicy {

	private static final Logger log = LoggerFactory.getLogger(AccessPolicy.class);

	/** {@code cap} is the module switch (NV hidden, V read-only, A no final approvals, O on). */
	public record ModuleInfo(String code, String label, boolean configurable, String cap) {
	}

	public record NavItem(String key, String section, int sectionOrder, String label, String path, String icon,
			String module, String minLevel, Set<CompanyType> sides) {
	}

	/** One sidebar entry as the signed-in user sees it. */
	public record MenuItem(String key, String label, String path, String icon) {
	}

	public record MenuSection(String title, List<MenuItem> items) {
	}

	/** Everything the client needs to render: the caller's level per module (NV/V/A/O) and their sidebar. */
	public record AccessView(long version, Map<String, String> permissions, List<MenuSection> menu) {
	}

	private record Snapshot(long version, Map<String, Map<Role, String>> levels, List<ModuleInfo> modules,
			List<NavItem> nav) {
	}

	private static final AtomicReference<Snapshot> CURRENT = new AtomicReference<>();

	/**
	 * The snapshot one request works with. A request must see one consistent sheet from its permission check to its
	 * last role-set lookup: an edit landing in between must not flip a caller's audience mid-request.
	 */
	private static final ThreadLocal<Snapshot> PINNED = new ThreadLocal<>();

	/** The bean, for the static switch checks that must read the database rather than the snapshot. */
	private static volatile AccessPolicy instance;

	private final JdbcTemplate jdbc;

	AccessPolicy(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@PostConstruct
	void load() {
		instance = this;
		install(read());
	}

	/** Called by the request filter around each request (see PermissionsVersionFilter). */
	static void pin() {
		PINNED.set(CURRENT.get());
	}

	static void unpin() {
		PINNED.remove();
	}

	/** Never replaces a newer snapshot with an older one (a slow refresh racing a save). */
	private static void install(Snapshot next) {
		CURRENT.accumulateAndGet(next, (cur, n) -> cur == null || n.version() >= cur.version() ? n : cur);
	}

	/** Cheap single-row check; reloads only when another instance (or a save) changed the version. Also run at the
	 * start of every API request (PermissionsVersionFilter), so a permission or switch change made on one instance
	 * applies to the next request on every other instance, not only after the polling interval. */
	@Scheduled(fixedDelayString = "${ergo.access.refresh-ms:5000}")
	void refreshIfChanged() {
		try {
			Long version = jdbc.queryForObject("SELECT version FROM access_config", Long.class);
			Snapshot snapshot = CURRENT.get();
			if (version != null && (snapshot == null || snapshot.version() != version)) {
				install(read());
			}
		}
		catch (RuntimeException e) {
			log.warn("Access policy refresh failed; keeping the last good snapshot", e);
		}
	}

	/**
	 * The switch of each named module straight from the database, never from the snapshot, so a check made right
	 * before a write is correct on every instance and on every thread (background jobs included). Inside a writable
	 * transaction the rows are share-locked until it ends: a concurrent switch change then waits for the in-flight
	 * operation instead of landing in the middle of it, so one operation never straddles a switch change.
	 */
	static Map<String, String> capsNow(Collection<String> modules) {
		AccessPolicy policy = instance;
		if (policy == null) {
			throw new IllegalStateException("Access policy not loaded");
		}
		boolean lock = TransactionSynchronizationManager.isActualTransactionActive()
				&& !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
		Map<String, String> caps = new LinkedHashMap<>();
		policy.jdbc.query(con -> {
			var ps = con.prepareStatement("SELECT module_code, access_cap FROM app_modules WHERE module_code = ANY (?)"
					+ " ORDER BY module_code" + (lock ? " FOR SHARE" : ""));
			ps.setArray(1, con.createArrayOf("varchar", modules.toArray()));
			return ps;
		}, rs -> {
			caps.put(rs.getString(1), rs.getString(2));
		});
		return caps;
	}

	/** Reload now (called after a permission save commits). The instance that just saved is the live one: the static
	 * switch checks read its database (matters only where several application contexts share a JVM, as in tests). */
	public void refresh() {
		instance = this;
		install(read());
	}

	/** Version, levels and menu must belong together: retry if a save landed while they were being read. */
	private Snapshot read() {
		for (int attempt = 0; attempt < 3; attempt++) {
			long before = jdbc.queryForObject("SELECT version FROM access_config", Long.class);
			Snapshot snapshot = readOnce(before);
			long after = jdbc.queryForObject("SELECT version FROM access_config", Long.class);
			if (before == after) {
				return snapshot;
			}
		}
		throw new IllegalStateException("Access policy changed while loading; will retry");
	}

	private Snapshot readOnce(long version) {
		List<ModuleInfo> modules = jdbc.query(
				"SELECT module_code, label, configurable, access_cap FROM app_modules ORDER BY sort_order",
				(rs, n) -> new ModuleInfo(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4)));
		Map<String, String> caps = new LinkedHashMap<>();
		modules.forEach(m -> caps.put(m.code(), m.cap()));
		Map<String, Map<Role, String>> levels = new LinkedHashMap<>();
		modules.forEach(m -> levels.put(m.code(), new EnumMap<>(Role.class)));
		// the effective level: a role's own level, lowered to the module's switch
		jdbc.query("SELECT role_code, module_code, access_level FROM role_module_permissions", rs -> {
			Map<Role, String> byRole = levels.get(rs.getString(2));
			if (byRole != null) {
				byRole.put(Role.valueOf(rs.getString(1)), lower(rs.getString(3), caps.get(rs.getString(2))));
			}
		});
		for (ModuleInfo m : modules) {
			for (Role role : Role.values()) {
				if (!levels.get(m.code()).containsKey(role)) {
					throw new IllegalStateException("No access level configured for " + role + " on " + m.code()
							+ " (role_module_permissions); refusing to start");
				}
			}
		}
		List<NavItem> nav = jdbc.query("""
				SELECT item_key, section_title, section_order, label, path, icon, module_code, min_level, sides
				FROM nav_items WHERE active ORDER BY section_order, sort_order""",
				(rs, n) -> new NavItem(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5),
						rs.getString(6), rs.getString(7), rs.getString(8),
						Arrays.stream(rs.getString(9).split(",")).map(CompanyType::valueOf)
							.collect(Collectors.toUnmodifiableSet())));
		for (NavItem item : nav) {
			if (!levels.containsKey(item.module())) {
				throw new IllegalStateException("Menu item " + item.key() + " names unknown module " + item.module());
			}
		}
		return new Snapshot(version, levels, List.copyOf(modules), List.copyOf(nav));
	}

	private static Snapshot snapshot() {
		Snapshot s = PINNED.get() != null ? PINNED.get() : CURRENT.get();
		if (s == null) {
			throw new IllegalStateException("Access policy not loaded");
		}
		return s;
	}

	public static long version() {
		return snapshot().version();
	}

	public static List<ModuleInfo> modules() {
		return snapshot().modules();
	}

	/** Brings this instance's snapshot up to the database now, for background threads that must see a switch change
	 * that has just committed (their first read would otherwise race the post-commit refresh). */
	public static void syncNow() {
		AccessPolicy policy = instance;
		if (policy != null) {
			policy.refreshIfChanged();
		}
	}

	/** The module's switch (NV hidden, V read-only, A no final approvals, O on) in this request's snapshot. */
	public static String cap(String module) {
		for (ModuleInfo m : snapshot().modules()) {
			if (m.code().equals(module)) {
				return m.cap();
			}
		}
		throw new IllegalArgumentException("Unknown module " + module);
	}

	/** The effective level code (NV/V/A/O) of a role on a module: its own level lowered to the module switch. */
	public static String code(Role role, String module) {
		Map<Role, String> byRole = snapshot().levels().get(module);
		if (byRole == null) {
			throw new IllegalArgumentException("Unknown module " + module);
		}
		return byRole.get(role);
	}

	/** The engine's level: a customer-side {@code O} is the sheet's {@code CO}. */
	public static Level level(Role role, String module) {
		return switch (code(role, module)) {
			case "V" -> Level.V;
			case "A" -> Level.A;
			case "O" -> role.companyType() == CompanyType.CUSTOMER ? Level.CO : Level.O;
			default -> Level.NONE;
		};
	}

	/** The caller's permissions and sidebar for the client. Server-side filtered: hidden items are never sent. */
	public static AccessView view(Role role, CompanyType side) {
		Snapshot s = snapshot();
		Map<String, String> permissions = new LinkedHashMap<>();
		s.modules().forEach(m -> permissions.put(m.code(), s.levels().get(m.code()).get(role)));
		Map<String, List<MenuItem>> bySection = new LinkedHashMap<>();
		Map<String, Integer> order = new LinkedHashMap<>();
		for (NavItem item : s.nav()) {
			if (!item.sides().contains(side) || rank(s.levels().get(item.module()).get(role)) < rank(item.minLevel())) {
				continue;
			}
			String title = item.section() == null ? "" : item.section();
			order.putIfAbsent(title, item.sectionOrder());
			bySection.computeIfAbsent(title, k -> new ArrayList<>())
				.add(new MenuItem(item.key(), item.label(), item.path(), item.icon()));
		}
		List<MenuSection> menu = bySection.entrySet().stream()
			.sorted(Comparator.comparingInt(e -> order.get(e.getKey())))
			.map(e -> new MenuSection(e.getKey().isEmpty() ? null : e.getKey(), List.copyOf(e.getValue())))
			.toList();
		return new AccessView(s.version(), permissions, menu);
	}

	/** The lower of two level codes (NV &lt; V &lt; A &lt; O). */
	public static String lower(String a, String b) {
		return rank(a) <= rank(b) ? a : b;
	}

	private static int rank(String code) {
		return switch (code) {
			case "V" -> 1;
			case "A" -> 2;
			case "O" -> 3;
			default -> 0;
		};
	}

}
