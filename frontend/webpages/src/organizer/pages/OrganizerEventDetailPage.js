import React, { useEffect, useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Grid from '@mui/material/Grid';
import Chip from '@mui/material/Chip';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import Skeleton from '@mui/material/Skeleton';
import Alert from '@mui/material/Alert';
import Pagination from '@mui/material/Pagination';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import DownloadIcon from '@mui/icons-material/Download';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from 'recharts';
import { useOrganization } from '../useOrganization';
import { organizerApi } from '../organizerApi';

const currency = (n) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(Number(n || 0));

function StatBlock({ label, value, hint }) {
  return (
    <Box sx={{ p: 2, borderRadius: 1, backgroundColor: 'background.default' }}>
      <Typography variant="caption" color="text.secondary" sx={{ textTransform: 'uppercase' }}>
        {label}
      </Typography>
      <Typography variant="h5" sx={{ fontWeight: 700, mt: 0.5 }}>{value}</Typography>
      {hint && (
        <Typography variant="caption" color="text.secondary">{hint}</Typography>
      )}
    </Box>
  );
}

function InventoryBar({ inventory }) {
  const { available, held, sold, comped, capacity } = inventory || {};
  if (!capacity) return null;
  const pct = (n) => (capacity ? (Number(n || 0) / capacity) * 100 : 0);
  return (
    <Box sx={{ mt: 2 }}>
      <Box sx={{ display: 'flex', height: 16, borderRadius: 1, overflow: 'hidden', backgroundColor: 'background.default' }}>
        <Box sx={{ width: `${pct(sold)}%`, backgroundColor: 'success.main' }} title={`${sold} sold`} />
        <Box sx={{ width: `${pct(held)}%`, backgroundColor: 'warning.main' }} title={`${held} held`} />
        <Box sx={{ width: `${pct(comped)}%`, backgroundColor: 'info.main' }} title={`${comped} comped`} />
        <Box sx={{ width: `${pct(available)}%`, backgroundColor: 'text.disabled' }} title={`${available} available`} />
      </Box>
      <Box sx={{ display: 'flex', gap: 2, mt: 1, flexWrap: 'wrap' }}>
        <Chip size="small" label={`Sold ${sold}`} color="success" />
        <Chip size="small" label={`Held ${held}`} color="warning" />
        <Chip size="small" label={`Comped ${comped}`} color="info" />
        <Chip size="small" label={`Available ${available}`} variant="outlined" />
        <Chip size="small" label={`Capacity ${capacity}`} variant="outlined" />
      </Box>
    </Box>
  );
}

function VelocityChart({ data }) {
  const chartData = useMemo(
    () => (data || []).map((d) => ({
      date: d.date,
      tickets: Number(d.ticketsSold || 0),
      revenue: Number(d.revenue || 0),
    })),
    [data]
  );
  if (chartData.length === 0) {
    return <Typography variant="body2" color="text.secondary">No sales yet in this window.</Typography>;
  }
  return (
    <ResponsiveContainer width="100%" height={260}>
      <LineChart data={chartData}>
        <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.1)" />
        <XAxis dataKey="date" stroke="#b0b0b0" tick={{ fontSize: 11 }} />
        <YAxis stroke="#b0b0b0" allowDecimals={false} />
        <Tooltip contentStyle={{ backgroundColor: '#16213e', border: 'none', color: '#fff' }} />
        <Line type="monotone" dataKey="tickets" stroke="#4caf50" strokeWidth={2} dot={{ r: 3 }} name="Tickets sold" />
      </LineChart>
    </ResponsiveContainer>
  );
}

function OrganizerEventDetailPage() {
  const { eventId } = useParams();
  const { current } = useOrganization();
  const navigate = useNavigate();

  const [summary, setSummary] = useState(null);
  const [velocity, setVelocity] = useState([]);
  const [attendees, setAttendees] = useState({ attendees: [], total: 0, page: 0, size: 50 });
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!current?.id || !eventId) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    Promise.all([
      organizerApi.eventSummary(current.id, eventId),
      organizerApi.velocity(current.id, eventId, 14),
    ])
      .then(([s, v]) => {
        if (cancelled) return;
        setSummary(s);
        setVelocity(v || []);
      })
      .catch((e) => { if (!cancelled) setError(e); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [current?.id, eventId]);

  useEffect(() => {
    if (!current?.id || !eventId) return;
    let cancelled = false;
    organizerApi
      .attendees(current.id, eventId, { q: search, page, size: 25 })
      .then((data) => { if (!cancelled) setAttendees(data); })
      .catch((e) => { if (!cancelled) setError(e); });
    return () => { cancelled = true; };
  }, [current?.id, eventId, search, page]);

  if (loading) return <Skeleton variant="rectangular" height={500} />;
  if (error) return <Alert severity="error">Could not load event: {error.message}</Alert>;
  if (!summary) return null;

  const totalPages = Math.max(1, Math.ceil((attendees.total || 0) / (attendees.size || 25)));
  const csvHref = (process.env.REACT_APP_API_URL || '') +
    organizerApi.attendeesCsvUrl(current.id, eventId);

  const downloadCsv = async () => {
    // Use fetch with credentials to honour cookie auth, then trigger a blob download.
    const res = await fetch(csvHref, { credentials: 'include' });
    if (!res.ok) return;
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `attendees-${eventId}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <Box>
      <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/organizer/events')} sx={{ mb: 2 }}>
        Back to events
      </Button>

      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', mb: 2, flexWrap: 'wrap', gap: 2 }}>
        <Box>
          <Typography variant="h4" sx={{ fontWeight: 700 }}>{summary.title}</Typography>
          <Typography variant="body2" color="text.secondary">
            {summary.venueName || '—'} · {new Date(summary.startTime).toLocaleString()}
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
          <Chip label={summary.status} color="primary" />
          <Button size="small" variant="outlined" onClick={() => navigate(`/organizer/events/${eventId}/comps`)}>Comps</Button>
          <Button size="small" variant="outlined" onClick={() => navigate(`/organizer/events/${eventId}/holds`)}>Holds</Button>
          <Button size="small" variant="outlined" onClick={() => navigate(`/organizer/events/${eventId}/will-call`)}>Will-call</Button>
        </Box>
      </Box>

      <Grid container spacing={2}>
        <Grid item xs={12} md={8}>
          <Card>
            <CardContent>
              <Typography variant="h6" sx={{ fontWeight: 600, mb: 1 }}>Inventory</Typography>
              <InventoryBar inventory={summary.inventory} />
            </CardContent>
          </Card>

          <Card sx={{ mt: 2 }}>
            <CardContent>
              <Typography variant="h6" sx={{ fontWeight: 600, mb: 1 }}>Sales velocity (last 14 days)</Typography>
              <VelocityChart data={velocity} />
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={4}>
          <Card>
            <CardContent>
              <Typography variant="h6" sx={{ fontWeight: 600, mb: 2 }}>Financials</Typography>
              <Grid container spacing={1}>
                <Grid item xs={12}><StatBlock label="Gross" value={currency(summary.financials.gross)} /></Grid>
                <Grid item xs={12}>
                  <StatBlock
                    label="Platform fee"
                    value={currency(summary.financials.platformFee)}
                    hint={`${(summary.financials.platformFeeBps / 100).toFixed(2)}% of gross`}
                  />
                </Grid>
                <Grid item xs={12}>
                  <StatBlock label="Stripe fee (est.)" value={currency(summary.financials.stripeFee)} hint="2.9% + $0.30/ticket" />
                </Grid>
                <Grid item xs={12}>
                  <StatBlock label="Payout estimate" value={currency(summary.financials.payoutEstimate)} hint="Gross − fees" />
                </Grid>
              </Grid>
            </CardContent>
          </Card>

          <Card sx={{ mt: 2 }}>
            <CardContent>
              <Typography variant="h6" sx={{ fontWeight: 600, mb: 1 }}>Refunds</Typography>
              <Typography variant="body2">Pending: <strong>{summary.refundsPending}</strong></Typography>
              <Typography variant="body2">Completed: <strong>{summary.refundsCompleted}</strong></Typography>
            </CardContent>
          </Card>

          <Card sx={{ mt: 2 }}>
            <CardContent>
              <Typography variant="h6" sx={{ fontWeight: 600, mb: 1 }}>Scan progress</Typography>
              <Typography variant="body2" color="text.secondary">
                Live scan stream wires in with the M3 scanner module.
              </Typography>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12}>
          <Card>
            <CardContent>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2, gap: 2, flexWrap: 'wrap' }}>
                <Typography variant="h6" sx={{ fontWeight: 600 }}>
                  Attendees <Typography component="span" variant="body2" color="text.secondary">({attendees.total})</Typography>
                </Typography>
                <Box sx={{ display: 'flex', gap: 1 }}>
                  <TextField
                    size="small"
                    placeholder="Search by email…"
                    value={search}
                    onChange={(e) => { setPage(0); setSearch(e.target.value); }}
                  />
                  <Button startIcon={<DownloadIcon />} variant="outlined" onClick={downloadCsv}>
                    Export CSV
                  </Button>
                </Box>
              </Box>

              {attendees.attendees.length === 0 ? (
                <Typography variant="body2" color="text.secondary">No matching attendees.</Typography>
              ) : (
                <>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>Email</TableCell>
                        <TableCell>Seat</TableCell>
                        <TableCell>Status</TableCell>
                        <TableCell align="right">Price</TableCell>
                        <TableCell>Issued</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {attendees.attendees.map((a) => (
                        <TableRow key={a.ticketId}>
                          <TableCell>{a.buyerEmail}</TableCell>
                          <TableCell>{a.seatSection} {a.seatRow}-{a.seatNumber}</TableCell>
                          <TableCell>
                            <Chip size="small" label={a.status} />
                          </TableCell>
                          <TableCell align="right">{currency(a.price)}</TableCell>
                          <TableCell>{new Date(a.issuedAt).toLocaleString()}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                  {totalPages > 1 && (
                    <Box sx={{ display: 'flex', justifyContent: 'center', mt: 2 }}>
                      <Pagination
                        page={page + 1}
                        count={totalPages}
                        onChange={(_, p) => setPage(p - 1)}
                      />
                    </Box>
                  )}
                </>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
}

export default OrganizerEventDetailPage;
