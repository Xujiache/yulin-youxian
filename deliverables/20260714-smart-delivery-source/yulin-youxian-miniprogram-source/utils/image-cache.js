const { API_BASE_URL } = require("./config");
const CACHE_STORAGE_KEY = "staticImageCacheMap";
const STATIC_ASSET_PATHS = [
    "/assets/products/avatar.png",
    "/assets/products/carrot.png",
    "/assets/products/cucumber.png",
    "/assets/products/hero.png",
    "/assets/products/home-banner-1.jpg",
    "/assets/products/home-banner-2.jpg",
    "/assets/products/home-banner-3.jpg",
    "/assets/products/lettuce.png",
    "/assets/products/login-hero-illustration.jpg",
    "/assets/products/profile-hero-bg.jpg",
    "/assets/products/setup-hero-bg.jpg",
    "/assets/products/spinach.png",
    "/assets/products/store-logo.png",
    "/assets/products/strawberry.png",
    "/assets/products/tomato.png"
];
const LOCAL_ASSET_PATHS = [
    "/assets/icons/order-pending-payment.png",
    "/assets/icons/order-pending-shipment.png",
    "/assets/icons/order-pending-receipt.png",
    "/assets/icons/order-pending-review.png",
    "/assets/icons/profile-menu-location.png",
    "/assets/icons/profile-menu-refund.png",
    "/assets/icons/profile-menu-service.png",
    "/assets/icons/profile-menu-store.png",
    "/assets/icons/profile-menu-settings.png",
    "/assets/icons/tab-home.png",
    "/assets/icons/tab-home-active.png",
    "/assets/icons/tab-category.png",
    "/assets/icons/tab-category-active.png",
    "/assets/icons/tab-cart.png",
    "/assets/icons/tab-cart-active.png",
    "/assets/icons/tab-orders.png",
    "/assets/icons/tab-orders-active.png",
    "/assets/icons/tab-profile.png",
    "/assets/icons/tab-profile-active.png"
];
let cacheMap = null;
let preloadPromise = null;
const inflight = {};
const downloadQueue = [];
let activeDownloads = 0;
const DOWNLOAD_CONCURRENCY = 2;
const PRELOAD_CONCURRENCY = 2;
const LOCAL_PRELOAD_CONCURRENCY = 1;
const LOCAL_PRELOAD_DELAY = 500;
const IMAGE_INFO_TIMEOUT = 3000;
function readCacheMap() {
    if (cacheMap) {
        return cacheMap;
    }
    try {
        cacheMap = wx.getStorageSync(CACHE_STORAGE_KEY) || {};
    }
    catch {
        cacheMap = {};
    }
    return cacheMap;
}
function writeCacheMap() {
    try {
        wx.setStorageSync(CACHE_STORAGE_KEY, readCacheMap());
        return true;
    }
    catch {
        return false;
    }
}
function getBaseUrl() {
    try {
        const app = getApp();
        return (app.globalData && app.globalData.apiBaseUrl) || API_BASE_URL;
    }
    catch {
        return API_BASE_URL;
    }
}
function normalizeUrl(url) {
    if (!url) {
        return url;
    }
    if (/^https?:\/\//.test(url)) {
        return url;
    }
    if (url.startsWith("/assets/") || url.startsWith("/uploads/")) {
        return `${getBaseUrl()}${url}`;
    }
    return url;
}
function isCacheable(url) {
    const normalized = normalizeUrl(url);
    const baseUrl = String(getBaseUrl() || "").replace(/\/$/, "");
    return Boolean(normalized
        && baseUrl
        && normalized.startsWith(`${baseUrl}/`)
        && (normalized.includes("/assets/products/") || normalized.includes("/uploads/")));
}
function removeSavedFile(filePath) {
    if (!filePath || !wx.removeSavedFile) {
        return;
    }
    try {
        wx.removeSavedFile({ filePath });
    }
    catch { }
}
function verifySavedFile(filePath) {
    return new Promise((resolve) => {
        if (!filePath || !wx.getFileSystemManager) {
            resolve(false);
            return;
        }
        try {
            wx.getFileSystemManager().access({
                path: filePath,
                success: () => resolve(true),
                fail: () => resolve(false)
            });
        }
        catch {
            resolve(false);
        }
    });
}
function getCachedImageUrl(url) {
    const normalized = normalizeUrl(url);
    const localPath = readCacheMap()[normalized];
    return localPath || normalized;
}
function cachedAssetUrl(path) {
    const normalized = normalizeUrl(path);
    cacheImage(normalized).catch(() => normalized);
    return getCachedImageUrl(normalized);
}
function drainDownloadQueue() {
    while (activeDownloads < DOWNLOAD_CONCURRENCY && downloadQueue.length) {
        const item = downloadQueue.shift();
        activeDownloads += 1;
        Promise.resolve()
            .then(item.task)
            .then(item.resolve, item.reject)
            .finally(() => {
            activeDownloads -= 1;
            drainDownloadQueue();
        });
    }
}
function enqueueDownload(task) {
    return new Promise((resolve, reject) => {
        downloadQueue.push({ task, resolve, reject });
        drainDownloadQueue();
    });
}
function downloadImage(normalized, map) {
    return new Promise((resolve) => {
        try {
            wx.downloadFile({
                url: normalized,
                timeout: 12000,
                success(downloadResult) {
                    if (downloadResult.statusCode !== 200 || !downloadResult.tempFilePath) {
                        resolve(normalized);
                        return;
                    }
                    try {
                        wx.saveFile({
                            tempFilePath: downloadResult.tempFilePath,
                            success(saveResult) {
                                try {
                                    if (map[normalized] && map[normalized] !== saveResult.savedFilePath) {
                                        removeSavedFile(map[normalized]);
                                    }
                                    map[normalized] = saveResult.savedFilePath;
                                    if (!writeCacheMap()) {
                                        delete map[normalized];
                                        removeSavedFile(saveResult.savedFilePath);
                                        resolve(downloadResult.tempFilePath);
                                        return;
                                    }
                                    resolve(saveResult.savedFilePath);
                                }
                                catch {
                                    resolve(downloadResult.tempFilePath);
                                }
                            },
                            fail() {
                                resolve(downloadResult.tempFilePath);
                            }
                        });
                    }
                    catch {
                        resolve(downloadResult.tempFilePath);
                    }
                },
                fail() {
                    resolve(normalized);
                }
            });
        }
        catch {
            resolve(normalized);
        }
    });
}
async function cacheImage(url) {
    const normalized = normalizeUrl(url);
    if (!isCacheable(normalized)) {
        return normalized;
    }
    const map = readCacheMap();
    const existing = map[normalized];
    if (existing && await verifySavedFile(existing)) {
        return existing;
    }
    if (existing) {
        delete map[normalized];
        writeCacheMap();
    }
    if (inflight[normalized]) {
        return inflight[normalized];
    }
    inflight[normalized] = enqueueDownload(() => downloadImage(normalized, map)).catch(() => normalized).finally(() => {
        delete inflight[normalized];
    });
    return inflight[normalized];
}
function runPool(items, concurrency, handler) {
    const queue = Array.from(items);
    let cursor = 0;
    const worker = async () => {
        const results = [];
        while (cursor < queue.length) {
            const item = queue[cursor++];
            try {
                results.push(await handler(item));
            }
            catch {
                results.push(item);
            }
        }
        return results;
    };
    return Promise.all(Array.from({ length: Math.min(concurrency, queue.length) }, worker))
        .then((groups) => groups.reduce((all, group) => all.concat(group), []));
}
function preloadImageInfo(src) {
    return new Promise((resolve) => {
        let settled = false;
        const finish = (result) => {
            if (settled) {
                return;
            }
            settled = true;
            clearTimeout(timer);
            resolve(result || src);
        };
        const timer = setTimeout(() => finish(src), IMAGE_INFO_TIMEOUT);
        try {
            wx.getImageInfo({
                src,
                success: finish,
                fail: () => finish(src)
            });
        }
        catch {
            finish(src);
        }
    });
}
function preloadLocalAssets() {
    if (!wx.getImageInfo) {
        return Promise.resolve([]);
    }
    return new Promise((resolve) => {
        setTimeout(() => {
            runPool(LOCAL_ASSET_PATHS, LOCAL_PRELOAD_CONCURRENCY, preloadImageInfo).then(resolve, () => resolve([]));
        }, LOCAL_PRELOAD_DELAY);
    });
}
function preloadStaticImages(extraUrls = []) {
    if (preloadPromise) {
        return preloadPromise;
    }
    const urls = STATIC_ASSET_PATHS.map((path) => `${getBaseUrl()}${path}`).concat(extraUrls.map(normalizeUrl));
    const queue = Array.from(new Set(urls));
    preloadPromise = Promise.all([
        preloadLocalAssets(),
        runPool(queue, PRELOAD_CONCURRENCY, cacheImage)
    ]).then((groups) => groups.reduce((all, group) => all.concat(group), [])).catch(() => []);
    return preloadPromise;
}
module.exports = {
    STATIC_ASSET_PATHS,
    cacheImage,
    cachedAssetUrl,
    getCachedImageUrl,
    normalizeUrl,
    preloadStaticImages
};
