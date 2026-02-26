package com.mysite.sbb.mail;

public record MailJob(
        String jobId,
        String type,   // SIGNUP_VERIFY | PASSWORD_RESET
        String email,
        int retry,
        long createdAt
) {
}