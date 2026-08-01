'use strict';

const config = require('./config');

function url(page, query) {
  const base = `${config.getConfig().subpackageRoot}/pages/${page}/index`;
  const pairs = Object.keys(query || {}).map((key) => `${encodeURIComponent(key)}=${encodeURIComponent(query[key])}`);
  return `${base}${pairs.length ? `?${pairs.join('&')}` : ''}`;
}

function navigate(page, query) {
  return wx.navigateTo({ url: url(page, query) });
}

module.exports = { url, navigate };
