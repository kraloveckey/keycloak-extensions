package io.github.kraloveckey.keycloak.accesspolicy;

/**
 * What a policy asks about the user. Kept separate from Keycloak's models so the policy logic can be tested alone.
 */
public interface AccessSubject {

	/** Effective realm role: assigned directly, through a composite role or through a group. */
	boolean hasRealmRole(String role);

	/** Effective role of the client with the given client ID, same rules as {@link #hasRealmRole(String)}. */
	boolean hasClientRole(String clientId, String role);

	/** Member of the group with this path ({@code /Parent/Child}) or of any of its subgroups. */
	boolean isMemberOf(String groupPath);
}
