'use strict';

const { request } = require('./request');
const session = require('./session');

function projectIdFromSession(active) {
  if (!active || !active.project || !active.project.id) throw new Error('反馈项目会话无效');
  return active.project.id;
}

function currentProjectId() {
  return projectIdFromSession(session.getSession());
}

function withCurrentProject(callback) {
  return session.ensureSession().then((active) => callback(projectIdFromSession(active)));
}

function listFeedbacks(query) {
  return withCurrentProject((projectId) => request({
    path: '/api/feedbacks',
    query: Object.assign({}, query || {}, { projectId }),
  }));
}

function getFeedback(id) {
  return request({ path: `/api/feedbacks/${encodeURIComponent(id)}` });
}

function createFeedback(payload) {
  return withCurrentProject((projectId) => request({
    path: '/api/feedbacks',
    method: 'POST',
    data: Object.assign({}, payload, { projectId }),
  }));
}

function supplementFeedback(id, payload) {
  return request({ path: `/api/feedbacks/${encodeURIComponent(id)}/supplements`, method: 'POST', data: payload });
}

function listKnowledge(query) {
  return withCurrentProject((projectId) => request({
    path: '/api/knowledge-base',
    query: Object.assign({}, query || {}, { projectId }),
  }));
}

function createSocketTicket() {
  return request({ path: '/api/ws/tickets', method: 'POST', data: {} });
}

module.exports = { currentProjectId, listFeedbacks, getFeedback, createFeedback, supplementFeedback, listKnowledge, createSocketTicket };
