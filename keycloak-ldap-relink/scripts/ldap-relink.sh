#!/usr/bin/env bash
# Links local Keycloak users (keeping their IDs) to the LDAP entries with the same username, through the
# keycloak-ldap-relink admin endpoint. Previews by default; --apply makes the changes.
#
# Usage:
#   ldap-relink.sh [options] USER...              USER = username or user ID
#   ldap-relink.sh [options] --file FILE          one USER per line (first CSV column), '#' starts a comment
#
# Options:
#   --apply            link the users; without it nothing is changed (preview)
#   --provider NAME    LDAP provider name or ID; only needed when the realm has more than one
#   --config FILE      file with the settings below (shell syntax, sourced)
#   --report FILE      also append the output to FILE (e.g. to mail it afterwards)
#   -h, --help
#
# Settings (environment or --config file):
#   KC_URL             Keycloak base URL, e.g. https://keycloak.example.com
#   KC_REALM           realm of the users
#   KC_CLIENT_ID       confidential client with a service account that has realm-management: manage-users
#   KC_CLIENT_SECRET
#   KC_CLIENT_REALM    realm of that client (default: KC_REALM)
#   KC_CURL_OPTS       extra curl options, e.g. "--cacert /etc/ssl/internal-ca.pem"
#
# Exit status: 0 every user was (or in a preview: can be) linked, 1 at least one cannot, 2 usage or login error.
# Needs curl and jq.

set -uo pipefail

apply=false
provider=""
file=""
report=""
users=()

usage() {
	sed -n '2,/^# Needs/p' "$0" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
	case "$1" in
		--apply) apply=true ;;
		--provider) provider="${2:?--provider needs a value}"; shift ;;
		--config) # shellcheck disable=SC1090
			source "${2:?--config needs a file}"; shift ;;
		--file) file="${2:?--file needs a file}"; shift ;;
		--report) report="${2:?--report needs a file}"; shift ;;
		-h|--help) usage; exit 0 ;;
		--) shift; users+=("$@"); break ;;
		-*) echo "unknown option: $1" >&2; exit 2 ;;
		*) users+=("$1") ;;
	esac
	shift
done

if [[ -n "$file" ]]; then
	[[ -r "$file" ]] || { echo "cannot read $file" >&2; exit 2; }
	while IFS= read -r line || [[ -n "$line" ]]; do
		line="${line%%#*}"
		line="${line%%,*}"
		line="$(echo "$line" | tr -d '[:space:]')"
		[[ -n "$line" ]] && users+=("$line")
	done < "$file"
fi

for setting in KC_URL KC_REALM KC_CLIENT_ID KC_CLIENT_SECRET; do
	[[ -n "${!setting:-}" ]] || { echo "$setting is not set (environment or --config)" >&2; exit 2; }
done
[[ ${#users[@]} -gt 0 ]] || { usage >&2; exit 2; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }

KC_URL="${KC_URL%/}"
KC_CLIENT_REALM="${KC_CLIENT_REALM:-$KC_REALM}"
# shellcheck disable=SC2206
curl_opts=(-sS --max-time 60 ${KC_CURL_OPTS:-})

out() {
	echo "$@"
	if [[ -n "$report" ]]; then echo "$@" >> "$report"; fi
}

urlencode() {
	jq -rn --arg v "$1" '$v|@uri'
}

# token for the service account; requested again per user so that long lists never outlive it
token() {
	curl "${curl_opts[@]}" -X POST "$KC_URL/realms/$(urlencode "$KC_CLIENT_REALM")/protocol/openid-connect/token" \
		--data-urlencode grant_type=client_credentials \
		--data-urlencode "client_id=$KC_CLIENT_ID" \
		--data-urlencode "client_secret=$KC_CLIENT_SECRET" | jq -r '.access_token // empty'
}

uuid_re='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
realm_path="$KC_URL/admin/realms/$(urlencode "$KC_REALM")"
query=""
[[ -n "$provider" ]] && query="?provider=$(urlencode "$provider")"

failed=0
mode=$([[ "$apply" == true ]] && echo "attach" || echo "preview (nothing is changed, use --apply to link)")
out "ldap-relink: realm $KC_REALM, ${#users[@]} user(s), $mode"

for user in "${users[@]}"; do
	out ""
	access_token="$(token)"
	if [[ -z "$access_token" ]]; then
		out "[ERROR] login of client $KC_CLIENT_ID to realm $KC_CLIENT_REALM failed"
		exit 2
	fi
	auth=(-H "Authorization: Bearer $access_token")

	user_id="$user"
	if [[ ! "$user" =~ $uuid_re ]]; then
		found="$(curl "${curl_opts[@]}" "${auth[@]}" "$realm_path/users?exact=true&briefRepresentation=true&username=$(urlencode "$user")")"
		count="$(jq 'length' <<< "$found" 2>/dev/null || echo error)"
		if [[ "$count" != "1" ]]; then
			out "[FAIL] $user: user lookup returned ${count} users: $found"
			failed=1
			continue
		fi
		user_id="$(jq -r '.[0].id' <<< "$found")"
	fi

	if [[ "$apply" == true ]]; then
		response="$(curl "${curl_opts[@]}" "${auth[@]}" -X POST -w '\n%{http_code}' "$realm_path/ldap-relink/users/$user_id/attach$query")"
	else
		response="$(curl "${curl_opts[@]}" "${auth[@]}" -w '\n%{http_code}' "$realm_path/ldap-relink/users/$user_id$query")"
	fi
	status="${response##*$'\n'}"
	body="${response%$'\n'*}"

	if ! jq -e '.problems' >/dev/null 2>&1 <<< "$body"; then
		out "[FAIL] $user ($user_id): HTTP $status $body"
		failed=1
		continue
	fi

	username="$(jq -r '.before.username' <<< "$body")"
	if [[ "$(jq '.problems | length' <<< "$body")" != "0" ]]; then
		out "[FAIL] $username ($user_id): cannot be linked, nothing changed"
		jq -r '.problems[] | "       - " + .' <<< "$body" | while IFS= read -r l; do out "$l"; done
		failed=1
		continue
	fi

	label=$([[ "$(jq -r '.applied' <<< "$body")" == true ]] && echo "[LINKED]" || echo "[CAN LINK]")
	out "$label $username ($user_id) -> $(jq -r '.ldapEntry.dn' <<< "$body") (provider $(jq -r '.provider.name' <<< "$body"))"
	jq -r '
		def show: if . == null then "(empty)" else tostring end;
		.before as $b | .after as $a
		| ["email", "firstName", "lastName", "enabled", "emailVerified"][]
		| select($b[.] != $a[.])
		| "       " + . + ": " + ($b[.] | show) + " -> " + ($a[.] | show)' <<< "$body" | while IFS= read -r l; do out "$l"; done
	jq -r '.before.groups as $b | .after.groups - $b | select(length > 0) | "       groups added: " + join(", ")' <<< "$body" \
		| while IFS= read -r l; do out "$l"; done
done

exit "$failed"
