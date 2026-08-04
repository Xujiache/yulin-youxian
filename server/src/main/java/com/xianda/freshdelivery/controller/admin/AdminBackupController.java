package com.xianda.freshdelivery.controller.admin;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.service.BackupService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/backups")
public class AdminBackupController {
    private final BackupService backupService;

    public AdminBackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping
    public ApiResponse<List<BackupService.BackupMetadata>> list() {
        return ApiResponse.ok(backupService.listBackups());
    }

    @PostMapping
    public ApiResponse<BackupService.BackupMetadata> create() {
        return ApiResponse.ok(backupService.createManualBackup());
    }

    @PostMapping("/{fileName}/restore")
    public ApiResponse<BackupService.RestoreResult> restore(@PathVariable String fileName) {
        return ApiResponse.ok(backupService.restoreBackup(fileName));
    }
}
