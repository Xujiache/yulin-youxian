package com.xianda.freshdelivery.service;

import com.xianda.freshdelivery.backup.BackupMaintenanceMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Gradually upgrades existing uploads without competing with backup/restore work. */
@Component
public class ImageThumbnailBackfillJob {
    private static final Logger log = LoggerFactory.getLogger(ImageThumbnailBackfillJob.class);
    private static final List<String> EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp");

    private final ImageVariantService imageVariantService;
    private final BackupMaintenanceMode maintenanceMode;
    private final List<Path> roots;
    private final AtomicLong generated = new AtomicLong();

    public ImageThumbnailBackfillJob(ImageVariantService imageVariantService,
                                     BackupMaintenanceMode maintenanceMode,
                                     @Value("${backup.data-directory:data}") String dataDirectory,
                                     @Value("${delivery.upload.delivery-path:data/uploads/delivery}") String deliveryDirectory) {
        this.imageVariantService = imageVariantService;
        this.maintenanceMode = maintenanceMode;
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        Path data = resolve(workingDirectory, dataDirectory);
        this.roots = List.of(data.resolve("uploads"), resolve(workingDirectory, deliveryDirectory));
    }

    @Scheduled(fixedDelay = 10_000L, initialDelay = 60_000L)
    public void generateOneMissingThumbnail() {
        if (maintenanceMode.state().active()) return;
        for (Path root : roots) {
            Path source = nextMissing(root);
            if (source == null) continue;
            if (imageVariantService.generate(source) != null) {
                long count = generated.incrementAndGet();
                if (count % 25 == 0) log.info("历史图片缩略图已补齐 {} 张", count);
            }
            return;
        }
    }

    private Path nextMissing(Path root) {
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> paths = Files.walk(root, 8)) {
            return paths.filter(Files::isRegularFile)
                    .filter(this::isImage)
                    .filter(path -> !path.toString().contains("/thumbnails/"))
                    .filter(path -> !Files.exists(imageVariantService.thumbnailPath(path)))
                    .findFirst().orElse(null);
        } catch (IOException exception) {
            log.warn("扫描历史缩略图失败: {}", root, exception);
            return null;
        }
    }

    private boolean isImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private Path resolve(Path workingDirectory, String configured) {
        Path path = Path.of(configured);
        return (path.isAbsolute() ? path : workingDirectory.resolve(path)).normalize().toAbsolutePath();
    }
}
