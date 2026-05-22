import api from '../api/client';

/**
 * Path builder for the org-scoped dashboard endpoints. Centralises the
 * `/api/organizations/{orgId}` prefix so per-page calls stay focused on the
 * resource they want.
 */
export const orgPath = (orgId, suffix = '') => `/api/organizations/${orgId}${suffix}`;

export const organizerApi = {
  overview: (orgId) => api.get(orgPath(orgId, '/dashboard/overview')),
  events: (orgId) => api.get(orgPath(orgId, '/events')),
  eventSummary: (orgId, eventId) =>
    api.get(orgPath(orgId, `/events/${eventId}/summary`)),
  velocity: (orgId, eventId, days = 14) =>
    api.get(orgPath(orgId, `/events/${eventId}/velocity?days=${days}`)),
  attendees: (orgId, eventId, { q = '', page = 0, size = 50 } = {}) => {
    const params = new URLSearchParams();
    if (q) params.set('q', q);
    params.set('page', String(page));
    params.set('size', String(size));
    return api.get(orgPath(orgId, `/events/${eventId}/attendees?${params.toString()}`));
  },
  attendeesCsvUrl: (orgId, eventId) =>
    orgPath(orgId, `/events/${eventId}/attendees.csv`),
};
