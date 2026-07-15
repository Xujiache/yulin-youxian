package com.xianda.freshdelivery.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "persistence.mode", havingValue = "file")
public class FileStateStore implements StateStore {
    @Override
    public Optional<byte[]> load(String stateKey, Path legacyPath) {
        if (!Files.isRegularFile(legacyPath)) {
            return Optional.empty();
        }
        return Optional.of(StatePayloadCodec.readValidated(legacyPath));
    }

    @Override
    public void save(String stateKey, Path legacyPath, byte[] payload) {
        StatePayloadCodec.writeAtomically(legacyPath, payload);
    }
}
