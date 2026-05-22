import api from '../api/client';
import { orgPath } from './organizerApi';

export const brandingApi = {
  get: (orgId) => api.get(orgPath(orgId, '/branding')),
  update: (orgId, body) => api.patch(orgPath(orgId, '/branding'), body),

  getEventPage: (orgId, eventId) =>
    api.get(orgPath(orgId, `/events/${eventId}/page`)),
  updateEventPage: (orgId, eventId, body) =>
    api.patch(orgPath(orgId, `/events/${eventId}/page`), body),

  listDomains: (orgId) => api.get(orgPath(orgId, '/custom-domains')),
  addDomain: (orgId, hostname) =>
    api.post(orgPath(orgId, '/custom-domains'), { hostname }),
  verifyDomain: (orgId, domainId) =>
    api.post(orgPath(orgId, `/custom-domains/${domainId}/verify`)),
  deleteDomain: (orgId, domainId) =>
    api.delete(orgPath(orgId, `/custom-domains/${domainId}`)),
};

export const publicBrandingApi = {
  branding: (orgSlug) => api.get(`/api/public/organizations/${orgSlug}/branding`),
  events: (orgSlug) => api.get(`/api/public/organizations/${orgSlug}/events`),
  event: (orgSlug, eventSlug) =>
    api.get(`/api/public/organizations/${orgSlug}/events/${eventSlug}`),
};
