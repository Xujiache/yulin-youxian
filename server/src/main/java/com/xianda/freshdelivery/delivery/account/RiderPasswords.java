package com.xianda.freshdelivery.delivery.account;

import com.xianda.freshdelivery.delivery.common.DeliveryException;
import java.security.SecureRandom;

public final class RiderPasswords {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String LETTERS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGITS = "23456789";
    private static final int GENERATED_LENGTH = 10;
    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 32;

    private RiderPasswords() {
    }

    public static String generate() {
        StringBuilder builder = new StringBuilder(GENERATED_LENGTH);
        builder.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        builder.append(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        String alphabet = LETTERS + DIGITS;
        for (int index = 2; index < GENERATED_LENGTH; index++) {
            builder.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        for (int index = builder.length() - 1; index > 0; index--) {
            int target = RANDOM.nextInt(index + 1);
            char temp = builder.charAt(index);
            builder.setCharAt(index, builder.charAt(target));
            builder.setCharAt(target, temp);
        }
        return builder.toString();
    }

    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw new DeliveryException(400, "密码需 8-32 位");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char character : password.toCharArray()) {
            if (Character.isLetter(character)) {
                hasLetter = true;
            } else if (Character.isDigit(character)) {
                hasDigit = true;
            }
            if (Character.isWhitespace(character)) {
                throw new DeliveryException(400, "密码不能包含空格");
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new DeliveryException(400, "密码需同时包含字母和数字");
        }
    }
}
