const { API_BASE_URL } = require("./config");

const CACHE_STORAGE_KEY = "staticImageCacheMap";
const STATIC_ASSET_PATHS = [
  "/assets/products/avatar.png",
  "/assets/products/carrot.png",
  "/assets/products/cucumber.png",
  "/assets/products/hero.png",
  "/assets/products/lettuce.png",
  "/assets/products/login-hero-illustration.jpg",
  "/assets/products/profile-hero-bg.jpg",
  "/assets/products/setup-hero-bg.jpg",
  "/assets/products/spinach.png",
  "/assets/products/strawberry.png",
  "/assets/products/tomato.png"
];

let cacheMap = null;
let preloadPromise = null;
const inflight = {};

function readCacheMap() {
  if (cacheMap) {
    return cacheMap;
  }
  cacheMap = wx.getStorageSync(CACHE_STORAGE_KEY) || {};
  return cacheMap;
}

function writeCacheMap() {
  wx.setStorageSync(CACHE_STORAGE_KEY, readCacheMap());
}

function getBaseUrl() {
  try {
    const app = getApp();
    return (app.globalData && app.globalData.apiBaseUrl) || API_BASE_URL;
  } catch {
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
  return Boolean(normalized && /^https?:\/\//.test(normalized) && normalized.includes("/assets/products/"));
}

function removeSavedFile(filePath) {
  if (!filePath || !wx.removeSavedFile) {
    return;
  }
  wx.removeSavedFile({ filePath });
}

function verifySavedFile(filePath) {
  return new Promise((resolve) => {
    if (!filePath || !wx.getFileSystemManager) {
      resolve(false);
      return;
    }
    wx.getFileSystemManager().access({
      path: filePath,
      success: () => resolve(true),
      fail: () => resolve(false)
    });
  });
}

function getCachedImageUrl(url) {
  const normalized = normalizeUrl(url);
  const localPath = readCacheMap()[normalized];
  return localPath || normalized;
}

function cachedAssetUrl(path) {
  const normalized = normalizeUrl(path);
  cacheImage(normalized);
  return getCachedImageUrl(normalized);
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

  inflight[normalized] = new Promise((resolve) => {
    wx.downloadFile({
      url: normalized,
      success(downloadResult) {
        if (downloadResult.statusCode !== 200 || !downloadResult.tempFilePath) {
          resolve(normalized);
          return;
        }
        wx.saveFile({
          tempFilePath: downloadResult.tempFilePath,
          success(saveResult) {
            if (map[normalized] && map[normalized] !== saveResult.savedFilePath) {
              removeSavedFile(map[normalized]);
            }
            map[normalized] = saveResult.savedFilePath;
            writeCacheMap();
            resolve(saveResult.savedFilePath);
          },
          fail() {
            resolve(downloadResult.tempFilePath);
          }
        });
      },
      fail() {
        resolve(normalized);
      }
    });
  }).finally(() => {
    delete inflight[normalized];
  });

  return inflight[normalized];
}

function preloadStaticImages(extraUrls = []) {
  if (preloadPromise) {
    return preloadPromise;
  }
  const urls = STATIC_ASSET_PATHS.map((path) => `${getBaseUrl()}${path}`).concat(extraUrls.map(normalizeUrl));
  preloadPromise = Promise.all(Array.from(new Set(urls)).map((url) => cacheImage(url))).catch(() => []);
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
