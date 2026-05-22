import React, { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import Checkbox from '@mui/material/Checkbox';
import FormControlLabel from '@mui/material/FormControlLabel';
import Table from '@mui/material/Table';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TableCell from '@mui/material/TableCell';
import TableBody from '@mui/material/TableBody';
import Alert from '@mui/material/Alert';
import { useOrganization } from '../useOrganization';
import { holdsApi } from '../holdsApi';

export default function CompsPage() {
  const { eventId } = useParams();
  const { current } = useOrganization();
  const organizationId = current?.id;
  const [comps, setComps] = useState([]);
  const [error, setError] = useState(null);
  const [form, setForm] = useState({
    seatIds: '',
    recipientName: '',
    recipientEmail: '',
    reason: '',
    willCall: true,
  });

  const refresh = useCallback(async () => {
    if (!organizationId || !eventId) return;
    try {
      const res = await holdsApi.listComps(organizationId, eventId);
      setComps(res || []);
    } catch (e) {
      setError(e?.message || 'Failed to load comps');
    }
  }, [organizationId, eventId]);

  useEffect(() => { refresh(); }, [refresh]);

  const submit = async (e) => {
    e.preventDefault();
    setError(null);
    const seatIds = form.seatIds.split(',').map((s) => s.trim()).filter(Boolean);
    if (seatIds.length === 0) {
      setError('Provide at least one seat ID');
      return;
    }
    try {
      await holdsApi.issueComp(organizationId, {
        eventId,
        seatIds,
        recipientName: form.recipientName || null,
        recipientEmail: form.recipientEmail || null,
        reason: form.reason || null,
        willCall: form.willCall,
      });
      setForm({ seatIds: '', recipientName: '', recipientEmail: '', reason: '', willCall: true });
      refresh();
    } catch (err) {
      setError(err?.message || 'Failed to issue comp');
    }
  };

  return (
    <Box>
      <Typography variant="h4" sx={{ fontWeight: 700, mb: 2 }}>Comps</Typography>
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

      <Box component="form" onSubmit={submit} sx={{ display: 'grid', gap: 2, maxWidth: 720, mb: 4 }}>
        <TextField
          label="Seat IDs (comma-separated)"
          value={form.seatIds}
          onChange={(e) => setForm({ ...form, seatIds: e.target.value })}
          required
        />
        <TextField
          label="Recipient name"
          value={form.recipientName}
          onChange={(e) => setForm({ ...form, recipientName: e.target.value })}
        />
        <TextField
          label="Recipient email"
          type="email"
          value={form.recipientEmail}
          onChange={(e) => setForm({ ...form, recipientEmail: e.target.value })}
        />
        <TextField
          label="Reason"
          value={form.reason}
          onChange={(e) => setForm({ ...form, reason: e.target.value })}
          multiline
          minRows={2}
        />
        <FormControlLabel
          control={<Checkbox checked={form.willCall}
                             onChange={(e) => setForm({ ...form, willCall: e.target.checked })} />}
          label="Hold at will-call"
        />
        <Button type="submit" variant="contained">Issue comp</Button>
      </Box>

      <Typography variant="h6" sx={{ mb: 1 }}>Issued comps</Typography>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>Seat</TableCell>
            <TableCell>Recipient</TableCell>
            <TableCell>Reason</TableCell>
            <TableCell>Will-call</TableCell>
            <TableCell>Issued</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {comps.map((c) => (
            <TableRow key={c.id}>
              <TableCell>{c.seatLabel}</TableCell>
              <TableCell>{c.recipientName || c.recipientEmail || '—'}</TableCell>
              <TableCell>{c.reason || '—'}</TableCell>
              <TableCell>{c.willCall ? 'Yes' : 'No'}</TableCell>
              <TableCell>{new Date(c.issuedAt).toLocaleString()}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Box>
  );
}
