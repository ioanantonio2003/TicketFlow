package com.ticketflow.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Sends plain-text emails on a bounded background executor, so a slow SMTP server never delays a request. A
 * failure is logged and swallowed: the business operation has already been committed and must not be undone by
 * a mail problem. (Durable redelivery arrives with Kafka, Steps 18-19.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
class EmailService {

    private final JavaMailSender mailSender;
    private final NotificationProperties properties;

    @Async("mailExecutor")
    void send(EmailMessage message) {
        if (!properties.enabled()) {
            log.debug("Notifications are disabled: not sending '{}'", message.subject());
            return;
        }
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(properties.from());
            mail.setTo(message.to());
            mail.setSubject(message.subject());
            mail.setText(message.body());
            mailSender.send(mail);
            log.info("Sent email '{}'", message.subject()); // the address is personal data: not logged
        } catch (MailException e) {
            log.warn("Could not send email '{}': {}", message.subject(), e.getMessage());
        }
    }
}
