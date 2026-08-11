package com.xianda.freshdelivery.delivery.repository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class DeliveryTestDatabase {
    public static final List<String> MIGRATIONS = List.of(
            "V2__create_rider_account.sql",
            "V3__create_delivery_task.sql",
            "V4__create_delivery_tracking.sql",
            "V5__create_delivery_exception.sql",
            "V6__create_delivery_settlement.sql",
            "V7__create_delivery_routing.sql",
            "V8__create_delivery_support.sql",
            "V9__add_amap_config.sql",
            "V10__create_marketing_lottery.sql",
            "V11__harden_delivery_integrity.sql"
    );

    private DeliveryTestDatabase() {
    }

    public static JdbcTemplate create(String databaseName) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        JdbcTemplate jdbcTemplate = new JdbcTemplate((DataSource) dataSource);
        jdbcTemplate.execute("DROP ALL OBJECTS");
        for (String migration : MIGRATIONS) {
            for (String statement : statementsOf(migration)) {
                jdbcTemplate.execute(statement);
            }
        }
        return jdbcTemplate;
    }

    public static List<String> statementsOf(String migration) {
        return List.of(toH2Dialect(read(migration)).split(";")).stream()
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .toList();
    }

    static String read(String migration) {
        try (InputStream input = DeliveryTestDatabase.class.getResourceAsStream("/db/migration/" + migration)) {
            if (input == null) {
                throw new IllegalStateException("迁移文件不存在: " + migration);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("迁移文件读取失败: " + migration, exception);
        }
    }

    static String toH2Dialect(String sql) {
        String result = sql;
        // V11 uses MySQL JSON_EXTRACT to backfill legacy idempotency fields.
        // H2 has no SQL/JSON path implementation, so its compatibility suite
        // validates the resulting columns/indexes while real MySQL validates the backfill.
        result = result.replaceAll(
                "(?s)-- MYSQL_JSON_BACKFILL_BEGIN.*?-- MYSQL_JSON_BACKFILL_END",
                ""
        );
        result = result.replaceAll("(?s)COMMENT\\s+'[^']*'", "");
        result = result.replaceAll("(?i)COLLATE\\s+utf8mb4_bin", "");
        result = result.replaceAll("(?i)ENGINE=InnoDB\\s+DEFAULT\\s+CHARSET=utf8mb4\\s+COLLATE=utf8mb4_unicode_ci", "");
        result = result.replaceAll("(?i)ON UPDATE CURRENT_TIMESTAMP\\(6\\)", "");
        result = result.replaceAll("(?i)TINYINT\\(1\\)", "TINYINT");
        result = result.replaceAll("(?i)\\bDATETIME\\(6\\)", "TIMESTAMP(6)");
        result = result.replaceAll("\\bJSON\\b", "LONGTEXT");
        result = result.replaceAll("\\bMEDIUMTEXT\\b", "LONGTEXT");
        result = result.replaceAll("(?im)^\\s*UNIQUE KEY\\s+(\\w+)\\s*\\(([^)]*)\\)", "    CONSTRAINT $1 UNIQUE ($2)");
        result = result.replaceAll("(?im)^\\s*KEY\\s+\\w+\\s*\\([^)]*\\),?[ \\t]*$", "");
        result = result.replaceAll("(?s),(\\s*)\\)", "$1)");
        return result;
    }
}
