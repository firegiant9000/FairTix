import React from 'react';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Grid from '@mui/material/Grid';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import { useOrganization } from '../useOrganization';

function StatCard({ label, value, hint }) {
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Typography variant="caption" color="text.secondary" sx={{ textTransform: 'uppercase' }}>
          {label}
        </Typography>
        <Typography variant="h4" sx={{ mt: 1, fontWeight: 700 }}>
          {value}
        </Typography>
        {hint && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            {hint}
          </Typography>
        )}
      </CardContent>
    </Card>
  );
}

function OrganizerDashboard() {
  const { current } = useOrganization();

  return (
    <Box>
      <Typography variant="h4" sx={{ fontWeight: 700, mb: 1 }}>
        {current?.name ? `Welcome, ${current.name}` : 'Welcome'}
      </Typography>
      <Typography variant="body1" color="text.secondary" sx={{ mb: 3 }}>
        Your organizer dashboard. Real data wiring lands as the M2 surfaces ship.
      </Typography>

      <Grid container spacing={2}>
        <Grid item xs={12} sm={6} md={3}>
          <StatCard label="Today's shows" value="—" hint="Coming soon" />
        </Grid>
        <Grid item xs={12} sm={6} md={3}>
          <StatCard label="This week's revenue" value="—" hint="Coming soon" />
        </Grid>
        <Grid item xs={12} sm={6} md={3}>
          <StatCard label="Refund queue" value="—" hint="Coming soon" />
        </Grid>
        <Grid item xs={12} sm={6} md={3}>
          <StatCard label="Top event velocity" value="—" hint="Coming soon" />
        </Grid>
      </Grid>

      <Box sx={{ mt: 4 }}>
        <Typography variant="h6" sx={{ mb: 1 }}>Getting started</Typography>
        <Typography variant="body2" color="text.secondary">
          1. Connect Stripe (Payouts) — 2. Create your first event — 3. Invite your team
        </Typography>
      </Box>
    </Box>
  );
}

export default OrganizerDashboard;
