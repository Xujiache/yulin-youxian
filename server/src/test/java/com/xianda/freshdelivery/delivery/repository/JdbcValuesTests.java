package com.xianda.freshdelivery.delivery.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.GeneratedKeyHolder;

class JdbcValuesTests {

    @Test
    void readsIdWhenDriverAlsoReturnsCreatedAt() {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("ID", 42L);
        keys.put("CREATED_AT", Timestamp.valueOf(LocalDateTime.now()));

        GeneratedKeyHolder holder = new GeneratedKeyHolder(List.of(keys));

        assertEquals(42L, JdbcValues.generatedId(holder));
    }

    @Test
    void supportsSingleUnnamedMysqlGeneratedKey() {
        GeneratedKeyHolder holder = new GeneratedKeyHolder(
                List.of(Map.of("GENERATED_KEY", 73L))
        );

        assertEquals(73L, JdbcValues.generatedId(holder));
    }
}
