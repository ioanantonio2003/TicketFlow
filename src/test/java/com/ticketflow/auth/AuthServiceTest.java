package com.ticketflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.auth.dto.AuthResponse;
import com.ticketflow.auth.dto.LoginRequest;
import com.ticketflow.auth.dto.RegisterRequest;
import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.user.Role;
import com.ticketflow.user.UserService;
import com.ticketflow.user.dto.UserResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserService userService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private TokenService tokenService;

    @InjectMocks
    private AuthService authService;

    @Test
    void registerStoresTheHashNeverThePlainPassword() {
        when(passwordEncoder.encode("plain-password")).thenReturn("HASH");
        UserResponse created = new UserResponse(1L, "ana@example.com", "Ana", Role.CUSTOMER);
        when(userService.createCustomer("Ana@Example.com ", "HASH", "Ana")).thenReturn(created);

        UserResponse result = authService.register(new RegisterRequest("Ana@Example.com ", "plain-password", "Ana"));

        assertThat(result.role()).isEqualTo(Role.CUSTOMER);
        verify(userService).createCustomer(any(), eq("HASH"), any());
        verify(userService, never()).createCustomer(any(), eq("plain-password"), any());
    }

    @Test
    void registerPropagatesDuplicateEmailAsConflict() {
        when(passwordEncoder.encode(any())).thenReturn("HASH");
        when(userService.createCustomer(any(), any(), any())).thenThrow(new ConflictException("Email is already registered."));

        assertThatThrownBy(() -> authService.register(new RegisterRequest("ana@example.com", "plain-password", "Ana")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void registerRejectsPasswordsOverBcryptLimitInBytes() {
        // 40 characters, 80 bytes in UTF-8 (each "é" takes 2 bytes): passes @Size(max = 72) but not BCrypt
        String longPassword = "é".repeat(40);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("ana@example.com", longPassword, "Ana")))
                .isInstanceOf(BusinessRuleException.class);
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void loginReturnsBearerTokenAndUser() {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "ana@example.com", "HASH", Role.CUSTOMER, true);
        when(authenticationManager.authenticate(any()))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        UserResponse user = new UserResponse(7L, "ana@example.com", "Ana", Role.CUSTOMER);
        when(userService.findById(7L)).thenReturn(user);
        when(tokenService.createAccessToken(user)).thenReturn(new TokenService.AccessToken("jwt", 900));

        AuthResponse response = authService.login(new LoginRequest("ana@example.com", "plain-password"));

        assertThat(response.accessToken()).isEqualTo("jwt");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(response.user()).isEqualTo(user);
    }

    @Test
    void loginDoesNotCatchAuthenticationFailures() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("ana@example.com", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
        verify(tokenService, never()).createAccessToken(any());
    }
}
