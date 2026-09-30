package io.github.kraloveckey.keycloak.idplink;

import java.util.List;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

public class IdpDetectByAttributeAuthenticatorFactory implements AuthenticatorFactory {

	public static final String ID = "idp-detect-existing-by-attribute";

	static final String CONFIG_ATTRIBUTE = "attributeName";
	static final String CONFIG_REQUIRE_VERIFIED = "requireVerifiedEmail";

	private static final IdpDetectByAttributeAuthenticator INSTANCE = new IdpDetectByAttributeAuthenticator();
	private static final Requirement[] REQUIREMENTS = { Requirement.REQUIRED, Requirement.DISABLED };

	private static final List<ProviderConfigProperty> CONFIG = ProviderConfigurationBuilder.create()
			.property()
				.name(CONFIG_ATTRIBUTE)
				.label("Attribute name")
				.type(ProviderConfigProperty.STRING_TYPE)
				.helpText("User attribute compared with the e-mail from the identity provider. The e-mail is lower-cased "
						+ "and must equal the attribute value exactly, so store the values in lower case.")
				.add()
			.property()
				.name(CONFIG_REQUIRE_VERIFIED)
				.label("Reject unverified e-mail")
				.type(ProviderConfigProperty.BOOLEAN_TYPE)
				.defaultValue("true")
				.helpText("Do not link when the identity provider states email_verified=false. Providers that send no "
						+ "such claim are not affected.")
				.add()
			.build();

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getDisplayType() {
		return "Detect existing broker user by attribute";
	}

	@Override
	public String getHelpText() {
		return "Finds the existing user whose attribute equals the e-mail from the identity provider, for "
				+ "\"Automatically set existing user\" to link.";
	}

	@Override
	public String getReferenceCategory() {
		return null;
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
