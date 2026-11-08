package com.ticketflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class AuthenticatedAuditorAwareTest {

    private final AuthenticatedAuditorAware auditorAware = new AuthenticatedAuditorAware();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void emptyWhenNobodyIsAuthenticated() {
        assertThat(auditorAware.getCurrentAuditor()).isEmpty();
    }

    @Test
    void emptyForAnonymous() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(auditorAware.getCurrentAuditor()).isEmpty();
    }

    @Test
    void userIdForAuthenticatedUser() {
        TestingAuthenticationToken token = new TestingAuthenticationToken("42", null, List.of());
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);

        assertThat(auditorAware.getCurrentAuditor()).contains(42L);
    }

    @Test
    void emptyWhenNameIsNotAUserId() {
        TestingAuthenticationToken token = new TestingAuthenticationToken("batch-job", null, List.of());
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);

        assertThat(auditorAware.getCurrentAuditor()).isEmpty();
    }
}
