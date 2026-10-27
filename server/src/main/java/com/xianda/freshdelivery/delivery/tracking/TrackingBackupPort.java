package com.xianda.freshdelivery.delivery.tracking;

import java.util.Optional;

public interface TrackingBackupPort {

    Optional<BackupInfo> lastBackup();

    record BackupInfo(String createdAt, boolean deliveryTablesIncluded) {
    }
}
