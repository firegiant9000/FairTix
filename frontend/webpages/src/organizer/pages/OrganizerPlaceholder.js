import React from 'react';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';

function OrganizerPlaceholder({ title, description }) {
  return (
    <Box>
      <Typography variant="h4" sx={{ fontWeight: 700, mb: 1 }}>{title}</Typography>
      <Typography variant="body1" color="text.secondary">
        {description || 'This surface is part of the organizer scaffold and will be wired in a later milestone.'}
      </Typography>
    </Box>
  );
}

export default OrganizerPlaceholder;
