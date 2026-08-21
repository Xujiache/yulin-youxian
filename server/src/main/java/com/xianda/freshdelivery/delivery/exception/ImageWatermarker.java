package com.xianda.freshdelivery.delivery.exception;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ImageWatermarker {
    private static final Logger log = LoggerFactory.getLogger(ImageWatermarker.class);
    private static final float BACKGROUND_ALPHA = 0.45f;
    private static final float TEXT_ALPHA = 0.92f;
    private static final int MARGIN = 16;
    private static final int PADDING = 10;
    private static final int MIN_FONT_SIZE = 14;
    private static final int MAX_FONT_SIZE = 34;

    public Result apply(byte[] source, String format, List<String> lines) {
        List<String> texts = lines == null ? List.of() : lines.stream()
                .filter(line -> line != null && !line.isBlank())
                .toList();
        if (!ImageFormats.supportedByImageIo(format) || texts.isEmpty()) {
            return new Result(source, null, null, false);
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null) {
                return new Result(source, null, null, false);
            }
            BufferedImage canvas = toDrawable(image, format);
            draw(canvas, texts);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            String writerFormat = ImageFormats.PNG.equals(format) ? "png" : "jpg";
            if (!ImageIO.write(canvas, writerFormat, output)) {
                return new Result(source, image.getWidth(), image.getHeight(), false);
            }
            return new Result(output.toByteArray(), canvas.getWidth(), canvas.getHeight(), true);
        } catch (IOException | RuntimeException exception) {
            log.warn("凭证水印绘制失败，保存原图：{}", exception.getMessage());
            return new Result(source, null, null, false);
        }
    }

    private BufferedImage toDrawable(BufferedImage image, String format) {
        int targetType = ImageFormats.PNG.equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        if (image.getType() == targetType) {
            return image;
        }
        BufferedImage converted = new BufferedImage(image.getWidth(), image.getHeight(), targetType);
        Graphics2D graphics = converted.createGraphics();
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return converted;
    }

    private void draw(BufferedImage image, List<String> lines) {
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int fontSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, image.getWidth() / 34));
            Font font = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);
            graphics.setFont(font);
            FontRenderContext context = graphics.getFontRenderContext();
            List<Rectangle2D> bounds = new ArrayList<>(lines.size());
            double blockWidth = 0d;
            double lineHeight = 0d;
            for (String line : lines) {
                Rectangle2D rectangle = font.getStringBounds(line, context);
                bounds.add(rectangle);
                blockWidth = Math.max(blockWidth, rectangle.getWidth());
                lineHeight = Math.max(lineHeight, rectangle.getHeight());
            }
            int boxWidth = (int) Math.ceil(blockWidth) + PADDING * 2;
            int boxHeight = (int) Math.ceil(lineHeight * lines.size()) + PADDING * 2;
            int boxX = Math.max(0, image.getWidth() - boxWidth - MARGIN);
            int boxY = Math.max(0, image.getHeight() - boxHeight - MARGIN);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, BACKGROUND_ALPHA));
            graphics.setColor(new Color(0, 0, 0));
            graphics.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 12, 12);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, TEXT_ALPHA));
            graphics.setColor(Color.WHITE);
            int baseline = boxY + PADDING + (int) Math.ceil(lineHeight) - (int) Math.ceil(lineHeight * 0.25d);
            for (int i = 0; i < lines.size(); i++) {
                graphics.drawString(lines.get(i), boxX + PADDING, baseline + (int) Math.ceil(lineHeight) * i);
            }
        } finally {
            graphics.dispose();
        }
    }

    public record Result(byte[] bytes, Integer width, Integer height, boolean watermarked) {
    }
}
