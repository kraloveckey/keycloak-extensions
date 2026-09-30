package io.github.kraloveckey.keycloak.accesspolicy;

import org.keycloak.models.ClientModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.RoleUtils;

/**
 * {@link AccessSubject} backed by a Keycloak user. Unknown roles, clients and groups simply do not match.
 */
final class UserAccessSubject implements AccessSubject {

	private final KeycloakSession session;
	private final RealmModel realm;
	private final UserModel user;

	UserAccessSubject(KeycloakSession session, RealmModel realm, UserModel user) {
		this.session = session;
		this.realm = realm;
		this.user = user;
	}

	@Override
	public boolean hasRealmRole(String role) {
		RoleModel model = realm.getRole(role);
		// UserModel#hasRole covers composite roles and roles inherited from groups
		return model != null && user.hasRole(model);
	}

	@Override
	public boolean hasClientRole(String clientId, String role) {
		ClientModel client = realm.getClientByClientId(clientId);
		if (client == null) {
			return false;
		}
		RoleModel model = client.getRole(role);
		return model != null && user.hasRole(model);
	}

	@Override
	public boolean isMemberOf(String groupPath) {
		GroupModel group = KeycloakModelUtils.findGroupByPath(session, realm, groupPath);
		// RoleUtils#isMember also counts membership in any subgroup of the group
		return group != null && RoleUtils.isMember(user.getGroupsStream(), group);
	}
}
