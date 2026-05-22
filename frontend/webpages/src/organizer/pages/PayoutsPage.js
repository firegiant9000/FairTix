import React, { useEffect, useState, useCallback } from 'react';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Chip from '@mui/material/Chip';
import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';
import Alert from '@mui/material/Alert';
import Skeleton from '@mui/material/Skeleton';
import RefreshIcon from '@mui/icons-material/Refresh';
import DownloadIcon from '@mui/icons-material/Download';
import { useOrganization } from '../useOrganization';
import { reportsApi } from '../reportsApi';

const currency = (n, c = 'USD') =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: c }).format(Number(n || 0));

const statusColor = (s) => {
  switch (s) {
    case 'paid': return 'success';
    case 'failed': return 'error';
    case 'pending':
    case 'in_transit': return 'warning';
    default: return 'default';
  }
};

export default function PayoutsPage() {
  const { current } = useOrganization();
  const orgId = current?.id;
  const [rows, setRows] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!orgId) return;
    try {
      const r = await reportsApi.payouts(orgId, 30);
      setRows(r || []);
    } catch (e) {
      setError(e?.message || 'Failed to load payouts');
    }
  }, [orgId]);

  useEffect(() => { load(); }, [load]);

  const sync = async () => {
    setBusy(true);
    setError(null);
    try {
      await reportsApi.syncPayouts(orgId, 25);
      await load();
    } catch (e) {
      setError(e?.message || 'Sync failed (Stripe disabled?)');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box sx={{ p: 3 }}>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h4" sx={{ fontWeight: 700 }}>Payouts</Typography>
        <Stack direction="row" spacing={1}>
          <Button startIcon={<RefreshIcon />} onClick={sync} disabled={busy}>Sync from Stripe</Button>
          <Button startIcon={<DownloadIcon />} href={reportsApi.payoutsCsvUrl(orgId, 30)}>CSV</Button>
        </Stack>
      </Stack>

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

      <Card>
        <CardContent>
          {!rows && <Skeleton variant="rectangular" height={140} />}
          {rows && rows.length === 0 && (
            <Typography color="text.secondary">No payouts in the last 30 days.</Typography>
          )}
          {rows && rows.length > 0 && (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Payout</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Amount</TableCell>
                  <TableCell>Arrival</TableCell>
                  <TableCell>Paid at</TableCell>
                  <TableCell>Events</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((p) => (
                  <React.Fragment key={p.stripePayoutId}>
                    <TableRow>
                      <TableCell sx={{ fontFamily: 'monospace', fontSize: 12 }}>{p.stripePayoutId}</TableCell>
                      <TableCell>
                        <Chip size="small" label={p.status} color={statusColor(p.status)} />
                      </TableCell>
                      <TableCell>{currency(p.amount, (p.currency || 'usd').toUpperCase())}</TableCell>
                      <TableCell>{p.arrivalDate || '-'}</TableCell>
                      <TableCell>{p.paidAt ? new Date(p.paidAt).toLocaleString() : '-'}</TableCell>
                      <TableCell>{p.events?.length || 0}</TableCell>
                    </TableRow>
                    {p.events?.map((e) => (
                      <TableRow key={p.stripePayoutId + e.eventId} sx={{ '& td': { color: 'text.secondary' } }}>
                        <TableCell colSpan={2} sx={{ pl: 4 }}>↳ {e.title}</TableCell>
                        <TableCell>{currency(e.grossContributed)}</TableCell>
                        <TableCell colSpan={2}>{new Date(e.startTime).toLocaleDateString()}</TableCell>
                        <TableCell>{e.ticketsSold} tickets</TableCell>
                      </TableRow>
                    ))}
                    {p.failureMessage && (
                      <TableRow>
                        <TableCell colSpan={6}>
                          <Alert severity="error" sx={{ my: 1 }}>
                            {p.failureCode}: {p.failureMessage}
                          </Alert>
                        </TableCell>
                      </TableRow>
                    )}
                  </React.Fragment>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </Box>
  );
}
