import React, { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import Select from '@mui/material/Select';
import MenuItem from '@mui/material/MenuItem';
import InputLabel from '@mui/material/InputLabel';
import FormControl from '@mui/material/FormControl';
import Table from '@mui/material/Table';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TableCell from '@mui/material/TableCell';
import TableBody from '@mui/material/TableBody';
import Alert from '@mui/material/Alert';
import Chip from '@mui/material/Chip';
import Stack from '@mui/material/Stack';
import { useOrganization } from '../useOrganization';
import { holdsApi } from '../holdsApi';

const CATEGORIES = ['ARTIST', 'PRESS', 'HOUSE'];

export default function HoldsPage() {
  const { eventId } = useParams();
  const { current } = useOrganization();
  const organizationId = current?.id;
  const [filter, setFilter] = useState('');
  const [holds, setHolds] = useState([]);
  const [error, setError] = useState(null);
  const [form, setForm] = useState({ seatIds: '', category: 'ARTIST', note: '', autoReleaseAt: '' });

  const refresh = useCallback(async () => {
    if (!organizationId || !eventId) return;
    try {
      const res = await holdsApi.listHolds(organizationId, eventId, filter || null);
      setHolds(res || []);
    } catch (e) {
      setError(e?.message || 'Failed to load holds');
    }
  }, [organizationId, eventId, filter]);

  useEffect(() => { refresh(); }, [refresh]);

  const submit = async (e) => {
    e.preventDefault();
    setError(null);
    const seatIds = form.seatIds.split(',').map((s) => s.trim()).filter(Boolean);
    try {
      await holdsApi.createHolds(organizationId, {
        eventId,
        seatIds,
        category: form.category,
        note: form.note || null,
        autoReleaseAt: form.autoReleaseAt ? new Date(form.autoReleaseAt).toISOString() : null,
      });
      setForm({ seatIds: '', category: form.category, note: '', autoReleaseAt: '' });
      refresh();
    } catch (err) {
      setError(err?.message || 'Failed to create holds');
    }
  };

  const release = async (id) => {
    try { await holdsApi.releaseHold(organizationId, id); refresh(); }
    catch (err) { setError(err?.message || 'Failed to release'); }
  };

  const bulkRelease = async (cat) => {
    if (!window.confirm(`Release all ${cat} holds for this event?`)) return;
    try { await holdsApi.bulkRelease(organizationId, eventId, cat); refresh(); }
    catch (err) { setError(err?.message || 'Failed to bulk-release'); }
  };

  return (
    <Box>
      <Typography variant="h4" sx={{ fontWeight: 700, mb: 2 }}>Holds</Typography>
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

      <Box component="form" onSubmit={submit} sx={{ display: 'grid', gap: 2, maxWidth: 720, mb: 4 }}>
        <TextField
          label="Seat IDs (comma-separated)"
          value={form.seatIds}
          onChange={(e) => setForm({ ...form, seatIds: e.target.value })}
          required
        />
        <FormControl>
          <InputLabel>Category</InputLabel>
          <Select label="Category" value={form.category}
                  onChange={(e) => setForm({ ...form, category: e.target.value })}>
            {CATEGORIES.map((c) => <MenuItem key={c} value={c}>{c}</MenuItem>)}
          </Select>
        </FormControl>
        <TextField label="Note" value={form.note}
                   onChange={(e) => setForm({ ...form, note: e.target.value })} />
        <TextField label="Auto-release at" type="datetime-local"
                   InputLabelProps={{ shrink: true }}
                   value={form.autoReleaseAt}
                   onChange={(e) => setForm({ ...form, autoReleaseAt: e.target.value })} />
        <Button type="submit" variant="contained">Hold seats</Button>
      </Box>

      <Stack direction="row" spacing={1} sx={{ mb: 2, alignItems: 'center' }}>
        <Typography variant="body2">Filter:</Typography>
        <Chip label="All" onClick={() => setFilter('')} color={!filter ? 'primary' : 'default'} />
        {CATEGORIES.map((c) => (
          <Chip key={c} label={c} onClick={() => setFilter(c)}
                color={filter === c ? 'primary' : 'default'} />
        ))}
        {filter && (
          <Button size="small" onClick={() => bulkRelease(filter)}>Release all {filter}</Button>
        )}
      </Stack>

      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>Category</TableCell>
            <TableCell>Seat</TableCell>
            <TableCell>Note</TableCell>
            <TableCell>Auto-release</TableCell>
            <TableCell />
          </TableRow>
        </TableHead>
        <TableBody>
          {holds.map((h) => (
            <TableRow key={h.id}>
              <TableCell><Chip size="small" label={h.category} /></TableCell>
              <TableCell>{h.seatLabel}</TableCell>
              <TableCell>{h.note || '—'}</TableCell>
              <TableCell>{h.autoReleaseAt ? new Date(h.autoReleaseAt).toLocaleString() : '—'}</TableCell>
              <TableCell><Button size="small" onClick={() => release(h.id)}>Release</Button></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Box>
  );
}
