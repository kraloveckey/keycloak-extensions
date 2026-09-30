package io.github.kraloveckey.keycloak.loginnotification;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.ws.rs.core.HttpHeaders;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.common.util.Time;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.Urls;

/**
 * Sends the user an e-mail about a sign-in after a long absence and/or from an IP address not used recently.
 * <p>
 * Never blocks or fails the sign-in: every problem (no SMTP, no e-mail address, storage or mail errors) is logged and
 * the flow continues.
 */
public class LoginNotificationAuthenticator implements Authenticator {

	private static final Logger log = Logger.getLogger(LoginNotificationAuthenticator.class);

	static final String TEMPLATE = "login-notification.ftl";
	static final String SUBJECT_KEY = "loginNotificationSubject";

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
			.withZone(ZoneId.systemDefault());
	private static final int MAX_USER_AGENT = 200;

	@Override
	public void authenticate(AuthenticationFlowContext context) {
		try {
			process(context);
		} catch (Exception e) {
			log.warnf(e, "Login notification failed for user '%s' in realm '%s'; sign-in continues",
					context.getUser().getUsername(), context.getRealm().getName());
		}
		context.success();
	}

	private void process(AuthenticationFlowContext context) throws Exception {
		KeycloakSession session = context.getSession();
		RealmModel realm = context.getRealm();
		UserModel user = context.getUser();
		Map<String, String> config = Optional.ofNullable(context.getAuthenticatorConfig())
				.map(AuthenticatorConfigModel::getConfig)
				.orElse(Map.of());

		LoginNotificationRules rules = LoginNotificationAuthenticatorFactory.rules(config);
		String ip = context.getConnection().getRemoteAddr();
		long now = Time.currentTimeSeconds();

		LoginHistory history = new LoginHistory(session, realm, user);
		LoginNotificationRules.Outcome outcome = rules.evaluate(now, history.lastLogin(), history.recentIps(), ip);
		history.save(now, outcome.recentIps());

		if (!outcome.shouldNotify()) {
			return;
		}
		if (user.getEmail() == null || user.getEmail().isBlank()) {
			log.debugf("No login notification for '%s': the user has no e-mail address", user.getUsername());
			return;
		}
		if (Boolean.parseBoolean(config.getOrDefault(LoginNotificationAuthenticatorFactory.CONFIG_VERIFIED_ONLY, "true"))
				&& !user.isEmailVerified()) {
			log.debugf("No login notification for '%s': the e-mail address is not verified", user.getUsername());
			return;
		}
		if (realm.getSmtpConfig() == null || realm.getSmtpConfig().isEmpty()) {
			log.warnf("No login notification for '%s': realm '%s' has no SMTP server configured",
					user.getUsername(), realm.getName());
			return;
		}

		ClientModel client = context.getAuthenticationSession().getClient();
		String application = client.getName() != null && !client.getName().isBlank() ? client.getName()
				: client.getClientId();
		String userAgent = Optional.ofNullable(context.getHttpRequest().getHttpHeaders()
				.getHeaderString(HttpHeaders.USER_AGENT)).orElse("-");
		if (userAgent.length() > MAX_USER_AGENT) {
			userAgent = userAgent.substring(0, MAX_USER_AGENT) + "...";
		}
		String realmName = realm.getDisplayName() != null && !realm.getDisplayName().isBlank()
				? realm.getDisplayName() : realm.getName();

		Map<String, Object> attributes = new HashMap<>();
		attributes.put("username", user.getUsername());
		attributes.put("ipAddress", ip);
		attributes.put("userAgent", userAgent);
		attributes.put("application", application);
		attributes.put("loginTime", TIME.format(Instant.ofEpochSecond(now)));
		attributes.put("afterInactivity", outcome.afterInactivity());
		// days for the usual multi-day periods, hours when the configured period is shorter than a day
		attributes.put("inactiveInDays", outcome.inactiveDays() >= 1);
		attributes.put("inactiveDays", String.valueOf(outcome.inactiveDays()));
		attributes.put("inactiveHours", String.valueOf(outcome.inactiveHours()));
		attributes.put("fromNewIp", outcome.fromNewIp());
		attributes.put("accountUrl", Urls.accountBase(context.getUriInfo().getBaseUri()).build(realm.getName()).toString());

		session.getProvider(EmailTemplateProvider.class)
				.setRealm(realm)
				.setUser(user)
				.send(SUBJECT_KEY, List.of(realmName), TEMPLATE, attributes);
		log.infof("Login notification sent to '%s' (realm '%s', ip %s, inactivity %s, new ip %s)", user.getUsername(),
				realm.getName(), ip, outcome.afterInactivity(), outcome.fromNewIp());
	}

	@Override
	public void action(AuthenticationFlowContext context) {
		// no form
	}

	@Override
	public boolean requiresUser() {
		return true;
	}

	@Override
	public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
		return true;
	}

	@Override
	public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
		// none
	}

	@Override
	public void close() {
		// stateless
	}
}
