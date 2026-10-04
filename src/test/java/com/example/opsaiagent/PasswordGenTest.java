package com.example.opsaiagent;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordGenTest {

    @Test
    void genPasswords() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        System.out.println("admin123    -> " + encoder.encode("admin123"));
        System.out.println("operator123 -> " + encoder.encode("operator123"));
        System.out.println("viewer123   -> " + encoder.encode("viewer123"));
    }
}