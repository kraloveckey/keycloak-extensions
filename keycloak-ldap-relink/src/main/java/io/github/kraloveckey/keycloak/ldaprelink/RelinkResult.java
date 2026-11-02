package io.github.kraloveckey.keycloak.ldaprelink;

import java.util.List;

/**
 * Outcome of a preview or an attach, returned as JSON by the admin endpoint and shown by the UI page.
 *
 * @param applied   {@code true} only when the change was made and committed; a preview is never applied
 * @param userId    the Keycloak user ID, the same before and after
 * @param provider  the LDAP provider the user is (to be) linked to, {@code null} if none could be chosen
 * @param ldapEntry the LDAP entry found by username, {@code null} if none was found
 * @param before    the user as stored now
 * @param after     the user as it is (or would be) after linking, {@code null} when there are problems
 * @param problems  reasons the user cannot be linked; empty when linking is possible
 */
public record RelinkResult(
		boolean applied,
		String userId,
		Provider provider,
		LdapEntry ldapEntry,
		UserState before,
		UserState after,
		List<String> problems) {

	public boolean linkable() {
		return problems.isEmpty();
	}

	/** An LDAP user storage provider of the realm. */
	public record Provider(String id, String name) {
	}

	/** The LDAP entry matched by username. */
	public record LdapEntry(String dn, String uuid, String username) {
	}

	/** The fields of a user that linking reads or changes. */
	public record UserState(
			String username,
			String email,
			boolean emailVerified,
			String firstName,
			String lastName,
			boolean enabled,
			String federationLink,
			String ldapId,
			String ldapEntryDn,
			List<String> requiredActions,
			List<String> groups) {
	}
}
