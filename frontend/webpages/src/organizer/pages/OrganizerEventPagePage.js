import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Paper from '@mui/material/Paper';
import TextField from '@mui/material/TextField';
import Stack from '@mui/material/Stack';
import Select from '@mui/material/Select';
import MenuItem from '@mui/material/MenuItem';
import InputLabel from '@mui/material/InputLabel';
import FormControl from '@mui/material/FormControl';
import Button from '@mui/material/Button';
import Alert from '@mui/material/Alert';
import Chip from '@mui/material/Chip';
import { useOrganization } from '../useOrganization';
import { brandingApi } from '../brandingApi';

const AGE_OPTIONS = [
  { value: '', label: 'No restriction' },
  { value: 'ALL_AGES', label: 'All ages' },
  { value: 'EIGHTEEN_PLUS', label: '18+' },
  { value: 'TWENTY_ONE_PLUS', label: '21+' },
];

function toLocalDatetime(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function OrganizerEventPagePage() {
  const { eventId } = useParams();
  const { current } = useOrganization();
  const [page, setPage] = useState(null);
  const [tagsDraft, setTagsDraft] = useState('');
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!current?.id || !eventId) return;
    brandingApi.getEventPage(current.id, eventId)
      .then((p) => {
        setPage(p || {});
        setTagsDraft((p?.accessibilityTags || []).join(', '));
      })
      .catch(setError);
  }, [current?.id, eventId]);

  if (!page) return <Typography>Loading…</Typography>;

  const set = (key, value) => setPage((p) => ({ ...p, [key]: value }));

  const save = async () => {
    setBusy(true); setError(null); setSuccess(false);
    try {
      const tags = tagsDraft.split(',').map((s) => s.trim()).filter(Boolean);
      const body = {
        slug: page.slug,
        heroImageUrl: page.heroImageUrl || null,
        descriptionMarkdown: page.descriptionMarkdown || null,
        doorsOpenTime: page.doorsOpenTime || null,
        setTimes: page.setTimes || null,
        ageRestriction: page.ageRestriction || null,
        accessibilityInfo: page.accessibilityInfo || null,
        accessibilityTags: tags,
        parkingInfo: page.parkingInfo || null,
        transitInfo: page.transitInfo || null,
        seoDescription: page.seoDescription || null,
      };
      const next = await brandingApi.updateEventPage(current.id, eventId, body);
      setPage(next);
      setTagsDraft((next.accessibilityTags || []).join(', '));
      setSuccess(true);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box sx={{ maxWidth: 880 }}>
      <Typography variant="h4" sx={{ fontWeight: 700, mb: 1 }}>Event page</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Configure the public-facing details audiences see when they open this
        event's listing or share it on social media.
      </Typography>

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error.message || 'Save failed.'}</Alert>}
      {success && <Alert severity="success" sx={{ mb: 2 }}>Page saved.</Alert>}

      <Paper sx={{ p: 3 }}>
        <Stack spacing={2}>
          <TextField
            label="URL slug"
            helperText="Used in the event page URL: /o/<org>/e/<slug>. Renaming preserves old links."
            value={page.slug || ''}
            onChange={(e) => set('slug', e.target.value)}
          />
          <TextField
            label="Hero image URL"
            placeholder="https://cdn.example.com/hero.jpg"
            value={page.heroImageUrl || ''}
            onChange={(e) => set('heroImageUrl', e.target.value)}
          />
          <TextField
            label="Description (Markdown)"
            multiline
            minRows={6}
            value={page.descriptionMarkdown || ''}
            onChange={(e) => set('descriptionMarkdown', e.target.value)}
            helperText="Supports headings (# ## ###), **bold**, *italic*, lists, and [links](https://). Raw HTML is stripped."
          />
          <TextField
            label="Set times"
            multiline
            minRows={2}
            value={page.setTimes || ''}
            onChange={(e) => set('setTimes', e.target.value)}
          />
          <TextField
            label="Doors open"
            type="datetime-local"
            value={toLocalDatetime(page.doorsOpenTime)}
            onChange={(e) => set('doorsOpenTime', e.target.value ? new Date(e.target.value).toISOString() : null)}
            InputLabelProps={{ shrink: true }}
          />
          <FormControl fullWidth>
            <InputLabel>Age restriction</InputLabel>
            <Select
              label="Age restriction"
              value={page.ageRestriction || ''}
              onChange={(e) => set('ageRestriction', e.target.value)}
            >
              {AGE_OPTIONS.map((o) => (
                <MenuItem key={o.value || 'none'} value={o.value}>{o.label}</MenuItem>
              ))}
            </Select>
          </FormControl>
          <TextField
            label="Accessibility info"
            multiline minRows={2}
            value={page.accessibilityInfo || ''}
            onChange={(e) => set('accessibilityInfo', e.target.value)}
          />
          <TextField
            label="Accessibility tags (comma-separated)"
            value={tagsDraft}
            onChange={(e) => setTagsDraft(e.target.value)}
            helperText="e.g. wheelchair, asl, hearing-loop"
          />
          <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
            {tagsDraft.split(',').map((s) => s.trim()).filter(Boolean).map((tag) => (
              <Chip key={tag} label={tag} size="small" />
            ))}
          </Stack>
          <TextField
            label="Parking info"
            multiline minRows={2}
            value={page.parkingInfo || ''}
            onChange={(e) => set('parkingInfo', e.target.value)}
          />
          <TextField
            label="Public transit info"
            multiline minRows={2}
            value={page.transitInfo || ''}
            onChange={(e) => set('transitInfo', e.target.value)}
          />
          <TextField
            label="SEO description"
            helperText="Used in &lt;meta description&gt; and OG cards. Up to 320 chars."
            inputProps={{ maxLength: 320 }}
            value={page.seoDescription || ''}
            onChange={(e) => set('seoDescription', e.target.value)}
            multiline minRows={2}
          />
          <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Button variant="contained" onClick={save} disabled={busy}>
              {busy ? 'Saving…' : 'Save event page'}
            </Button>
          </Box>
        </Stack>
      </Paper>
    </Box>
  );
}

export default OrganizerEventPagePage;
