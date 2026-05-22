import React, { useCallback, useEffect, useMemo, useState } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Typography from '@mui/material/Typography';
import TextField from '@mui/material/TextField';
import Stack from '@mui/material/Stack';
import Alert from '@mui/material/Alert';
import Chip from '@mui/material/Chip';
import Divider from '@mui/material/Divider';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import Grid from '@mui/material/Grid';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import CircularProgress from '@mui/material/CircularProgress';
import { useOrganization } from '../useOrganization';
import { boxOfficeApi } from '../boxOfficeApi';

const currency = (n) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(Number(n || 0));

function OpenShift({ orgId, onOpened }) {
  const [cash, setCash] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const amount = Number(cash);
      if (Number.isNaN(amount) || amount < 0) throw new Error('Enter a non-negative number');
      const session = await boxOfficeApi.openSession(orgId, amount);
      onOpened(session);
    } catch (e) {
      setError(e.message || 'Failed to open session');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card sx={{ maxWidth: 480, mx: 'auto', mt: 8 }}>
      <CardContent>
        <Typography variant="h5" sx={{ fontWeight: 700, mb: 1 }}>Open box-office shift</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
          Count the cash in the drawer and enter the opening total.
        </Typography>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <TextField
          label="Opening cash ($)"
          type="number"
          value={cash}
          onChange={(e) => setCash(e.target.value)}
          fullWidth
          inputProps={{ inputMode: 'decimal', step: '0.01', min: '0' }}
          sx={{ mb: 2 }}
        />
        <Button variant="contained" size="large" fullWidth onClick={submit} disabled={busy}>
          {busy ? 'Opening…' : 'Open shift'}
        </Button>
      </CardContent>
    </Card>
  );
}

function SeatPicker({ event, selected, onToggle }) {
  const [seats, setSeats] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    setSeats(null);
    setError(null);
    boxOfficeApi
      .seatMap(event.id)
      .then((data) => { if (!cancelled) setSeats(data); })
      .catch((e) => { if (!cancelled) setError(e); });
    return () => { cancelled = true; };
  }, [event.id]);

  if (error) return <Alert severity="error">Could not load seats: {error.message}</Alert>;
  if (!seats) return <CircularProgress />;

  const available = seats.filter((s) => s.status === 'AVAILABLE');
  if (available.length === 0) return <Alert severity="info">No available seats.</Alert>;

  return (
    <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, maxHeight: 320, overflowY: 'auto' }}>
      {available.map((s) => {
        const isSelected = selected.includes(s.id);
        return (
          <Chip
            key={s.id}
            label={`${s.section} ${s.rowLabel}-${s.seatNumber} (${currency(s.price)})`}
            color={isSelected ? 'primary' : 'default'}
            onClick={() => onToggle(s.id)}
            sx={{ fontSize: '0.95rem', height: 40 }}
          />
        );
      })}
    </Box>
  );
}

function Floor({ orgId, session, onClose }) {
  const [events, setEvents] = useState([]);
  const [selectedEvent, setSelectedEvent] = useState(null);
  const [selectedSeats, setSelectedSeats] = useState([]);
  const [customerEmail, setCustomerEmail] = useState('');
  const [customerName, setCustomerName] = useState('');
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(null);
  const [busy, setBusy] = useState(false);
  const [compDialogOpen, setCompDialogOpen] = useState(false);
  const [compReason, setCompReason] = useState('');
  const [report, setReport] = useState(null);

  const reload = useCallback(async () => {
    try {
      const [evts, rpt] = await Promise.all([
        boxOfficeApi.todaysEvents(orgId),
        boxOfficeApi.sessionReport(orgId, session.id),
      ]);
      setEvents(evts || []);
      setReport(rpt);
    } catch (e) {
      setError(e);
    }
  }, [orgId, session.id]);

  useEffect(() => { reload(); }, [reload]);

  const total = useMemo(() => {
    if (!selectedEvent) return 0;
    // Total is reflected on the server; this is a UI estimate. The Chip labels
    // include the per-seat price, so we sum from the SeatPicker's view via the
    // map data on the client.
    return null;
  }, [selectedEvent, selectedSeats]);

  const toggle = (seatId) => {
    setSelectedSeats((s) => (s.includes(seatId) ? s.filter((x) => x !== seatId) : [...s, seatId]));
  };

  const reset = () => {
    setSelectedSeats([]);
    setCustomerEmail('');
    setCustomerName('');
    setCompReason('');
  };

  const sell = async (method) => {
    if (!selectedEvent || selectedSeats.length === 0) {
      setError(new Error('Pick an event and at least one seat'));
      return;
    }
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      const payload = {
        eventId: selectedEvent.id,
        seatIds: selectedSeats,
        customerEmail: customerEmail || null,
        customerName: customerName || null,
      };
      let sale;
      if (method === 'CASH') {
        sale = await boxOfficeApi.cashSale(orgId, session.id, payload);
      } else if (method === 'COMP') {
        if (!compReason.trim()) {
          setError(new Error('Comp reason is required'));
          setBusy(false);
          return;
        }
        sale = await boxOfficeApi.compSale(orgId, session.id, { ...payload, reason: compReason });
        setCompDialogOpen(false);
      } else if (method === 'CARD') {
        const intent = await boxOfficeApi.createCardIntent(orgId, session.id, {
          ...payload,
          terminalReaderId: null,
        });
        // Terminal SDK normally takes intent.clientSecret on a paired reader and
        // calls collectPaymentMethod → processPayment. In dev without a reader
        // we cannot complete this client-side. The frontend dispatches an event
        // a Terminal-integrated wrapper can listen for.
        window.dispatchEvent(
          new CustomEvent('box-office:collect-card-present', { detail: intent })
        );
        setSuccess(
          `Card payment intent created (id ${intent.paymentIntentId}). ` +
          'Hand the reader to the customer to complete; confirm from the receipts area when done.'
        );
        await reload();
        reset();
        setBusy(false);
        return;
      }
      setSuccess(`${method} sale recorded — ${currency(sale.amount)} for ${sale.seatCount} seat(s)`);
      reset();
      await reload();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h5" sx={{ fontWeight: 700 }}>Box office</Typography>
        <Stack direction="row" spacing={1}>
          <Chip label={`Cash: ${currency(report?.cashSalesTotal)}`} color="success" />
          <Chip label={`Card: ${currency(report?.cardSalesTotal)}`} color="primary" />
          <Chip label={`Comps: ${report?.compSaleCount || 0}`} />
          <Button variant="outlined" color="warning" onClick={onClose}>Close shift</Button>
        </Stack>
      </Stack>

      {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>{error.message}</Alert>}
      {success && <Alert severity="success" sx={{ mb: 2 }} onClose={() => setSuccess(null)}>{success}</Alert>}

      <Grid container spacing={2}>
        <Grid item xs={12} md={4}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 1 }}>Tonight's shows</Typography>
              {events.length === 0 ? (
                <Typography variant="body2" color="text.secondary">No shows scheduled.</Typography>
              ) : (
                <Stack spacing={1}>
                  {events.map((e) => (
                    <Button
                      key={e.id}
                      variant={selectedEvent?.id === e.id ? 'contained' : 'outlined'}
                      size="large"
                      fullWidth
                      onClick={() => { setSelectedEvent(e); setSelectedSeats([]); }}
                      sx={{ justifyContent: 'flex-start', textAlign: 'left', py: 2 }}
                    >
                      <Box>
                        <Typography variant="body1" sx={{ fontWeight: 600 }}>{e.title}</Typography>
                        <Typography variant="caption" color="text.secondary">
                          {new Date(e.startTime).toLocaleString()} · {e.venueName}
                        </Typography>
                      </Box>
                    </Button>
                  ))}
                </Stack>
              )}
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={8}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              {!selectedEvent ? (
                <Typography variant="body2" color="text.secondary">
                  Pick a show on the left to start a sale.
                </Typography>
              ) : (
                <>
                  <Typography variant="h6" sx={{ mb: 2 }}>
                    {selectedEvent.title} — pick seats
                  </Typography>
                  <SeatPicker event={selectedEvent} selected={selectedSeats} onToggle={toggle} />
                  <Divider sx={{ my: 2 }} />
                  <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mb: 2 }}>
                    <TextField
                      label="Customer name (optional)"
                      value={customerName}
                      onChange={(e) => setCustomerName(e.target.value)}
                      fullWidth
                    />
                    <TextField
                      label="Customer email (optional)"
                      value={customerEmail}
                      onChange={(e) => setCustomerEmail(e.target.value)}
                      fullWidth
                    />
                  </Stack>
                  <Stack direction="row" spacing={2}>
                    <Button
                      variant="contained"
                      color="success"
                      size="large"
                      disabled={busy || selectedSeats.length === 0}
                      onClick={() => sell('CASH')}
                      sx={{ flex: 1, py: 2 }}
                    >
                      Cash — {selectedSeats.length} seat(s)
                    </Button>
                    <Button
                      variant="contained"
                      size="large"
                      disabled={busy || selectedSeats.length === 0}
                      onClick={() => sell('CARD')}
                      sx={{ flex: 1, py: 2 }}
                    >
                      Card (Terminal)
                    </Button>
                    <Button
                      variant="outlined"
                      size="large"
                      disabled={busy || selectedSeats.length === 0}
                      onClick={() => setCompDialogOpen(true)}
                      sx={{ flex: 1, py: 2 }}
                    >
                      Comp
                    </Button>
                  </Stack>
                </>
              )}
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12}>
          <Card>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 1 }}>Sales this shift</Typography>
              {(report?.sales?.length ?? 0) === 0 ? (
                <Typography variant="body2" color="text.secondary">No sales yet.</Typography>
              ) : (
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>When</TableCell>
                      <TableCell>Method</TableCell>
                      <TableCell>Seats</TableCell>
                      <TableCell>Customer</TableCell>
                      <TableCell align="right">Amount</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {report.sales.map((s) => (
                      <TableRow key={s.id}>
                        <TableCell>{new Date(s.createdAt).toLocaleTimeString()}</TableCell>
                        <TableCell>{s.method}</TableCell>
                        <TableCell>{s.seatCount}</TableCell>
                        <TableCell>{s.customerName || s.customerEmail || '—'}</TableCell>
                        <TableCell align="right">{currency(s.amount)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Dialog open={compDialogOpen} onClose={() => setCompDialogOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>Issue comp</DialogTitle>
        <DialogContent>
          <TextField
            label="Reason"
            value={compReason}
            onChange={(e) => setCompReason(e.target.value)}
            fullWidth
            multiline
            rows={3}
            sx={{ mt: 1 }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCompDialogOpen(false)}>Cancel</Button>
          <Button variant="contained" disabled={!compReason.trim() || busy} onClick={() => sell('COMP')}>
            Issue comp
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

function CloseShift({ orgId, session, onClosed, onCancel }) {
  const [closingCash, setClosingCash] = useState('');
  const [reason, setReason] = useState('');
  const [report, setReport] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    boxOfficeApi.sessionReport(orgId, session.id).then(setReport).catch(setError);
  }, [orgId, session.id]);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const amount = Number(closingCash);
      if (Number.isNaN(amount) || amount < 0) throw new Error('Enter a non-negative number');
      const closed = await boxOfficeApi.closeSession(orgId, session.id, amount, reason || null);
      onClosed(closed);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  if (!report) return <CircularProgress sx={{ mt: 8 }} />;
  const expected = Number(report.expectedCash || 0);
  const variance = closingCash === '' ? null : Number(closingCash) - expected;
  const needsReason = variance !== null && Math.abs(variance) > 5 && !reason.trim();

  return (
    <Card sx={{ maxWidth: 640, mx: 'auto', mt: 4 }}>
      <CardContent>
        <Typography variant="h5" sx={{ fontWeight: 700, mb: 2 }}>Close shift</Typography>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error.message}</Alert>}
        <Grid container spacing={2} sx={{ mb: 2 }}>
          <Grid item xs={6}><Chip label={`Opening cash: ${currency(report.session.openingCash)}`} /></Grid>
          <Grid item xs={6}><Chip label={`Cash sales: ${currency(report.cashSalesTotal)}`} color="success" /></Grid>
          <Grid item xs={6}><Chip label={`Card sales: ${currency(report.cardSalesTotal)}`} color="primary" /></Grid>
          <Grid item xs={6}><Chip label={`Comps: ${report.compSaleCount}`} /></Grid>
          <Grid item xs={12}><Chip label={`Expected cash in drawer: ${currency(expected)}`} sx={{ fontWeight: 700 }} /></Grid>
        </Grid>
        <TextField
          label="Closing cash count ($)"
          type="number"
          value={closingCash}
          onChange={(e) => setClosingCash(e.target.value)}
          fullWidth
          inputProps={{ inputMode: 'decimal', step: '0.01', min: '0' }}
          sx={{ mb: 2 }}
        />
        {variance !== null && (
          <Alert severity={Math.abs(variance) > 5 ? 'warning' : 'info'} sx={{ mb: 2 }}>
            Variance: {currency(variance)} {Math.abs(variance) > 5 && '— reason required'}
          </Alert>
        )}
        <TextField
          label="Variance reason (required when >$5 off)"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          fullWidth
          multiline
          rows={2}
          sx={{ mb: 2 }}
        />
        <Stack direction="row" spacing={2}>
          <Button onClick={onCancel} fullWidth>Back</Button>
          <Button
            variant="contained"
            onClick={submit}
            disabled={busy || closingCash === '' || needsReason}
            fullWidth
            size="large"
          >
            {busy ? 'Closing…' : 'Close & sign off'}
          </Button>
        </Stack>
      </CardContent>
    </Card>
  );
}

function BoxOfficePage() {
  const { current, isLoading } = useOrganization();
  const [session, setSession] = useState(null);
  const [closing, setClosing] = useState(false);
  const [closedReport, setClosedReport] = useState(null);
  const [bootError, setBootError] = useState(null);
  const [booting, setBooting] = useState(true);

  useEffect(() => {
    if (!current?.id) return;
    let cancelled = false;
    setBooting(true);
    boxOfficeApi
      .activeSession(current.id)
      .then((s) => { if (!cancelled) setSession(s); })
      .catch((e) => {
        if (cancelled) return;
        if (e.status === 404) setSession(null);
        else setBootError(e);
      })
      .finally(() => { if (!cancelled) setBooting(false); });
    return () => { cancelled = true; };
  }, [current?.id]);

  if (isLoading || booting) {
    return <Box sx={{ display: 'flex', justifyContent: 'center', mt: 10 }}><CircularProgress /></Box>;
  }
  if (!current) return <Alert severity="info" sx={{ m: 4 }}>Select an organization to use box office.</Alert>;
  if (bootError) return <Alert severity="error" sx={{ m: 4 }}>{bootError.message}</Alert>;

  if (closedReport) {
    return (
      <Card sx={{ maxWidth: 640, mx: 'auto', mt: 8 }}>
        <CardContent>
          <Typography variant="h5" sx={{ fontWeight: 700, mb: 2 }}>Shift closed</Typography>
          <Stack spacing={1}>
            <Chip label={`Closing cash: ${currency(closedReport.closingCash)}`} />
            <Chip label={`Expected: ${currency(closedReport.expectedCash)}`} />
            <Chip
              label={`Variance: ${currency(closedReport.variance)}`}
              color={Math.abs(Number(closedReport.variance)) > 5 ? 'warning' : 'success'}
            />
            {closedReport.varianceReason && (
              <Typography variant="body2">Reason: {closedReport.varianceReason}</Typography>
            )}
          </Stack>
          <Button
            sx={{ mt: 3 }}
            variant="contained"
            onClick={() => { setClosedReport(null); setSession(null); setClosing(false); }}
          >
            Open new shift
          </Button>
        </CardContent>
      </Card>
    );
  }

  if (!session) {
    return (
      <Box sx={{ p: { xs: 2, md: 4 } }}>
        <OpenShift orgId={current.id} onOpened={setSession} />
      </Box>
    );
  }

  if (closing) {
    return (
      <Box sx={{ p: { xs: 2, md: 4 } }}>
        <CloseShift
          orgId={current.id}
          session={session}
          onClosed={setClosedReport}
          onCancel={() => setClosing(false)}
        />
      </Box>
    );
  }

  return (
    <Box sx={{ p: { xs: 2, md: 3 } }}>
      <Floor orgId={current.id} session={session} onClose={() => setClosing(true)} />
    </Box>
  );
}

export default BoxOfficePage;
