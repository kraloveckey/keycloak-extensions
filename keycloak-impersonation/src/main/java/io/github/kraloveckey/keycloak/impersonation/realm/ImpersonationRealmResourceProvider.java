package io.github.kraloveckey.keycloak.impersonation.realm;

import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

public class ImpersonationRealmResourceProvider implements RealmResourceProvider {
	private final KeycloakSession session;

	public ImpersonationRealmResourceProvider(KeycloakSession session) {
		this.session = session;
	}

	@Override
	public Object getResource() {
		return new ImpersonationRedeemResource(session);
	}

	@Override
	public void close() {
	}
}
