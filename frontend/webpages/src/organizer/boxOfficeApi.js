import api from '../api/client';

const base = (orgId) => `/api/organizations/${orgId}/box-office`;

export const boxOfficeApi = {
  todaysEvents: (orgId) => api.get(`${base(orgId)}/events/today`),
  activeSession: (orgId) => api.get(`${base(orgId)}/sessions/active`),
  openSession: (orgId, openingCash) =>
    api.post(`${base(orgId)}/sessions`, { openingCash }),
  closeSession: (orgId, sessionId, closingCash, varianceReason) =>
    api.post(`${base(orgId)}/sessions/${sessionId}/close`, {
      closingCash,
      varianceReason,
    }),
  sessionReport: (orgId, sessionId) =>
    api.get(`${base(orgId)}/sessions/${sessionId}`),
  cashSale: (orgId, sessionId, payload) =>
    api.post(`${base(orgId)}/sessions/${sessionId}/sales/cash`, payload),
  compSale: (orgId, sessionId, payload) =>
    api.post(`${base(orgId)}/sessions/${sessionId}/sales/comp`, payload),
  createCardIntent: (orgId, sessionId, payload) =>
    api.post(`${base(orgId)}/sessions/${sessionId}/sales/card-present/intent`, payload),
  confirmCardSale: (orgId, sessionId, paymentIntentId) =>
    api.post(`${base(orgId)}/sessions/${sessionId}/sales/card-present/confirm`, {
      paymentIntentId,
    }),
  terminalConnectionToken: (orgId) =>
    api.post(`${base(orgId)}/terminal/connection-token`),
  // Seat data is served by the existing inventory API
  seatMap: (eventId) => api.get(`/api/events/${eventId}/seats/map`),
};
