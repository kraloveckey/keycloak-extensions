package io.github.kraloveckey.keycloak.ldaprelink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;

import dasniko.testcontainers.keycloak.KeycloakContainer;

/**
 * Runs the extension in a real Keycloak (provider classes from target/classes, {@code declarative-ui} enabled)
 * against an in-memory LDAP server in the test JVM, reached from the container through
 * {@code host.testcontainers.internal}. Entries get an {@code entryUUID}, so the provider uses vendor "other".
 * <p>
 * Every test creates its own users. A placeholder (local user with a fixed ID) is created while its LDAP entry does
 * not exist yet, as after the entry was deleted; then the entry is added, as when the person comes back.
 * <p>
 * {@code -Dldaprelink.it.keycloak=http://localhost:8080} runs against an already running Keycloak (admin/admin in
 * master, the jar installed) instead of a container; LDAP is then reached on localhost.
 */
public class LdapRelinkTest {

	private static final String REALM = "relink";
	private static final String BASE_DN = "dc=example,dc=org";
	private static final String PEOPLE = "ou=people," + BASE_DN;
	private static final String PASSWORD = "secret";
	private static final ObjectMapper JSON = new ObjectMapper();

	private static InMemoryDirectoryServer ldap;
	private static KeycloakContainer keycloak;
	private static String base;
	private static final Api api = new Api();
	private static String realmId;
	private static String providerId;

	@BeforeAll
	static void start() throws Exception {
		InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
		config.addAdditionalBindCredentials("cn=admin," + BASE_DN, "admin");
		config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0));
		ldap = new InMemoryDirectoryServer(config);
		ldap.add("dn: " + BASE_DN, "objectClass: top", "objectClass: domain", "dc: example");
		ldap.add("dn: " + PEOPLE, "objectClass: top", "objectClass: organizationalUnit", "ou: people");
		ldap.startListening();
		int ldapPort = ldap.getListenPort();

		String ldapHost;
		String external = System.getProperty("ldaprelink.it.keycloak");
		if (external == null) {
			Testcontainers.exposeHostPorts(ldapPort);
			keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:" + System.getProperty("keycloak.version", "26.7.0"))
					.withDefaultProviderClasses()
					.withFeaturesEnabled("declarative-ui");
			keycloak.start();
			base = keycloak.getAuthServerUrl().replaceAll("/$", "");
			ldapHost = "host.testcontainers.internal";
		} else {
			base = external.replaceAll("/$", "");
			ldapHost = "localhost";
		}

		api.login(base);
		api.delete("/admin/realms/" + REALM); // external Keycloak: leftovers of an earlier run
		expect(201, api.post("/admin/realms", Map.of("realm", REALM, "enabled", true)));
		realmId = api.json("/admin/realms/" + REALM).get("id").asText();
		expect(201, api.post("/admin/realms/" + REALM + "/roles", Map.of("name", "legacy-app")));
		expect(201, api.post("/admin/realms/" + REALM + "/clients", Map.of(
				"clientId", "tester", "publicClient", true, "directAccessGrantsEnabled", true)));

		Map<String, List<String>> ldapConfig = Map.ofEntries(
				Map.entry("enabled", List.of("true")),
				Map.entry("vendor", List.of("other")),
				Map.entry("connectionUrl", List.of("ldap://" + ldapHost + ":" + ldapPort)),
				Map.entry("authType", List.of("simple")),
				Map.entry("bindDn", List.of("cn=admin," + BASE_DN)),
				Map.entry("bindCredential", List.of("admin")),
				Map.entry("usersDn", List.of(PEOPLE)),
				Map.entry("searchScope", List.of("1")),
				Map.entry("usernameLDAPAttribute", List.of("uid")),
				Map.entry("rdnLDAPAttribute", List.of("uid")),
				Map.entry("uuidLDAPAttribute", List.of("entryUUID")),
				Map.entry("userObjectClasses", List.of("inetOrgPerson")),
				Map.entry("editMode", List.of("READ_ONLY")),
				Map.entry("importEnabled", List.of("true")),
				Map.entry("syncRegistrations", List.of("false")),
				Map.entry("trustEmail", List.of("true")));
		HttpResponse<String> created = api.post("/admin/realms/" + REALM + "/components", Map.of(
				"name", "test-ldap", "providerId", "ldap", "providerType", "org.keycloak.storage.UserStorageProvider",
				"parentId", realmId, "config", ldapConfig));
		expect(201, created);
		String location = created.headers().firstValue("Location").orElseThrow();
		providerId = location.substring(location.lastIndexOf('/') + 1);
	}

	@AfterAll
	static void stop() {
		if (keycloak != null) {
			keycloak.stop();
		}
		if (ldap != null) {
			ldap.shutDown(true);
		}
	}

	@Test
	public void previewChangesNothingAndAttachKeepsTheId() throws Exception {
		String id = "a1a1a1a1-0000-4000-8000-000000000001";
		placeholder(id, "alice", "alice.old@example.org", "Alice", "Old", List.of("legacy-app"));
		ldapUser("alice", "Alice", "Newname", "alice@example.org");

		JsonNode preview = api.json(relink(id));
		assertFalse(preview.get("applied").asBoolean());
		assertEquals(0, preview.get("problems").size(), preview.toString());
		assertEquals("uid=alice," + PEOPLE, preview.get("ldapEntry").get("dn").asText());
		assertEquals("alice@example.org", preview.get("after").get("email").asText());
		assertEquals("Newname", preview.get("after").get("lastName").asText());
		assertTrue(preview.get("after").get("enabled").asBoolean());

		JsonNode untouched = user(id);
		assertTrue(untouched.path("federationLink").isMissingNode(), "a preview must not link: " + untouched);
		assertEquals("alice.old@example.org", untouched.get("email").asText());
		assertFalse(untouched.get("enabled").asBoolean());

		HttpResponse<String> attached = api.post(relink(id) + "/attach", null);
		expect(200, attached);
		assertTrue(JSON.readTree(attached.body()).get("applied").asBoolean());

		JsonNode linked = user(id);
		assertEquals(id, linked.get("id").asText());
		assertEquals(providerId, linked.get("federationLink").asText());
		assertEquals("alice@example.org", linked.get("email").asText());
		assertEquals("Newname", linked.get("lastName").asText());
		assertTrue(linked.get("enabled").asBoolean());
		List<String> roles = new ArrayList<>();
		api.json("/admin/realms/" + REALM + "/users/" + id + "/role-mappings/realm").forEach(r -> roles.add(r.get("name").asText()));
		assertTrue(roles.contains("legacy-app"), "role mappings must survive: " + roles);

		// the user signs in with the LDAP password and gets a session under the old ID
		expect(200, api.postForm(base + "/realms/" + REALM + "/protocol/openid-connect/token", Map.of(
				"grant_type", "password", "client_id", "tester", "username", "alice", "password", PASSWORD,
				"scope", "openid")));
		assertEquals(1, api.json("/admin/realms/" + REALM + "/users/" + id + "/sessions").size());

		// once linked, a second attach is refused
		HttpResponse<String> again = api.post(relink(id) + "/attach", null);
		expect(409, again);
		assertTrue(again.body().contains("already linked"), again.body());
	}

	@Test
	public void matchesTheUsernameExactly() throws Exception {
		String id = "a1a1a1a1-0000-4000-8000-000000000002";
		placeholder(id, "test", "test@example.org", "Test", "User", List.of());
		ldapUser("test.a", "Test", "A", "test.a@example.org");
		ldapUser("a.test", "A", "Test", "a.test@example.org");

		JsonNode preview = api.json(relink(id));
		assertEquals(1, preview.get("problems").size(), preview.toString());
		assertTrue(preview.get("problems").get(0).asText().startsWith("No LDAP entry with uid=test "), preview.toString());

		expect(409, api.post(relink(id) + "/attach", null));
		assertTrue(user(id).path("federationLink").isMissingNode());
	}

	@Test
	public void refusesWhenAnotherUserHasTheLdapEmail() throws Exception {
		String id = "a1a1a1a1-0000-4000-8000-000000000003";
		placeholder(id, "bob", "bob.old@example.org", "Bob", "Old", List.of());
		expect(201, api.post("/admin/realms/" + REALM + "/users", Map.of(
				"username", "bob-other", "email", "bob@example.org", "firstName", "Other", "lastName", "Bob",
				"enabled", true)));
		ldapUser("bob", "Bob", "New", "bob@example.org");

		HttpResponse<String> attached = api.post(relink(id) + "/attach", null);
		expect(409, attached);
		assertTrue(attached.body().contains("bob@example.org"), attached.body());

		JsonNode untouched = user(id);
		assertTrue(untouched.path("federationLink").isMissingNode(), untouched.toString());
		assertEquals("bob.old@example.org", untouched.get("email").asText());
	}

	@Test
	public void needsTheRightToManageTheUser() throws Exception {
		String id = "a1a1a1a1-0000-4000-8000-000000000004";
		placeholder(id, "carol", "carol@example.org", "Carol", "C", List.of());
		ldapUser("carol", "Carol", "C", "carol@example.org");
		staff("viewer", "view-users");
		staff("helpdesk", "manage-users");

		String viewer = adminApiToken("viewer");
		expect(403, api.send(api.request(base + relink(id), viewer).GET()));
		expect(403, api.send(api.request(base + relink(id) + "/attach", viewer).POST(HttpRequest.BodyPublishers.noBody())));
		assertTrue(user(id).path("federationLink").isMissingNode());

		String helpdesk = adminApiToken("helpdesk");
		expect(200, api.send(api.request(base + relink(id), helpdesk).GET()));
		expect(200, api.send(api.request(base + relink(id) + "/attach", helpdesk).POST(HttpRequest.BodyPublishers.noBody())));
		assertEquals(providerId, user(id).get("federationLink").asText());
	}

	/** What the admin console's tab does: save a ui-tab component with the route parameters in its config. */
	@Test
	public void adminConsoleTabPreviewsAndAttaches() throws Exception {
		String id = "a1a1a1a1-0000-4000-8000-000000000005";
		placeholder(id, "dave", "dave.old@example.org", "Dave", "Old", List.of());
		ldapUser("dave", "Dave", "New", "dave@example.org");

		HttpResponse<String> preview = saveTab(id, "preview", null);
		expect(400, preview);
		assertTrue(preview.body().contains("Preview, nothing changed."), preview.body());
		assertTrue(preview.body().contains("dave.old@example.org"), preview.body());
		assertTrue(user(id).path("federationLink").isMissingNode());

		HttpResponse<String> unconfirmed = saveTab(id, "attach", "someone-else");
		expect(400, unconfirmed);
		assertTrue(unconfirmed.body().contains("Confirm username"), unconfirmed.body());
		assertTrue(user(id).path("federationLink").isMissingNode());

		HttpResponse<String> attached = saveTab(id, "attach", "dave");
		assertTrue(attached.statusCode() == 201 || attached.statusCode() == 204, attached.statusCode() + " " + attached.body());
		assertEquals(providerId, user(id).get("federationLink").asText());
		assertEquals(id, user(id).get("id").asText());

		// the stored component carries nothing user-specific
		for (JsonNode tab : api.json("/admin/realms/" + REALM + "/components?type=org.keycloak.services.ui.extend.UiTabProvider")) {
			assertEquals(0, tab.get("config").size(), tab.toString());
		}
	}

	/** Creates the tab's component on first use and updates it afterwards, like the admin console. */
	private static HttpResponse<String> saveTab(String userId, String action, String confirm) throws Exception {
		Map<String, List<String>> config = new HashMap<>(Map.of(
				"ldapRelinkAction", List.of(action), "realm", List.of(REALM), "id", List.of(userId),
				"tab", List.of("ldap-relink")));
		if (confirm != null) {
			config.put("ldapRelinkConfirmUsername", List.of(confirm));
		}
		Map<String, Object> component = Map.of("providerId", "ldap-relink",
				"providerType", "org.keycloak.services.ui.extend.UiTabProvider", "parentId", realmId, "config", config);
		JsonNode existing = api.json("/admin/realms/" + REALM + "/components?type=org.keycloak.services.ui.extend.UiTabProvider");
		if (existing.isEmpty()) {
			return api.post("/admin/realms/" + REALM + "/components", component);
		}
		String componentId = existing.get(0).get("id").asText();
		Map<String, Object> update = new HashMap<>(component);
		update.put("id", componentId);
		return api.send(api.request(base + "/admin/realms/" + REALM + "/components/" + componentId, api.bearer())
				.header("Content-Type", "application/json")
				.PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(update))));
	}

	private static String relink(String userId) {
		return "/admin/realms/" + REALM + "/ldap-relink/users/" + userId;
	}

	private static JsonNode user(String id) throws Exception {
		return api.json("/admin/realms/" + REALM + "/users/" + id);
	}

	/**
	 * A disabled local user with a fixed ID, as a placeholder for a person whose LDAP entry was deleted. Created by
	 * partial import, which keeps the given ID.
	 */
	private static void placeholder(String id, String username, String email, String firstName, String lastName,
			List<String> realmRoles) throws Exception {
		HttpResponse<String> imported = api.post("/admin/realms/" + REALM + "/partialImport", Map.of(
				"ifResourceExists", "FAIL",
				"users", List.of(Map.of("id", id, "username", username, "email", email, "firstName", firstName,
						"lastName", lastName, "enabled", false, "realmRoles", realmRoles))));
		expect(200, imported);
	}

	private static void ldapUser(String uid, String givenName, String sn, String mail) throws Exception {
		ldap.add("dn: uid=" + uid + "," + PEOPLE, "objectClass: inetOrgPerson", "uid: " + uid,
				"cn: " + givenName, "givenName: " + givenName, "sn: " + sn, "mail: " + mail,
				"userPassword: " + PASSWORD);
	}

	/** A local administrator of the realm with one realm-management role. */
	private static void staff(String username, String role) throws Exception {
		HttpResponse<String> imported = api.post("/admin/realms/" + REALM + "/partialImport", Map.of(
				"ifResourceExists", "FAIL",
				"users", List.of(Map.of("username", username, "enabled", true, "email", username + "@example.net",
						"emailVerified", true, "firstName", username, "lastName", "Staff",
						"credentials", List.of(Map.of("type", "password", "value", PASSWORD, "temporary", false)),
						"clientRoles", Map.of("realm-management", List.of(role))))));
		expect(200, imported);
	}

	private static String adminApiToken(String username) throws Exception {
		HttpResponse<String> token = api.postForm(base + "/realms/" + REALM + "/protocol/openid-connect/token", Map.of(
				"grant_type", "password", "client_id", "admin-cli", "username", username, "password", PASSWORD));
		expect(200, token);
		return "Bearer " + JSON.readTree(token.body()).get("access_token").asText();
	}

	private static void expect(int status, HttpResponse<String> response) {
		assertEquals(status, response.statusCode(), response.request().method() + " " + response.uri() + ": " + response.body());
	}

	/** Admin REST client authenticated as the master admin. */
	static final class Api {
		private final HttpClient http = HttpClient.newHttpClient();
		private String bearer;
		private long loggedInAt;
		private String base;

		/** The master realm's access tokens live 60 s: log in again before that. */
		String bearer() throws Exception {
			if (System.currentTimeMillis() - loggedInAt > 40_000) {
				login(base);
			}
			return bearer;
		}

		void login(String base) throws Exception {
			this.base = base;
			loggedInAt = System.currentTimeMillis();
			String username = keycloak == null ? "admin" : keycloak.getAdminUsername();
			String password = keycloak == null ? "admin" : keycloak.getAdminPassword();
			HttpResponse<String> token = postForm(base + "/realms/master/protocol/openid-connect/token", Map.of(
					"grant_type", "password", "client_id", "admin-cli", "username", username, "password", password));
			expect(200, token);
			bearer = "Bearer " + JSON.readTree(token.body()).get("access_token").asText();
		}

		JsonNode json(String path) throws Exception {
			HttpResponse<String> response = send(request(base + path, bearer()).GET());
			expect(200, response);
			return JSON.readTree(response.body());
		}

		HttpResponse<String> post(String path, Object body) throws Exception {
			HttpRequest.Builder request = request(base + path, bearer());
			if (body == null) {
				return send(request.POST(HttpRequest.BodyPublishers.noBody()));
			}
			return send(request.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
		}

		HttpResponse<String> delete(String path) throws Exception {
			return send(request(base + path, bearer()).DELETE());
		}

		HttpResponse<String> postForm(String url, Map<String, String> form) throws Exception {
			String body = form.entrySet().stream()
					.map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
					.collect(Collectors.joining("&"));
			return send(request(url, null).header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString(body)));
		}

		HttpRequest.Builder request(String url, String authorization) {
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
			if (authorization != null) {
				builder.header("Authorization", authorization);
			}
			return builder;
		}

		HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
			return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
		}

		private static String enc(String value) {
			return URLEncoder.encode(value, StandardCharsets.UTF_8);
		}
	}
}
