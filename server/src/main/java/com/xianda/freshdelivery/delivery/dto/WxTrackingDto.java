package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record WxTrackingDto(
        Boolean hasDelivery,
        String taskStatus,
        String taskStatusText,
        List<TimelineNodeDto> timeline,
        TrackingRiderDto rider,
        GeoPointDto destination,
        StoreDto store,
        EtaDto eta,
        Integer distanceMeters,
        Integer stopsAhead,
        PollingDto polling,
        List<String> subscribeTemplateIds,
        RatingDto rating,
        String notice,
        /** 送达凭证照片。只在送达后返回，未送达时为空列表。 */
        List<String> deliveryPhotos,
        /** 发车后最近 2–3 分钟清洗轨迹。未发车、终态或关闭短轨迹时为 null。 */
        List<TrailPointDto> recentTrail,
        /** 到本单的剩余路线几何。不含前序顾客姓名或地址。 */
        RemainingRouteDto remainingRoute
) {
    public record TimelineNodeDto(
            String code,
            String label,
            String at,
            Boolean done
    ) {}

    public record TrackingRiderDto(
            String name,
            String avatarUrl,
            String vehicleType,
            Double ratingStar,
            String callNumber,
            Boolean phoneDegraded,
            GeoPointDto location,
            Double bearing,
            String locatedAt,
            Boolean locationFresh
    ) {}

    public record StoreDto(
            Double lat,
            Double lng,
            String name
    ) {}

    public record EtaDto(
            String displayText,
            String lowerAt,
            String upperAt,
            Integer remainingSeconds,
            Boolean isRange,
            String source,
            String updatedAt
    ) {}

    public record TrailPointDto(
            Double lat,
            Double lng,
            String at
    ) {}

    public record RemainingRouteDto(
            String polyline,
            Integer distanceMeters,
            List<GeoPointDto> points
    ) {}

    public record PollingDto(
            Integer intervalSeconds,
            Boolean stopWhenDone
    ) {}

    /** 顾客已提交的配送评价；未评价时整体为 null，小程序据此继续显示「去评价」 */
    public record RatingDto(
            Boolean rated,
            Integer star,
            List<String> tags,
            String comment,
            String createdAt
    ) {}
}
