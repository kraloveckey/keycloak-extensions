package io.github.kraloveckey.keycloak.impersonation.admin;

/**
 * Same shape as the response of the built-in impersonation endpoint, so the stock admin console can consume it.
 *
 * @param sameRealm {@code true} when the admin impersonates a user of the realm they are logged into; the admin
 *                  console then opens {@code redirect} in the current tab, otherwise in a new one
 * @param redirect  single-use link that performs the impersonation when opened in a browser
 */
public record ImpersonationResponseDto(
		boolean sameRealm,
		String redirect) {

}
