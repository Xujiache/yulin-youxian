package com.xianda.freshdelivery.delivery.tracking;

import com.xianda.freshdelivery.service.BackupService;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class BackupServiceStatus implements TrackingBackupPort {
    private final ObjectProvider<BackupService> backupServiceProvider;

    public BackupServiceStatus(ObjectProvider<BackupService> backupServiceProvider) {
        this.backupServiceProvider = backupServiceProvider;
    }

    @Override
    public Optional<BackupInfo> lastBackup() {
        BackupService backupService = backupServiceProvider.getIfAvailable();
        if (backupService == null) {
            return Optional.empty();
        }
        try {
            return backupService.listBackups().stream()
                    .map(BackupService.BackupMetadata::createdAt)
                    .filter(createdAt -> createdAt != null && !createdAt.isBlank())
                    .max(Comparator.naturalOrder())
                    .map(createdAt -> new BackupInfo(createdAt, true));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }
}
