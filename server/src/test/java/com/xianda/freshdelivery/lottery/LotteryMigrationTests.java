package com.xianda.freshdelivery.lottery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.xianda.freshdelivery.delivery.repository.DeliveryTestDatabase;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LotteryMigrationTests {
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setUp() {
        jdbcTemplate = DeliveryTestDatabase.create("lottery_migration");
    }

    @Test
    void v10CreatesLotteryRelationsAndDeliveryProjectionColumns() {
        List<String> tables = List.of(
                "marketing_lottery_campaign",
                "marketing_lottery_tier",
                "marketing_lottery_prize",
                "marketing_lottery_challenge",
                "marketing_lottery_draw",
                "marketing_lottery_gift",
                "marketing_lottery_order_decision"
        );
        for (String table : tables) {
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables"
                            + " WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) = ?",
                    Integer.class,
                    table
            ));
        }
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns"
                        + " WHERE LOWER(table_name) = 'delivery_task'"
                        + " AND LOWER(column_name) = 'marketing_discount_amount'",
                Integer.class
        ));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns"
                        + " WHERE LOWER(table_name) = 'delivery_task'"
                        + " AND LOWER(column_name) = 'marketing_gift_summary'",
                Integer.class
        ));
    }
}
