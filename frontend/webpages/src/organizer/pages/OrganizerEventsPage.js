import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
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
import Alert from '@mui/material/Alert';
import Skeleton from '@mui/material/Skeleton';
import { useOrganization } from '../useOrganization';
import { organizerApi } from '../organizerApi';

const currency = (n) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(Number(n || 0));

const statusColor = (s) => {
  switch (s) {
    case 'ACTIVE': return 'success';
    case 'PUBLISHED': return 'info';
    case 'DRAFT': return 'default';
    case 'CANCELLED': return 'error';
    case 'COMPLETED': return 'secondary';
    default: return 'default';
  }
};

function OrganizerEventsPage() {
  const { current, isLoading: orgLoading } = useOrganization();
  const navigate = useNavigate();
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!current?.id) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    organizerApi.events(current.id)
      .then((data) => { if (!cancelled) setRows(data || []); })
      .catch((e) => { if (!cancelled) setError(e); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [current?.id]);

  if (orgLoading) return <Skeleton variant="rectangular" height={400} />;

  return (
    <Box>
      <Typography variant="h4" sx={{ fontWeight: 700, mb: 1 }}>Events</Typography>
      <Typography variant="body1" color="text.secondary" sx={{ mb: 3 }}>
        Every event owned by {current?.name}. Click an event to see attendees, velocity, and payout estimate.
      </Typography>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          Could not load events: {error.message}
        </Alert>
      )}

      <Card>
        <CardContent>
          {loading ? (
            <Skeleton variant="rectangular" height={300} />
          ) : rows.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              No events yet. Use the admin tools to create one — the organizer-side create wizard ships in M2-05.
            </Typography>
          ) : (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Title</TableCell>
                  <TableCell>Venue</TableCell>
                  <TableCell>Start</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell align="right">Sold / cap</TableCell>
                  <TableCell align="right">Gross</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((e) => (
                  <TableRow
                    hover
                    key={e.eventId}
                    sx={{ cursor: 'pointer' }}
                    onClick={() => navigate(`/organizer/events/${e.eventId}`)}
                  >
                    <TableCell>{e.title}</TableCell>
                    <TableCell>{e.venueName || '—'}</TableCell>
                    <TableCell>{new Date(e.startTime).toLocaleString()}</TableCell>
                    <TableCell>
                      <Chip size="small" label={e.status} color={statusColor(e.status)} />
                    </TableCell>
                    <TableCell align="right">
                      {e.sold} / {e.capacity || '—'}
                    </TableCell>
                    <TableCell align="right">{currency(e.gross)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </Box>
  );
}

export default OrganizerEventsPage;
