import React from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import Drawer from '@mui/material/Drawer';
import List from '@mui/material/List';
import ListItem from '@mui/material/ListItem';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import Toolbar from '@mui/material/Toolbar';
import Typography from '@mui/material/Typography';
import Box from '@mui/material/Box';
import DashboardIcon from '@mui/icons-material/Dashboard';
import EventIcon from '@mui/icons-material/Event';
import TrendingUpIcon from '@mui/icons-material/TrendingUp';
import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet';
import SettingsIcon from '@mui/icons-material/Settings';
import GroupIcon from '@mui/icons-material/Group';
import IntegrationInstructionsIcon from '@mui/icons-material/IntegrationInstructions';
import PointOfSaleIcon from '@mui/icons-material/PointOfSale';
import ReceiptLongIcon from '@mui/icons-material/ReceiptLong';
import PaletteIcon from '@mui/icons-material/Palette';
import LanguageIcon from '@mui/icons-material/Language';

export const DRAWER_WIDTH = 240;

const navItems = [
  { label: 'Dashboard', path: '/organizer', icon: <DashboardIcon /> },
  { label: 'Events', path: '/organizer/events', icon: <EventIcon /> },
  { label: 'Box office', path: '/organizer/box-office', icon: <PointOfSaleIcon /> },
  { label: 'Sales', path: '/organizer/sales', icon: <TrendingUpIcon /> },
  { label: 'Payouts', path: '/organizer/payouts', icon: <AccountBalanceWalletIcon /> },
  { label: 'Tax', path: '/organizer/tax', icon: <ReceiptLongIcon /> },
  { label: 'Team', path: '/organizer/team', icon: <GroupIcon /> },
  { label: 'Settings', path: '/organizer/settings', icon: <SettingsIcon /> },
  { label: 'Branding', path: '/organizer/settings/branding', icon: <PaletteIcon /> },
  { label: 'Domains', path: '/organizer/settings/domains', icon: <LanguageIcon /> },
  { label: 'Integrations', path: '/organizer/integrations', icon: <IntegrationInstructionsIcon /> },
];

function OrganizerSidebar() {
  const location = useLocation();
  const navigate = useNavigate();

  const isActive = (path) => {
    if (path === '/organizer') return location.pathname === '/organizer';
    return location.pathname.startsWith(path);
  };

  return (
    <Drawer
      variant="permanent"
      sx={{
        width: DRAWER_WIDTH,
        flexShrink: 0,
        '& .MuiDrawer-paper': { width: DRAWER_WIDTH, boxSizing: 'border-box' },
      }}
    >
      <Toolbar>
        <Box
          sx={{ display: 'flex', alignItems: 'center', gap: 1, cursor: 'pointer' }}
          onClick={() => navigate('/organizer')}
        >
          <Typography variant="h6" sx={{ fontWeight: 700, color: 'primary.main' }}>
            FairTix
          </Typography>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            Organizer
          </Typography>
        </Box>
      </Toolbar>
      <List>
        {navItems.map((item) => (
          <ListItem key={item.path} disablePadding>
            <ListItemButton
              selected={isActive(item.path)}
              onClick={() => navigate(item.path)}
              sx={{
                '&.Mui-selected': {
                  backgroundColor: 'rgba(233, 69, 96, 0.15)',
                  borderRight: '3px solid',
                  borderColor: 'primary.main',
                },
                '&.Mui-selected:hover': {
                  backgroundColor: 'rgba(233, 69, 96, 0.25)',
                },
              }}
            >
              <ListItemIcon
                sx={{ color: isActive(item.path) ? 'primary.main' : 'text.secondary' }}
              >
                {item.icon}
              </ListItemIcon>
              <ListItemText primary={item.label} />
            </ListItemButton>
          </ListItem>
        ))}
      </List>
    </Drawer>
  );
}

export default OrganizerSidebar;
