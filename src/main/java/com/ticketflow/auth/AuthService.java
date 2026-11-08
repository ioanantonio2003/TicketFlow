package com.ticketflow.auth;

import com.ticketflow.auth.dto.AuthResponse;
import com.ticketflow.auth.dto.LoginRequest;
import com.ticketflow.auth.dto.RegisterRequest;
import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.user.UserService;
import com.ticketflow.user.dto.UserResponse;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class AuthService {

    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;

    /** No @Transactional here: UserService owns the transaction, and hashing must not hold one open. */
    UserResponse register(RegisterRequest request) {
        // @Size counts characters, BCrypt counts bytes: multi-byte characters can exceed the limit
        if (request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new BusinessRuleException("Password is too long.");
        }
        String hash = passwordEncoder.encode(request.password());
        return userService.createCustomer(request.email(), hash, request.displayName());
    }

    /**
     * A wrong password, an unknown email and a disabled account all end in an AuthenticationException,
     * which the exception handler turns into the same generic 401.
     */
    AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();

        UserResponse user = userService.findById(principal.id());
        TokenService.AccessToken token = tokenService.createAccessToken(user);
        return new AuthResponse(token.value(), "Bearer", token.expiresInSeconds(), user);
    }
}
