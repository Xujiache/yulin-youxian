import { StorageConfig } from '@/utils/storage';
export class StorageKeyManager {
    private getCurrentVersionKey(storeId: string): string {
        return StorageConfig.generateStorageKey(storeId);
    }
    private hasCurrentVersionData(key: string): boolean {
        return localStorage.getItem(key) !== null;
    }
    private findExistingKey(storeId: string): string | null {
        const storageKeys = Object.keys(localStorage);
        const pattern = StorageConfig.createKeyPattern(storeId);
        return storageKeys.find((key) => pattern.test(key) && localStorage.getItem(key)) || null;
    }
    private migrateData(fromKey: string, toKey: string): void {
        try {
            const existingData = localStorage.getItem(fromKey);
            if (existingData) {
                localStorage.setItem(toKey, existingData);
                console.info(`[Storage] 已迁移数据: ${fromKey} → ${toKey}`);
            }
        }
        catch (error) {
            console.warn(`[Storage] 数据迁移失败: ${fromKey}`, error);
        }
    }
    getStorageKey(storeId: string): string {
        const currentKey = this.getCurrentVersionKey(storeId);
        if (this.hasCurrentVersionData(currentKey)) {
            return currentKey;
        }
        const existingKey = this.findExistingKey(storeId);
        if (existingKey) {
            this.migrateData(existingKey, currentKey);
        }
        return currentKey;
    }
}
