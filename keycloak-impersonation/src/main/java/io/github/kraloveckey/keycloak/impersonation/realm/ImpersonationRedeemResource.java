package io.github.kraloveckey.keycloak.impersonation.realm;

import static org.keycloak.models.ImpersonationSessionNote.IMPERSONATOR_ID;
import static org.keycloak.models.ImpersonationSessionNote.IMPERSONATOR_USERNAME;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.logging.Logger;
import org.keycloak.common.ClientConnection;
import org.keycloak.common.Profile;
import org.keycloak.common.util.Time;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.services.ErrorPage;
import org.keycloak.services.Urls;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.UserSessionManager;
import org.keycloak.services.messages.Messages;
import org.keycloak.services.resources.RealmsResource;

import io.github.kraloveckey.keycloak.impersonation.token.ImpersonationLinkToken;

/**
 * Redeems an impersonation link: {@code GET /realms/{realm}/impersonation/redeem?key=<signed token>}.
 * <p>
 * The link is deliberately not redeemed by Keycloak's generic action-token endpoint ({@code /login-actions/action-token}).
 * That endpoint runs {@code LoginActionsServiceChecks#checkIsUserValid} for every action token before the handler
 * gets control, and the check rejects the request with "You are already authenticated as different user ..." whenever
 * the browser holds an SSO session of another user in the same realm, which is always the case when an administrator
 * impersonates somebody from the browser they are logged in with. An SPI cannot opt out of that check. This endpoint
 * is served on the realm's own (frontend) hostname, where the realm cookies live.
 * <p>
 * What happens on redemption mirrors the built-in {@code UserResource#impersonate}: whatever SSO session the browser
 * holds in the realm (and the administrator's own session when impersonating within the same realm) is logged out,
 * a new user session with impersonator notes is created, the identity cookie is overwritten in this very response
 * and the browser is sent to the account console.
 */
public class ImpersonationRedeemResource {
	public static final String REDEEM_PATH = "redeem";

	private static final Logger log = Logger.getLogger(ImpersonationRedeemResource.class);

	private final KeycloakSession session;

	public ImpersonationRedeemResource(KeycloakSession session) {
		this.session = session;
	}

	/**
	 * Builds the link the admin endpoint hands out. {@code uriInfo} should be the frontend URI info so the link points
	 * at the hostname the realm's cookies are scoped to.
	 */
	public static URI redeemLink(UriInfo uriInfo, RealmModel realm, String serializedToken) {
		return RealmsResource.realmBaseUrl(uriInfo)
				.path(ImpersonationRealmResourceProviderFactory.PROVIDER_ID)
				.path(REDEEM_PATH)
				.queryParam(Constants.KEY, serializedToken)
				.build(realm.getName());
	}

	@GET
	@Path(REDEEM_PATH)
	public Response redeem(@QueryParam(Constants.KEY) String key) {
		KeycloakContext context = session.getContext();
		RealmModel realm = context.getRealm();
		ClientConnection connection = context.getConnection();
		EventBuilder event = new EventBuilder(realm, session, connection).event(EventType.IMPERSONATE);

		if (!Profile.isFeatureEnabled(Profile.Feature.IMPERSONATION)) {
			return error(event, Errors.NOT_ALLOWED, Status.NOT_FOUND, "Impersonation is disabled on this server.");
		}
		if (!realm.isEnabled()) {
			return error(event, Errors.REALM_DISABLED, Status.BAD_REQUEST, Messages.REALM_NOT_ENABLED);
		}
		if (!"https".equals(context.getUri().getBaseUri().getScheme()) && realm.getSslRequired().isRequired(connection)) {
			return error(event, Errors.SSL_REQUIRED, Status.BAD_REQUEST, Messages.HTTPS_REQUIRED);
		}

		// decode() verifies the signature against this realm's keys, so a token issued for another realm is rejected
		ImpersonationLinkToken token = key == null || key.isBlank() ? null
				: session.tokens().decode(key, ImpersonationLinkToken.class);
		if (token == null
				|| !ImpersonationLinkToken.TOKEN_TYPE.equals(token.getActionId())
				|| token.getActionVerificationNonce() == null
				|| token.getUserId() == null) {
			return error(event, Errors.INVALID_TOKEN, Status.BAD_REQUEST, "Invalid impersonation link.");
		}

		event.detail(Details.IMPERSONATOR_REALM, token.getImpersonatorRealm())
				.detail(Details.IMPERSONATOR, token.getImpersonatorUsername());

		if (token.getExp() == null || !token.isActive()) {
			return error(event, Errors.EXPIRED_CODE, Status.BAD_REQUEST,
					"This impersonation link has expired. Start the impersonation again from the Admin Console.");
		}

		// Single use: burn the token before doing anything else. Same store Keycloak uses for its own action tokens.
		long lifespan = Math.max(1L, token.getExp() - Time.currentTimeSeconds());
		if (!session.revokedTokens().put(token.serializeKey(), lifespan)) {
			return error(event, Errors.EXPIRED_CODE, Status.BAD_REQUEST,
					"This impersonation link has already been used. Start the impersonation again from the Admin Console.");
		}

		UserModel user = session.users().getUserById(realm, token.getUserId());
		if (user == null) {
			return error(event, Errors.USER_NOT_FOUND, Status.BAD_REQUEST, "The user to impersonate does not exist.");
		}
		event.user(user);
		if (!user.isEnabled()) {
			return error(event, Errors.USER_DISABLED, Status.BAD_REQUEST, "The user to impersonate is disabled.");
		}
		if (user.getServiceAccountClientLink() != null) {
			return error(event, Errors.NOT_ALLOWED, Status.BAD_REQUEST, "Service accounts cannot be impersonated.");
		}

		// The link lives for a few minutes; make sure the admin who asked for it still exists and is enabled.
		RealmModel impersonatorRealm = token.getImpersonatorRealm() == null ? null
				: session.realms().getRealmByName(token.getImpersonatorRealm());
		UserModel impersonator = impersonatorRealm == null || token.getImpersonatorId() == null ? null
				: session.users().getUserById(impersonatorRealm, token.getImpersonatorId());
		if (impersonator == null || !impersonator.isEnabled()) {
			return error(event, Errors.NOT_ALLOWED, Status.BAD_REQUEST,
					"The administrator who requested this impersonation no longer exists or is disabled.");
		}

		logoutSessionsBeingReplaced(realm, token, connection, context.getHttpRequest().getHttpHeaders());

		UserSessionModel userSession = new UserSessionManager(session).createUserSession(realm, user,
				user.getUsername(), connection.getRemoteHost(), "impersonate", false, null, null);
		userSession.setNote(IMPERSONATOR_ID.toString(), impersonator.getId());
		userSession.setNote(IMPERSONATOR_USERNAME.toString(), impersonator.getUsername());

		// Written to the current HTTP response; this request is served from /realms/{realm}/..., so the browser stores
		// the cookie under exactly the path and host every later request of the realm uses.
		AuthenticationManager.createLoginCookie(session, realm, user, userSession, context.getUri(), connection);

		event.session(userSession).success();
		log.debugf("User '%s' (realm '%s') now impersonates '%s' in realm '%s'", impersonator.getUsername(),
				impersonatorRealm.getName(), user.getUsername(), realm.getName());

		URI redirect = token.getRedirectUri() != null ? URI.create(token.getRedirectUri())
				: Urls.accountBase(context.getUri().getBaseUri()).build(realm.getName());

		return Response.seeOther(redirect)
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.build();
	}

	/**
	 * A browser can hold one SSO session per realm. The session about to be replaced is logged out properly (with
	 * backchannel logout to its clients) instead of being orphaned, same as the built-in impersonation endpoint does.
	 */
	private void logoutSessionsBeingReplaced(RealmModel realm, ImpersonationLinkToken token, ClientConnection connection,
			HttpHeaders headers) {
		Set<String> loggedOut = new HashSet<>();

		// whatever this browser is logged in with in the target realm (the admin, or a previous impersonation)
		AuthenticationManager.AuthResult current = AuthenticationManager.authenticateIdentityCookie(session, realm, true);
		if (current != null) {
			logout(realm, current.session(), connection, headers, loggedOut);
		}

		// same-realm impersonation: the administrator's own session, even if its cookie lives on another hostname
		if (token.getImpersonatorSessionId() != null) {
			UserSessionModel adminSession = session.sessions().getUserSession(realm, token.getImpersonatorSessionId());
			logout(realm, adminSession, connection, headers, loggedOut);
		}

		if (!loggedOut.isEmpty()) {
			// login hint (remembered username) and a half-finished login of the previous user must not leak over
			AuthenticationManager.expireRememberMeCookie(session);
			AuthenticationManager.expireAuthSessionCookie(session);
		}
	}

	private void logout(RealmModel realm, UserSessionModel userSession, ClientConnection connection, HttpHeaders headers,
			Set<String> loggedOut) {
		if (userSession == null || !loggedOut.add(userSession.getId())) {
			return;
		}
		AuthenticationManager.backchannelLogout(session, realm, userSession, session.getContext().getUri(), connection,
				headers, true);
	}

	private Response error(EventBuilder event, String error, Status status, String message) {
		event.error(error);
		return ErrorPage.error(session, null, status, message);
	}
}
