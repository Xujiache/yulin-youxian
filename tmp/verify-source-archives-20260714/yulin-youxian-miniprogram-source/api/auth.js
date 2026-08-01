const request = require("../utils/request");
const { API_BASE_URL } = require("../utils/config");
const { cacheImage, getCachedImageUrl } = require("../utils/image-cache");
function getBaseUrl() {
    const app = getApp();
    return app.globalData.apiBaseUrl || API_BASE_URL;
}
function normalizeAvatarUrl(avatarUrl) {
    if (!avatarUrl || avatarUrl.startsWith("http")) {
        cacheImage(avatarUrl);
        return getCachedImageUrl(avatarUrl);
    }
    const normalized = `${getBaseUrl()}${avatarUrl}`;
    cacheImage(normalized);
    return getCachedImageUrl(normalized);
}
function normalizeProfile(profile) {
    if (!profile) {
        return profile;
    }
    return {
        ...profile,
        avatarUrl: normalizeAvatarUrl(profile.avatarUrl)
    };
}
async function getProfile() {
    return normalizeProfile(await request({
        url: "/api/wx/profile"
    }));
}
async function updateProfile(data) {
    return normalizeProfile(await request({
        url: "/api/wx/profile",
        method: "PUT",
        data
    }));
}
function uploadAvatar(filePath) {
    const app = getApp();
    const token = app.globalData.authToken || wx.getStorageSync("authToken");
    const baseUrl = getBaseUrl();
    return new Promise((resolve, reject) => {
        if (!baseUrl) {
            reject(new Error("请先配置后端服务地址"));
            return;
        }
        wx.uploadFile({
            url: `${baseUrl}/api/wx/profile/avatar`,
            filePath,
            name: "file",
            header: {
                Authorization: `Bearer ${token}`
            },
            success(response) {
                let body = {};
                try {
                    body = JSON.parse(response.data || "{}");
                }
                catch {
                    reject(new Error("头像上传响应解析失败"));
                    return;
                }
                if (response.statusCode >= 200 && response.statusCode < 300 && body.code === 0) {
                    resolve({
                        avatarUrl: body.data.avatarUrl,
                        displayUrl: normalizeAvatarUrl(body.data.avatarUrl)
                    });
                    return;
                }
                reject(new Error(body.message || "头像上传失败"));
            },
            fail() {
                reject(new Error("头像上传失败，请检查网络"));
            }
        });
    });
}
module.exports = {
    getProfile,
    updateProfile,
    uploadAvatar,
    normalizeProfile
};
