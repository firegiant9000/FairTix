import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Grid from '@mui/material/Grid';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Alert from '@mui/material/Alert';
import Skeleton from '@mui/material/Skeleton';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Chip from '@mui/material/Chip';
import { useOrganization } from '../useOrganization';
import { organizerApi } from '../organizerApi';

const currency = (n) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(Number(n || 0));

function StatCard({ label, value, hint, tone = 'default' }) {
  const toneColor = tone === 'warn' ? 'warning.main' : tone === 'good' ? 'success.main' : 'text.primary';
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Typography variant="caption" color="text.secondary" sx={{ textTransform: 'uppercase' }}>
          {label}
        </Typography>
        <Typography variant="h4" sx={{ mt: 1, fontWeight: 700, color: toneColor }}>
          {value}
        </Typography>
        {hint && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            {hint}
          </Typography>
        )}
      </CardContent>
    </Card>
  );
}

function deltaHint(current, prior) {
  const a = Number(current || 0);
  const b = Number(prior || 0);
  if (b === 0 && a === 0) return 'No prior-week sales';
  if (b === 0) return 'No prior-week comparison';
  const pct = ((a - b) / b) * 100;
  const sign = pct >= 0 ? '+' : '';
  return `${sign}${pct.toFixed(1)}% vs prior week`;
}

function OrganizerDashboard() {
  const { current, isLoading: orgLoading } = useOrganization();
  const navigate = useNavigate();
  const [overview, setOverview] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!current?.id) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    organizerApi
      .overview(current.id)
      .then((data) => { if (!cancelled) setOverview(data); })
      .catch((e) => { if (!cancelled) setError(e); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [current?.id]);

  if (orgLoading) return <Skeleton variant="rectangular" height={300} />;
  if (!current) return <Alert severity="info">Select an organization to view its dashboard.</Alert>;

  const refundCount = overview?.refundQueue?.pendingCount ?? 0;
  const todayShows = overview?.todayShows ?? [];

  return (
    <Box>
      <Typography variant="h4" sx={{ fontWeight: 700, mb: 1 }}>
        {current.name}
      </Typography>
      <Typography variant="body1" color="text.secondary" sx={{ mb: 3 }}>
        Sales, refunds, and tonight's shows at a glance.
      </Typography>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          Could not load dashboard data: {error.message}
        </Alert>
      )}

      <Grid container spacing={2}>
        <Grid item xs={12} sm={6} md={3}>
          {loading ? (
            <Skeleton variant="rectangular" height={120} />
          ) : (
            <StatCard
              label="Today's shows"
              value={todayShows.length}
              hint={todayShows[0]?.title || 'None scheduled today'}
            />
          )}
        </Grid>
        <Grid item xs={12} sm={6} md={3}>
          {loading ? (
            <Skeleton variant="rectangular" height={120} />
          ) : (
            <StatCard
              label="This week's revenue"
              value={currency(overview?.weekRevenue?.grossThisWeek)}
              hint={deltaHint(
                overview?.weekRevenue?.grossThisWeek,
                overview?.weekRevenue?.grossPriorWeek
              )}
            />
          )}
        </Grid>
        <Grid item xs={12} sm={6} md={3}>
          {loading ? (
            <Skeleton variant="rectangular" height={120} />
          ) : (
            <StatCard
              label="Refund queue"
              value={refundCount}
              tone={refundCount > 0 ? 'warn' : 'default'}
              hint={
                overview?.refundQueue?.oldestRequestedAt
                  ? `Oldest: ${new Date(overview.refundQueue.oldestRequestedAt).toLocaleDateString()}`
                  : 'No pending refunds'
              }
            />
          )}
        </Grid>
        <Grid item xs={12} sm={6} md={3}>
          {loading ? (
            <Skeleton variant="rectangular" height={120} />
          ) : (
            <StatCard
              label="Tickets sold this week"
              value={overview?.weekRevenue?.ticketsThisWeek ?? 0}
              hint={deltaHint(
                overview?.weekRevenue?.ticketsThisWeek,
                overview?.weekRevenue?.ticketsPriorWeek
              )}
            />
          )}
        </Grid>
      </Grid>

      <Grid container spacing={2} sx={{ mt: 1 }}>
        <Grid item xs={12} md={6}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography variant="h6" sx={{ fontWeight: 600, mb: 1 }}>
                Today's shows
              </Typography>
              {loading ? (
                <Skeleton variant="rectangular" height={180} />
              ) : todayShows.length === 0 ? (
                <Typography variant="body2" color="text.secondary">No shows scheduled for today.</Typography>
              ) : (
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Event</TableCell>
                      <TableCell>Venue</TableCell>
                      <TableCell>Start</TableCell>
                      <TableCell align="right">Sold / cap</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {todayShows.map((s) => (
                      <TableRow
                        hover
                        key={s.eventId}
                        sx={{ cursor: 'pointer' }}
                        onClick={() => navigate(`/organizer/events/${s.eventId}`)}
                      >
                        <TableCell>{s.title}</TableCell>
                        <TableCell>{s.venueName || '—'}</TableCell>
                        <TableCell>{new Date(s.startTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</TableCell>
                        <TableCell align="right">
                          {s.sold} / {s.capacity || '—'}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={6}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography variant="h6" sx={{ fontWeight: 600, mb: 1 }}>
                Top events (last 7 days)
              </Typography>
              {loading ? (
                <Skeleton variant="rectangular" height={180} />
              ) : (overview?.topEvents?.length ?? 0) === 0 ? (
                <Typography variant="body2" color="text.secondary">No sales in the last 7 days.</Typography>
              ) : (
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Event</TableCell>
                      <TableCell align="right">Tickets</TableCell>
                      <TableCell align="right">Revenue</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {overview.topEvents.map((e) => (
                      <TableRow
                        hover
                        key={e.eventId}
                        sx={{ cursor: 'pointer' }}
                        onClick={() => navigate(`/organizer/events/${e.eventId}`)}
                      >
                        <TableCell>{e.title}</TableCell>
                        <TableCell align="right">{e.ticketsLast7d}</TableCell>
                        <TableCell align="right">{currency(e.revenueLast7d)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12}>
          <Card>
            <CardContent>
              <Typography variant="h6" sx={{ fontWeight: 600, mb: 1 }}>
                Recently sold
              </Typography>
              {loading ? (
                <Skeleton variant="rectangular" height={220} />
              ) : (overview?.recentSales?.length ?? 0) === 0 ? (
                <Typography variant="body2" color="text.secondary">No sales yet.</Typography>
              ) : (
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>When</TableCell>
                      <TableCell>Event</TableCell>
                      <TableCell>Buyer</TableCell>
                      <TableCell>Seat</TableCell>
                      <TableCell align="right">Price</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {overview.recentSales.map((r) => (
                      <TableRow key={r.ticketId}>
                        <TableCell>{new Date(r.issuedAt).toLocaleString()}</TableCell>
                        <TableCell>
                          <Chip
                            size="small"
                            label={r.eventTitle}
                            onClick={() => navigate(`/organizer/events/${r.eventId}`)}
                            sx={{ cursor: 'pointer' }}
                          />
                        </TableCell>
                        <TableCell>{r.buyerEmail}</TableCell>
                        <TableCell>{r.seatLabel}</TableCell>
                        <TableCell align="right">{currency(r.price)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
}

export default OrganizerDashboard;
