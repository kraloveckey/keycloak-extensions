package io.github.kraloveckey.keycloak.loginnotification;

import java.util.Arrays;
import java.util.List;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.cache.UserCache;
import org.keycloak.storage.UserStoragePrivateUtil;
import org.keycloak.storage.UserStorageUtil;

/**
 * Last sign-in time and recently used IP addresses (one comma-separated value), kept as user attributes.
 * <p>
 * Written to Keycloak's own database, never to a user federation: LDAP users in READ_ONLY mode reject every
 * attribute change, which would otherwise break the sign-in. Imported users get the attributes on their local copy,
 * users that are not imported get them in Keycloak's federated user storage.
 */
final class LoginHistory {

	static final String LAST_LOGIN = "login-notification.last-login";
	static final String RECENT_IPS = "login-notification.recent-ips";

	private final KeycloakSession session;
	private final RealmModel realm;
	private final UserModel user;
	private final UserModel local;

	LoginHistory(KeycloakSession session, RealmModel realm, UserModel user) {
		this.session = session;
		this.realm = realm;
		this.user = user;
		this.local = UserStoragePrivateUtil.userLocalStorage(session).getUserById(realm, user.getId());
	}

	/** Epoch seconds of the previous sign-in, {@code null} if never recorded. */
	Long lastLogin() {
		return read(LAST_LOGIN).stream().findFirst().map(value -> {
			try {
				return Long.parseLong(value);
			} catch (NumberFormatException e) {
				return null;
			}
		}).orElse(null);
	}

	/** Most recent first. */
	List<String> recentIps() {
		// one comma-separated value; older versions stored one value per address, both are read
		return read(RECENT_IPS).stream()
				.flatMap(value -> Arrays.stream(value.split(",")))
				.map(String::trim)
				.filter(ip -> !ip.isEmpty())
				.distinct()
				.toList();
	}

	void save(long lastLogin, List<String> recentIps) {
		write(LAST_LOGIN, List.of(String.valueOf(lastLogin)));
		// a single value: Keycloak warns about multi-valued attributes outside the user profile whenever it builds
		// an e-mail for the user
		write(RECENT_IPS, recentIps.isEmpty() ? List.of() : List.of(String.join(",", recentIps)));
	}

	private List<String> read(String name) {
		if (local != null) {
			return local.getAttributeStream(name).toList();
		}
		return UserStorageUtil.userFederatedStorage(session).getAttributes(realm, user.getId())
				.getOrDefault(name, List.of());
	}

	private void write(String name, List<String> values) {
		if (local != null) {
			local.setAttribute(name, values);
			// the flow may hold a cached copy of the user; drop it so the next read sees the new values
			UserCache cache = UserStorageUtil.userCache(session);
			if (cache != null) {
				cache.evict(realm, user);
			}
		} else {
			UserStorageUtil.userFederatedStorage(session).setAttribute(realm, user.getId(), name, values);
		}
	}
}
