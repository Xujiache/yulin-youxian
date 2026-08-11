const HOME_URL = "/pages/home/index";

function homeNavigationPlan(url = HOME_URL) {
  const target = typeof url === "string" && url.startsWith("/") ? url : HOME_URL;
  return [
    { method: "redirectTo", url: target },
    { method: "reLaunch", url: target }
  ];
}

function navigateHome(wxApi, url = HOME_URL) {
  const api = wxApi || {};
  const plan = homeNavigationPlan(url);
  const fallback = plan[1];
  const runFallback = () => {
    if (typeof api[fallback.method] === "function") {
      api[fallback.method]({ url: fallback.url });
    }
  };
  const primary = plan[0];
  if (typeof api[primary.method] !== "function") {
    runFallback();
    return;
  }
  api[primary.method]({
    url: primary.url,
    fail: runFallback
  });
}

module.exports = {
  HOME_URL,
  homeNavigationPlan,
  navigateHome
};
