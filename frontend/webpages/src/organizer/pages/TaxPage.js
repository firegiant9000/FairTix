import React, { useEffect, useState, useCallback } from 'react';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Grid from '@mui/material/Grid';
import LinearProgress from '@mui/material/LinearProgress';
import TextField from '@mui/material/TextField';
import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';
import Alert from '@mui/material/Alert';
import Skeleton from '@mui/material/Skeleton';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import DownloadIcon from '@mui/icons-material/Download';
import { useOrganization } from '../useOrganization';
import { reportsApi } from '../reportsApi';

const currency = (n) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(Number(n || 0));

const pct = (n) => `${(Number(n || 0) * 100).toFixed(2)}%`;

export default function TaxPage() {
  const { current } = useOrganization();
  const orgId = current?.id;
  const year = new Date().getUTCFullYear();
  const [threshold, setThreshold] = useState(null);
  const [yearData, setYearData] = useState(null);
  const [error, setError] = useState(null);
  const [form, setForm] = useState({ state: '', defaultTaxRatePct: '', taxLegalName: '', ein: '' });
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!orgId) return;
    try {
      const [t, y] = await Promise.all([
        reportsApi.taxThreshold(orgId, year),
        reportsApi.taxYear(orgId, year),
      ]);
      setThreshold(t);
      setYearData(y);
      setForm({
        state: t.state || '',
        defaultTaxRatePct: t.defaultTaxRatePct ?? '',
        taxLegalName: t.taxLegalName || '',
        ein: '',
      });
    } catch (e) {
      setError(e?.message || 'Failed to load tax helper');
    }
  }, [orgId, year]);

  useEffect(() => { load(); }, [load]);

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await reportsApi.putTaxConfig(orgId, {
        state: form.state || null,
        defaultTaxRatePct: form.defaultTaxRatePct === '' ? null : Number(form.defaultTaxRatePct),
        taxLegalName: form.taxLegalName || null,
        ein: form.ein || null,
      });
      await load();
    } catch (e) {
      setError(e?.message || 'Save failed');
    } finally {
      setBusy(false);
    }
  };

  if (!threshold || !yearData) {
    return <Box sx={{ p: 3 }}><Skeleton variant="rectangular" height={200} /></Box>;
  }

  const progress = Math.min(100, Math.round(Number(threshold.pctOfThreshold || 0) * 100));

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h4" sx={{ fontWeight: 700, mb: 2 }}>Tax helper</Typography>
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

      <Grid container spacing={2}>
        <Grid item xs={12} md={7}>
          <Card>
            <CardContent>
              <Typography variant="h6">1099-K threshold ({threshold.year})</Typography>
              <Box sx={{ mt: 2 }}>
                <Typography variant="body2" color="text.secondary">
                  YTD gross: <strong>{currency(threshold.ytdGross)}</strong> across {String(threshold.ytdTransactions)} transactions
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  Federal threshold: {currency(threshold.threshold)}
                </Typography>
                <Box sx={{ mt: 1.5 }}>
                  <LinearProgress
                    variant="determinate"
                    value={progress}
                    color={threshold.alert ? 'warning' : 'primary'}
                  />
                  <Typography variant="caption" color="text.secondary">
                    {pct(threshold.pctOfThreshold)} of threshold
                  </Typography>
                </Box>
                {threshold.alert && (
                  <Alert severity="warning" sx={{ mt: 2 }}>
                    You are at or above 80% of the federal 1099-K threshold. Make sure your legal
                    name + EIN are on file before year end.
                  </Alert>
                )}
              </Box>
            </CardContent>
          </Card>

          <Card sx={{ mt: 2 }}>
            <CardContent>
              <Stack direction="row" justifyContent="space-between" alignItems="center">
                <Typography variant="h6">Year-end per-event rollup</Typography>
                <Button startIcon={<DownloadIcon />} href={reportsApi.taxYearCsvUrl(orgId, year)}>CSV</Button>
              </Stack>
              <Table size="small" sx={{ mt: 2 }}>
                <TableHead>
                  <TableRow>
                    <TableCell>Event</TableCell>
                    <TableCell>Date</TableCell>
                    <TableCell align="right">Gross</TableCell>
                    <TableCell align="right">Rate</TableCell>
                    <TableCell align="right">Tax collected</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {yearData.rows.length === 0 && (
                    <TableRow><TableCell colSpan={5}>No events in {year}.</TableCell></TableRow>
                  )}
                  {yearData.rows.map((r) => (
                    <TableRow key={r.eventId}>
                      <TableCell>{r.title}</TableCell>
                      <TableCell>{r.eventDate}</TableCell>
                      <TableCell align="right">{currency(r.gross)}</TableCell>
                      <TableCell align="right">{pct(r.taxRatePct)}</TableCell>
                      <TableCell align="right">{currency(r.taxCollected)}</TableCell>
                    </TableRow>
                  ))}
                  <TableRow>
                    <TableCell colSpan={2}><strong>Total</strong></TableCell>
                    <TableCell align="right"><strong>{currency(yearData.totalGross)}</strong></TableCell>
                    <TableCell />
                    <TableCell align="right"><strong>{currency(yearData.totalTaxCollected)}</strong></TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={5}>
          <Card>
            <CardContent>
              <Typography variant="h6">Tax configuration</Typography>
              <Stack spacing={2} sx={{ mt: 2 }}>
                <TextField
                  label="State (2-letter)"
                  value={form.state}
                  onChange={(e) => setForm({ ...form, state: e.target.value.toUpperCase().slice(0, 2) })}
                  inputProps={{ maxLength: 2 }}
                />
                <TextField
                  label="Default sales tax rate (0..1)"
                  type="number"
                  inputProps={{ step: '0.0001', min: 0, max: 1 }}
                  value={form.defaultTaxRatePct}
                  onChange={(e) => setForm({ ...form, defaultTaxRatePct: e.target.value })}
                  helperText="Per-event override lives on the settlement page."
                />
                <TextField
                  label="Legal name (for 1099)"
                  value={form.taxLegalName}
                  onChange={(e) => setForm({ ...form, taxLegalName: e.target.value })}
                />
                <TextField
                  label="EIN"
                  value={form.ein}
                  onChange={(e) => setForm({ ...form, ein: e.target.value })}
                  helperText={threshold.einOnFile
                    ? 'EIN on file. Re-enter to overwrite.'
                    : 'Not yet on file. Required before issuing a 1099-K.'}
                />
                <Button variant="contained" onClick={save} disabled={busy}>Save</Button>
              </Stack>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
}
