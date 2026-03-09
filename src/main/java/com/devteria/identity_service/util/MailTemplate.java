package com.devteria.identity_service.util;

public enum MailTemplate {
    RESET_PASSWORD_REQUEST("reset-password-request", "Reset your password");

    private final String template;
    private final String subject;

    MailTemplate(String template, String subject) {
        this.template = template;
        this.subject = subject;
    }

    public String getTemplate() {
        return template;
    }

    public String getSubject() {
        return subject;
    }
}
