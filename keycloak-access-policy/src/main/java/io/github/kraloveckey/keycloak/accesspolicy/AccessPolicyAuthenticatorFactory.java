package io.github.kraloveckey.keycloak.accesspolicy;

import java.util.List;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

public class AccessPolicyAuthenticatorFactory implements AuthenticatorFactory {

	public static final String ID = "access-policy";

	static final String CONFIG_POLICY = "accessPolicy";
	static final String CONFIG_ERROR_MESSAGE = "errorMessage";

	private static final AccessPolicyAuthenticator INSTANCE = new AccessPolicyAuthenticator();

	private static final Requirement[] REQUIREMENTS = { Requirement.REQUIRED, Requirement.DISABLED };

	private static final List<ProviderConfigProperty> CONFIG = ProviderConfigurationBuilder.create()
			.property()
				.name(CONFIG_POLICY)
				.label("Access policy (JSON)")
				.type(ProviderConfigProperty.TEXT_TYPE)
				.defaultValue("""
						{
						  "unlistedClients": "allow",
						  "rules": [
						    { "clients": ["my-client"], "realmRoles": ["my-role"], "clientRoles": {}, "groups": [] }
						  ]
						}""")
				.helpText("Rules decide which users may sign in to which clients. A rule applies to clients listed in "
						+ "\"clients\" (exact client ID, or a regular expression starting with ^). The user needs any of "
						+ "the rule's \"realmRoles\", \"clientRoles\" ({\"client-id\": [\"role\"]}) or \"groups\" (full "
						+ "path, subgroups count). A rule without roles and groups blocks the client. Clients without "
						+ "rules follow \"unlistedClients\": \"allow\" or \"deny\". An invalid policy denies access.")
				.add()
			.property()
				.name(CONFIG_ERROR_MESSAGE)
				.label("Error message")
				.type(ProviderConfigProperty.STRING_TYPE)
				.helpText("Shown on the error page when access is denied: a message key of the login theme or plain "
						+ "text. Empty uses Keycloak's \"access denied\" message.")
				.add()
			.build();

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getDisplayType() {
		return "Access policy";
	}

	@Override
	public String getReferenceCategory() {
		return "access-policy";
	}

	@Override
	public String getHelpText() {
		return "Allows the user into the client only if a rule grants it through realm roles, client roles or groups.";
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
