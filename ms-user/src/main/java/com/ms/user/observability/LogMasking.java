package com.ms.user.observability;

/**
 * Mascara dados pessoais antes de irem para o log (LGPD — ver estratégia de segurança).
 * Ex.: "maria.silva@gmail.com" -> "m***@gmail.com".
 */
public final class LogMasking {

    private LogMasking() {
    }

    public static String email(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
