package com.xianda.freshdelivery.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class StatePayloadCodec {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private StatePayloadCodec() {
    }

    static String decodeAndValidate(byte[] payload) {
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString();
            if (json.indexOf('\uFFFD') >= 0) {
                throw new IllegalStateException("状态数据包含 Unicode 替换字符，疑似已经发生乱码");
            }
            OBJECT_MAPPER.readTree(json);
            return json;
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("状态数据不是有效的 UTF-8 编码", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("状态数据不是有效的 JSON", exception);
        }
    }

    static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    static byte[] utf8(String payload) {
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    static boolean hasSuspiciousQuestionMarks(byte[] payload) {
        return decodeAndValidate(payload).matches("(?s).*\\?{3,}.*");
    }

    static byte[] readValidated(Path path) {
        try {
            byte[] payload = Files.readAllBytes(path);
            decodeAndValidate(payload);
            return payload;
        } catch (IOException exception) {
            throw new IllegalStateException("读取状态文件失败: " + path, exception);
        }
    }

    static void writeAtomically(Path path, byte[] payload) {
        decodeAndValidate(payload);
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tempDir = parent == null ? Path.of(".") : parent;
            Path tempFile = Files.createTempFile(tempDir, path.getFileName().toString(), ".tmp");
            Files.write(tempFile, payload);
            try {
                Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("写入状态文件失败: " + path, exception);
        }
    }

    static Path backupOnce(Path path) {
        Path backupPath = path.resolveSibling(path.getFileName() + ".pre-mysql.bak");
        if (Files.exists(backupPath)) {
            return backupPath;
        }
        try {
            Files.copy(path, backupPath, StandardCopyOption.COPY_ATTRIBUTES);
            return backupPath;
        } catch (IOException exception) {
            throw new IllegalStateException("创建迁移前备份失败: " + backupPath, exception);
        }
    }
}
