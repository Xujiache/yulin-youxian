package com.xianda.freshdelivery.delivery.tracking;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TrackingPorts {
    private final TrackingConfigPort config;
    private final TrackingNotifyPort notify;
    private final TrackingPrivacyNumberPort privacyNumber;
    private final TrackingRatingPort rating;

    @Autowired
    public TrackingPorts(
            JdbcTemplate jdbcTemplate,
            ObjectProvider<TrackingConfigPort> configProvider,
            ObjectProvider<TrackingNotifyPort> notifyProvider,
            ObjectProvider<TrackingPrivacyNumberPort> privacyNumberProvider,
            ObjectProvider<TrackingRatingPort> ratingProvider
    ) {
        this.config = configProvider.getIfAvailable(() -> new JdbcTrackingConfig(jdbcTemplate));
        this.notify = notifyProvider.getIfAvailable(() -> new JdbcTrackingNotify(jdbcTemplate));
        this.privacyNumber = privacyNumberProvider.getIfAvailable(() -> new JdbcTrackingPrivacyNumber(jdbcTemplate));
        this.rating = ratingProvider.getIfAvailable();
    }

    public TrackingPorts(TrackingConfigPort config, TrackingNotifyPort notify, TrackingPrivacyNumberPort privacyNumber) {
        this(config, notify, privacyNumber, null);
    }

    public TrackingPorts(
            TrackingConfigPort config,
            TrackingNotifyPort notify,
            TrackingPrivacyNumberPort privacyNumber,
            TrackingRatingPort rating
    ) {
        this.config = config;
        this.notify = notify;
        this.privacyNumber = privacyNumber;
        this.rating = rating;
    }

    public TrackingConfigPort config() {
        return config;
    }

    public TrackingNotifyPort notifier() {
        return notify;
    }

    public TrackingPrivacyNumberPort privacyNumber() {
        return privacyNumber;
    }

    public TrackingRatingPort rating() {
        return rating;
    }
}
