import api from '../api/client';
import { orgPath } from './organizerApi';

/**
 * M2-15..M2-18 reports endpoints. Mirrors the server's
 * /api/organizations/{orgId}/reports surface; CSV and HTML responses are
 * exposed as URLs for the browser to follow directly (download / print).
 */
export const reportsApi = {
  dos: (orgId, eventId) => api.get(orgPath(orgId, `/reports/events/${eventId}/dos`)),
  dosCsvUrl: (orgId, eventId) => orgPath(orgId, `/reports/events/${eventId}/dos.csv`),
  dosHtmlUrl: (orgId, eventId) => orgPath(orgId, `/reports/events/${eventId}/dos.html`),

  settlement: (orgId, eventId) => api.get(orgPath(orgId, `/reports/events/${eventId}/settlement`)),
  settlementCsvUrl: (orgId, eventId) => orgPath(orgId, `/reports/events/${eventId}/settlement.csv`),
  settlementHtmlUrl: (orgId, eventId) => orgPath(orgId, `/reports/events/${eventId}/settlement.html`),

  getSettlementConfig: (orgId, eventId) =>
    api.get(orgPath(orgId, `/reports/events/${eventId}/settlement/config`)),
  putSettlementConfig: (orgId, eventId, body) =>
    api.put(orgPath(orgId, `/reports/events/${eventId}/settlement/config`), body),
  finalizeSettlement: (orgId, eventId) =>
    api.post(orgPath(orgId, `/reports/events/${eventId}/settlement/finalize`)),

  payouts: (orgId, days = 30) => api.get(orgPath(orgId, `/reports/payouts?days=${days}`)),
  payoutsCsvUrl: (orgId, days = 30) => orgPath(orgId, `/reports/payouts.csv?days=${days}`),
  syncPayouts: (orgId, limit = 25) =>
    api.post(orgPath(orgId, `/reports/payouts/sync?limit=${limit}`)),

  taxThreshold: (orgId, year) => api.get(orgPath(orgId,
    `/reports/tax/threshold${year ? `?year=${year}` : ''}`)),
  taxYear: (orgId, year) => api.get(orgPath(orgId,
    `/reports/tax/year${year ? `?year=${year}` : ''}`)),
  taxYearCsvUrl: (orgId, year) => orgPath(orgId,
    `/reports/tax/year.csv${year ? `?year=${year}` : ''}`),
  putTaxConfig: (orgId, body) => api.put(orgPath(orgId, `/reports/tax/config`), body),
};
