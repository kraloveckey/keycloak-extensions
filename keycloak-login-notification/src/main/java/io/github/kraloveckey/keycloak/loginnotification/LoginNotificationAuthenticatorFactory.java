package io.github.kraloveckey.keycloak.loginnotification;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

public class LoginNotificationAuthenticatorFactory implements AuthenticatorFactory {

	private static final Logger log = Logger.getLogger(LoginNotificationAuthenticatorFactory.class);

	public static final String ID = "login-notification";

	static final String CONFIG_INACTIVITY = "inactivityPeriod";
	static final String CONFIG_NEW_IP = "notifyOnNewIp";
	static final String CONFIG_REMEMBER_IPS = "rememberedIps";
	static final String CONFIG_VERIFIED_ONLY = "verifiedEmailOnly";

	private static final String DEFAULT_INACTIVITY = "P30D";
	private static final int DEFAULT_REMEMBER_IPS = 5;

	private static final LoginNotificationAuthenticator INSTANCE = new LoginNotificationAuthenticator();
	private static final Requirement[] REQUIREMENTS = { Requirement.REQUIRED, Requirement.DISABLED };

	private static final List<ProviderConfigProperty> CONFIG = ProviderConfigurationBuilder.create()
			.property()
				.name(CONFIG_INACTIVITY)
				.label("Notify after inactivity")
				.type(ProviderConfigProperty.STRING_TYPE)
				.defaultValue(DEFAULT_INACTIVITY)
				.helpText("E-mail the user when the previous sign-in was longer ago than this ISO-8601 duration, "
						+ "e.g. P30D (30 days) or PT12H (12 hours). Empty disables this check.")
				.add()
			.property()
				.name(CONFIG_NEW_IP)
				.label("Notify on new IP address")
				.type(ProviderConfigProperty.BOOLEAN_TYPE)
				.defaultValue("false")
				.helpText("E-mail the user when the sign-in comes from an IP address that is not among the recently "
						+ "used ones.")
				.add()
			.property()
				.name(CONFIG_REMEMBER_IPS)
				.label("Remembered IP addresses")
				.type(ProviderConfigProperty.STRING_TYPE)
				.defaultValue(String.valueOf(DEFAULT_REMEMBER_IPS))
				.helpText("How many recently used IP addresses are kept per user for the new-address check.")
				.add()
			.property()
				.name(CONFIG_VERIFIED_ONLY)
				.label("Verified e-mail only")
				.type(ProviderConfigProperty.BOOLEAN_TYPE)
				.defaultValue("true")
				.helpText("Send only to e-mail addresses marked as verified.")
				.add()
			.build();

	/** Reads the settings leniently: a bad value falls back to its default and is logged. */
	static LoginNotificationRules rules(Map<String, String> config) {
		Duration inactivity = null;
		String period = config.getOrDefault(CONFIG_INACTIVITY, DEFAULT_INACTIVITY).trim();
		if (!period.isEmpty()) {
			try {
				inactivity = Duration.parse(period);
			} catch (DateTimeParseException e) {
				log.warnf("Invalid '%s' value '%s', using %s", CONFIG_INACTIVITY, period, DEFAULT_INACTIVITY);
				inactivity = Duration.parse(DEFAULT_INACTIVITY);
			}
		}
		int remember = DEFAULT_REMEMBER_IPS;
		try {
			remember = Math.max(1, Integer.parseInt(config.getOrDefault(CONFIG_REMEMBER_IPS, "").trim()));
		} catch (NumberFormatException e) {
			// default
		}
		boolean newIp = Boolean.parseBoolean(config.getOrDefault(CONFIG_NEW_IP, "false"));
		return new LoginNotificationRules(inactivity, newIp, remember);
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getDisplayType() {
		return "Login notification e-mail";
	}

	@Override
	public String getReferenceCategory() {
		return "login-notification";
	}

	@Override
	public String getHelpText() {
		return "E-mails the user about a sign-in after a long absence or from a new IP address. Never blocks the sign-in.";
	}

	@Override
	public boolean isConfigurable() {
		return true;
	}

	@Override
	public Requirement[] getRequirementChoices() {
		return REQUIREMENTS;
	}

	@Override
	public boolean isUserSetupAllowed() {
		return false;
	}

	@Override
	public List<ProviderConfigProperty> getConfigProperties() {
		return CONFIG;
	}

	@Override
	public Authenticator create(KeycloakSession session) {
		return INSTANCE;
	}

	@Override
	public void init(Config.Scope config) {
		// no server configuration
	}

	@Override
	public void postInit(KeycloakSessionFactory factory) {
		// nothing to wire
	}

	@Override
	public void close() {
		// stateless
	}
}
