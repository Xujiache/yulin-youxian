const { API_BASE_URL } = require("./config");
const DEFAULT_BASE_URL = API_BASE_URL;
const DEFAULT_TIMEOUT = 12000;
const GET_RETRY_LIMIT = 1;
const RETRY_DELAY = 350;
const inflightGetRequests = {};
function createRequestError(message, code, retryable = false) {
    const error = new Error(message);
    error.code = code;
    error.retryable = retryable;
    return error;
}
function normalizeError(error) {
    const message = (error && error.errMsg) || "";
    if (message.includes("timeout")) {
        return createRequestError("网络响应超时，请稍后重试", "NETWORK_TIMEOUT", true);
    }
    if (message.includes("fail") && !message.includes("abort")) {
        return createRequestError("网络连接失败，请检查网络后重试", "NETWORK_ERROR", true);
    }
    return error instanceof Error ? error : new Error("请求失败");
}
function stableStringify(value) {
    if (Array.isArray(value)) {
        return `[${value.map(stableStringify).join(",")}]`;
    }
    if (value && typeof value === "object") {
        return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`).join(",")}}`;
    }
    return JSON.stringify(value);
}
function wait(milliseconds) {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
function sendOnce(config) {
    return new Promise((resolve, reject) => {
        try {
            wx.request({
                ...config,
                success(response) {
                    const body = response.data;
                    if (response.statusCode >= 200 && response.statusCode < 300 && body && body.code === 0) {
                        resolve(body.data);
                        return;
                    }
                    if (response.statusCode === 401 || (body && body.code === 401)) {
                        reject(createRequestError((body && body.message) || "请先登录", 401));
                        return;
                    }
                    const retryable = [408, 429, 500, 502, 503, 504].includes(response.statusCode);
                    const requestError = createRequestError((body && body.message) || "请求失败", response.statusCode, retryable);
                    requestError.statusCode = response.statusCode;
                    reject(requestError);
                },
                fail(error) {
                    reject(normalizeError(error));
                }
            });
        }
        catch (error) {
            reject(normalizeError(error));
        }
    });
}
async function sendWithRetry(config, retryLimit) {
    let attempt = 0;
    while (true) {
        try {
            return await sendOnce(config);
        }
        catch (error) {
            if (!error || !error.retryable || attempt >= retryLimit) {
                throw error;
            }
            attempt += 1;
            await wait(RETRY_DELAY * attempt);
        }
    }
}
function request(options = {}) {
    const app = getApp();
    const baseUrl = app.globalData.apiBaseUrl || DEFAULT_BASE_URL;
    if (!baseUrl) {
        return Promise.reject(new Error("请先配置后端服务地址"));
    }
    const send = () => {
        const token = app.globalData.authToken || wx.getStorageSync("authToken");
        const method = String(options.method || "GET").toUpperCase();
        const data = options.data || {};
        const header = {
            "content-type": "application/json",
            ...(options.header || {})
        };
        if (token) {
            header.Authorization = `Bearer ${token}`;
        }
        const config = {
            url: `${baseUrl}${options.url}`,
            method,
            data,
            header,
            timeout: options.timeout || DEFAULT_TIMEOUT
        };
        const retryLimit = method === "GET" && options.retry !== false ? GET_RETRY_LIMIT : 0;
        if (method !== "GET") {
            return sendWithRetry(config, retryLimit);
        }
        const requestKey = `${method}|${config.url}|${stableStringify(data)}|${stableStringify(header)}`;
        if (inflightGetRequests[requestKey]) {
            return inflightGetRequests[requestKey];
        }
        inflightGetRequests[requestKey] = sendWithRetry(config, retryLimit).finally(() => {
            delete inflightGetRequests[requestKey];
        });
        return inflightGetRequests[requestKey];
    };
    if (options.skipAuth || !app.ensureLogin) {
        return send();
    }
    return app.ensureLogin().then(send).catch((error) => {
        if (error && error.loginRequired) {
            throw error;
        }
        if (error && error.code === 401 && !options._retried && app.clearLogin) {
            app.clearLogin();
            return request({
                ...options,
                _retried: true
            });
        }
        throw error;
    });
}
module.exports = request;
