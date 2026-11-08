package com.ticketflow.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Turns on {@code @Async} and defines the executor for emails. It is bounded on purpose: a slow or dead mail
 * server must not grow memory or threads without limit. When the queue is full the email is dropped and logged
 * (an email is a notification, not a business fact; the order and tickets are safe in the database).
 */
@Slf4j
@Configuration
@EnableAsync
class AsyncConfig {

    static final String MAIL_EXECUTOR = "mailExecutor";

    @Bean(MAIL_EXECUTOR)
    ThreadPoolTaskExecutor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("mail-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("Mail queue is full: an email was dropped"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
