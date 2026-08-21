package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.domain.BuildingHandoffStat;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface HandoffStatDao {
    Optional<BuildingHandoffStat> find(String groupKey, String floorBucket);

    List<BuildingHandoffStat> findByGroup(String groupKey);

    List<HandoffSample> findSamples(LocalDateTime since);

    Map<String, Integer> countAccessDeniedByGroup(LocalDateTime since);

    void upsertStat(BuildingHandoffStat stat);

    void applyAccessDifficulty(String groupKey, int difficulty);

    void bumpAccessDifficulty(String groupKey, int cap);

    record HandoffSample(
            String groupKey,
            String areaLabel,
            String buildingLabel,
            Integer floorNo,
            int handoffSeconds,
            LocalDateTime deliveredAt
    ) {
    }
}
