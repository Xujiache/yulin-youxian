package com.xianda.freshdelivery.service;

import com.luciad.imageio.webp.CompressionType;
import com.luciad.imageio.webp.WebPWriteParam;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Generates a small, immutable WebP representation next to a managed upload.
 * A thumbnail failure must never make an otherwise valid upload unusable.
 */
@Service
public class ImageVariantService {
    private static final Logger log = LoggerFactory.getLogger(ImageVariantService.class);
    private static final int MAX_EDGE = 480;
    private static final float QUALITY = 0.78f;

    public Path generate(Path source) {
        if (source == null || !Files.isRegularFile(source)) {
            return null;
        }
        Path target = thumbnailPath(source);
        if (Files.isRegularFile(target)) {
            return target;
        }
        try {
            BufferedImage input = ImageIO.read(source.toFile());
            if (input == null) {
                log.warn("无法解码图片，跳过缩略图: {}", source);
                return null;
            }
            BufferedImage scaled = scale(input);
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".thumbnail-", ".webp");
            try {
                writeWebp(scaled, temporary);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                Files.deleteIfExists(temporary);
                throw exception;
            }
            return target;
        } catch (Exception exception) {
            log.warn("生成缩略图失败，原图将继续使用: {}", source, exception);
            return null;
        }
    }

    public Path thumbnailPath(Path source) {
        return source.getParent().resolve("thumbnails").resolve(source.getFileName().toString() + ".webp");
    }

    private BufferedImage scale(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        double ratio = Math.min(1d, (double) MAX_EDGE / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * ratio));
        int targetHeight = Math.max(1, (int) Math.round(height * ratio));
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, type);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private void writeWebp(BufferedImage image, Path output) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/webp");
        if (!writers.hasNext()) {
            throw new IOException("WebP ImageIO writer unavailable");
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(output.toFile())) {
            WebPWriteParam parameter = (WebPWriteParam) writer.getDefaultWriteParam();
            parameter.setCompressionType(CompressionType.Lossy);
            parameter.setCompressionQuality(QUALITY);
            parameter.setUseSharpYUV(true);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), parameter);
        } finally {
            writer.dispose();
        }
    }
}
