import React, { useEffect, useState } from 'react';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Paper from '@mui/material/Paper';
import TextField from '@mui/material/TextField';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import FormControlLabel from '@mui/material/FormControlLabel';
import Button from '@mui/material/Button';
import Alert from '@mui/material/Alert';
import { useOrganization } from '../useOrganization';
import { brandingApi } from '../brandingApi';

const FIELDS = [
  { key: 'logoUrl', label: 'Logo URL', placeholder: 'https://cdn.example.com/logo.svg', helper: 'HTTPS only, max 1024 chars.' },
  { key: 'primaryColor', label: 'Primary color', placeholder: '#1A2B3C', helper: '7-char hex.' },
  { key: 'emailSenderName', label: 'Email sender name', placeholder: 'Blue Note NYC', helper: 'Shown as the From display name.' },
  { key: 'emailReplyTo', label: 'Email reply-to', placeholder: 'box-office@bluenotenyc.com', helper: 'Optional override; From is still fairtix.io.' },
  { key: 'statementDescriptorSuffix', label: 'Stripe statement descriptor', placeholder: 'BLUENOTE', helper: 'Up to 22 chars; appears on customer card statements.' },
];

function OrganizerBrandingPage() {
  const { current } = useOrganization();
  const [form, setForm] = useState({});
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(false);

  useEffect(() => {
    if (!current?.id) return;
    setLoaded(false);
    brandingApi.get(current.id)
      .then((b) => { setForm(b || {}); setLoaded(true); })
      .catch(setError);
  }, [current?.id]);

  const update = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.value }));
  const toggle = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.checked }));

  const save = async () => {
    setBusy(true); setError(null); setSuccess(false);
    try {
      const next = await brandingApi.update(current.id, form);
      setForm(next || {});
      setSuccess(true);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  if (!current?.id) {
    return <Typography color="text.secondary">Select an organization first.</Typography>;
  }

  return (
    <Box sx={{ maxWidth: 720 }}>
      <Typography variant="h4" sx={{ fontWeight: 700, mb: 2 }}>Branding</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Customise how your organisation appears across event pages, emails, and
        customer card statements.
      </Typography>

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error.message || 'Failed to save.'}</Alert>}
      {success && <Alert severity="success" sx={{ mb: 2 }}>Branding saved.</Alert>}

      <Paper sx={{ p: 3 }}>
        <Stack spacing={2}>
          {FIELDS.map(({ key, label, placeholder, helper }) => (
            <TextField
              key={key}
              label={label}
              placeholder={placeholder}
              helperText={helper}
              value={form[key] || ''}
              onChange={update(key)}
              disabled={!loaded || busy}
              fullWidth
            />
          ))}
          <FormControlLabel
            control={
              <Switch
                checked={Boolean(form.darkModeEnabled)}
                onChange={toggle('darkModeEnabled')}
                disabled={!loaded || busy}
              />
            }
            label="Dark mode event pages"
          />
          <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Button variant="contained" onClick={save} disabled={!loaded || busy}>
              {busy ? 'Saving…' : 'Save branding'}
            </Button>
          </Box>
        </Stack>
      </Paper>
    </Box>
  );
}

export default OrganizerBrandingPage;
