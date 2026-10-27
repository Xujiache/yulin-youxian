package com.xianda.freshdelivery;

import com.xianda.freshdelivery.service.ImageVariantService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class ImageVariantServiceTests {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesSmallWebpForLargeTransparentPng() throws Exception {
        BufferedImage original = new BufferedImage(1600, 800, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = original.createGraphics();
        graphics.setColor(new Color(20, 180, 90, 150));
        graphics.fillRect(0, 0, 1600, 800);
        graphics.dispose();
        Path source = temporaryDirectory.resolve("source.png");
        ImageIO.write(original, "png", source.toFile());

        Path thumbnail = new ImageVariantService().generate(source);

        assertThat(thumbnail).isNotNull();
        assertThat(thumbnail).exists();
        BufferedImage decoded = ImageIO.read(thumbnail.toFile());
        assertThat(decoded).isNotNull();
        assertThat(Math.max(decoded.getWidth(), decoded.getHeight())).isLessThanOrEqualTo(480);
    }

    @Test
    void leavesUnreadableFilesWithoutThumbnail() throws Exception {
        Path source = temporaryDirectory.resolve("broken.jpg");
        Files.writeString(source, "not an image");

        assertThat(new ImageVariantService().generate(source)).isNull();
        assertThat(Files.exists(temporaryDirectory.resolve("thumbnails/broken.jpg.webp"))).isFalse();
    }
}
