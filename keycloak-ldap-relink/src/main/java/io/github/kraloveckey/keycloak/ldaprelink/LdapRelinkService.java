package io.github.kraloveckey.keycloak.ldaprelink;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;
import org.keycloak.common.constants.KerberosConstants;
import org.keycloak.component.ComponentModel;
import org.keycloak.federation.kerberos.KerberosPrincipal;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.LDAPConstants;
import org.keycloak.models.ModelException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.models.cache.UserCache;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.storage.UserStoragePrivateUtil;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderModel;
import org.keycloak.storage.UserStorageUtil;
import org.keycloak.storage.ldap.LDAPConfig;
import org.keycloak.storage.ldap.LDAPStorageProvider;
import org.keycloak.storage.ldap.LDAPStorageProviderFactory;
import org.keycloak.storage.ldap.LDAPUtils;
import org.keycloak.storage.ldap.idm.model.LDAPObject;
import org.keycloak.storage.ldap.idm.query.internal.LDAPQuery;
import org.keycloak.storage.ldap.idm.query.internal.LDAPQueryConditionsBuilder;
import org.keycloak.storage.ldap.kerberos.LDAPProviderKerberosConfig;
import org.keycloak.storage.ldap.mappers.LDAPMappersComparator;
import org.keycloak.storage.ldap.mappers.LDAPStorageMapper;

/**
 * Links an existing local user to the LDAP entry with the same username, keeping the user's ID, roles, groups and
 * everything else stored for it.
 * <p>
 * The user becomes exactly what Keycloak's own LDAP import would have produced for that entry (same steps as
 * {@code LDAPStorageProvider#doImportUser}: enabled, all LDAP mappers in import mode, federation link, {@code LDAP_ID},
 * {@code LDAP_ENTRY_DN}, trusted e-mail, Kerberos principal), except that the user is the existing one instead of a
 * new one with a random ID.
 * <p>
 * Preview and attach run the same code: a preview makes the changes in the request's transaction, records the
 * result and marks the transaction rollback-only, so it shows precisely what an attach would do and changes nothing.
 */
public class LdapRelinkService {

	private static final Logger LOG = Logger.getLogger(LdapRelinkService.class);

	private final KeycloakSession session;
	private final RealmModel realm;

	public LdapRelinkService(KeycloakSession session, RealmModel realm) {
		this.session = session;
		this.realm = realm;
	}

	/**
	 * @param userId     the local user to link
	 * @param providerId the LDAP provider to link to; may be {@code null} when the realm has exactly one enabled
	 *                   LDAP provider
	 * @param apply      {@code false} for a preview: nothing is stored
	 */
	public RelinkResult attach(String userId, String providerId, boolean apply) {
		UserProvider localStorage = UserStoragePrivateUtil.userLocalStorage(session);
		UserModel user = localStorage.getUserById(realm, userId);
		if (user == null) {
			throw new IllegalArgumentException("User not found: " + userId);
		}

		List<String> problems = new ArrayList<>();
		RelinkResult.UserState before = state(user);

		if (user.getServiceAccountClientLink() != null) {
			problems.add("The user is the service account of a client.");
		}
		if (user.getFederationLink() != null) {
			problems.add("The user is already linked to the user federation provider '" + providerName(user.getFederationLink())
					+ "'. Only local users can be linked.");
		}

		ComponentModel providerModel = chooseProvider(providerId, problems);
		RelinkResult.Provider provider = providerModel == null ? null
				: new RelinkResult.Provider(providerModel.getId(), providerModel.getName());
		if (!problems.isEmpty()) {
			return new RelinkResult(false, user.getId(), provider, null, before, null, problems);
		}

		LDAPStorageProvider ldap = ldapProvider(providerModel);
		LDAPConfig config = ldap.getLdapIdentityStore().getConfig();

		List<LDAPObject> entries = findByUsername(ldap, config, user.getUsername());
		if (entries.isEmpty()) {
			problems.add("No LDAP entry with " + config.getUsernameLdapAttribute() + "=" + user.getUsername()
					+ " under " + config.getUsersDn() + " (with the provider's user filter, if any).");
			return new RelinkResult(false, user.getId(), provider, null, before, null, problems);
		}
		if (entries.size() > 1) {
			problems.add("More than one LDAP entry has " + config.getUsernameLdapAttribute() + "=" + user.getUsername()
					+ ": " + entries.stream().map(e -> e.getDn().toString()).toList());
			return new RelinkResult(false, user.getId(), provider, null, before, null, problems);
		}

		LDAPObject entry = entries.get(0);
		String dn = entry.getDn().toString();
		String ldapUsername;
		String uuid;
		try {
			ldapUsername = LDAPUtils.getUsername(entry, config);
			LDAPUtils.checkUuid(entry, config);
			uuid = entry.getUuid();
		} catch (ModelException e) {
			problems.add(e.getMessage());
			return new RelinkResult(false, user.getId(), provider, new RelinkResult.LdapEntry(dn, null, null), before,
					null, problems);
		}
		RelinkResult.LdapEntry ldapEntry = new RelinkResult.LdapEntry(dn, uuid, ldapUsername);

		// the LDAP search is an equality match, this guards against servers with unusual matching rules
		if (!user.getUsername().equalsIgnoreCase(ldapUsername)) {
			problems.add("The LDAP entry's username '" + ldapUsername + "' differs from the user's username '"
					+ user.getUsername() + "'.");
		}
		Optional<UserModel> linkedElsewhere = localStorage
				.searchForUserByUserAttributeStream(realm, LDAPConstants.LDAP_ID, uuid)
				.filter(other -> !other.getId().equals(user.getId()))
				.findFirst();
		linkedElsewhere.ifPresent(other -> problems.add("The LDAP entry is already linked to the user '"
				+ other.getUsername() + "' (ID " + other.getId() + "). Delete or unlink that user first."));
		if (!problems.isEmpty()) {
			return new RelinkResult(false, user.getId(), provider, ldapEntry, before, null, problems);
		}

		try {
			link(ldap, providerModel, config, user, entry);
		} catch (ModelException e) {
			// e.g. ModelDuplicateException from the e-mail mapper: another user already has the LDAP e-mail
			session.getTransactionManager().setRollbackOnly();
			problems.add(e.getMessage());
			return new RelinkResult(false, user.getId(), provider, ldapEntry, before, null, problems);
		}

		RelinkResult.UserState after = state(user);
		if (apply) {
			UserCache userCache = UserStorageUtil.userCache(session);
			if (userCache != null) {
				userCache.evict(realm, user);
			}
			LOG.infof("Linked user '%s' (ID %s) in realm '%s' to LDAP entry '%s' (LDAP_ID %s) of provider '%s'",
					user.getUsername(), user.getId(), realm.getName(), dn, uuid, providerModel.getName());
		} else {
			session.getTransactionManager().setRollbackOnly();
		}
		return new RelinkResult(apply, user.getId(), provider, ldapEntry, before, after, problems);
	}

	/**
	 * The deprecated lookup is the one Keycloak's LDAP code uses: it registers the provider as a session attribute
	 * under the component ID, which is where the LDAP mapper factories look for it.
	 * {@code getComponentProvider} does not find user storage providers.
	 */
	@SuppressWarnings("deprecation")
	private LDAPStorageProvider ldapProvider(ComponentModel providerModel) {
		return (LDAPStorageProvider) session.getProvider(UserStorageProvider.class, providerModel);
	}

	/** The steps of {@code LDAPStorageProvider#doImportUser}, applied to an existing user. */
	private void link(LDAPStorageProvider ldap, ComponentModel providerModel, LDAPConfig config, UserModel user,
			LDAPObject entry) {
		user.setEnabled(true);

		LDAPMappersComparator comparator = new LDAPMappersComparator(config);
		realm.getComponentsStream(providerModel.getId(), LDAPStorageMapper.class.getName())
				.sorted(comparator.sortDesc())
				.forEachOrdered(mapperModel -> ldap.getMapperManager().getMapper(mapperModel)
						// isCreate=true: group and role mappers in IMPORT mode add the LDAP memberships, as on import
						.onImportUserFromLDAP(entry, user, realm, true));

		user.setFederationLink(providerModel.getId());
		user.setSingleAttribute(LDAPConstants.LDAP_ID, entry.getUuid());
		user.setSingleAttribute(LDAPConstants.LDAP_ENTRY_DN, entry.getDn().toString());
		if (config.isTrustEmail()) {
			user.setEmailVerified(true);
		}

		LDAPProviderKerberosConfig kerberos = ldap.getKerberosConfig();
		if (kerberos.isAllowKerberosAuthentication() && kerberos.getKerberosPrincipalAttribute() != null) {
			String principal = entry.getAttributeAsString(kerberos.getKerberosPrincipalAttribute());
			if (principal != null) {
				user.setSingleAttribute(KerberosConstants.KERBEROS_PRINCIPAL, new KerberosPrincipal(principal).toString());
			}
		}
	}

	/** Equality search on the provider's username attribute, within its users DN, scope and user filter. */
	private List<LDAPObject> findByUsername(LDAPStorageProvider ldap, LDAPConfig config, String username) {
		try (LDAPQuery query = LDAPUtils.createQueryForUserSearch(ldap, realm)) {
			query.addWhereCondition(new LDAPQueryConditionsBuilder().equal(config.getUsernameLdapAttribute(), username));
			// two are enough to tell "one" from "more than one"
			query.setLimit(2);
			return query.getResultList();
		}
	}

	private ComponentModel chooseProvider(String providerId, List<String> problems) {
		List<ComponentModel> ldapProviders = realm
				.getComponentsStream(realm.getId(), UserStorageProvider.class.getName())
				.filter(c -> LDAPStorageProviderFactory.PROVIDER_NAME.equals(c.getProviderId()))
				.toList();

		if (providerId != null && !providerId.isBlank()) {
			Optional<ComponentModel> chosen = ldapProviders.stream()
					.filter(c -> c.getId().equals(providerId) || c.getName().equals(providerId))
					.findFirst();
			if (chosen.isEmpty()) {
				problems.add("The realm has no LDAP provider with ID or name '" + providerId + "'.");
				return null;
			}
			return check(chosen.get(), problems);
		}

		List<ComponentModel> enabled = ldapProviders.stream()
				.filter(c -> new UserStorageProviderModel(c).isEnabled())
				.toList();
		if (enabled.isEmpty()) {
			problems.add("The realm has no enabled LDAP provider.");
			return null;
		}
		if (enabled.size() > 1) {
			problems.add("The realm has several enabled LDAP providers " + enabled.stream().map(ComponentModel::getName).toList()
					+ ": choose one with the 'provider' parameter.");
			return null;
		}
		return check(enabled.get(0), problems);
	}

	private ComponentModel check(ComponentModel provider, List<String> problems) {
		UserStorageProviderModel model = new UserStorageProviderModel(provider);
		if (!model.isEnabled()) {
			problems.add("The LDAP provider '" + provider.getName() + "' is disabled.");
		}
		if (!model.isImportEnabled()) {
			problems.add("The LDAP provider '" + provider.getName()
					+ "' does not import users, so there is no stored user to link.");
		}
		return provider;
	}

	private String providerName(String componentId) {
		ComponentModel component = realm.getComponent(componentId);
		return component == null ? componentId : component.getName();
	}

	private RelinkResult.UserState state(UserModel user) {
		return new RelinkResult.UserState(
				user.getUsername(),
				user.getEmail(),
				user.isEmailVerified(),
				user.getFirstName(),
				user.getLastName(),
				user.isEnabled(),
				user.getFederationLink() == null ? null : providerName(user.getFederationLink()),
				user.getFirstAttribute(LDAPConstants.LDAP_ID),
				user.getFirstAttribute(LDAPConstants.LDAP_ENTRY_DN),
				user.getRequiredActionsStream().sorted().toList(),
				user.getGroupsStream().map(KeycloakModelUtils::buildGroupPath).sorted().toList());
	}
}
