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
import PeopleIcon from '@mui/icons-material/People';
import LocationOnIcon from '@mui/icons-material/LocationOn';
import ReceiptLongIcon from '@mui/icons-material/ReceiptLong';
import SupportAgentIcon from '@mui/icons-material/SupportAgent';
import GppMaybeOutlinedIcon from '@mui/icons-material/GppMaybeOutlined';
import MicNoneOutlinedIcon from '@mui/icons-material/MicNoneOutlined';
import HowToRegIcon from '@mui/icons-material/HowToReg';

const DRAWER_WIDTH = 240;

const navItems = [
  { label: 'Dashboard', path: '/admin', icon: <DashboardIcon /> },
  { label: 'Events', path: '/admin/events', icon: <EventIcon /> },
  { label: 'Venues', path: '/admin/venues', icon: <LocationOnIcon /> },
  { label: 'Users', path: '/admin/users', icon: <PeopleIcon /> },
  { label: 'Refunds', path: '/admin/refunds', icon: <ReceiptLongIcon /> },
  { label: 'Support', path: '/admin/support', icon: <SupportAgentIcon /> },
  { label: 'Fraud', path: '/admin/fraud', icon: <GppMaybeOutlinedIcon /> },
  { label: 'Performers', path: '/admin/performers', icon: <MicNoneOutlinedIcon /> },
  { label: 'Org approvals', path: '/admin/org-approvals', icon: <HowToRegIcon /> },
];

function AdminSidebar() {
  const location = useLocation();
  const navigate = useNavigate();

  const isActive = (path) => {
    if (path === '/admin') return location.pathname === '/admin';
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
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, cursor: 'pointer' }} onClick={() => navigate('/admin')}>
          <Typography variant="h6" sx={{ fontWeight: 700, color: 'primary.main' }}>
            FairTix
          </Typography>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            Admin
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
              <ListItemIcon sx={{ color: isActive(item.path) ? 'primary.main' : 'text.secondary' }}>
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

export { DRAWER_WIDTH };
export default AdminSidebar;
