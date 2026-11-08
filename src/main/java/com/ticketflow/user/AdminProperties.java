package com.ticketflow.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Credentials of the first admin; both optional. When blank, no admin is created. */
@ConfigurationProperties("app.admin")
record AdminProperties(String email, String password) {

    boolean isConfigured() {
        return email != null && !email.isBlank() && password != null && !password.isBlank();
    }

    @Override
    public String toString() {
        return "AdminProperties[configured=" + isConfigured() + "]"; // never print the password
    }
}
