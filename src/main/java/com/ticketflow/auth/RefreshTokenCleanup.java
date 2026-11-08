package com.ticketflow.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class RefreshTokenCleanup {

    private final RefreshTokenService refreshTokenService;

    /** Every day at 03:00 server time. */
    @Scheduled(cron = "0 0 3 * * *")
    void deleteExpiredTokens() {
        int deleted = refreshTokenService.deleteExpired();
        log.info("Deleted {} expired refresh token(s)", deleted);
    }
}
