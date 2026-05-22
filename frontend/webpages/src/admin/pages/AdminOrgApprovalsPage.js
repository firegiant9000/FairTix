import React, { useCallback, useEffect, useState } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import Divider from '@mui/material/Divider';
import TextField from '@mui/material/TextField';
import Chip from '@mui/material/Chip';
import Alert from '@mui/material/Alert';
import api from '../../api/client';

function timeAgo(iso) {
  if (!iso) return '—';
  const ms = Date.now() - new Date(iso).getTime();
  const hours = Math.floor(ms / 3_600_000);
  if (hours < 1) return 'just now';
  if (hours < 48) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

function ReviewCard({ org, onApprove, onReject }) {
  const [rejectMode, setRejectMode] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const approve = async () => {
    setBusy(true);
    setError(null);
    try { await onApprove(org.id); } catch (e) { setError(e.message); } finally { setBusy(false); }
  };
  const reject = async () => {
    if (!reason.trim()) { setError('Reason required'); return; }
    setBusy(true);
    setError(null);
    try { await onReject(org.id, reason.trim()); } catch (e) { setError(e.message); } finally { setBusy(false); }
  };

  return (
    <Paper sx={{ p: 3, mb: 2 }}>
      <Stack direction="row" justifyContent="space-between" alignItems="flex-start" sx={{ mb: 2 }}>
        <Box>
          <Typography variant="h6" sx={{ fontWeight: 700 }}>{org.name}</Typography>
          <Typography variant="body2" color="text.secondary">{org.legalName || '—'} · {org.slug}</Typography>
        </Box>
        <Stack direction="row" spacing={1} alignItems="center">
          <Chip
            size="small"
            color={org.stripeChargesEnabled ? 'success' : 'warning'}
            label={org.stripeChargesEnabled ? 'Stripe ready' : 'Stripe pending'}
          />
          <Chip size="small" label={`Submitted ${timeAgo(org.submittedForReviewAt)}`} />
        </Stack>
      </Stack>

      <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1.5, mb: 2 }}>
        <Field label="Contact" value={`${org.primaryContactName || '—'} · ${org.contactEmail || '—'}`} />
        <Field label="Phone" value={org.primaryContactPhone} />
        <Field label="Address" value={[org.addressLine1, org.addressLine2, org.addressCity, org.addressRegion, org.addressPostalCode, org.addressCountry].filter(Boolean).join(', ')} />
        <Field label="Referred by" value={org.referredBy} />
      </Box>

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

      <Divider sx={{ my: 1 }} />

      {!rejectMode ? (
        <Stack direction="row" spacing={1}>
          <Button variant="contained" color="success" disabled={busy} onClick={approve}>
            Approve
          </Button>
          <Button variant="outlined" color="error" disabled={busy} onClick={() => setRejectMode(true)}>
            Reject
          </Button>
        </Stack>
      ) : (
        <Stack spacing={1}>
          <TextField
            label="Rejection reason (emailed to organizer)"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            multiline
            minRows={2}
            fullWidth
          />
          <Stack direction="row" spacing={1}>
            <Button variant="contained" color="error" disabled={busy} onClick={reject}>
              Confirm reject
            </Button>
            <Button onClick={() => { setRejectMode(false); setReason(''); }} disabled={busy}>
              Cancel
            </Button>
          </Stack>
        </Stack>
      )}
    </Paper>
  );
}

function Field({ label, value }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="body2">{value || '—'}</Typography>
    </Box>
  );
}

function AdminOrgApprovalsPage() {
  const [queue, setQueue] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await api.get('/api/admin/organizations/review-queue');
      setQueue(data || []);
      setError(null);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const approve = async (id) => {
    await api.post(`/api/admin/organizations/${id}/approve`, {});
    await load();
  };
  const reject = async (id, reason) => {
    await api.post(`/api/admin/organizations/${id}/reject`, { reason });
    await load();
  };

  return (
    <Box sx={{ p: 3 }}>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 3 }}>
        <Typography variant="h4" sx={{ fontWeight: 700 }}>Organization approvals</Typography>
        <Button onClick={load}>Refresh</Button>
      </Stack>

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      {loading && <Typography>Loading…</Typography>}
      {!loading && queue.length === 0 && (
        <Paper sx={{ p: 4, textAlign: 'center' }}>
          <Typography color="text.secondary">No organizations awaiting review.</Typography>
        </Paper>
      )}
      {queue.map((org) => (
        <ReviewCard key={org.id} org={org} onApprove={approve} onReject={reject} />
      ))}
    </Box>
  );
}

export default AdminOrgApprovalsPage;
