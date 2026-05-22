import React, { useEffect, useState } from 'react';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Paper from '@mui/material/Paper';
import api from '../../api/client';
import { useOrganization } from '../useOrganization';

function OrganizerTeam() {
  const { current } = useOrganization();
  const [members, setMembers] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!current?.id) return;
    api.get(`/api/organizations/${current.id}/members`)
      .then(setMembers)
      .catch(setError);
  }, [current?.id]);

  return (
    <Box>
      <Typography variant="h4" sx={{ fontWeight: 700, mb: 2 }}>Team</Typography>
      {error && (
        <Typography color="error" sx={{ mb: 2 }}>
          {error.message || 'Failed to load team members.'}
        </Typography>
      )}
      <Paper>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>User</TableCell>
              <TableCell>Role</TableCell>
              <TableCell>Joined</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {members.length === 0 && (
              <TableRow>
                <TableCell colSpan={3}>
                  <Typography variant="body2" color="text.secondary">
                    No team members yet. Invite teammates from the API once available.
                  </Typography>
                </TableCell>
              </TableRow>
            )}
            {members.map((m) => (
              <TableRow key={m.id}>
                <TableCell>{m.email || m.userId}</TableCell>
                <TableCell>{m.role}</TableCell>
                <TableCell>{m.createdAt ? new Date(m.createdAt).toLocaleDateString() : '—'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>
    </Box>
  );
}

export default OrganizerTeam;
