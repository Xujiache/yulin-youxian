package com.xianda.freshdelivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.dto.AddressDto;
import com.xianda.freshdelivery.service.DeliveryAddressIntelligence;
import org.junit.jupiter.api.Test;

class DeliveryAddressIntelligenceTests {
    @Test
    void equivalentBuildingSpellingsUseSameGroup() {
        DeliveryAddressIntelligence.Profile first = profile("幸福里小区", "3号楼 2单元 1201室");
        DeliveryAddressIntelligence.Profile second = profile("幸福里小区", "三栋 1单元 603室");

        assertEquals(first.groupKey(), second.groupKey());
        assertEquals("3号楼", first.buildingLabel());
        assertEquals("3号楼", second.buildingLabel());
    }

    @Test
    void fullWidthDigitsAreNormalizedAndBuildingsSortNaturally() {
        DeliveryAddressIntelligence.Profile buildingTwo = profile("幸福里小区", "２号楼 １０单元 １００２室");
        DeliveryAddressIntelligence.Profile buildingTen = profile("幸福里小区", "10号楼 2单元 201室");

        assertEquals("2号楼", buildingTwo.buildingLabel());
        assertTrue(buildingTwo.buildingSortKey().compareTo(buildingTen.buildingSortKey()) < 0);
        assertTrue(buildingTen.unitSortKey().compareTo(buildingTwo.unitSortKey()) < 0);
    }

    @Test
    void addressesWithoutBuildingOnlyGroupWhenFullAddressMatches() {
        DeliveryAddressIntelligence.Profile first = profile("万泉河路", "北门东侧商铺");
        DeliveryAddressIntelligence.Profile same = profile("万泉河路", "北门东侧商铺");
        DeliveryAddressIntelligence.Profile different = profile("万泉河路", "南门东侧商铺");

        assertEquals(first.groupKey(), same.groupKey());
        assertNotEquals(first.groupKey(), different.groupKey());
        assertEquals("同一地址", first.buildingLabel());
    }

    private DeliveryAddressIntelligence.Profile profile(String location, String detail) {
        return DeliveryAddressIntelligence.analyze(new AddressDto(1L, "测试用户", "13800000000", detail, location, 43.8, 87.6, true));
    }
}
