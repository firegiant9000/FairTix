import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import TextField from '@mui/material/TextField';
import Button from '@mui/material/Button';
import Paper from '@mui/material/Paper';
import Stepper from '@mui/material/Stepper';
import Step from '@mui/material/Step';
import StepLabel from '@mui/material/StepLabel';
import Stack from '@mui/material/Stack';
import Alert from '@mui/material/Alert';
import api from '../../api/client';
import { useOrganization } from '../useOrganization';

const STEPS = ['Create org', 'Stripe Connect', 'Business details', 'Submit for review'];

function OrganizerOnboarding() {
  const navigate = useNavigate();
  const { refresh, selectOrg, currentOrg } = useOrganization();
  const [step, setStep] = useState(0);
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const [name, setName] = useState('');
  const [contactEmail, setContactEmail] = useState('');
  const [details, setDetails] = useState({
    legalName: '', dba: '',
    addressLine1: '', addressLine2: '',
    addressCity: '', addressRegion: '',
    addressPostalCode: '', addressCountry: 'US',
    primaryContactName: '', primaryContactPhone: '',
    ein: '', referredBy: '',
  });
  const [createdOrg, setCreatedOrg] = useState(null);

  useEffect(() => {
    // If org already exists and has Stripe connected, jump ahead to step 2.
    if (currentOrg && !createdOrg) {
      setCreatedOrg(currentOrg);
      if (currentOrg.status === 'PENDING_REVIEW') setStep(3);
      else if (currentOrg.stripeChargesEnabled) setStep(2);
      else setStep(1);
    }
  }, [currentOrg, createdOrg]);

  const createOrg = async (e) => {
    e?.preventDefault();
    setSubmitting(true); setError(null);
    try {
      const org = await api.post('/api/organizations', { name, contactEmail });
      await refresh();
      if (org?.id) selectOrg(org.id);
      setCreatedOrg(org);
      setStep(1);
    } catch (err) {
      setError(err.message || 'Failed to create organization.');
    } finally { setSubmitting(false); }
  };

  const startStripe = async () => {
    setSubmitting(true); setError(null);
    try {
      const res = await api.post(`/api/organizer/connect/onboard`, { organizationId: createdOrg.id });
      if (res?.url) window.location.href = res.url;
    } catch (err) {
      setError(err.message || 'Failed to start Stripe onboarding.');
    } finally { setSubmitting(false); }
  };

  const submitDetails = async (e) => {
    e?.preventDefault();
    setSubmitting(true); setError(null);
    try {
      await api.post(`/api/organizations/${createdOrg.id}/submit-for-review`, details);
      setStep(3);
    } catch (err) {
      setError(err.message || 'Submission failed.');
    } finally { setSubmitting(false); }
  };

  const update = (field) => (e) => setDetails({ ...details, [field]: e.target.value });

  return (
    <Box sx={{ maxWidth: 720, mx: 'auto', mt: 6 }}>
      <Paper sx={{ p: 4 }}>
        <Typography variant="h5" sx={{ fontWeight: 700, mb: 1 }}>
          Get FairTix set up for your venue
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
          Four quick steps. You can come back and finish later.
        </Typography>

        <Stepper activeStep={step} sx={{ mb: 4 }}>
          {STEPS.map((s) => <Step key={s}><StepLabel>{s}</StepLabel></Step>)}
        </Stepper>

        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

        {step === 0 && (
          <form onSubmit={createOrg}>
            <Stack spacing={2}>
              <TextField label="Organization name" value={name} onChange={(e) => setName(e.target.value)} required />
              <TextField label="Contact email" type="email" value={contactEmail} onChange={(e) => setContactEmail(e.target.value)} />
              <Button type="submit" variant="contained" disabled={submitting || !name.trim()}>
                {submitting ? 'Creating…' : 'Create organization'}
              </Button>
            </Stack>
          </form>
        )}

        {step === 1 && (
          <Stack spacing={2}>
            <Typography>
              Connect with Stripe to take payments. We use Stripe Connect Standard, so you'll have a
              real Stripe dashboard for payouts and tax docs.
            </Typography>
            <Button variant="contained" onClick={startStripe} disabled={submitting}>
              {submitting ? 'Redirecting…' : 'Start Stripe onboarding'}
            </Button>
            <Button onClick={() => setStep(2)}>Skip for now (you can finish later)</Button>
          </Stack>
        )}

        {step === 2 && (
          <form onSubmit={submitDetails}>
            <Stack spacing={2}>
              <TextField label="Legal name (as on tax docs)" value={details.legalName} onChange={update('legalName')} required />
              <TextField label="DBA / trading name" value={details.dba} onChange={update('dba')} />
              <TextField label="Address line 1" value={details.addressLine1} onChange={update('addressLine1')} required />
              <TextField label="Address line 2" value={details.addressLine2} onChange={update('addressLine2')} />
              <Stack direction="row" spacing={2}>
                <TextField label="City" value={details.addressCity} onChange={update('addressCity')} required fullWidth />
                <TextField label="State" value={details.addressRegion} onChange={update('addressRegion')} required sx={{ width: 120 }} />
                <TextField label="ZIP" value={details.addressPostalCode} onChange={update('addressPostalCode')} required sx={{ width: 120 }} />
              </Stack>
              <Stack direction="row" spacing={2}>
                <TextField label="Country" value={details.addressCountry} onChange={update('addressCountry')} sx={{ width: 120 }} />
                <TextField label="Primary contact" value={details.primaryContactName} onChange={update('primaryContactName')} required fullWidth />
                <TextField label="Phone" value={details.primaryContactPhone} onChange={update('primaryContactPhone')} fullWidth />
              </Stack>
              <TextField
                label="EIN (if collecting sales tax)"
                value={details.ein}
                onChange={update('ein')}
                helperText="Encrypted at rest. Only required if you're collecting sales tax through FairTix."
              />
              <TextField label="Which venue / friend referred you?" value={details.referredBy} onChange={update('referredBy')} />
              <Button type="submit" variant="contained" disabled={submitting}>
                {submitting ? 'Submitting…' : 'Submit for review'}
              </Button>
            </Stack>
          </form>
        )}

        {step === 3 && (
          <Stack spacing={2}>
            <Alert severity="success">
              Submitted! Our team typically reviews new organizations within 48 hours. We'll email
              you at <strong>{createdOrg?.contactEmail || 'your contact email'}</strong> when you're
              approved.
            </Alert>
            <Typography variant="body2" color="text.secondary">
              While you wait you can finish your Stripe Connect onboarding, draft events, and
              configure your team. You won't be able to publish events or take payments until
              approval lands.
            </Typography>
            <Button variant="contained" onClick={() => navigate('/organizer')}>
              Go to your dashboard
            </Button>
          </Stack>
        )}
      </Paper>
    </Box>
  );
}

export default OrganizerOnboarding;
