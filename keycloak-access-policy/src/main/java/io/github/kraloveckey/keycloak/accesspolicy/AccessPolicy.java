package io.github.kraloveckey.keycloak.accesspolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Which users may sign in to which clients.
 *
 * <pre>
 * {
 *   "unlistedClients": "allow",
 *   "rules": [
 *     {
 *       "clients": ["staff-portal", "^https://learning\\..*$"],
 *       "realmRoles": ["employee"],
 *       "clientRoles": { "staff-portal": ["user"] },
 *       "groups": ["/Staff/HR"]
 *     },
 *     { "clients": ["legacy-app"] }
 *   ]
 * }
 * </pre>
 *
 * A rule applies to a client when one of its {@code clients} entries equals the client ID, or, for entries starting
 * with {@code ^}, when the regular expression matches the whole client ID. For a client with applicable rules the user
 * needs one of the listed realm roles, client roles or groups of any of those rules. A rule without roles and groups
 * lets nobody in. Clients without any applicable rule follow {@code unlistedClients} ({@code allow} by default).
 */
public final class AccessPolicy {

	private static final ObjectMapper JSON = new ObjectMapper()
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

	private final boolean allowUnlistedClients;
	private final List<CompiledRule> rules;

	private AccessPolicy(boolean allowUnlistedClients, List<CompiledRule> rules) {
		this.allowUnlistedClients = allowUnlistedClients;
		this.rules = rules;
	}

	/**
	 * @throws IllegalArgumentException with a message pointing at the problem, if the document is not a valid policy
	 */
	public static AccessPolicy parse(String json) {
		if (json == null || json.isBlank()) {
			throw new IllegalArgumentException("the access policy is empty");
		}
		Document document;
		try {
			document = JSON.readValue(json, Document.class);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("the access policy is not valid JSON: " + e.getOriginalMessage(), e);
		}
		if (document == null) {
			throw new IllegalArgumentException("the access policy is empty");
		}

		boolean allowUnlisted;
		String unlisted = document.unlistedClients() == null ? "allow" : document.unlistedClients();
		switch (unlisted) {
			case "allow" -> allowUnlisted = true;
			case "deny" -> allowUnlisted = false;
			default -> throw new IllegalArgumentException("\"unlistedClients\" must be \"allow\" or \"deny\", not \"" + unlisted + "\"");
		}

		List<CompiledRule> compiled = new ArrayList<>();
		List<Rule> rules = document.rules() == null ? List.of() : document.rules();
		for (int i = 0; i < rules.size(); i++) {
			compiled.add(CompiledRule.of(i + 1, rules.get(i)));
		}
		return new AccessPolicy(allowUnlisted, List.copyOf(compiled));
	}

	public Decision evaluate(String clientId, AccessSubject subject) {
		List<CompiledRule> applicable = rules.stream().filter(r -> r.appliesTo(clientId)).toList();
		if (applicable.isEmpty()) {
			return allowUnlistedClients
					? Decision.allow("no rule for the client, unlisted clients are allowed")
					: Decision.deny("no rule for the client, unlisted clients are denied");
		}
		for (CompiledRule rule : applicable) {
			String grant = rule.grantFor(subject);
			if (grant != null) {
				return Decision.allow("rule " + rule.number + ": " + grant);
			}
		}
		return Decision.deny("none of the roles or groups required by rule(s) "
				+ String.join(", ", applicable.stream().map(r -> String.valueOf(r.number)).toList()));
	}

	/** Result of {@link #evaluate}; {@code reason} is meant for logs and events. */
	public record Decision(boolean allowed, String reason) {
		static Decision allow(String reason) {
			return new Decision(true, reason);
		}

		static Decision deny(String reason) {
			return new Decision(false, reason);
		}
	}

	record Document(
			@JsonProperty("unlistedClients") String unlistedClients,
			@JsonProperty("rules") List<Rule> rules) {
	}

	record Rule(
			@JsonProperty("clients") List<String> clients,
			@JsonProperty("realmRoles") List<String> realmRoles,
			@JsonProperty("clientRoles") Map<String, List<String>> clientRoles,
			@JsonProperty("groups") List<String> groups) {
	}

	private record CompiledRule(int number, List<String> exactClients, List<Pattern> clientPatterns,
			List<String> realmRoles, Map<String, List<String>> clientRoles, List<String> groups) {

		static CompiledRule of(int number, Rule rule) {
			String where = "rule " + number;
			if (rule == null) {
				throw new IllegalArgumentException(where + " is null");
			}
			if (rule.clients() == null || rule.clients().isEmpty()) {
				throw new IllegalArgumentException(where + ": \"clients\" must list at least one client ID or pattern");
			}
			List<String> exact = new ArrayList<>();
			List<Pattern> patterns = new ArrayList<>();
			for (String client : rule.clients()) {
				if (client == null || client.isBlank()) {
					throw new IllegalArgumentException(where + ": empty entry in \"clients\"");
				}
				if (client.startsWith("^")) {
					try {
						patterns.add(Pattern.compile(client));
					} catch (PatternSyntaxException e) {
						throw new IllegalArgumentException(where + ": invalid pattern " + client + ": " + e.getDescription());
					}
				} else {
					exact.add(client);
				}
			}

			List<String> groups = nonBlank(where, "groups", rule.groups());
			for (String group : groups) {
				if (!group.startsWith("/")) {
					throw new IllegalArgumentException(where + ": group \"" + group
							+ "\" must be a full path starting with '/', e.g. \"/Staff/HR\"");
				}
			}

			Map<String, List<String>> clientRoles = new java.util.LinkedHashMap<>();
			if (rule.clientRoles() != null) {
				rule.clientRoles().forEach((clientId, roles) -> {
					if (clientId == null || clientId.isBlank()) {
						throw new IllegalArgumentException(where + ": empty client ID in \"clientRoles\"");
					}
					clientRoles.put(clientId, nonBlank(where, "clientRoles." + clientId, roles));
				});
			}

			return new CompiledRule(number, List.copyOf(exact), List.copyOf(patterns),
					nonBlank(where, "realmRoles", rule.realmRoles()), Map.copyOf(clientRoles), groups);
		}

		private static List<String> nonBlank(String where, String field, List<String> values) {
			if (values == null) {
				return List.of();
			}
			for (String value : values) {
				if (value == null || value.isBlank()) {
					throw new IllegalArgumentException(where + ": empty entry in \"" + field + "\"");
				}
			}
			return List.copyOf(values);
		}

		boolean appliesTo(String clientId) {
			return exactClients.contains(clientId) || clientPatterns.stream().anyMatch(p -> p.matcher(clientId).matches());
		}

		/** @return what grants access, or {@code null} */
		String grantFor(AccessSubject subject) {
			for (String role : realmRoles) {
				if (subject.hasRealmRole(role)) {
					return "realm role " + role;
				}
			}
			for (Map.Entry<String, List<String>> entry : clientRoles.entrySet()) {
				for (String role : entry.getValue()) {
					if (subject.hasClientRole(entry.getKey(), role)) {
						return "client role " + entry.getKey() + "/" + role;
					}
				}
			}
			for (String group : groups) {
				if (subject.isMemberOf(group)) {
					return "group " + group;
				}
			}
			return null;
		}
	}
}
