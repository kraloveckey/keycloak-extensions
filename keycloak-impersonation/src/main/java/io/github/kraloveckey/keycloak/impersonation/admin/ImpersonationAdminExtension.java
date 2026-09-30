package io.github.kraloveckey.keycloak.impersonation.admin;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProvider;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProviderFactory;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;

/**
 * Mounts {@link ImpersonationAdminResource} under {@code /admin/realms/{realm}/impersonation}.
 * <p>
 * The provider receives session, realm and permissions per call and keeps no state, so one instance serves as
 * factory and provider.
 */
public class ImpersonationAdminExtension implements AdminRealmResourceProviderFactory, AdminRealmResourceProvider {

	public static final String ID = "impersonation";

	@Override
	public Object getResource(KeycloakSession session, RealmModel realm, AdminPermissionEvaluator auth,
			AdminEventBuilder adminEvent) {
		return new ImpersonationAdminResource(session, realm, auth);
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
