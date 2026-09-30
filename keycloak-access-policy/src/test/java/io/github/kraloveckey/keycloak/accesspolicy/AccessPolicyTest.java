package io.github.kraloveckey.keycloak.accesspolicy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

public class AccessPolicyTest {

	/** Test double: roles as "role" / "client/role", groups as full paths; subgroups count like in Keycloak. */
	private record Subject(Set<String> realmRoles, Set<String> clientRoles, Set<String> groups) implements AccessSubject {
		@Override
		public boolean hasRealmRole(String role) {
			return realmRoles.contains(role);
		}

		@Override
		public boolean hasClientRole(String clientId, String role) {
			return clientRoles.contains(clientId + "/" + role);
		}

		@Override
		public boolean isMemberOf(String groupPath) {
			return groups.stream().anyMatch(g -> g.equals(groupPath) || g.startsWith(groupPath + "/"));
		}
	}

	private static final Subject NOBODY = new Subject(Set.of(), Set.of(), Set.of());

	private static final String POLICY = """
			{
			  "rules": [
			    { "clients": ["portal"], "realmRoles": ["employee"] },
			    { "clients": ["hr-app"], "clientRoles": { "hr-app": ["user"], "portal": ["hr"] } },
			    { "clients": ["wiki"], "groups": ["/Staff"] },
			    { "clients": ["^https://learning\\\\.example\\\\.com/.*$"], "groups": ["/Staff/Teachers"] },
			    { "clients": ["legacy"] }
			  ]
			}""";

	private static boolean allowed(String policy, String client, Subject subject) {
		return AccessPolicy.parse(policy).evaluate(client, subject).allowed();
	}

	@Test
	void realmRole() {
		assertTrue(allowed(POLICY, "portal", new Subject(Set.of("employee"), Set.of(), Set.of())));
		assertFalse(allowed(POLICY, "portal", new Subject(Set.of("guest"), Set.of(), Set.of())));
	}

	@Test
	void clientRoleOfTheSameAndOfAnotherClient() {
		assertTrue(allowed(POLICY, "hr-app", new Subject(Set.of(), Set.of("hr-app/user"), Set.of())));
		assertTrue(allowed(POLICY, "hr-app", new Subject(Set.of(), Set.of("portal/hr"), Set.of())));
		assertFalse(allowed(POLICY, "hr-app", new Subject(Set.of("user"), Set.of("portal/user"), Set.of())));
	}

	@Test
	void groupIncludesSubgroups() {
		assertTrue(allowed(POLICY, "wiki", new Subject(Set.of(), Set.of(), Set.of("/Staff"))));
		assertTrue(allowed(POLICY, "wiki", new Subject(Set.of(), Set.of(), Set.of("/Staff/HR"))));
		assertFalse(allowed(POLICY, "wiki", new Subject(Set.of(), Set.of(), Set.of("/StaffOther"))));
	}

	@Test
	void regexClientsMatchTheWholeClientId() {
		Subject teacher = new Subject(Set.of(), Set.of(), Set.of("/Staff/Teachers"));
		assertTrue(allowed(POLICY, "https://learning.example.com/auth/saml2/sp/metadata.php", teacher));
		assertFalse(allowed(POLICY, "https://learning.example.com/auth/saml2/sp/metadata.php", NOBODY));
		// not matched by the pattern -> unlisted -> allowed by default
		assertTrue(allowed(POLICY, "https://other.example.com/learning.example.com/", NOBODY));
	}

	@Test
	void ruleWithoutRolesAndGroupsBlocksTheClient() {
		assertFalse(allowed(POLICY, "legacy", new Subject(Set.of("admin"), Set.of(), Set.of("/Staff"))));
	}

	@Test
	void anyApplicableRuleIsEnough() {
		String policy = """
				{ "rules": [
				  { "clients": ["app"], "realmRoles": ["a"] },
				  { "clients": ["^ap.$"], "groups": ["/G"] }
				] }""";
		assertTrue(allowed(policy, "app", new Subject(Set.of(), Set.of(), Set.of("/G"))));
		assertTrue(allowed(policy, "app", new Subject(Set.of("a"), Set.of(), Set.of())));
		assertFalse(allowed(policy, "app", NOBODY));
	}

	@Test
	void unlistedClients() {
		String deny = """
				{ "unlistedClients": "deny", "rules": [ { "clients": ["open"], "realmRoles": ["x"] } ] }""";
		assertFalse(allowed(deny, "other", new Subject(Set.of("x"), Set.of(), Set.of())));
		assertTrue(allowed(deny, "open", new Subject(Set.of("x"), Set.of(), Set.of())));
		assertTrue(allowed("{ \"rules\": [] }", "anything", NOBODY));
		assertFalse(allowed("{ \"unlistedClients\": \"deny\" }", "anything", NOBODY));
	}

	@Test
	void invalidPoliciesAreRejectedWithAPointer() {
		Map<String, String> invalid = Map.of(
				"", "empty",
				"{ \"rules\": [ { \"clients\": [\"a\"], \"role\": [\"x\"] } ] }", "Unrecognized field",
				"{ \"rules\": [ { \"realmRoles\": [\"x\"] } ] }", "rule 1",
				"{ \"rules\": [ { \"clients\": [\"^(\"] } ] }", "invalid pattern",
				"{ \"rules\": [ { \"clients\": [\"a\"], \"groups\": [\"Staff\"] } ] }", "full path",
				"{ \"unlistedClients\": \"maybe\" }", "unlistedClients",
				"{ \"rules\": [ ", "not valid JSON");
		invalid.forEach((json, expected) -> {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AccessPolicy.parse(json), json);
			assertTrue(e.getMessage().contains(expected), () -> "'" + e.getMessage() + "' should mention '" + expected + "'");
		});
	}
}
