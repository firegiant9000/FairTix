import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import TextField from '@mui/material/TextField';
import Button from '@mui/material/Button';
import Paper from '@mui/material/Paper';
import api from '../../api/client';
import { useOrganization } from '../useOrganization';

function OrganizerOnboarding() {
  const navigate = useNavigate();
  const { refresh, selectOrg } = useOrganization();
  const [name, setName] = useState('');
  const [contactEmail, setContactEmail] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  const submit = async (e) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const org = await api.post('/api/organizations', { name, contactEmail });
      await refresh();
      if (org?.id) selectOrg(org.id);
      navigate('/organizer');
    } catch (err) {
      setError(err.message || 'Failed to create organization.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Box sx={{ maxWidth: 480, mx: 'auto', mt: 8 }}>
      <Paper sx={{ p: 4 }}>
        <Typography variant="h5" sx={{ fontWeight: 700, mb: 2 }}>
          Create your organization
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
          You'll need an organization to start selling tickets on FairTix.
        </Typography>
        <form onSubmit={submit}>
          <TextField
            fullWidth
            label="Organization name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            sx={{ mb: 2 }}
          />
          <TextField
            fullWidth
            label="Contact email"
            type="email"
            value={contactEmail}
            onChange={(e) => setContactEmail(e.target.value)}
            sx={{ mb: 2 }}
          />
          {error && (
            <Typography color="error" sx={{ mb: 2 }}>{error}</Typography>
          )}
          <Button
            type="submit"
            variant="contained"
            disabled={submitting || !name.trim()}
            fullWidth
          >
            {submitting ? 'Creating…' : 'Create organization'}
          </Button>
        </form>
      </Paper>
    </Box>
  );
}

export default OrganizerOnboarding;
