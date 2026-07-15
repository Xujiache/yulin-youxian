package com.xianda.freshdelivery.persistence;

import java.nio.file.Path;
import java.util.Optional;

public interface StateStore {
    Optional<byte[]> load(String stateKey, Path legacyPath);

    void save(String stateKey, Path legacyPath, byte[] payload);
}
