package io.github.kraloveckey.keycloak.ldaprelink.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.keycloak.Config;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.services.ui.extend.UiTabProvider;
import org.keycloak.services.ui.extend.UiTabProviderFactory;

import io.github.kraloveckey.keycloak.ldaprelink.LdapRelinkService;
import io.github.kraloveckey.keycloak.ldaprelink.RelinkResult;

/**
 * Tab "LDAP relink" on the user details page of the admin console (needs the {@code declarative-ui} feature).
 * <p>
 * The admin console renders a tab as a form whose Save stores a component of this provider type through the
 * Components API (which requires {@code manage-realm}), with the route parameters ({@code realm}, {@code id} = the
 * user's ID, {@code tab}) merged into the config. There is no other hook, so {@link #validateConfiguration} does the
 * work: a preview is answered with a validation error carrying the preview (nothing is saved), an attach links the
 * user. The stored component keeps an empty config, it only exists because the admin console insists on saving one.
 */
public class LdapRelinkTab implements UiTabProvider, UiTabProviderFactory<ComponentModel> {

	public static final String ID = "ldap-relink";

	static final String ACTION = "ldapRelinkAction";
	static final String CONFIRM = "ldapRelinkConfirmUsername";
	static final String PROVIDER = "ldapRelinkProvider";
	/** Route parameter the admin console merges into the config: the user's ID. */
	static final String USER_ID = "id";
	static final String PREVIEW = "preview";
	static final String ATTACH = "attach";

	/** messages key whose text is just {0}: the message is passed as parameter, verbatim. */
	static final String MESSAGE = "ldapRelinkMessage";

	@Override
	public void validateConfiguration(KeycloakSession session, RealmModel realm, ComponentModel model)
			throws ComponentValidationException {
		String userId = model.get(USER_ID);
		String requestedAction = model.get(ACTION);
		String action = Objects.requireNonNullElse(requestedAction, PREVIEW);
		String confirm = model.get(CONFIRM);
		String provider = model.get(PROVIDER);

		// nothing user-specific is ever stored; also makes a realm import of this component a no-op
		model.setConfig(new MultivaluedHashMap<>());
		if (userId == null) {
			if (requestedAction == null && confirm == null && provider == null) {
				return; // saved without the admin console, e.g. by a realm import: nothing to do
			}
			throw message("The user ID is missing, nothing changed. Open this tab from the user's page.");
		}

		UserModel user = session.users().getUserById(realm, userId);
		if (user == null) {
			throw message("User not found.");
		}

		boolean apply = ATTACH.equals(action);
		if (apply && (confirm == null || !confirm.trim().equalsIgnoreCase(user.getUsername()))) {
			throw message("Nothing changed. To attach, type the username '" + user.getUsername()
					+ "' into \"Confirm username\" and save again.");
		}

		RelinkResult result = new LdapRelinkService(session, realm).attach(userId, provider, apply);
		if (!result.linkable()) {
			throw message("Cannot link: " + String.join(" ", result.problems()));
		}
		if (!apply) {
			throw message("Preview, nothing changed. " + describe(result)
					+ " To link, choose \"Attach\", confirm the username and save.");
		}
		// success: returning normally lets the admin console show "saved"; the change is in the admin events
	}

	static String describe(RelinkResult result) {
		RelinkResult.UserState before = result.before();
		RelinkResult.UserState after = result.after();
		List<String> changes = new ArrayList<>();
		change(changes, "e-mail", before.email(), after.email());
		change(changes, "first name", before.firstName(), after.firstName());
		change(changes, "last name", before.lastName(), after.lastName());
		change(changes, "enabled", String.valueOf(before.enabled()), String.valueOf(after.enabled()));
		change(changes, "e-mail verified", String.valueOf(before.emailVerified()), String.valueOf(after.emailVerified()));
		List<String> addedGroups = after.groups().stream().filter(g -> !before.groups().contains(g)).toList();
		if (!addedGroups.isEmpty()) {
			changes.add("groups added: " + String.join(", ", addedGroups));
		}
		List<String> addedActions = after.requiredActions().stream()
				.filter(a -> !before.requiredActions().contains(a)).toList();
		if (!addedActions.isEmpty()) {
			changes.add("required actions added: " + String.join(", ", addedActions));
		}
		return "The user (ID " + result.userId() + ", unchanged) would be linked to " + result.ldapEntry().dn()
				+ " of provider '" + result.provider().name() + "'"
				+ (changes.isEmpty() ? ", no other changes." : "; " + String.join("; ", changes) + ".");
	}

	private static void change(List<String> changes, String label, String before, String after) {
		if (!Objects.equals(before, after)) {
			changes.add(label + ": " + (before == null ? "(empty)" : before) + " → " + (after == null ? "(empty)" : after));
		}
	}

	private static ComponentValidationException message(String text) {
		return new ComponentValidationException(MESSAGE, text);
	}

	@Override
	public String getPath() {
		return "/:realm/users/:id/:tab";
	}

	@Override
	public Map<String, String> getParams() {
		return Map.of("tab", ID);
	}

	@Override
	public String getHelpText() {
		return "Links this local user, keeping its ID, to the LDAP entry with the same username.";
	}

	@Override
	public List<ProviderConfigProperty> getConfigProperties() {
		return ProviderConfigurationBuilder.create()
				.property()
				.name(ACTION)
				.label("ldapRelinkActionLabel")
				.helpText("ldapRelinkActionHelp")
				.type(ProviderConfigProperty.LIST_TYPE)
				.options(PREVIEW, ATTACH)
				.defaultValue(PREVIEW)
				.add()
				.property()
				.name(CONFIRM)
				.label("ldapRelinkConfirmLabel")
				.helpText("ldapRelinkConfirmHelp")
				.type(ProviderConfigProperty.STRING_TYPE)
				.add()
				.property()
				.name(PROVIDER)
				.label("ldapRelinkProviderLabel")
				.helpText("ldapRelinkProviderHelp")
				.type(ProviderConfigProperty.STRING_TYPE)
				.add()
				// declared so that updates keep it (undeclared keys are dropped); the admin console has no editor
				// for this type and renders nothing, it fills the value from the route
				.property()
				.name(USER_ID)
				.type("hidden")
				.add()
				.build();
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public void init(Config.Scope config) {
		// no configuration
	}

	@Override
	public void postInit(KeycloakSessionFactory factory) {
		// nothing to wire
	}

	@Override
	public void close() {
		// stateless
	}
}
