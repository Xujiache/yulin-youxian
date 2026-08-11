package com.xianda.freshdelivery.service;

import com.xianda.freshdelivery.backup.BackupFaultInjector;
import com.xianda.freshdelivery.backup.BackupMaintenanceMode;
import com.xianda.freshdelivery.backup.SafeBackupEngine;
import com.xianda.freshdelivery.backup.SessionRevocationGuard;
import com.xianda.freshdelivery.persistence.StateChangedEvent;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class BackupService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackupService.class);

    private final SafeBackupEngine engine;
    private final boolean realtimeEnabled;
    private final long realtimeDebounceMs;
    private final ScheduledExecutorService realtimeExecutor;
    private final AtomicBoolean realtimeScheduled = new AtomicBoolean();

    public BackupService(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            StorefrontService storefrontService,
            AuthService authService,
            PrintJobService printJobService,
            BackupMaintenanceMode maintenanceMode,
            SessionRevocationGuard sessionRevocationGuard,
            BackupFaultInjector faultInjector,
            @Value("${backup.directory:data/backups}") String backupDirectory,
            @Value("${backup.data-directory:data}") String dataDirectory,
            @Value("${delivery.upload.delivery-path:data/uploads/delivery}") String deliveryUploadDirectory,
            @Value("${persistence.mode:mysql}") String persistenceMode,
            @Value("${backup.retention-count:30}") int retentionCount,
            @Value("${backup.realtime-enabled:true}") boolean realtimeEnabled,
            @Value("${backup.realtime-debounce-ms:1500}") long realtimeDebounceMs,
            @Value("${storefront.storage-path:data/storefront-state.json}") String storefrontStoragePath,
            @Value("${auth.profile-storage-path:data/user-profiles.json}") String profileStoragePath,
            @Value("${printing.storage-path:data/printing-state.json}") String printingStoragePath
    ) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider == null ? null : jdbcTemplateProvider.getIfAvailable();
        this.engine = new SafeBackupEngine(
                jdbcTemplate,
                storefrontService,
                authService,
                printJobService,
                maintenanceMode,
                sessionRevocationGuard,
                faultInjector,
                backupDirectory,
                dataDirectory,
                deliveryUploadDirectory,
                persistenceMode,
                retentionCount,
                storefrontStoragePath,
                profileStoragePath,
                printingStoragePath
        );
        this.realtimeEnabled = realtimeEnabled;
        this.realtimeDebounceMs = Math.max(250, realtimeDebounceMs);
        this.realtimeExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fresh-backup-realtime");
            thread.setDaemon(true);
            return thread;
        });
    }

    public BackupMetadata createManualBackup() {
        return engine.createManualBackup();
    }

    public List<BackupMetadata> listBackups() {
        return engine.listBackups();
    }

    public RestoreResult restoreBackup(String fileName) {
        return engine.restore(fileName);
    }

    public RecoveryStatus recoveryStatus() {
        return engine.recoveryStatus();
    }

    @EventListener
    public void onStateChanged(StateChangedEvent event) {
        if (!realtimeEnabled || !realtimeScheduled.compareAndSet(false, true)) {
            return;
        }
        realtimeExecutor.schedule(() -> {
            try {
                engine.createAutomaticBackup("REALTIME");
            } catch (RuntimeException exception) {
                LOGGER.error("实时备份失败，stateKey={}", event.stateKey(), exception);
            } finally {
                realtimeScheduled.set(false);
            }
        }, realtimeDebounceMs, TimeUnit.MILLISECONDS);
    }

    @Scheduled(
            fixedDelayString = "${backup.scheduled-interval-ms:300000}",
            initialDelayString = "${backup.scheduled-initial-delay-ms:30000}"
    )
    public void scheduledBackup() {
        try {
            engine.createAutomaticBackup("SCHEDULED");
        } catch (RuntimeException exception) {
            LOGGER.error("定时备份失败", exception);
        }
    }

    @PreDestroy
    public void shutdown() {
        realtimeExecutor.shutdownNow();
    }

    public record BackupMetadata(String fileName, String type, String createdAt, long sizeBytes, String sha256) {
    }

    public record RestoreResult(BackupMetadata restoredBackup, BackupMetadata preRestoreBackup) {
    }

    public record RecoveryStatus(
            boolean maintenanceActive,
            boolean failClosed,
            String journalPath,
            String phase,
            String instructions
    ) {
    }
}
