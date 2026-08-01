const API_BASE_URL = "https://hqhjxt.vip";

// 统一反馈平台公开接入配置。正式发布前由部署人员替换为已备案 HTTPS 域名
// 及平台项目管理页生成的 projectKey；任何 AppSecret 均不得写入小程序。
const FEEDBACK_BASE_URL = "https://feedback.example.com";
const FEEDBACK_PROJECT_KEY = "replace-with-project-key";

function assetUrl(path) {
  if (!path || /^https?:\/\//.test(path)) {
    return path;
  }
  return `${API_BASE_URL}${path}`;
}

module.exports = {
  API_BASE_URL,
  FEEDBACK_BASE_URL,
  FEEDBACK_PROJECT_KEY,
  assetUrl
};
