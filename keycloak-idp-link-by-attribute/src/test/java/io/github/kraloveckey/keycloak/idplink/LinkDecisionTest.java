package io.github.kraloveckey.keycloak.idplink;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.kraloveckey.keycloak.idplink.LinkDecision.Candidate;
import io.github.kraloveckey.keycloak.idplink.LinkDecision.Result;

public class LinkDecisionTest {

	private static final Candidate OK = new Candidate("u1", true, false);

	@Test
	void exactlyOneEnabledUnlinkedUserIsLinked() {
		assertEquals(Result.LINK, LinkDecision.decide(List.of(OK), true, true));
		assertEquals(Result.LINK, LinkDecision.decide(List.of(OK), null, true));
	}

	@Test
	void noneOrSeveral() {
		assertEquals(Result.NOT_FOUND, LinkDecision.decide(List.of(), true, true));
		assertEquals(Result.CONFLICT, LinkDecision.decide(List.of(OK, new Candidate("u2", true, false)), true, true));
		// a conflict wins even if one of them would be unusable anyway
		assertEquals(Result.CONFLICT, LinkDecision.decide(List.of(OK, new Candidate("u2", false, true)), true, true));
	}

	@Test
	void disabledAndAlreadyLinked() {
		assertEquals(Result.DISABLED, LinkDecision.decide(List.of(new Candidate("u1", false, false)), true, true));
		assertEquals(Result.ALREADY_LINKED, LinkDecision.decide(List.of(new Candidate("u1", true, true)), true, true));
		assertEquals(Result.DISABLED, LinkDecision.decide(List.of(new Candidate("u1", false, true)), true, true));
	}

	@Test
	void unverifiedEmail() {
		assertEquals(Result.EMAIL_NOT_VERIFIED, LinkDecision.decide(List.of(OK), false, true));
		assertEquals(Result.LINK, LinkDecision.decide(List.of(OK), false, false));
	}

	@Test
	void everyFailureHasAnErrorCodeAndAMessage() {
		for (Result r : Result.values()) {
			if (r != Result.LINK) {
				assertEquals(true, r.error != null && r.message != null && r.message.startsWith("idpAttr"), r.name());
			}
		}
	}
}
