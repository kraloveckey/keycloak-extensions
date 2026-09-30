package io.github.kraloveckey.keycloak.impersonation.admin;

import java.net.URI;

import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;

import org.keycloak.common.Profile;
import org.keycloak.common.util.Time;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.KeycloakUriInfo;
import org.keycloak.services.ErrorResponse;
import org.keycloak.services.Urls;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;
import org.keycloak.utils.ProfileHelper;

import io.github.kraloveckey.keycloak.impersonation.token.ImpersonationLinkToken;
import io.github.kraloveckey.keycloak.impersonation.realm.ImpersonationRedeemResource;

/**
 * {@code POST /admin/realms/{realm}/impersonation/users/{user-id}}.
 * <p>
 * Same contract as the built-in {@code POST /admin/realms/{realm}/users/{user-id}/impersonation} (the admin console
 * reads {@code sameRealm} and {@code redirect}), but nothing is impersonated and no cookie is touched here. The
 * response carries a short-lived, single-use link to {@link ImpersonationRedeemResource} on the realm's frontend
 * hostname; the impersonation happens when the browser opens it. See the README for why.
 */
public class ImpersonationAdminResource {
	static final int LINK_LIFESPAN_SECONDS = 180;

	private final KeycloakSession session;
	private final RealmModel realm;
	private final AdminPermissionEvaluator auth;

	public ImpersonationAdminResource(KeycloakSession session, RealmModel realm, AdminPermissionEvaluator auth) {
		this.session = session;
		this.realm = realm;
		this.auth = auth;
	}

	@POST
	@Path("users/{user-id}")
	@Produces(MediaType.APPLICATION_JSON)
	public ImpersonationResponseDto impersonateUser(@PathParam("user-id") String userId) {
		ProfileHelper.requireFeature(Profile.Feature.IMPERSONATION);

		UserModel user = session.users().getUserById(realm, userId);
		if (user == null) {
			// same as UsersResource#user: don't let callers without query rights probe for user ids
			if (auth.users().canQuery()) {
				throw new NotFoundException("User not found");
			}
			throw new ForbiddenException();
		}

		auth.users().requireImpersonate(user);

		if (!user.isEnabled()) {
			throw ErrorResponse.error("User is disabled", Status.BAD_REQUEST);
		}
		if (user.getServiceAccountClientLink() != null) {
			throw ErrorResponse.error("Service accounts cannot be impersonated", Status.BAD_REQUEST);
		}

		UserModel adminUser = auth.adminAuth().getUser();
		RealmModel adminRealm = auth.adminAuth().getRealm();
		String adminSessionId = auth.adminAuth().getToken().getSessionId();

		// Same rule as the built-in endpoint. When true the admin console navigates the current tab to the link
		// (the admin's session in this realm is replaced anyway); otherwise it opens the link in a new tab.
		boolean sameRealm = adminRealm.getId().equals(realm.getId()) && adminSessionId != null;

		// frontend URI: the link must point to the hostname the realm cookies are scoped to, not the admin hostname
		KeycloakUriInfo frontendUri = session.getContext().getUri();
		URI accountConsole = Urls.accountBase(frontendUri.getBaseUri()).build(realm.getName());

		ImpersonationLinkToken token = new ImpersonationLinkToken(
				user.getId(),
				adminRealm.getName(),
				adminUser.getId(),
				adminUser.getUsername(),
				// the admin's own session is logged out on redemption only when it lives in the same realm
				sameRealm ? adminSessionId : null,
				accountConsole.toString(),
				(int) (Time.currentTimeSeconds() + LINK_LIFESPAN_SECONDS));

		String serialized = token.serialize(session, realm, frontendUri);
		URI link = ImpersonationRedeemResource.redeemLink(frontendUri, realm, serialized);

		return new ImpersonationResponseDto(sameRealm, link.toString());
	}
}
