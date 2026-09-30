package io.github.kraloveckey.keycloak.loginnotification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class LoginNotificationRulesTest {

	private static final long DAY = 86_400;
	private static final long NOW = 1_800_000_000L;

	private final LoginNotificationRules both = new LoginNotificationRules(Duration.ofDays(30), true, 3);

	@Test
	void firstSignInOnlyStartsTheHistory() {
		var outcome = both.evaluate(NOW, null, List.of(), "10.0.0.1");
		assertFalse(outcome.shouldNotify());
		assertEquals(List.of("10.0.0.1"), outcome.recentIps());
	}

	@Test
	void inactivity() {
		assertFalse(both.evaluate(NOW, NOW - 29 * DAY, List.of("10.0.0.1"), "10.0.0.1").shouldNotify());
		var outcome = both.evaluate(NOW, NOW - 45 * DAY, List.of("10.0.0.1"), "10.0.0.1");
		assertTrue(outcome.afterInactivity());
		assertFalse(outcome.fromNewIp());
		assertEquals(45, outcome.inactiveDays());
	}

	@Test
	void newIpAndHistoryIsMostRecentFirstAndCapped() {
		var outcome = both.evaluate(NOW, NOW - DAY, List.of("a", "b", "c"), "d");
		assertTrue(outcome.fromNewIp());
		assertEquals(List.of("d", "a", "b"), outcome.recentIps());

		var known = both.evaluate(NOW, NOW - DAY, List.of("a", "b", "c"), "b");
		assertFalse(known.shouldNotify());
		assertEquals(List.of("b", "a", "c"), known.recentIps());
	}

	@Test
	void checksCanBeSwitchedOff() {
		var none = new LoginNotificationRules(null, false, 5);
		assertFalse(none.evaluate(NOW, NOW - 400 * DAY, List.of("a"), "z").shouldNotify());
	}

	@Test
	void settingsFallBackToDefaults() {
		var rules = LoginNotificationAuthenticatorFactory.rules(Map.of());
		assertEquals(Duration.ofDays(30), rules.inactivity());
		assertFalse(rules.newIp());
		assertEquals(5, rules.rememberIps());

		var bad = LoginNotificationAuthenticatorFactory.rules(Map.of("inactivityPeriod", "30 days", "rememberedIps", "x"));
		assertEquals(Duration.ofDays(30), bad.inactivity());
		assertEquals(5, bad.rememberIps());

		var off = LoginNotificationAuthenticatorFactory.rules(Map.of("inactivityPeriod", " ", "notifyOnNewIp", "true"));
		assertEquals(null, off.inactivity());
		assertTrue(off.newIp());
	}
}
