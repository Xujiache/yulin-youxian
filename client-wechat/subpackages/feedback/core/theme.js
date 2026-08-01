'use strict';

const config = require('./config');

function cssVariables() {
  const theme = config.getConfig().theme;
  return [
    `--feedback-primary:${theme.primary}`,
    `--feedback-primary-text:${theme.primaryText}`,
    `--feedback-page-bg:${theme.pageBackground}`,
    `--feedback-card-bg:${theme.cardBackground}`,
    `--feedback-text:${theme.text}`,
    `--feedback-muted:${theme.muted}`,
    `--feedback-danger:${theme.danger}`,
  ].join(';');
}

module.exports = { cssVariables };
