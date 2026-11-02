package io.github.kraloveckey.keycloak.ldaprelink.admin;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProvider;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProviderFactory;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;

/**
 * Mounts {@link LdapRelinkAdminResource} under {@code /admin/realms/{realm}/ldap-relink}.
 * <p>
 * Stateless: one instance serves as factory and provider.
 */
public class LdapRelinkAdminExtension implements AdminRealmResourceProviderFactory, AdminRealmResourceProvider {

	public static final String ID = "ldap-relink";

	@Override
	public Object getResource(KeycloakSession session, RealmModel realm, AdminPermissionEvaluator auth,
			AdminEventBuilder adminEvent) {
		return new LdapRelinkAdminResource(session, realm, auth, adminEvent);
	}

	@Override
	public AdminRealmResourceProvider create(KeycloakSession session) {
		return this;
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public void init(Config.Scope config) {
		// no configuration
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
