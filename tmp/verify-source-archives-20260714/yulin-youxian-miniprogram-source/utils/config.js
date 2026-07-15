const API_BASE_URL = "https://hqhjxt.vip";
function assetUrl(path) {
    if (!path || /^https?:\/\//.test(path)) {
        return path;
    }
    return `${API_BASE_URL}${path}`;
}
module.exports = {
    API_BASE_URL,
    assetUrl
};
