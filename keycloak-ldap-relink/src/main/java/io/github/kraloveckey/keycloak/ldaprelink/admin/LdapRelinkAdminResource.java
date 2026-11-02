package io.github.kraloveckey.keycloak.ldaprelink.admin;

import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;

import io.github.kraloveckey.keycloak.ldaprelink.LdapRelinkService;
import io.github.kraloveckey.keycloak.ldaprelink.RelinkResult;

/**
 * <ul>
 * <li>{@code GET /admin/realms/{realm}/ldap-relink/users/{user-id}[?provider=...]}: preview, changes nothing.</li>
 * <li>{@code POST /admin/realms/{realm}/ldap-relink/users/{user-id}/attach[?provider=...]}: links the user; 200 with
 * the result, or 409 with the problems and nothing changed.</li>
 * </ul>
 * Both need the right to manage the user ({@code manage-users}, or the fine-grained equivalent).
 */
public class LdapRelinkAdminResource {

	private final KeycloakSession session;
	private final RealmModel realm;
	private final AdminPermissionEvaluator auth;
	private final AdminEventBuilder adminEvent;

	public LdapRelinkAdminResource(KeycloakSession session, RealmModel realm, AdminPermissionEvaluator auth,
			AdminEventBuilder adminEvent) {
		this.session = session;
		this.realm = realm;
		this.auth = auth;
		this.adminEvent = adminEvent.resource(ResourceType.USER);
	}

	@GET
	@Path("users/{user-id}")
	@Produces(MediaType.APPLICATION_JSON)
	public RelinkResult preview(@PathParam("user-id") String userId, @QueryParam("provider") String provider) {
		requireManage(userId);
		return new LdapRelinkService(session, realm).attach(userId, provider, false);
	}

	@POST
	@Path("users/{user-id}/attach")
	@Produces(MediaType.APPLICATION_JSON)
	public Response attach(@PathParam("user-id") String userId, @QueryParam("provider") String provider) {
		requireManage(userId);
		RelinkResult result = new LdapRelinkService(session, realm).attach(userId, provider, true);
		if (!result.linkable()) {
			return Response.status(Response.Status.CONFLICT).entity(result).type(MediaType.APPLICATION_JSON).build();
		}
		adminEvent.operation(OperationType.UPDATE)
				.resourcePath("users", userId)
				.detail("ldap-relink", "attach")
				.representation(result)
				.success();
		return Response.ok(result, MediaType.APPLICATION_JSON).build();
	}

	private void requireManage(String userId) {
		UserModel user = session.users().getUserById(realm, userId);
		if (user == null) {
			// same as UsersResource#user: don't let callers without query rights probe for user ids
			if (auth.users().canQuery()) {
				throw new NotFoundException("User not found");
			}
			throw new ForbiddenException();
		}
		auth.users().requireManage(user);
	}
}
