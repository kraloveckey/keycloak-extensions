package io.github.kraloveckey.keycloak.idplink;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.authenticators.broker.AbstractIdpAuthenticator;
import org.keycloak.authentication.authenticators.broker.util.ExistingUserInfo;
import org.keycloak.authentication.authenticators.broker.util.SerializedBrokeredIdentityContext;
import org.keycloak.broker.oidc.OIDCIdentityProvider;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.events.Errors;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.IDToken;
import org.keycloak.representations.JsonWebToken;
import org.keycloak.services.messages.Messages;

/**
 * First-broker-login step: finds the existing user whose attribute equals the e-mail sent by the identity provider
 * and hands it to "Automatically set existing user" through the {@code EXISTING_USER_INFO} auth note. Keycloak
 * creates the identity provider link when the flow completes; this step writes nothing.
 */
public class IdpDetectByAttributeAuthenticator extends AbstractIdpAuthenticator {

	private static final Logger log = Logger.getLogger(IdpDetectByAttributeAuthenticator.class);

	/** More matches than this are not needed: two already mean a conflict, the rest is for the log. */
	static final int SEARCH_LIMIT = 10;

	static final String DETAIL_ATTRIBUTE = "link_attribute";
	/** Provider-side id of the account that tried to sign in. */
	static final String DETAIL_IDP_USER_ID = "idp_user_id";
	/** Provider-side id of the account the user is already linked to. */
	static final String DETAIL_LINKED_IDP_USER_ID = "linked_idp_user_id";

	@Override
	protected void authenticateImpl(AuthenticationFlowContext context, SerializedBrokeredIdentityContext serializedCtx,
			BrokeredIdentityContext brokerContext) {
		Map<String, String> config = Optional.ofNullable(context.getAuthenticatorConfig())
				.map(AuthenticatorConfigModel::getConfig)
				.orElse(Map.of());
		String attribute = config.get(IdpDetectByAttributeAuthenticatorFactory.CONFIG_ATTRIBUTE);
		String rawEmail = brokerContext.getEmail();
		String idpAlias = brokerContext.getIdpConfig().getAlias();

		if (attribute == null || attribute.isBlank() || rawEmail == null || rawEmail.isBlank()) {
			log.errorf("Cannot link a '%s' sign-in in realm '%s': attribute name '%s', e-mail from the identity provider '%s'",
					idpAlias, context.getRealm().getName(), attribute, rawEmail);
			// the default page for INTERNAL_ERROR says "Invalid username or password", which misleads users who never
			// typed a password; show a plain internal error instead, the log has the cause
			context.getEvent().error(Errors.INVALID_CONFIG);
			context.failure(AuthenticationFlowError.INTERNAL_ERROR, context.form()
					.setError(Messages.INTERNAL_SERVER_ERROR)
					.createErrorPage(Response.Status.INTERNAL_SERVER_ERROR));
			return;
		}

		String email = rawEmail.toLowerCase(Locale.ROOT);
		KeycloakSession session = context.getSession();
		RealmModel realm = context.getRealm();
		boolean requireVerified = Boolean.parseBoolean(
				config.getOrDefault(IdpDetectByAttributeAuthenticatorFactory.CONFIG_REQUIRE_VERIFIED, "true"));

		List<UserModel> users = session.users()
				.searchForUserByUserAttributeStream(realm, attribute, email)
				.limit(SEARCH_LIMIT)
				.toList();
		List<LinkDecision.Candidate> candidates = users.stream()
				.map(u -> new LinkDecision.Candidate(u.getId(), u.isEnabled(),
						session.users().getFederatedIdentity(realm, u, idpAlias) != null))
				.toList();

		LinkDecision.Result result = LinkDecision.decide(candidates, emailVerified(brokerContext), requireVerified);
		context.getEvent().detail(DETAIL_ATTRIBUTE, attribute);

		switch (result) {
			case LINK -> {
				UserModel user = users.get(0);
				log.debugf("Linking '%s' sign-in %s to user '%s' (%s) by %s", idpAlias, email, user.getUsername(),
						user.getId(), attribute);
				context.getAuthenticationSession().setAuthNote(EXISTING_USER_INFO,
						new ExistingUserInfo(user.getId(), attribute, email).serialize());
				context.success();
			}
			case NOT_FOUND -> {
				log.infof("No user with %s=%s in realm '%s' for a '%s' sign-in", attribute, email, realm.getName(), idpAlias);
				fail(context, result, email, null, Response.Status.FORBIDDEN);
			}
			case CONFLICT -> {
				log.warnf("Several users with %s=%s in realm '%s', not linking the '%s' sign-in; users: %s", attribute, email,
						realm.getName(), idpAlias, users.stream().map(UserModel::getId).collect(Collectors.joining(", ")));
				fail(context, result, email, null, Response.Status.CONFLICT);
			}
			case DISABLED -> {
				log.infof("User %s (%s=%s) is disabled, not linking the '%s' sign-in", users.get(0).getId(), attribute,
						email, idpAlias);
				fail(context, result, email, users.get(0), Response.Status.FORBIDDEN);
			}
			case ALREADY_LINKED -> {
				UserModel user = users.get(0);
				FederatedIdentityModel existing = session.users().getFederatedIdentity(realm, user, idpAlias);
				// both provider-side ids, so a re-created provider account (new id, same address) is visible at once
				log.warnf("User '%s' (%s, %s=%s) is already linked to '%s' account %s (%s); refusing sign-in of '%s' "
						+ "account %s (%s). Same address with a new id usually means the provider account was "
						+ "re-created: unlink the old one if so.", user.getUsername(), user.getId(), attribute, email,
						idpAlias, existing.getUserId(), existing.getUserName(), idpAlias, brokerContext.getId(),
						brokerContext.getUsername());
				context.getEvent()
						.detail(DETAIL_LINKED_IDP_USER_ID, existing.getUserId())
						.detail(DETAIL_IDP_USER_ID, brokerContext.getId());
				fail(context, result, email, user, Response.Status.CONFLICT);
			}
			case EMAIL_NOT_VERIFIED -> {
				log.warnf("'%s' reports %s as not verified, not linking it by %s", idpAlias, email, attribute);
				fail(context, result, email, null, Response.Status.FORBIDDEN);
			}
		}
	}

	/** {@code email_verified} of the identity provider's ID (or access) token; {@code null} if there is none. */
	private static Boolean emailVerified(BrokeredIdentityContext brokerContext) {
		Map<String, Object> data = brokerContext.getContextData();
		Object token = Optional.ofNullable(data.get(OIDCIdentityProvider.VALIDATED_ID_TOKEN))
				.orElse(data.get(OIDCIdentityProvider.VALIDATED_ACCESS_TOKEN));
		if (!(token instanceof JsonWebToken jwt)) {
			return null;
		}
		Object claim = jwt.getOtherClaims().get(IDToken.EMAIL_VERIFIED);
		return claim == null ? null : Boolean.valueOf(claim.toString());
	}

	private static void fail(AuthenticationFlowContext context, LinkDecision.Result result, String email, UserModel user,
			Response.Status status) {
		if (user != null) {
			context.getEvent().user(user);
		}
		context.getEvent().detail("email", email).error(result.error);
		Response page = context.form()
				.setError(result.message, email)
				.createErrorPage(status);
		context.failureChallenge(AuthenticationFlowError.INVALID_USER, page);
	}

	@Override
	protected void actionImpl(AuthenticationFlowContext context, SerializedBrokeredIdentityContext serializedCtx,
			BrokeredIdentityContext brokerContext) {
		// no form, nothing to submit
	}

	@Override
	public boolean requiresUser() {
		return false;
	}

	@Override
	public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
		return true;
	}
}
