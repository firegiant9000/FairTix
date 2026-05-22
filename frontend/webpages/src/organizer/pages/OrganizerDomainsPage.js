import React, { useEffect, useState } from 'react';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Button from '@mui/material/Button';
import Alert from '@mui/material/Alert';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Chip from '@mui/material/Chip';
import IconButton from '@mui/material/IconButton';
import DeleteIcon from '@mui/icons-material/Delete';
import { useOrganization } from '../useOrganization';
import { brandingApi } from '../brandingApi';

function OrganizerDomainsPage() {
  const { current } = useOrganization();
  const [domains, setDomains] = useState([]);
  const [hostname, setHostname] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const refresh = () => {
    if (!current?.id) return;
    brandingApi.listDomains(current.id).then(setDomains).catch(setError);
  };
  useEffect(refresh, [current?.id]);  // eslint-disable-line react-hooks/exhaustive-deps

  const add = async () => {
    setBusy(true); setError(null);
    try {
      await brandingApi.addDomain(current.id, hostname.trim());
      setHostname('');
      refresh();
    } catch (e) { setError(e); }
    finally { setBusy(false); }
  };

  const verify = async (id) => {
    setError(null);
    try { await brandingApi.verifyDomain(current.id, id); refresh(); }
    catch (e) { setError(e); }
  };

  const remove = async (id) => {
    setError(null);
    try { await brandingApi.deleteDomain(current.id, id); refresh(); }
    catch (e) { setError(e); }
  };

  return (
    <Box sx={{ maxWidth: 960 }}>
      <Typography variant="h4" sx={{ fontWeight: 700, mb: 1 }}>Custom domains</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Point a subdomain of your own website (e.g. <code>tickets.yourvenue.com</code>)
        at FairTix. Add the hostname, then add a TXT record to prove ownership.
      </Typography>

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error.message || 'Request failed.'}</Alert>}

      <Paper sx={{ p: 3, mb: 3 }}>
        <Stack direction="row" spacing={2} alignItems="flex-end">
          <TextField
            label="Hostname"
            placeholder="tickets.yourvenue.com"
            value={hostname}
            onChange={(e) => setHostname(e.target.value)}
            sx={{ flex: 1 }}
          />
          <Button variant="contained" onClick={add} disabled={!hostname || busy}>Add</Button>
        </Stack>
      </Paper>

      <Paper>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Hostname</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Verification TXT</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {domains.length === 0 && (
              <TableRow><TableCell colSpan={4}>
                <Typography variant="body2" color="text.secondary">
                  No domains attached yet.
                </Typography>
              </TableCell></TableRow>
            )}
            {domains.map((d) => (
              <TableRow key={d.id}>
                <TableCell>{d.hostname}</TableCell>
                <TableCell>
                  {d.active
                    ? <Chip label="Active" color="success" size="small" />
                    : d.verified
                      ? <Chip label="Verified" color="info" size="small" />
                      : <Chip label="Pending" size="small" />}
                </TableCell>
                <TableCell>
                  <Typography variant="caption" sx={{ display: 'block' }}>
                    <strong>Name:</strong> {d.txtRecordName}
                  </Typography>
                  <Typography variant="caption" sx={{ display: 'block' }}>
                    <strong>Value:</strong> {d.txtRecordValue}
                  </Typography>
                </TableCell>
                <TableCell align="right">
                  {!d.verified && (
                    <Button size="small" onClick={() => verify(d.id)}>Verify now</Button>
                  )}
                  <IconButton size="small" onClick={() => remove(d.id)} aria-label="remove">
                    <DeleteIcon fontSize="small" />
                  </IconButton>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>
    </Box>
  );
}

export default OrganizerDomainsPage;
