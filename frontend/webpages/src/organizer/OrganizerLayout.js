import React from 'react';
import { Outlet, useNavigate } from 'react-router-dom';
import { ThemeProvider } from '@mui/material/styles';
import CssBaseline from '@mui/material/CssBaseline';
import Box from '@mui/material/Box';
import AppBar from '@mui/material/AppBar';
import Toolbar from '@mui/material/Toolbar';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import MenuItem from '@mui/material/MenuItem';
import Select from '@mui/material/Select';
import LogoutIcon from '@mui/icons-material/Logout';
import HomeIcon from '@mui/icons-material/Home';
import adminTheme from '../admin/theme';
import OrganizerSidebar, { DRAWER_WIDTH } from './OrganizerSidebar';
import { useAuth } from '../auth/useAuth';
import { useOrganization } from './useOrganization';

function OrganizerLayout() {
  const { user, logout } = useAuth();
  const { orgs, selectedId, selectOrg, current } = useOrganization();
  const navigate = useNavigate();

  return (
    <ThemeProvider theme={adminTheme}>
      <CssBaseline />
      <Box sx={{ display: 'flex', minHeight: '100vh' }}>
        <OrganizerSidebar />
        <AppBar
          position="fixed"
          sx={{
            width: `calc(100% - ${DRAWER_WIDTH}px)`,
            ml: `${DRAWER_WIDTH}px`,
            backgroundColor: 'background.paper',
            boxShadow: 'none',
            borderBottom: '1px solid rgba(255, 255, 255, 0.08)',
          }}
        >
          <Toolbar sx={{ justifyContent: 'space-between' }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
              {orgs.length > 1 ? (
                <Select
                  value={selectedId || ''}
                  onChange={(e) => selectOrg(e.target.value)}
                  size="small"
                  sx={{ minWidth: 200, color: 'text.primary' }}
                >
                  {orgs.map((o) => (
                    <MenuItem key={o.id} value={o.id}>{o.name}</MenuItem>
                  ))}
                </Select>
              ) : (
                <Typography variant="body1" sx={{ color: 'text.primary', fontWeight: 600 }}>
                  {current?.name || '—'}
                </Typography>
              )}
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                {user?.email}
              </Typography>
            </Box>
            <Box sx={{ display: 'flex', gap: 1 }}>
              <Button
                color="inherit"
                startIcon={<HomeIcon />}
                onClick={() => navigate('/')}
                size="small"
              >
                Main Site
              </Button>
              <Button
                color="inherit"
                startIcon={<LogoutIcon />}
                onClick={logout}
                size="small"
              >
                Log Out
              </Button>
            </Box>
          </Toolbar>
        </AppBar>
        <Box
          component="main"
          sx={{
            flexGrow: 1,
            p: 3,
            mt: '64px',
            backgroundColor: 'background.default',
            minHeight: 'calc(100vh - 64px)',
          }}
        >
          <Outlet />
        </Box>
      </Box>
    </ThemeProvider>
  );
}

export default OrganizerLayout;
