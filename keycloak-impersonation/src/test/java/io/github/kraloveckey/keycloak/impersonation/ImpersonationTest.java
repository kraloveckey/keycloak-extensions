package io.github.kraloveckey.keycloak.impersonation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dasniko.testcontainers.keycloak.KeycloakContainer;

/**
 * Drives the extension the way a browser and the stock admin console do, against a real Keycloak running the
 * provider classes from target/classes. Every {@link Browser} is one cookie jar, i.e. one browser profile.
 */
@Testcontainers
public class ImpersonationTest {

	private static final String REALM = "demo";
	private static final String PASSWORD = "pw";
	private static final ObjectMapper JSON = new ObjectMapper();

	@Container
	private static final KeycloakContainer keycloak = new KeycloakContainer(
			"quay.io/keycloak/keycloak:" + System.getProperty("keycloak.version", "26.7.0"))
			.withDefaultProviderClasses();

	private static String base;
	private static final Map<String, String> userIds = new LinkedHashMap<>();

	@BeforeAll
	static void provision() throws Exception {
		base = keycloak.getAuthServerUrl().replaceAll("/$", "");
		Browser api = new Browser();

		HttpResponse<String> tokenResponse = api.postForm(base + "/realms/master/protocol/openid-connect/token", Map.of(
				"grant_type", "password", "client_id", "admin-cli",
				"username", keycloak.getAdminUsername(), "password", keycloak.getAdminPassword()));
		expect(200, tokenResponse);
		String bearer = "Bearer " + JSON.readTree(tokenResponse.body()).get("access_token").asText();

		expect(201, api.postJson(base + "/admin/realms", bearer, Map.of("realm", REALM, "enabled", true)));
		for (String name : List.of("realm-admin", "target", "plain")) {
			expect(201, api.postJson(base + "/admin/realms/" + REALM + "/users", bearer, Map.of(
					"username", name, "enabled", true, "email", name + "@example.com", "emailVerified", true,
					"firstName", name, "lastName", "Test",
					"credentials", List.of(Map.of("type", "password", "value", PASSWORD, "temporary", false)))));
			JsonNode found = JSON.readTree(api.get(
					base + "/admin/realms/" + REALM + "/users?exact=true&username=" + name, bearer).body());
			userIds.put(name, found.get(0).get("id").asText());
		}

		String realmManagement = JSON.readTree(api.get(
				base + "/admin/realms/" + REALM + "/clients?clientId=realm-management", bearer).body())
				.get(0).get("id").asText();
		List<JsonNode> roles = new ArrayList<>();
		for (String role : List.of("impersonation", "view-users", "query-users")) {
			roles.add(JSON.readTree(api.get(
					base + "/admin/realms/" + REALM + "/clients/" + realmManagement + "/roles/" + role, bearer).body()));
		}
		expect(204, api.postJson(base + "/admin/realms/" + REALM + "/users/" + userIds.get("realm-admin")
				+ "/role-mappings/clients/" + realmManagement, bearer, roles));
	}

	@Test
	public void impersonatesFromTheBrowserTheAdminIsLoggedInWith() throws Exception {
		Browser browser = new Browser();
		String adminToken = browser.loginToAdminConsole("realm-admin");
		assertNotNull(browser.cookie("KEYCLOAK_IDENTITY"), "admin must hold an SSO session in the realm");

		JsonNode response = requestLink(browser, adminToken, userIds.get("target"));
		assertTrue(response.get("sameRealm").asBoolean(), "same-realm impersonation must report sameRealm=true");

		HttpResponse<String> redeemed = browser.get(response.get("redirect").asText(), null);
		assertEquals(303, redeemed.statusCode(), "redeem page said: " + redeemed.body());
		assertEquals(base + "/realms/" + REALM + "/account", redeemed.headers().firstValue("Location").orElse(null));

		// the account console signs in through SSO with the new identity cookie, no login form
		String token = browser.sso("account-console", base + "/realms/" + REALM + "/account/");
		assertEquals("target", claims(token).get("preferred_username").asText());
	}

	@Test
	public void linkCanBeUsedOnce() throws Exception {
		Browser browser = new Browser();
		String adminToken = browser.loginToAdminConsole("realm-admin");
		String link = requestLink(browser, adminToken, userIds.get("target")).get("redirect").asText();

		assertEquals(303, browser.get(link, null).statusCode());
		assertEquals(400, browser.get(link, null).statusCode());
		assertEquals(400, new Browser().get(link, null).statusCode());
	}

	@Test
	public void rejectsTamperedLink() throws Exception {
		Browser browser = new Browser();
		String adminToken = browser.loginToAdminConsole("realm-admin");
		String link = requestLink(browser, adminToken, userIds.get("target")).get("redirect").asText();

		String tampered = link.substring(0, link.length() - 4) + (link.endsWith("AAAA") ? "BBBB" : "AAAA");
		assertEquals(400, new Browser().get(tampered, null).statusCode());
	}

	@Test
	public void requiresImpersonationPermission() throws Exception {
		Browser browser = new Browser();
		String token = browser.loginToAdminConsole("plain");

		HttpResponse<String> response = browser.postJson(
				base + "/admin/realms/" + REALM + "/impersonation/users/" + userIds.get("target"), "Bearer " + token,
				Map.of());
		assertEquals(403, response.statusCode());
	}

	private static JsonNode requestLink(Browser browser, String adminToken, String userId) throws Exception {
		HttpResponse<String> response = browser.postJson(
				base + "/admin/realms/" + REALM + "/impersonation/users/" + userId, "Bearer " + adminToken,
				Map.of("user", userId, "realm", REALM));
		expect(200, response);
		return JSON.readTree(response.body());
	}

	private static JsonNode claims(String jwt) throws IOException {
		return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
	}

	private static void expect(int status, HttpResponse<String> response) {
		assertEquals(status, response.statusCode(), response.uri() + " -> " + response.body());
	}

	/**
	 * Minimal browser: never follows redirects, keeps one cookie jar. Cookies are tracked by name only, which is
	 * enough because the tests use a single realm on a single host.
	 */
	static final class Browser {
		private static final Pattern FORM_ACTION = Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"");
		private static final Pattern CODE = Pattern.compile("[?&]code=([^&]+)");

		private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
		private final Map<String, String> cookies = new LinkedHashMap<>();

		String cookie(String name) {
			return cookies.get(name);
		}

		HttpResponse<String> get(String url, String authorization) throws IOException, InterruptedException {
			return send(request(url, authorization).GET());
		}

		HttpResponse<String> postForm(String url, Map<String, String> form) throws IOException, InterruptedException {
			String body = form.entrySet().stream()
					.map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
					.collect(Collectors.joining("&"));
			return send(request(url, null).header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString(body)));
		}

		HttpResponse<String> postJson(String url, String authorization, Object body)
				throws IOException, InterruptedException {
			return send(request(url, authorization).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
		}

		/** Logs into the realm's admin console through the login form (authorization code + PKCE). */
		String loginToAdminConsole(String username) throws Exception {
			String redirectUri = base + "/admin/" + REALM + "/console/";
			Pkce pkce = new Pkce();
			HttpResponse<String> form = get(authUrl("security-admin-console", redirectUri, pkce), null);
			Matcher action = FORM_ACTION.matcher(form.body());
			assertTrue(action.find(), "login form expected, got: " + form.body());
			HttpResponse<String> login = postForm(action.group(1).replace("&amp;", "&"),
					Map.of("username", username, "password", PASSWORD));
			assertEquals(302, login.statusCode(), "login failed: " + login.body());
			return exchange("security-admin-console", redirectUri, pkce, login);
		}

		/** Authorization request that has to be answered from the existing SSO session, without a login form. */
		String sso(String clientId, String redirectUri) throws Exception {
			Pkce pkce = new Pkce();
			HttpResponse<String> response = get(authUrl(clientId, redirectUri, pkce), null);
			assertEquals(302, response.statusCode(), "expected SSO redirect, got: " + response.body());
			return exchange(clientId, redirectUri, pkce, response);
		}

		private String exchange(String clientId, String redirectUri, Pkce pkce, HttpResponse<String> redirect)
				throws Exception {
			String location = redirect.headers().firstValue("Location").orElse("");
			Matcher code = CODE.matcher(location);
			assertTrue(code.find(), "no code in " + location);
			HttpResponse<String> token = postForm(base + "/realms/" + REALM + "/protocol/openid-connect/token", Map.of(
					"grant_type", "authorization_code", "client_id", clientId, "code", code.group(1),
					"redirect_uri", redirectUri, "code_verifier", pkce.verifier));
			expect(200, token);
			return JSON.readTree(token.body()).get("access_token").asText();
		}

		private static String authUrl(String clientId, String redirectUri, Pkce pkce) {
			return base + "/realms/" + REALM + "/protocol/openid-connect/auth?response_type=code&scope=openid"
					+ "&client_id=" + enc(clientId) + "&redirect_uri=" + enc(redirectUri)
					+ "&code_challenge_method=S256&code_challenge=" + pkce.challenge + "&state=s&nonce=n";
		}

		private HttpRequest.Builder request(String url, String authorization) {
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
			if (authorization != null) {
				builder.header("Authorization", authorization);
			}
			if (!cookies.isEmpty()) {
				builder.header("Cookie", cookies.entrySet().stream()
						.map(e -> e.getKey() + "=" + e.getValue())
						.collect(Collectors.joining("; ")));
			}
			return builder;
		}

		private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
			HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			// applied in order, like a browser does: a later Set-Cookie for the same name wins
			for (String header : response.headers().allValues("Set-Cookie")) {
				String[] parts = header.split(";");
				String[] nameValue = parts[0].split("=", 2);
				String name = nameValue[0].trim();
				boolean expired = nameValue.length < 2 || nameValue[1].isBlank();
				for (String attribute : parts) {
					String a = attribute.trim().toLowerCase();
					if (a.equals("max-age=0") || a.startsWith("expires=thu, 01 jan 1970")
							|| a.startsWith("expires=thu, 01-jan-1970")) {
						expired = true;
					}
				}
				if (expired) {
					cookies.remove(name);
				} else {
					cookies.put(name, nameValue[1].trim());
				}
			}
			return response;
		}

		private static String enc(String value) {
			return URLEncoder.encode(value, StandardCharsets.UTF_8);
		}
	}

	private static final class Pkce {
		final String verifier;
		final String challenge;

		Pkce() throws NoSuchAlgorithmException {
			byte[] random = new byte[48];
			new SecureRandom().nextBytes(random);
			verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
			challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
					MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		}
	}
}
