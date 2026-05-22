import React from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/useAuth';
import { OrganizationProvider, useOrganization } from './useOrganization';

function OrganizerGate() {
  const { orgs, isLoading } = useOrganization();
  if (isLoading) return <div className="loading">Loading organization…</div>;
  if (!orgs || orgs.length === 0) {
    return <Navigate to="/organizer/onboarding" replace />;
  }
  return <Outlet />;
}

function OrganizerRoute() {
  const { user, isLoading } = useAuth();
  const location = useLocation();

  if (isLoading) return <div className="loading">Loading…</div>;
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  return (
    <OrganizationProvider>
      <OrganizerGate />
    </OrganizationProvider>
  );
}

export default OrganizerRoute;
