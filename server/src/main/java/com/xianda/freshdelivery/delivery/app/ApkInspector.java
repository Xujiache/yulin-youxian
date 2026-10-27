package com.xianda.freshdelivery.delivery.app;

import java.nio.file.Path;

public interface ApkInspector {
    ApkInspection inspect(Path apkFile);
}
