package io.github.kraloveckey.keycloak.idplink;

import java.util.List;

/**
 * Which existing user, if any, a first identity-provider sign-in is linked to. No Keycloak types, so it can be tested
 * alone.
 */
final class LinkDecision {

	/** A user whose attribute matched the e-mail from the identity provider. */
	record Candidate(String id, boolean enabled, boolean linkedToThisIdp) {
	}

	enum Result {
		/** Exactly one enabled user without a link to this identity provider: link it. */
		LINK(null, null),
		NOT_FOUND("user_not_found", "idpAttrUserNotFound"),
		CONFLICT("duplicate_attribute_value", "idpAttrConflict"),
		ALREADY_LINKED("federated_identity_account_exists", "idpAttrAlreadyLinked"),
		DISABLED("user_disabled", "idpAttrUserDisabled"),
		EMAIL_NOT_VERIFIED("email_not_verified", "idpAttrEmailNotVerified");

		/** Event error code. */
		final String error;
		/** Message key of the error page. */
		final String message;

		Result(String error, String message) {
			this.error = error;
			this.message = message;
		}
	}

	private LinkDecision() {
	}

	/**
	 * @param candidates     users whose attribute equals the e-mail (search result, possibly truncated)
	 * @param emailVerified  the identity provider's {@code email_verified} claim, {@code null} if it sent none
	 * @param requireVerified reject when the identity provider says the e-mail is not verified
	 */
	static Result decide(List<Candidate> candidates, Boolean emailVerified, boolean requireVerified) {
		if (requireVerified && Boolean.FALSE.equals(emailVerified)) {
			return Result.EMAIL_NOT_VERIFIED;
		}
		if (candidates.isEmpty()) {
			return Result.NOT_FOUND;
		}
		if (candidates.size() > 1) {
			return Result.CONFLICT;
		}
		Candidate user = candidates.get(0);
		if (!user.enabled()) {
			return Result.DISABLED;
		}
		if (user.linkedToThisIdp()) {
			return Result.ALREADY_LINKED;
		}
		return Result.LINK;
	}
}
