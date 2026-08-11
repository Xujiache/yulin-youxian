package com.xianda.freshdelivery.lottery;

import com.xianda.freshdelivery.lottery.LotteryModels.Campaign;
import com.xianda.freshdelivery.lottery.LotteryModels.Gift;
import com.xianda.freshdelivery.lottery.LotteryModels.Prize;
import com.xianda.freshdelivery.lottery.LotteryModels.Tier;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LotteryDao {
    private static final String DRAW_COLUMNS = """
            id, campaign_id, tier_id, prize_id, order_id, order_no, user_id, product_amount,
            prize_index, prize_type, prize_name, discount_amount, product_id, sku_id, image_url,
            payable_before, payable_amount, status, gift_stock_status, void_reason,
            fulfilled_at, fulfillment_remark, created_at, updated_at
            """;

    private static final RowMapper<ChallengeRow> CHALLENGE_MAPPER = (rs, rowNum) -> new ChallengeRow(
            rs.getLong("id"),
            rs.getLong("campaign_id"),
            rs.getLong("order_id"),
            rs.getLong("user_id"),
            rs.getString("challenge_token"),
            localDateTime(rs.getTimestamp("expires_at")),
            localDateTime(rs.getTimestamp("share_triggered_at")),
            localDateTime(rs.getTimestamp("consumed_at")),
            localDateTime(rs.getTimestamp("created_at"))
    );

    private static final RowMapper<DrawRow> DRAW_MAPPER = (rs, rowNum) -> new DrawRow(
            rs.getLong("id"),
            rs.getLong("campaign_id"),
            rs.getLong("tier_id"),
            rs.getLong("prize_id"),
            rs.getLong("order_id"),
            rs.getString("order_no"),
            rs.getLong("user_id"),
            rs.getInt("product_amount"),
            rs.getInt("prize_index"),
            rs.getString("prize_type"),
            rs.getString("prize_name"),
            rs.getInt("discount_amount"),
            nullableLong(rs.getObject("product_id")),
            nullableLong(rs.getObject("sku_id")),
            rs.getString("image_url"),
            rs.getInt("payable_before"),
            rs.getInt("payable_amount"),
            rs.getString("status"),
            rs.getString("gift_stock_status"),
            rs.getString("void_reason"),
            localDateTime(rs.getTimestamp("fulfilled_at")),
            rs.getString("fulfillment_remark"),
            localDateTime(rs.getTimestamp("created_at")),
            localDateTime(rs.getTimestamp("updated_at"))
    );

    private final JdbcTemplate jdbcTemplate;

    public LotteryDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Campaign> findCampaign() {
        Campaign campaign = jdbcTemplate.query("""
                SELECT id, enabled, name, start_at, end_at, daily_user_limit, daily_budget_amount,
                       share_title, share_description, share_image_url
                FROM marketing_lottery_campaign
                ORDER BY id
                LIMIT 1
                """, rs -> {
            if (!rs.next()) {
                return null;
            }
            return new Campaign(
                    rs.getLong("id"),
                    rs.getBoolean("enabled"),
                    rs.getString("name"),
                    format(localDateTime(rs.getTimestamp("start_at"))),
                    format(localDateTime(rs.getTimestamp("end_at"))),
                    rs.getInt("daily_user_limit"),
                    nullableInteger(rs.getObject("daily_budget_amount")),
                    rs.getString("share_title"),
                    rs.getString("share_description"),
                    rs.getString("share_image_url"),
                    List.of()
            );
        });
        if (campaign == null) {
            return Optional.empty();
        }
        return Optional.of(new Campaign(
                campaign.id(),
                campaign.enabled(),
                campaign.name(),
                campaign.startAt(),
                campaign.endAt(),
                campaign.dailyUserLimit(),
                campaign.dailyBudgetAmount(),
                campaign.shareTitle(),
                campaign.shareDescription(),
                campaign.shareImageUrl(),
                findTiers(campaign.id())
        ));
    }

    private List<Tier> findTiers(long campaignId) {
        List<Tier> tiers = jdbcTemplate.query("""
                SELECT id, name, min_product_amount, max_product_amount, enabled, sort_order
                FROM marketing_lottery_tier
                WHERE campaign_id = ?
                ORDER BY sort_order, min_product_amount, id
                """, (rs, rowNum) -> new Tier(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getInt("min_product_amount"),
                    nullableInteger(rs.getObject("max_product_amount")),
                    rs.getBoolean("enabled"),
                    rs.getInt("sort_order"),
                    List.of()
            ), campaignId);
        return tiers.stream()
                .map(tier -> new Tier(
                        tier.id(),
                        tier.name(),
                        tier.minProductAmount(),
                        tier.maxProductAmount(),
                        tier.enabled(),
                        tier.sortOrder(),
                        findPrizes(tier.id())
                ))
                .toList();
    }

    private List<Prize> findPrizes(long tierId) {
        return jdbcTemplate.query("""
                SELECT id, type, name, discount_amount, product_id, sku_id, image_url, weight,
                       stock_total, stock_remaining, enabled, sort_order
                FROM marketing_lottery_prize
                WHERE tier_id = ?
                ORDER BY sort_order, id
                """, (rs, rowNum) -> new Prize(
                rs.getLong("id"),
                rs.getString("type"),
                rs.getString("name"),
                nullableInteger(rs.getObject("discount_amount")),
                nullableLong(rs.getObject("product_id")),
                nullableLong(rs.getObject("sku_id")),
                rs.getString("image_url"),
                rs.getInt("weight"),
                rs.getInt("stock_total"),
                rs.getInt("stock_remaining"),
                rs.getBoolean("enabled"),
                rs.getInt("sort_order")
        ), tierId);
    }

    @Transactional
    public Campaign saveCampaign(Campaign request) {
        Long currentId = jdbcTemplate.query("""
                SELECT id FROM marketing_lottery_campaign ORDER BY id LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null);
        if (currentId != null) {
            lockCampaign(currentId);
        }

        Map<Long, StockState> previousStocks = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT id, stock_total, stock_remaining
                FROM marketing_lottery_prize
                """, rs -> {
            previousStocks.put(
                    rs.getLong("id"),
                    new StockState(rs.getInt("stock_total"), rs.getInt("stock_remaining"))
            );
        });

        long campaignId;
        if (currentId == null) {
            campaignId = insertCampaign(request);
        } else {
            campaignId = currentId;
            jdbcTemplate.update("""
                    UPDATE marketing_lottery_campaign
                    SET enabled = ?, name = ?, start_at = ?, end_at = ?, daily_user_limit = ?,
                        daily_budget_amount = ?, share_title = ?, share_description = ?, share_image_url = ?,
                        updated_at = ?
                    WHERE id = ?
                    """,
                    flag(request.enabled()),
                    request.name(),
                    timestamp(LotteryRules.parseTime(request.startAt(), "活动开始时间")),
                    timestamp(LotteryRules.parseTime(request.endAt(), "活动结束时间")),
                    request.dailyUserLimit(),
                    request.dailyBudgetAmount(),
                    request.shareTitle(),
                    request.shareDescription(),
                    request.shareImageUrl(),
                    timestamp(LocalDateTime.now()),
                    campaignId
            );
        }

        jdbcTemplate.update("DELETE FROM marketing_lottery_prize WHERE campaign_id = ?", campaignId);
        jdbcTemplate.update("DELETE FROM marketing_lottery_tier WHERE campaign_id = ?", campaignId);
        for (Tier tier : request.tiers()) {
            long tierId = insertTier(campaignId, tier);
            for (Prize prize : tier.prizes()) {
                int stockTotal = prize.stockTotal() == null ? 0 : prize.stockTotal();
                StockState old = prize.id() == null ? null : previousStocks.get(prize.id());
                int consumed = old == null ? 0 : Math.max(old.total() - old.remaining(), 0);
                int remaining = "GOODS".equalsIgnoreCase(prize.type())
                        ? old == null
                                ? Math.min(prize.stockRemaining() == null ? stockTotal : prize.stockRemaining(), stockTotal)
                                : Math.max(stockTotal - consumed, 0)
                        : 0;
                insertPrize(campaignId, tierId, prize, stockTotal, remaining);
            }
        }
        return findCampaign().orElseThrow();
    }

    private long insertCampaign(Campaign request) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO marketing_lottery_campaign (
                        enabled, name, start_at, end_at, daily_user_limit, daily_budget_amount,
                        share_title, share_description, share_image_url
                    ) VALUES (?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setInt(1, flag(request.enabled()));
            statement.setString(2, request.name());
            statement.setTimestamp(3, timestamp(LotteryRules.parseTime(request.startAt(), "活动开始时间")));
            statement.setTimestamp(4, timestamp(LotteryRules.parseTime(request.endAt(), "活动结束时间")));
            statement.setInt(5, request.dailyUserLimit());
            setNullableInteger(statement, 6, request.dailyBudgetAmount());
            statement.setString(7, request.shareTitle());
            statement.setString(8, request.shareDescription());
            statement.setString(9, request.shareImageUrl());
            return statement;
        }, holder);
        return generatedId(holder);
    }

    private long insertTier(long campaignId, Tier tier) {
        if (tier.id() != null && tier.id() > 0) {
            jdbcTemplate.update("""
                    INSERT INTO marketing_lottery_tier (
                        id, campaign_id, name, min_product_amount, max_product_amount, enabled, sort_order
                    ) VALUES (?,?,?,?,?,?,?)
                    """,
                    tier.id(), campaignId, tier.name(), tier.minProductAmount(), tier.maxProductAmount(),
                    flag(tier.enabled()), tier.sortOrder()
            );
            return tier.id();
        }
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO marketing_lottery_tier (
                        campaign_id, name, min_product_amount, max_product_amount, enabled, sort_order
                    ) VALUES (?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, campaignId);
            statement.setString(2, tier.name());
            statement.setInt(3, tier.minProductAmount());
            setNullableInteger(statement, 4, tier.maxProductAmount());
            statement.setInt(5, flag(tier.enabled()));
            statement.setInt(6, tier.sortOrder());
            return statement;
        }, holder);
        return generatedId(holder);
    }

    private void insertPrize(long campaignId, long tierId, Prize prize, int stockTotal, int stockRemaining) {
        List<Object> args = new ArrayList<>();
        String idColumn = "";
        String idPlaceholder = "";
        if (prize.id() != null && prize.id() > 0) {
            idColumn = "id, ";
            idPlaceholder = "?,";
            args.add(prize.id());
        }
        args.add(campaignId);
        args.add(tierId);
        args.add(prize.type().trim().toUpperCase());
        args.add(prize.name());
        args.add(prize.discountAmount());
        args.add(prize.productId());
        args.add(prize.skuId());
        args.add(prize.imageUrl());
        args.add(prize.weight());
        args.add(stockTotal);
        args.add(stockRemaining);
        args.add(flag(prize.enabled()));
        args.add(prize.sortOrder());
        jdbcTemplate.update("""
                INSERT INTO marketing_lottery_prize (
                    %scampaign_id, tier_id, type, name, discount_amount, product_id, sku_id, image_url,
                    weight, stock_total, stock_remaining, enabled, sort_order
                ) VALUES (%s?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.formatted(idColumn, idPlaceholder), args.toArray());
    }

    public Optional<ChallengeRow> findActiveChallenge(
            long campaignId,
            long orderId,
            long userId,
            LocalDateTime now
    ) {
        return jdbcTemplate.query("""
                SELECT id, campaign_id, order_id, user_id, challenge_token, expires_at,
                       share_triggered_at, consumed_at, created_at
                FROM marketing_lottery_challenge
                WHERE campaign_id = ? AND order_id = ? AND user_id = ?
                  AND consumed_at IS NULL AND expires_at > ?
                ORDER BY id DESC
                LIMIT 1
                """, CHALLENGE_MAPPER, campaignId, orderId, userId, timestamp(now))
                .stream().findFirst();
    }

    public Optional<ChallengeRow> findChallenge(String token, long orderId, long userId) {
        return jdbcTemplate.query("""
                SELECT id, campaign_id, order_id, user_id, challenge_token, expires_at,
                       share_triggered_at, consumed_at, created_at
                FROM marketing_lottery_challenge
                WHERE challenge_token = ? AND order_id = ? AND user_id = ?
                """, CHALLENGE_MAPPER, token, orderId, userId)
                .stream().findFirst();
    }

    public ChallengeRow createChallenge(
            long campaignId,
            long orderId,
            long userId,
            String token,
            LocalDateTime now,
            LocalDateTime expiresAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO marketing_lottery_challenge (
                    campaign_id, order_id, user_id, challenge_token, expires_at, created_at
                ) VALUES (?,?,?,?,?,?)
                """,
                campaignId, orderId, userId, token, timestamp(expiresAt), timestamp(now)
        );
        return findChallenge(token, orderId, userId).orElseThrow();
    }

    public int markShareTriggered(long challengeId, LocalDateTime now) {
        return jdbcTemplate.update("""
                UPDATE marketing_lottery_challenge
                SET share_triggered_at = COALESCE(share_triggered_at, ?)
                WHERE id = ? AND consumed_at IS NULL AND expires_at > ?
                """, timestamp(now), challengeId, timestamp(now));
    }

    public void consumeChallenge(long challengeId, LocalDateTime now) {
        jdbcTemplate.update("""
                UPDATE marketing_lottery_challenge
                SET consumed_at = COALESCE(consumed_at, ?)
                WHERE id = ?
                """, timestamp(now), challengeId);
    }

    public Optional<DrawRow> findDrawByOrder(long orderId) {
        return jdbcTemplate.query(
                "SELECT " + DRAW_COLUMNS + " FROM marketing_lottery_draw WHERE order_id = ?",
                DRAW_MAPPER,
                orderId
        ).stream().findFirst();
    }

    public Optional<DrawRow> findDraw(long drawId) {
        return jdbcTemplate.query(
                "SELECT " + DRAW_COLUMNS + " FROM marketing_lottery_draw WHERE id = ?",
                DRAW_MAPPER,
                drawId
        ).stream().findFirst();
    }

    public int countUserDraws(long campaignId, long userId, LocalDateTime start, LocalDateTime end) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM marketing_lottery_draw
                WHERE campaign_id = ? AND user_id = ? AND created_at >= ? AND created_at < ?
                """, Integer.class, campaignId, userId, timestamp(start), timestamp(end));
        return count == null ? 0 : count;
    }

    public int sumDailyDiscount(long campaignId, LocalDateTime start, LocalDateTime end) {
        Integer amount = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(discount_amount), 0)
                FROM marketing_lottery_draw
                WHERE campaign_id = ? AND created_at >= ? AND created_at < ?
                  AND status IN ('RESERVED','APPLIED')
                """, Integer.class, campaignId, timestamp(start), timestamp(end));
        return amount == null ? 0 : amount;
    }

    public void lockCampaign(long campaignId) {
        jdbcTemplate.queryForObject(
                "SELECT id FROM marketing_lottery_campaign WHERE id = ? FOR UPDATE",
                Long.class,
                campaignId
        );
    }

    public int reserveMarketingStock(long prizeId) {
        return jdbcTemplate.update("""
                UPDATE marketing_lottery_prize
                SET stock_remaining = stock_remaining - 1, updated_at = ?
                WHERE id = ? AND enabled = 1 AND stock_remaining > 0
                """, timestamp(LocalDateTime.now()), prizeId);
    }

    public void restoreMarketingStock(long prizeId) {
        jdbcTemplate.update("""
                UPDATE marketing_lottery_prize
                SET stock_remaining = CASE
                        WHEN stock_remaining < stock_total THEN stock_remaining + 1
                        ELSE stock_remaining
                    END,
                    updated_at = ?
                WHERE id = ?
                """, timestamp(LocalDateTime.now()), prizeId);
    }

    public long insertDraw(DrawInsert draw, LocalDateTime now) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO marketing_lottery_draw (
                        campaign_id, tier_id, prize_id, order_id, order_no, user_id, product_amount,
                        prize_index, prize_type, prize_name, discount_amount, product_id, sku_id, image_url,
                        payable_before, payable_amount, status, gift_stock_status, created_at, updated_at
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int index = 1;
            statement.setLong(index++, draw.campaignId());
            statement.setLong(index++, draw.tierId());
            statement.setLong(index++, draw.prizeId());
            statement.setLong(index++, draw.orderId());
            statement.setString(index++, draw.orderNo());
            statement.setLong(index++, draw.userId());
            statement.setInt(index++, draw.productAmount());
            statement.setInt(index++, draw.prizeIndex());
            statement.setString(index++, draw.prizeType());
            statement.setString(index++, draw.prizeName());
            statement.setInt(index++, draw.discountAmount());
            setNullableLong(statement, index++, draw.productId());
            setNullableLong(statement, index++, draw.skuId());
            statement.setString(index++, draw.imageUrl());
            statement.setInt(index++, draw.payableBefore());
            statement.setInt(index++, draw.payableAmount());
            statement.setString(index++, "RESERVED");
            statement.setString(index++, draw.gift() == null ? "NONE" : "RESERVED");
            statement.setTimestamp(index++, timestamp(now));
            statement.setTimestamp(index, timestamp(now));
            return statement;
        }, holder);
        return generatedId(holder);
    }

    public void insertGift(long drawId, long prizeId, long orderId, Gift gift, LocalDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO marketing_lottery_gift (
                    draw_id, prize_id, order_id, product_id, sku_id, product_name, sku_name,
                    image_url, quantity, status, created_at, updated_at
                ) VALUES (?,?,?,?,?,?,?,?,?,'RESERVED',?,?)
                """,
                drawId,
                prizeId,
                orderId,
                gift.productId(),
                gift.skuId(),
                gift.productName(),
                gift.skuName(),
                gift.imageUrl(),
                gift.quantity(),
                timestamp(now),
                timestamp(now)
        );
    }

    public List<Gift> findGifts(long drawId) {
        return jdbcTemplate.query("""
                SELECT draw_id, prize_id, product_id, sku_id, product_name, sku_name, image_url,
                       quantity, status
                FROM marketing_lottery_gift
                WHERE draw_id = ?
                ORDER BY id
                """, (rs, rowNum) -> new Gift(
                rs.getLong("draw_id"),
                rs.getLong("prize_id"),
                rs.getLong("product_id"),
                nullableLong(rs.getObject("sku_id")),
                rs.getString("product_name"),
                rs.getString("sku_name"),
                rs.getString("image_url"),
                rs.getBigDecimal("quantity"),
                rs.getString("status")
        ), drawId);
    }

    public void markDrawApplied(long drawId, LocalDateTime now) {
        jdbcTemplate.update("""
                UPDATE marketing_lottery_draw
                SET status = 'APPLIED', updated_at = ?
                WHERE id = ? AND status = 'RESERVED'
                """, timestamp(now), drawId);
    }

    public void saveDecision(
            long orderId,
            long userId,
            long campaignId,
            String decision,
            Long drawId,
            LocalDateTime now
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE marketing_lottery_order_decision
                SET user_id = ?, campaign_id = ?, decision = ?, draw_id = ?, locked_at = ?
                WHERE order_id = ?
                """, userId, campaignId, decision, drawId, timestamp(now), orderId);
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO marketing_lottery_order_decision (
                        order_id, user_id, campaign_id, decision, draw_id, locked_at
                    ) VALUES (?,?,?,?,?,?)
                    """, orderId, userId, campaignId, decision, drawId, timestamp(now));
        }
    }

    public Optional<DecisionRow> findDecision(long orderId) {
        return jdbcTemplate.query("""
                SELECT order_id, user_id, campaign_id, decision, draw_id, locked_at
                FROM marketing_lottery_order_decision
                WHERE order_id = ?
                """, (rs, rowNum) -> new DecisionRow(
                rs.getLong("order_id"),
                rs.getLong("user_id"),
                rs.getLong("campaign_id"),
                rs.getString("decision"),
                nullableLong(rs.getObject("draw_id")),
                localDateTime(rs.getTimestamp("locked_at"))
        ), orderId).stream().findFirst();
    }

    public void releaseGift(long drawId, LocalDateTime now) {
        jdbcTemplate.update("""
                UPDATE marketing_lottery_gift
                SET status = 'RELEASED', updated_at = ?
                WHERE draw_id = ? AND status = 'RESERVED'
                """, timestamp(now), drawId);
        jdbcTemplate.update("""
                UPDATE marketing_lottery_draw
                SET gift_stock_status = 'RELEASED', updated_at = ?
                WHERE id = ? AND gift_stock_status = 'RESERVED'
                """, timestamp(now), drawId);
    }

    public void reserveReleasedGift(long drawId, LocalDateTime now) {
        jdbcTemplate.update("""
                UPDATE marketing_lottery_gift
                SET status = 'RESERVED', updated_at = ?
                WHERE draw_id = ? AND status = 'RELEASED'
                """, timestamp(now), drawId);
        jdbcTemplate.update("""
                UPDATE marketing_lottery_draw
                SET gift_stock_status = 'RESERVED', updated_at = ?
                WHERE id = ? AND gift_stock_status = 'RELEASED'
                """, timestamp(now), drawId);
    }

    public void voidDraw(long drawId, String reason, LocalDateTime now) {
        jdbcTemplate.update("""
                UPDATE marketing_lottery_draw
                SET status = 'VOIDED', void_reason = ?, updated_at = ?
                WHERE id = ? AND status <> 'VOIDED'
                """, reason, timestamp(now), drawId);
    }

    public void fulfillDraw(long drawId, String remark, LocalDateTime now) {
        jdbcTemplate.update("""
                UPDATE marketing_lottery_gift
                SET status = 'FULFILLED', updated_at = ?
                WHERE draw_id = ? AND status = 'RESERVED'
                """, timestamp(now), drawId);
        jdbcTemplate.update("""
                UPDATE marketing_lottery_draw
                SET gift_stock_status = CASE
                        WHEN gift_stock_status = 'RESERVED' THEN 'FULFILLED'
                        ELSE gift_stock_status
                    END,
                    fulfilled_at = COALESCE(fulfilled_at, ?),
                    fulfillment_remark = ?,
                    updated_at = ?
                WHERE id = ? AND status = 'APPLIED'
                """, timestamp(now), remark, timestamp(now), drawId);
    }

    public List<DrawRow> searchDraws(String keyword, String prizeType, String status) {
        StringBuilder sql = new StringBuilder("SELECT " + DRAW_COLUMNS + " FROM marketing_lottery_draw WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append("""
                     AND (
                         order_no LIKE ? OR prize_name LIKE ? OR CAST(id AS CHAR) LIKE ?
                         OR CAST(order_id AS CHAR) LIKE ? OR CAST(user_id AS CHAR) LIKE ?
                     )
                    """);
            String pattern = "%" + keyword.trim() + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        if (prizeType != null && !prizeType.isBlank()) {
            sql.append(" AND prize_type = ?");
            args.add(prizeType.trim().toUpperCase());
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase());
        }
        sql.append(" ORDER BY id DESC");
        return jdbcTemplate.query(sql.toString(), DRAW_MAPPER, args.toArray());
    }

    public List<DrawRow> findDrawsWithStatus(String status) {
        return jdbcTemplate.query(
                "SELECT " + DRAW_COLUMNS + " FROM marketing_lottery_draw WHERE status = ? ORDER BY id",
                DRAW_MAPPER,
                status
        );
    }

    private static int flag(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }

    private static long generatedId(KeyHolder holder) {
        if (holder.getKeyList().isEmpty()) {
            throw new IllegalStateException("数据库未返回自增主键");
        }
        Map<String, Object> keys = holder.getKeyList().get(0);
        Object id = keys.entrySet().stream()
                .filter(entry -> "id".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseGet(() -> keys.values().stream()
                        .filter(Number.class::isInstance)
                        .findFirst()
                        .orElse(null));
        if (!(id instanceof Number number)) {
            throw new IllegalStateException("数据库未返回自增主键");
        }
        return number.longValue();
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static String format(LocalDateTime value) {
        return value == null ? null : value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Integer nullableInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static void setNullableInteger(PreparedStatement statement, int index, Integer value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private record StockState(int total, int remaining) {
    }

    public record ChallengeRow(
            long id,
            long campaignId,
            long orderId,
            long userId,
            String token,
            LocalDateTime expiresAt,
            LocalDateTime shareTriggeredAt,
            LocalDateTime consumedAt,
            LocalDateTime createdAt
    ) {
    }

    public record DecisionRow(
            long orderId,
            long userId,
            long campaignId,
            String decision,
            Long drawId,
            LocalDateTime lockedAt
    ) {
    }

    public record DrawInsert(
            long campaignId,
            long tierId,
            long prizeId,
            long orderId,
            String orderNo,
            long userId,
            int productAmount,
            int prizeIndex,
            String prizeType,
            String prizeName,
            int discountAmount,
            Long productId,
            Long skuId,
            String imageUrl,
            int payableBefore,
            int payableAmount,
            Gift gift
    ) {
    }

    public record DrawRow(
            long id,
            long campaignId,
            long tierId,
            long prizeId,
            long orderId,
            String orderNo,
            long userId,
            int productAmount,
            int prizeIndex,
            String prizeType,
            String prizeName,
            int discountAmount,
            Long productId,
            Long skuId,
            String imageUrl,
            int payableBefore,
            int payableAmount,
            String status,
            String giftStockStatus,
            String voidReason,
            LocalDateTime fulfilledAt,
            String fulfillmentRemark,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
