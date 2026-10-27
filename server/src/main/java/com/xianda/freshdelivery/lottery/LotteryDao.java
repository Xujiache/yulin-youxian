package com.xianda.freshdelivery.lottery;

import com.xianda.freshdelivery.lottery.LotteryModels.Campaign;
import com.xianda.freshdelivery.lottery.LotteryModels.Gift;
import com.xianda.freshdelivery.lottery.LotteryModels.Prize;
import com.xianda.freshdelivery.lottery.LotteryModels.Tier;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LotteryDao {
    /** 预算护栏行用 user_id = 0 占位，与真实用户行共用一张表。 */
    public static final long BUDGET_GUARD_USER = 0L;
    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DRAW_COLUMNS = """
            id, campaign_id, tier_id, prize_id, order_id, order_no, user_id, product_amount,
            prize_index, prize_type, prize_name, prize_code, discount_amount, product_id, sku_id, image_url,
            payable_before, payable_amount, status, gift_stock_status, void_reason,
            fulfilled_at, fulfillment_remark, applied_at, paid_at, settled_at, created_at, updated_at
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
            rs.getString("prize_code"),
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
            localDateTime(rs.getTimestamp("applied_at")),
            localDateTime(rs.getTimestamp("paid_at")),
            localDateTime(rs.getTimestamp("settled_at")),
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
        List<Tier> tiers = findTiers(campaign.id());
        List<Prize> prizes = tiers.stream()
                .filter(tier -> "GLOBAL".equals(tier.poolCode()))
                .findFirst()
                .map(tier -> LotteryRules.sortedFixedPrizes(tier.prizes()))
                .orElse(List.of());
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
                tiers,
                prizes
        ));
    }

    private List<Tier> findTiers(long campaignId) {
        List<Tier> tiers = jdbcTemplate.query("""
                SELECT id, name, min_product_amount, max_product_amount, enabled, sort_order, pool_code
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
                    List.of(),
                    rs.getString("pool_code")
            ), campaignId);
        return tiers.stream()
                .map(tier -> new Tier(
                        tier.id(),
                        tier.name(),
                        tier.minProductAmount(),
                        tier.maxProductAmount(),
                        tier.enabled(),
                        tier.sortOrder(),
                        findPrizes(tier.id()),
                        tier.poolCode()
                ))
                .toList();
    }

    private List<Prize> findPrizes(long tierId) {
        return jdbcTemplate.query("""
                SELECT id, type, name, discount_amount, product_id, sku_id, image_url, weight,
                       stock_total, stock_remaining, enabled, sort_order, prize_code, probability_bp,
                       discount_mode, threshold_amount, fixed_discount_amount, discount_rate_bp,
                       max_discount_amount
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
                rs.getInt("sort_order"),
                rs.getString("prize_code"),
                nullableInteger(rs.getObject("probability_bp")),
                rs.getString("discount_mode"),
                nullableInteger(rs.getObject("threshold_amount")),
                nullableInteger(rs.getObject("fixed_discount_amount")),
                nullableInteger(rs.getObject("discount_rate_bp")),
                nullableInteger(rs.getObject("max_discount_amount"))
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
                    timestamp(LocalDateTime.now(STORE_ZONE)),
                    campaignId
            );
        }

        long globalTierId = findOrCreateGlobalTier(campaignId);
        for (Prize prize : LotteryRules.sortedFixedPrizes(request.prizes())) {
            upsertFixedPrize(campaignId, globalTierId, prize);
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

    private long findOrCreateGlobalTier(long campaignId) {
        Long existing = jdbcTemplate.query("""
                SELECT id FROM marketing_lottery_tier
                WHERE campaign_id = ? AND pool_code = 'GLOBAL'
                ORDER BY id
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null, campaignId);
        if (existing != null) {
            jdbcTemplate.update("""
                    UPDATE marketing_lottery_tier
                    SET name = ?, min_product_amount = 0, max_product_amount = NULL,
                        enabled = 1, sort_order = 10, updated_at = ?
                    WHERE id = ?
                    """, "全部订单", timestamp(LocalDateTime.now(STORE_ZONE)), existing);
            return existing;
        }
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO marketing_lottery_tier (
                        campaign_id, name, min_product_amount, max_product_amount, enabled, sort_order, pool_code
                    ) VALUES (?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, campaignId);
            statement.setString(2, "全部订单");
            statement.setInt(3, 0);
            statement.setNull(4, java.sql.Types.INTEGER);
            statement.setInt(5, 1);
            statement.setInt(6, 10);
            statement.setString(7, "GLOBAL");
            return statement;
        }, holder);
        return generatedId(holder);
    }

    private void upsertFixedPrize(long campaignId, long tierId, Prize prize) {
        String prizeCode = LotteryRules.prizeCode(prize.prizeCode()).name();
        Long existingId = jdbcTemplate.query("""
                SELECT id FROM marketing_lottery_prize
                WHERE campaign_id = ? AND prize_code = ?
                ORDER BY id
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null, campaignId, prizeCode);
        if (existingId != null) {
            jdbcTemplate.update("""
                    UPDATE marketing_lottery_prize
                    SET type = ?, name = ?, discount_amount = ?, product_id = NULL, sku_id = NULL,
                        image_url = NULL, weight = ?, stock_total = 0, enabled = 1, sort_order = ?,
                        prize_code = ?, probability_bp = ?, discount_mode = ?, threshold_amount = ?,
                        fixed_discount_amount = ?, discount_rate_bp = ?, max_discount_amount = ?,
                        updated_at = ?
                    WHERE id = ?
                    """,
                    prize.type(),
                    prize.name(),
                    prize.discountAmount(),
                    prize.probabilityBp() == null ? 0 : prize.probabilityBp(),
                    prize.sortOrder(),
                    prizeCode,
                    prize.probabilityBp(),
                    prize.discountMode(),
                    prize.thresholdAmount(),
                    prize.fixedDiscountAmount(),
                    prize.discountRateBp(),
                    prize.maxDiscountAmount(),
                    timestamp(LocalDateTime.now(STORE_ZONE)),
                    existingId
            );
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO marketing_lottery_prize (
                    campaign_id, tier_id, type, name, discount_amount, product_id, sku_id, image_url,
                    weight, stock_total, stock_remaining, enabled, sort_order, prize_code, probability_bp,
                    discount_mode, threshold_amount, fixed_discount_amount, discount_rate_bp, max_discount_amount
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                campaignId,
                tierId,
                prize.type(),
                prize.name(),
                prize.discountAmount(),
                null,
                null,
                null,
                prize.probabilityBp() == null ? 0 : prize.probabilityBp(),
                0,
                0,
                1,
                prize.sortOrder(),
                prizeCode,
                prize.probabilityBp(),
                prize.discountMode(),
                prize.thresholdAmount(),
                prize.fixedDiscountAmount(),
                prize.discountRateBp(),
                prize.maxDiscountAmount()
        );
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
                  AND status IN ('RESERVED','APPLIED','SETTLED')
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

    /**
     * 抽奖统计（当日预算、用户当日次数）在 REPEATABLE READ 下会读到事务开始时的快照，
     * 所以先加锁读一行护栏，再做统计；锁的粒度是「活动 + 日期 + 用户」而不是整个活动。
     */
    public void lockDrawGuard(DrawGuardKey key) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO marketing_lottery_draw_guard (campaign_id, stat_date, user_id, created_at)
                    VALUES (?,?,?,?)
                    """, key.campaignId(), key.statDate(), key.userId(),
                    timestamp(LocalDateTime.now(STORE_ZONE)));
        } catch (DataIntegrityViolationException alreadyCreated) {
            // 并发下由另一个事务插入，下面的加锁读会等它提交。
        }
        jdbcTemplate.queryForList("""
                SELECT campaign_id
                FROM marketing_lottery_draw_guard
                WHERE campaign_id = ? AND stat_date = ? AND user_id = ?
                FOR UPDATE
                """, Long.class, key.campaignId(), key.statDate(), key.userId());
    }

    public int reserveMarketingStock(long prizeId) {
        return jdbcTemplate.update("""
                UPDATE marketing_lottery_prize
                SET stock_remaining = stock_remaining - 1, updated_at = ?
                WHERE id = ? AND enabled = 1 AND stock_remaining > 0
                """, timestamp(LocalDateTime.now(STORE_ZONE)), prizeId);
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
                """, timestamp(LocalDateTime.now(STORE_ZONE)), prizeId);
    }

    public long insertDraw(DrawInsert draw, LocalDateTime now) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO marketing_lottery_draw (
                        campaign_id, tier_id, prize_id, order_id, order_no, user_id, product_amount,
                        prize_index, prize_type, prize_name, prize_code, discount_amount, product_id, sku_id, image_url,
                        payable_before, payable_amount, status, gift_stock_status, created_at, updated_at
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
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
            statement.setString(index++, draw.prizeCode());
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
        return findGiftsByDraws(List.of(drawId)).getOrDefault(drawId, List.of());
    }

    /**
     * 后台流水列表逐条查赠品会退化成 N+1，这里一次查完再按 draw 分组。
     */
    public Map<Long, List<Gift>> findGiftsByDraws(List<Long> drawIds) {
        List<Long> ids = drawIds == null ? List.of() : drawIds.stream().filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<Long, List<Gift>> grouped = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT draw_id, prize_id, product_id, sku_id, product_name, sku_name, image_url,
                       quantity, status
                FROM marketing_lottery_gift
                WHERE draw_id IN (%s)
                ORDER BY draw_id, id
                """.formatted(placeholders), rs -> {
            grouped.computeIfAbsent(rs.getLong("draw_id"), key -> new ArrayList<>()).add(new Gift(
                    rs.getLong("draw_id"),
                    rs.getLong("prize_id"),
                    rs.getLong("product_id"),
                    nullableLong(rs.getObject("sku_id")),
                    rs.getString("product_name"),
                    rs.getString("sku_name"),
                    rs.getString("image_url"),
                    rs.getBigDecimal("quantity"),
                    rs.getString("status")
            ));
        }, ids.toArray());
        return grouped;
    }

    /**
     * 后台审计需要「朋友圈分享菜单触发时间」，抽奖时消费的挑战就是该订单最后一次触发的那条。
     */
    public Map<Long, LocalDateTime> findShareTriggeredAt(List<Long> orderIds) {
        List<Long> ids = orderIds == null ? List.of() : orderIds.stream().filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<Long, LocalDateTime> triggered = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT order_id, MAX(share_triggered_at) AS share_triggered_at
                FROM marketing_lottery_challenge
                WHERE order_id IN (%s) AND share_triggered_at IS NOT NULL
                GROUP BY order_id
                """.formatted(placeholders), rs -> {
            triggered.put(rs.getLong("order_id"), localDateTime(rs.getTimestamp("share_triggered_at")));
        }, ids.toArray());
        return triggered;
    }

    /**
     * challenge 表此前只写不删。已消费或过期足够久的记录没有审计价值，按批清理。
     */
    public int deleteStaleChallenges(LocalDateTime cutoff, int limit) {
        return jdbcTemplate.update("""
                DELETE FROM marketing_lottery_challenge
                WHERE created_at < ?
                  AND (consumed_at IS NOT NULL OR expires_at < ?)
                LIMIT %d
                """.formatted(Math.max(limit, 1)), timestamp(cutoff), timestamp(cutoff));
    }

    public void markDrawApplied(long drawId, LocalDateTime now) {
        jdbcTemplate.update("""
                UPDATE marketing_lottery_draw
                SET status = 'APPLIED', applied_at = COALESCE(applied_at, ?), updated_at = ?
                WHERE id = ? AND status = 'RESERVED'
                """, timestamp(now), timestamp(now), drawId);
    }

    /**
     * 成功抽奖此前永远停在 APPLIED，reconcile 的结果集只增不减。订单已完结（履约或
     * 无需履约）之后置为终态，后续对账不再扫到。
     */
    public int settleDraw(long drawId, LocalDateTime now) {
        return jdbcTemplate.update("""
                UPDATE marketing_lottery_draw
                SET status = 'SETTLED', settled_at = COALESCE(settled_at, ?), updated_at = ?
                WHERE id = ? AND status = 'APPLIED'
                """, timestamp(now), timestamp(now), drawId);
    }

    public int markDrawPaid(long orderId, LocalDateTime paidAt) {
        return jdbcTemplate.update("""
                UPDATE marketing_lottery_draw
                SET paid_at = COALESCE(paid_at, ?), updated_at = ?
                WHERE order_id = ?
                """, timestamp(paidAt), timestamp(paidAt), orderId);
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

    public List<DrawRow> searchDraws(
            String keyword,
            String prizeType,
            String status,
            LocalDateTime createdFrom,
            LocalDateTime createdTo,
            int offset,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("SELECT " + DRAW_COLUMNS + " FROM marketing_lottery_draw WHERE 1=1");
        List<Object> args = appendDrawFilters(sql, keyword, prizeType, status, createdFrom, createdTo);
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(Math.max(limit, 1));
        args.add(Math.max(offset, 0));
        return jdbcTemplate.query(sql.toString(), DRAW_MAPPER, args.toArray());
    }

    public long countDraws(
            String keyword,
            String prizeType,
            String status,
            LocalDateTime createdFrom,
            LocalDateTime createdTo
    ) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM marketing_lottery_draw WHERE 1=1");
        List<Object> args = appendDrawFilters(sql, keyword, prizeType, status, createdFrom, createdTo);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    private List<Object> appendDrawFilters(
            StringBuilder sql,
            String keyword,
            String prizeType,
            String status,
            LocalDateTime createdFrom,
            LocalDateTime createdTo
    ) {
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
        // created_at 与后台展示的「抽奖时间」同源（applied_at 只比它晚毫秒级），
        // 且有 (created_at)、(status, created_at) 两个索引可用。
        if (createdFrom != null) {
            sql.append(" AND created_at >= ?");
            args.add(timestamp(createdFrom));
        }
        if (createdTo != null) {
            // 后台的结束时间取当天 23:59:59，是闭区间。
            sql.append(" AND created_at <= ?");
            args.add(timestamp(createdTo));
        }
        // 后台的 status 既可能是流水状态，也可能是履约状态；分页之后不能再在内存里过滤，
        // 否则会把当页的记录筛掉，所以两种口径都下推到 SQL。这里的每个分支必须与
        // LotteryService#fulfillmentStatus 完全互为反函数，否则下拉里会出现永远为空的选项。
        switch (status == null ? "" : status.trim().toUpperCase()) {
            case "RESERVED", "APPLIED", "VOIDED", "SETTLED" -> {
                sql.append(" AND status = ?");
                args.add(status.trim().toUpperCase());
            }
            case "PENDING" -> sql.append(" AND status <> 'VOIDED' AND prize_type = 'GOODS'"
                    + " AND gift_stock_status NOT IN ('FULFILLED','RELEASED')");
            case "FULFILLED" -> sql.append(
                    " AND status <> 'VOIDED' AND prize_type = 'GOODS' AND gift_stock_status = 'FULFILLED'");
            case "RELEASED" -> sql.append(
                    " AND status <> 'VOIDED' AND prize_type = 'GOODS' AND gift_stock_status = 'RELEASED'");
            case "NOT_REQUIRED" -> sql.append(" AND status <> 'VOIDED' AND prize_type <> 'GOODS'");
            default -> {
                // 空值或未知过滤条件不生效，保持向后兼容。
            }
        }
        return args;
    }

    /**
     * 对账只看时间窗内的在途流水，并且一轮最多处理 limit 条；终态流水不会再被扫到。
     */
    public List<DrawRow> findDrawsForReconcile(String status, LocalDateTime createdAfter, int limit) {
        return jdbcTemplate.query(
                "SELECT " + DRAW_COLUMNS + """
                         FROM marketing_lottery_draw
                         WHERE status = ? AND created_at >= ?
                         ORDER BY id
                         LIMIT ?
                        """,
                DRAW_MAPPER,
                status,
                timestamp(createdAfter),
                Math.max(limit, 1)
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
            Gift gift,
            String prizeCode
    ) {
        public DrawInsert(
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
            this(
                    campaignId,
                    tierId,
                    prizeId,
                    orderId,
                    orderNo,
                    userId,
                    productAmount,
                    prizeIndex,
                    prizeType,
                    prizeName,
                    discountAmount,
                    productId,
                    skuId,
                    imageUrl,
                    payableBefore,
                    payableAmount,
                    gift,
                    null
            );
        }
    }

    public record DrawGuardKey(
            long campaignId,
            LocalDate statDate,
            long userId
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
            String prizeCode,
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
            LocalDateTime appliedAt,
            LocalDateTime paidAt,
            LocalDateTime settledAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
