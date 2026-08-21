package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.domain.BuildingHandoffStat;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class BuildingHandoffLearner {
    public static final int LOOKBACK_DAYS = 90;

    private static final Logger log = LoggerFactory.getLogger(BuildingHandoffLearner.class);

    private final HandoffStatDao handoffStatDao;
    private final Clock clock;

    @Autowired
    public BuildingHandoffLearner(HandoffStatDao handoffStatDao) {
        this(handoffStatDao, RoutingTimes.systemClock());
    }

    public BuildingHandoffLearner(HandoffStatDao handoffStatDao, Clock clock) {
        this.handoffStatDao = handoffStatDao;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Shanghai")
    public void aggregateDaily() {
        try {
            int buckets = aggregate(RoutingTimes.now(clock));
            log.info("楼栋交付时长聚合完成，写入 {} 个分桶", buckets);
        } catch (RuntimeException ex) {
            log.error("楼栋交付时长聚合失败", ex);
        }
    }

    public int aggregate(LocalDateTime now) {
        LocalDateTime since = now.minusDays(LOOKBACK_DAYS);
        List<HandoffStatDao.HandoffSample> samples = handoffStatDao.findSamples(since);
        Map<BucketKey, Accumulator> accumulators = new LinkedHashMap<>();
        for (HandoffStatDao.HandoffSample sample : samples) {
            if (sample.groupKey() == null || sample.groupKey().isBlank() || sample.handoffSeconds() <= 0) {
                continue;
            }
            accumulate(accumulators, new BucketKey(sample.groupKey(), FloorBucket.of(sample.floorNo())), sample);
            accumulate(accumulators, new BucketKey(sample.groupKey(), FloorBucket.ALL), sample);
        }
        for (Map.Entry<BucketKey, Accumulator> entry : accumulators.entrySet()) {
            handoffStatDao.upsertStat(entry.getValue().toStat(entry.getKey()));
        }
        refreshAccessDifficulty(since);
        return accumulators.size();
    }

    public void recordAccessDenied(String groupKey) {
        if (groupKey == null || groupKey.isBlank()) {
            return;
        }
        handoffStatDao.bumpAccessDifficulty(groupKey, HandoffEstimator.MAX_ACCESS_DIFFICULTY);
    }

    private void refreshAccessDifficulty(LocalDateTime since) {
        Map<String, Integer> counts = handoffStatDao.countAccessDeniedByGroup(since);
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            int difficulty = Math.min(HandoffEstimator.MAX_ACCESS_DIFFICULTY, Math.max(0, entry.getValue()));
            handoffStatDao.applyAccessDifficulty(entry.getKey(), difficulty);
        }
    }

    private void accumulate(Map<BucketKey, Accumulator> accumulators, BucketKey key,
                            HandoffStatDao.HandoffSample sample) {
        accumulators.computeIfAbsent(key, ignored -> new Accumulator()).add(sample);
    }

    public static int percentile(List<Integer> sortedValues, double quantile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(quantile * sortedValues.size());
        int index = Math.min(sortedValues.size() - 1, Math.max(0, rank - 1));
        return sortedValues.get(index);
    }

    private record BucketKey(String groupKey, String floorBucket) {
    }

    private static final class Accumulator {
        private final List<Integer> values = new ArrayList<>();
        private String areaLabel;
        private String buildingLabel;
        private LocalDateTime lastSampleAt;

        private void add(HandoffStatDao.HandoffSample sample) {
            values.add(sample.handoffSeconds());
            if (sample.areaLabel() != null) {
                areaLabel = sample.areaLabel();
            }
            if (sample.buildingLabel() != null) {
                buildingLabel = sample.buildingLabel();
            }
            if (sample.deliveredAt() != null && (lastSampleAt == null || sample.deliveredAt().isAfter(lastSampleAt))) {
                lastSampleAt = sample.deliveredAt();
            }
        }

        private BuildingHandoffStat toStat(BucketKey key) {
            Collections.sort(values);
            long total = 0L;
            for (int value : values) {
                total += value;
            }
            int average = (int) Math.round((double) total / values.size());
            return new BuildingHandoffStat(
                    null,
                    key.groupKey(),
                    areaLabel,
                    buildingLabel,
                    key.floorBucket(),
                    values.size(),
                    average,
                    percentile(values, 0.70d),
                    percentile(values, 0.90d),
                    null,
                    0,
                    lastSampleAt,
                    null,
                    null);
        }
    }
}
