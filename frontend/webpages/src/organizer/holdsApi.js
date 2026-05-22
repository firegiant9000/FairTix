import api from '../api/client';

const orgHeader = (orgId) => ({ headers: { 'X-Organization-Id': orgId } });

export const holdsApi = {
  // Comps
  issueComp: (orgId, body) => api.post('/api/organizer/comps', body, orgHeader(orgId)),
  listComps: (orgId, eventId) =>
    api.get(`/api/organizer/comps?eventId=${eventId}`, orgHeader(orgId)),

  // Event-side holds (artist/press/house)
  createHolds: (orgId, body) => api.post('/api/organizer/event-holds', body, orgHeader(orgId)),
  listHolds: (orgId, eventId, category) => {
    const q = new URLSearchParams({ eventId });
    if (category) q.set('category', category);
    return api.get(`/api/organizer/event-holds?${q}`, orgHeader(orgId));
  },
  releaseHold: (orgId, holdId) =>
    api.delete(`/api/organizer/event-holds/${holdId}`, orgHeader(orgId)),
  bulkRelease: (orgId, eventId, category) =>
    api.post(
      `/api/organizer/event-holds/bulk-release?eventId=${eventId}&category=${category}`,
      null,
      orgHeader(orgId)
    ),
  convertToComp: (orgId, holdId, body) =>
    api.post(`/api/organizer/event-holds/${holdId}/convert`, body, orgHeader(orgId)),

  // Will-call
  listWillCall: (orgId, eventId, q) => {
    const params = new URLSearchParams({ eventId });
    if (q) params.set('q', q);
    return api.get(`/api/organizer/will-call?${params}`, orgHeader(orgId));
  },
  claimWillCall: (orgId, ticketId) =>
    api.post(`/api/organizer/will-call/${ticketId}/claim`, null, orgHeader(orgId)),
  printWillCallUrl: (eventId) => `/api/organizer/will-call/print?eventId=${eventId}`,

  // Inventory aggregate
  inventoryStats: (orgId, eventId) =>
    api.get(`/api/organizer/events/${eventId}/inventory`, orgHeader(orgId)),
};
