package io.github.kraloveckey.keycloak.loginnotification;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a sign-in deserves an e-mail and what the history looks like afterwards. No Keycloak types, so it
 * can be tested alone.
 *
 * @param inactivity  e-mail when the previous sign-in is longer ago than this; {@code null} disables the check
 * @param newIp       e-mail when the sign-in comes from an address not among the remembered ones
 * @param rememberIps how many recent addresses to remember
 */
record LoginNotificationRules(Duration inactivity, boolean newIp, int rememberIps) {

	record Outcome(boolean afterInactivity, long inactiveSeconds, boolean fromNewIp, List<String> recentIps) {
		long inactiveDays() {
			return inactiveSeconds / 86_400;
		}

		long inactiveHours() {
			return inactiveSeconds / 3_600;
		}

		boolean shouldNotify() {
			return afterInactivity || fromNewIp;
		}
	}

	/**
	 * @param lastLogin epoch seconds of the previous sign-in or {@code null}; the very first recorded sign-in never
	 *                  triggers an e-mail, it only starts the history
	 */
	Outcome evaluate(long now, Long lastLogin, List<String> recentIps, String ip) {
		boolean first = lastLogin == null;
		long inactiveSeconds = first ? 0 : Math.max(0, now - lastLogin);

		boolean afterInactivity = !first && inactivity != null && inactiveSeconds > inactivity.getSeconds();
		boolean fromNewIp = !first && newIp && ip != null && !recentIps.isEmpty() && !recentIps.contains(ip);

		List<String> updated = new ArrayList<>();
		if (ip != null) {
			updated.add(ip);
		}
		for (String known : recentIps) {
			if (updated.size() >= Math.max(1, rememberIps)) {
				break;
			}
			if (!known.equals(ip)) {
				updated.add(known);
			}
		}
		return new Outcome(afterInactivity, inactiveSeconds, fromNewIp, List.copyOf(updated));
	}
}
