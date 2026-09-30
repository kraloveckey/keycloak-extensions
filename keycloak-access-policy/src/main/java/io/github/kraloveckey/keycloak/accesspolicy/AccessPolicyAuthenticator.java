package io.github.kraloveckey.keycloak.accesspolicy;

import java.util.Map;
import java.util.Optional;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.events.Errors;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.idm.OAuth2ErrorRepresentation;
import org.keycloak.services.messages.Messages;

/**
 * Lets the flow continue only if the {@link AccessPolicy} allows the user into the client being signed in to.
 * <p>
 * A policy that cannot be parsed denies access (fail closed) and logs why, so a typo never opens every client.
 */
public class AccessPolicyAuthenticator implements Authenticator {

	private static final Logger log = Logger.getLogger(AccessPolicyAuthenticator.class);

	static final String DETAIL_REASON = "access_policy_reason";

	/** Flow path Keycloak sets for the resource owner password credentials grant. */
	private static final String DIRECT_GRANT_FLOW_PATH = "token";

	@Override
	public void authenticate(AuthenticationFlowContext context) {
		RealmModel realm = context.getRealm();
		UserModel user = context.getUser();
		ClientModel client = context.getAuthenticationSession().getClient();
		Map<String, String> config = Optional.ofNullable(context.getAuthenticatorConfig())
				.map(AuthenticatorConfigModel::getConfig)
				.orElse(Map.of());

		AccessPolicy policy;
		try {
			policy = AccessPolicy.parse(config.get(AccessPolicyAuthenticatorFactory.CONFIG_POLICY));
		} catch (IllegalArgumentException e) {
			log.errorf("Access policy of realm '%s' is invalid, denying access to '%s' for '%s': %s",
					realm.getName(), client.getClientId(), user.getUsername(), e.getMessage());
			deny(context, config, "invalid access policy: " + e.getMessage());
			return;
		}

		AccessPolicy.Decision decision = policy.evaluate(client.getClientId(),
				new UserAccessSubject(context.getSession(), realm, user));
		if (decision.allowed()) {
			log.debugf("Access policy allows '%s' into '%s' (realm '%s'): %s", user.getUsername(),
					client.getClientId(), realm.getName(), decision.reason());
			context.success();
			return;
		}

		log.debugf("Access policy denies '%s' access to '%s' (realm '%s'): %s", user.getUsername(),
				client.getClientId(), realm.getName(), decision.reason());
		deny(context, config, decision.reason());
	}

	private void deny(AuthenticationFlowContext context, Map<String, String> config, String reason) {
		context.getEvent().user(context.getUser()).detail(DETAIL_REASON, reason).error(Errors.ACCESS_DENIED);

		String message = config.getOrDefault(AccessPolicyAuthenticatorFactory.CONFIG_ERROR_MESSAGE, "");
		if (DIRECT_GRANT_FLOW_PATH.equals(context.getFlowPath())) {
			// password grant: the caller is an API client and expects an OAuth2 error, not an HTML page
			Response error = Response.status(Response.Status.FORBIDDEN)
					.entity(new OAuth2ErrorRepresentation(Errors.ACCESS_DENIED, "Access to the client is not allowed"))
					.type(MediaType.APPLICATION_JSON_TYPE)
					.build();
			context.failure(AuthenticationFlowError.ACCESS_DENIED, error);
			return;
		}
		Response page = context.form()
				.setError(message.isBlank() ? Messages.ACCESS_DENIED : message)
				.createErrorPage(Response.Status.FORBIDDEN);
		context.failure(AuthenticationFlowError.ACCESS_DENIED, page);
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
