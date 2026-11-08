package com.ticketflow.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the first admin at startup when {@code app.admin.*} is set and no admin exists yet. */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(AdminProperties.class)
class AdminBootstrap implements ApplicationRunner {

    private final AdminProperties properties;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured() || userService.adminExists()) {
            return;
        }
        userService.createAdmin(properties.email(), passwordEncoder.encode(properties.password()), "Administrator");
        log.info("Created the initial admin user");
    }
}
