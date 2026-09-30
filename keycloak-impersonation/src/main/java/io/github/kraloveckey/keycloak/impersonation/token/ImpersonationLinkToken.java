package io.github.kraloveckey.keycloak.impersonation.token;

import org.keycloak.authentication.actiontoken.DefaultActionToken;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Signed payload of an impersonation link.
 * <p>
 * Extends {@link DefaultActionToken} only for its JWT serialization and its single-use key
 * ({@code userId.exp.nonce.typ}). It is never handed to Keycloak's action-token endpoint: the link is redeemed by
 * {@code ImpersonationRedeemResource}.
 */
public class ImpersonationLinkToken extends DefaultActionToken {

	public static final String TOKEN_TYPE = "impersonation-link";

	@JsonProperty("imp_realm")
	private String impersonatorRealm;

	@JsonProperty("imp_id")
	private String impersonatorId;

	@JsonProperty("imp_name")
	private String impersonatorUsername;

	/** Admin's own session, set only for same-realm impersonation; it is logged out on redemption. */
	@JsonProperty("imp_sid")
	private String impersonatorSessionId;

	@JsonProperty("redirect")
	private String redirectUri;

	/** For Jackson. */
	protected ImpersonationLinkToken() {
	}

	public ImpersonationLinkToken(String userId, String impersonatorRealm, String impersonatorId,
			String impersonatorUsername, String impersonatorSessionId, String redirectUri, int expiresAt) {
		super(userId, TOKEN_TYPE, expiresAt, null);
		this.impersonatorRealm = impersonatorRealm;
		this.impersonatorId = impersonatorId;
		this.impersonatorUsername = impersonatorUsername;
		this.impersonatorSessionId = impersonatorSessionId;
		this.redirectUri = redirectUri;
	}

	public String getImpersonatorRealm() {
		return impersonatorRealm;
	}

	public String getImpersonatorId() {
		return impersonatorId;
	}

	public String getImpersonatorUsername() {
		return impersonatorUsername;
	}

	public String getImpersonatorSessionId() {
		return impersonatorSessionId;
	}

	public String getRedirectUri() {
		return redirectUri;
	}
}
