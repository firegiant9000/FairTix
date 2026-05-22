import React, { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Grid from '@mui/material/Grid';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import MenuItem from '@mui/material/MenuItem';
import Alert from '@mui/material/Alert';
import Chip from '@mui/material/Chip';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import Divider from '@mui/material/Divider';
import DownloadIcon from '@mui/icons-material/Download';
import PrintIcon from '@mui/icons-material/Print';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import { useOrganization } from '../useOrganization';
import { reportsApi } from '../reportsApi';

const currency = (n) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(Number(n || 0));

const SPLIT_TYPES = [
  { value: '', label: '(not configured)' },
  { value: 'FLAT_PCT', label: 'Flat % of net to artist' },
  { value: 'DOOR_DEAL', label: 'Door deal (venue off the top, then % split)' },
];

function Row({ label, value, total }) {
  return (
    <Box sx={{ display: 'flex', justifyContent: 'space-between', py: 0.75,
      borderBottom: total ? '2px solid' : '1px solid', borderColor: total ? 'text.primary' : 'divider',
      fontWeight: total ? 700 : 400 }}>
      <Typography sx={{ fontWeight: 'inherit' }}>{label}</Typography>
      <Typography sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: 'inherit' }}>{value}</Typography>
    </Box>
  );
}

export default function SettlementPage() {
  const { eventId } = useParams();
  const navigate = useNavigate();
  const { current } = useOrganization();
  const orgId = current?.id;
  const [data, setData] = useState(null);
  const [config, setConfig] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!orgId) return;
    try {
      const [settlement, cfg] = await Promise.all([
        reportsApi.settlement(orgId, eventId),
        reportsApi.getSettlementConfig(orgId, eventId),
      ]);
      setData(settlement);
      setConfig(cfg);
    } catch (e) {
      setError(e?.message || 'Failed to load settlement');
    }
  }, [orgId, eventId]);

  useEffect(() => { load(); }, [load]);

  const saveConfig = async () => {
    setBusy(true);
    setError(null);
    try {
      const body = {
        splitType: config.splitType || null,
        artistPct: config.artistPct == null || config.artistPct === '' ? null : Number(config.artistPct),
        venueTakeOffTop: config.venueTakeOffTop == null || config.venueTakeOffTop === '' ? null : Number(config.venueTakeOffTop),
        taxRatePct: config.taxRatePct == null || config.taxRatePct === '' ? null : Number(config.taxRatePct),
        notes: config.notes || null,
      };
      const saved = await reportsApi.putSettlementConfig(orgId, eventId, body);
      setConfig(saved);
      await load();
    } catch (e) {
      setError(e?.message || 'Failed to save configuration');
    } finally {
      setBusy(false);
    }
  };

  const finalize = async () => {
    setBusy(true);
    setError(null);
    try {
      await reportsApi.finalizeSettlement(orgId, eventId);
      await load();
    } catch (e) {
      setError(e?.message || 'Failed to finalize');
    } finally {
      setBusy(false);
    }
  };

  if (!data || !config) {
    return <Box sx={{ p: 3 }}><Skeleton variant="rectangular" height={200} /></Box>;
  }

  const d = data.dosSnapshot;
  const settlementNet = Number(d.net) - Number(data.postShowRefunds || 0);

  return (
    <Box sx={{ p: 3 }}>
      <Button startIcon={<ArrowBackIcon />} onClick={() => navigate(`/organizer/events/${eventId}`)}>
        Back to event
      </Button>
      <Typography variant="h4" sx={{ fontWeight: 700, mt: 1 }}>{data.eventTitle}</Typography>
      <Typography variant="body2" color="text.secondary">
        {data.venueName} · {new Date(data.eventStartTime).toLocaleString()}
      </Typography>
      {data.finalized && (
        <Chip label="Finalized" color="success" sx={{ mt: 1 }} />
      )}
      {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}

      <Grid container spacing={2} sx={{ mt: 1 }}>
        <Grid item xs={12} md={7}>
          <Card>
            <CardContent>
              <Stack direction="row" justifyContent="space-between" alignItems="center">
                <Typography variant="h6">Revenue summary</Typography>
                <Stack direction="row" spacing={1}>
                  <Button size="small" startIcon={<DownloadIcon />} href={reportsApi.settlementCsvUrl(orgId, eventId)}>CSV</Button>
                  <Button size="small" startIcon={<PrintIcon />} href={reportsApi.settlementHtmlUrl(orgId, eventId)} target="_blank" rel="noreferrer">Print</Button>
                </Stack>
              </Stack>
              <Box sx={{ mt: 2 }}>
                <Row label="Gross (PAID face values)" value={currency(d.gross)} />
                <Row label="Add-ons" value={currency(d.addOnRevenue)} />
                <Row label="Sales tax collected" value={currency(d.salesTaxCollected)} />
                <Row label="Refunds (pre-show)" value={`(${currency(d.preShowRefunds)})`} />
                <Row label="Refunds (post-show, within 24h)" value={`(${currency(data.postShowRefunds)})`} />
                <Row label="Platform fee" value={`(${currency(d.platformFee)})`} />
                <Row label="Stripe processing fee" value={`(${currency(d.stripeProcessingFee)})`} />
                <Row label="Net (settlement)" value={currency(settlementNet)} total />
                <Box sx={{ mt: 2 }}>
                  <Row label="Artist payout" value={currency(data.artistPayout)} />
                  <Row label="Venue retention" value={currency(data.venueRetention)} total />
                </Box>
              </Box>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={5}>
          <Card>
            <CardContent>
              <Typography variant="h6">Split configuration</Typography>
              <Stack spacing={2} sx={{ mt: 2 }}>
                <TextField
                  select
                  label="Split type"
                  value={config.splitType || ''}
                  onChange={(e) => setConfig({ ...config, splitType: e.target.value || null })}
                  disabled={data.finalized}
                >
                  {SPLIT_TYPES.map((t) => (
                    <MenuItem key={t.value} value={t.value}>{t.label}</MenuItem>
                  ))}
                </TextField>
                <TextField
                  label="Artist share (0..1, e.g. 0.85)"
                  type="number"
                  inputProps={{ step: '0.01', min: 0, max: 1 }}
                  value={config.artistPct ?? ''}
                  onChange={(e) => setConfig({ ...config, artistPct: e.target.value })}
                  disabled={data.finalized || !config.splitType}
                />
                <TextField
                  label="Venue off the top ($)"
                  type="number"
                  inputProps={{ step: '0.01', min: 0 }}
                  value={config.venueTakeOffTop ?? ''}
                  onChange={(e) => setConfig({ ...config, venueTakeOffTop: e.target.value })}
                  disabled={data.finalized || config.splitType !== 'DOOR_DEAL'}
                />
                <TextField
                  label="Tax rate override (0..1)"
                  type="number"
                  inputProps={{ step: '0.0001', min: 0, max: 1 }}
                  value={config.taxRatePct ?? ''}
                  onChange={(e) => setConfig({ ...config, taxRatePct: e.target.value })}
                  disabled={data.finalized}
                  helperText="Falls back to the org default when blank"
                />
                <TextField
                  label="Notes"
                  multiline
                  minRows={3}
                  value={config.notes || ''}
                  onChange={(e) => setConfig({ ...config, notes: e.target.value })}
                  disabled={data.finalized}
                />
                <Stack direction="row" spacing={1}>
                  <Button variant="contained" onClick={saveConfig} disabled={busy || data.finalized}>
                    Save
                  </Button>
                  <Button color="success" variant="outlined" onClick={finalize}
                          disabled={busy || data.finalized || !config.splitType}>
                    Finalize & sign off
                  </Button>
                </Stack>
                <Divider />
                <Typography variant="caption" color="text.secondary">
                  Finalizing freezes the agreement so the signable PDF references a frozen split.
                  Post-show refunds beyond the 24h window are excluded from the settlement net per the
                  implementation plan.
                </Typography>
              </Stack>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
}
