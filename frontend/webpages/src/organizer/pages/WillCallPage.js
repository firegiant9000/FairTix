import React, { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import Table from '@mui/material/Table';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TableCell from '@mui/material/TableCell';
import TableBody from '@mui/material/TableBody';
import Alert from '@mui/material/Alert';
import Stack from '@mui/material/Stack';
import { useOrganization } from '../useOrganization';
import { holdsApi } from '../holdsApi';

export default function WillCallPage() {
  const { eventId } = useParams();
  const { current } = useOrganization();
  const organizationId = current?.id;
  const [list, setList] = useState([]);
  const [query, setQuery] = useState('');
  const [error, setError] = useState(null);

  const refresh = useCallback(async () => {
    if (!organizationId || !eventId) return;
    try {
      const res = await holdsApi.listWillCall(organizationId, eventId, query);
      setList(res || []);
    } catch (e) {
      setError(e?.message || 'Failed to load will-call list');
    }
  }, [organizationId, eventId, query]);

  useEffect(() => { refresh(); }, [refresh]);

  const claim = async (ticketId) => {
    try { await holdsApi.claimWillCall(organizationId, ticketId); refresh(); }
    catch (err) { setError(err?.message || 'Failed to claim'); }
  };

  const openBatchPrint = async () => {
    if (!organizationId || !eventId) return;
    try {
      const url = holdsApi.printWillCallUrl(eventId);
      const apiBase = process.env.REACT_APP_API_URL || '';
      const res = await fetch(apiBase + url, {
        credentials: 'include',
        headers: { 'X-Organization-Id': organizationId },
      });
      if (!res.ok) throw new Error('Failed to load print view');
      const html = await res.text();
      const win = window.open('', '_blank');
      if (win) {
        win.document.open();
        win.document.write(html);
        win.document.close();
      }
    } catch (e) {
      setError(e?.message || 'Failed to open print view');
    }
  };

  return (
    <Box>
      <Typography variant="h4" sx={{ fontWeight: 700, mb: 2 }}>Will-call</Typography>
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

      <Stack direction="row" spacing={2} sx={{ mb: 3, alignItems: 'center' }}>
        <TextField
          label="Search (name or email)"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          size="small"
        />
        <Button variant="outlined" onClick={openBatchPrint}>
          Batch print
        </Button>
      </Stack>

      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>Recipient</TableCell>
            <TableCell>Seat</TableCell>
            <TableCell>Kind</TableCell>
            <TableCell>Claimed</TableCell>
            <TableCell />
          </TableRow>
        </TableHead>
        <TableBody>
          {list.map((t) => (
            <TableRow key={t.id}>
              <TableCell>{t.recipientName || t.recipientEmail || '—'}</TableCell>
              <TableCell>{t.seatLabel}</TableCell>
              <TableCell>{t.kind}</TableCell>
              <TableCell>{t.willCall === false ? '—' : 'pending'}</TableCell>
              <TableCell>
                <Button size="small" onClick={() => claim(t.id)}>Mark claimed</Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Box>
  );
}
