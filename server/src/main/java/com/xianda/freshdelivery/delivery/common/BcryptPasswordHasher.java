package com.xianda.freshdelivery.delivery.common;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class BcryptPasswordHasher implements PasswordHasher {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String hashedPassword) {
        if (rawPassword == null || hashedPassword == null || hashedPassword.isBlank()) {
            return false;
        }
        return encoder.matches(rawPassword, hashedPassword);
    }
}
