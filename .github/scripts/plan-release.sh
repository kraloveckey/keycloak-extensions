#!/usr/bin/env bash
# Decides whether a release has to be built and for which Keycloak version.
#
# Inputs (environment):
#   KEYCLOAK_VERSION  optional, build for this Keycloak version instead of the latest stable release
#   GH_TOKEN          optional, raises the GitHub API rate limit
#   MAVEN_REPO        optional, Maven repository to check for Keycloak artifacts (default: Maven Central)
#   GITHUB_EVENT_NAME set by GitHub Actions; scheduled runs skip versions with an open failure issue
#   GITHUB_OUTPUT     set by GitHub Actions; outputs are also printed
#
# Outputs: build (true|false), reason, keycloak, plugin, tag, latest (true|false)
set -euo pipefail

MAVEN_REPO="${MAVEN_REPO:-https://repo1.maven.org/maven2}"
SEMVER='^[0-9]+\.[0-9]+\.[0-9]+$'

out() {
	echo "$1=$2"
	if [[ -n "${GITHUB_OUTPUT:-}" ]]; then echo "$1=$2" >> "$GITHUB_OUTPUT"; fi
}

skip() {
	out build false
	out reason "$1"
	exit 0
}

latest_keycloak() {
	local tag=""
	local auth=()
	if [[ -n "${GH_TOKEN:-}" ]]; then auth=(-H "Authorization: Bearer ${GH_TOKEN}"); fi
	tag=$(curl -fsSL "${auth[@]}" https://api.github.com/repos/keycloak/keycloak/releases/latest 2>/dev/null \
		| python3 -c 'import json, sys; print(json.load(sys.stdin)["tag_name"])' 2>/dev/null) || true
	if [[ -z "$tag" ]]; then
		# no API (rate limit): the web UI redirects /releases/latest to /releases/tag/<tag>
		tag=$(curl -fsSI https://github.com/keycloak/keycloak/releases/latest \
			| tr -d '\r' | sed -n 's|^[Ll]ocation: .*/releases/tag/||p')
	fi
	echo "$tag"
}

plugin=$(python3 -c '
import xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
print(ET.parse("pom.xml").getroot().find("m:version", ns).text.strip())')
out plugin "$plugin"
if [[ "$plugin" == *-SNAPSHOT ]]; then
	skip "plugin version $plugin is a snapshot"
fi

latest=$(latest_keycloak)
if [[ ! "$latest" =~ $SEMVER ]]; then
	echo "cannot determine the latest Keycloak release (got '$latest')" >&2
	exit 1
fi

keycloak="${KEYCLOAK_VERSION:-$latest}"
if [[ ! "$keycloak" =~ $SEMVER ]]; then
	echo "invalid Keycloak version '$keycloak', expected X.Y.Z" >&2
	exit 1
fi
out keycloak "$keycloak"
out latest "$([[ "$keycloak" == "$latest" ]] && echo true || echo false)"

tag="v${plugin}-kc${keycloak}"
out tag "$tag"

if git ls-remote --exit-code --tags origin "refs/tags/${tag}" >/dev/null 2>&1; then
	skip "release $tag already exists"
fi

# A failed build for this Keycloak version is waiting for a fix; the daily run does not retry it, a push does.
if [[ "${GITHUB_EVENT_NAME:-}" == "schedule" ]] && command -v gh >/dev/null; then
	title="Release for Keycloak ${keycloak} failed"
	open_issue=$(gh issue list --state open --search "in:title \"${title}\"" --json title \
		--jq ".[] | select(.title == \"${title}\") | .title" 2>/dev/null || true)
	if [[ -n "$open_issue" ]]; then
		skip "open issue '${title}', waiting for a fix to be pushed"
	fi
fi

# Keycloak's Maven artifacts can appear a few hours after the GitHub release
if ! curl -fsI "${MAVEN_REPO}/org/keycloak/keycloak-services/${keycloak}/keycloak-services-${keycloak}.pom" >/dev/null; then
	skip "org.keycloak:keycloak-services:${keycloak} is not on Maven Central yet"
fi

out build true
out reason "building $tag"
