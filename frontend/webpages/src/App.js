import './App.css';
import { BrowserRouter as Router, Route, Routes, Navigate } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { useAuth } from './auth/useAuth';
import MainLayout from './components/MainLayout';
import ProtectedRoute from './components/ProtectedRoute';
import AdminRoute from './components/AdminRoute';
import Home from './pages/Home';
import Login from './pages/Login';
import Signup from './pages/Signup';
import Events from './pages/Events';
import EventsNearYou from './pages/EventsNearYou';
import EventDetail from './pages/EventDetail';
import Dashboard from './pages/Dashboard';
import MyTickets from './pages/MyTickets';
import TransferRequests from './pages/TransferRequests';
import MyHolds from './pages/MyHolds';
import Checkout from './pages/Checkout';
import PrivacyPolicy from './pages/PrivacyPolicy';
import VerifyEmail from './pages/VerifyEmail';
import ForgotPassword from './pages/ForgotPassword';
import ResetPassword from './pages/ResetPassword';
import AdminLayout from './admin/AdminLayout';
import OrganizerRoute from './organizer/OrganizerRoute';
import OrganizerLayout from './organizer/OrganizerLayout';
import OrganizerDashboard from './organizer/pages/OrganizerDashboard';
import OrganizerEventsPage from './organizer/pages/OrganizerEventsPage';
import OrganizerEventDetailPage from './organizer/pages/OrganizerEventDetailPage';
import OrganizerTeam from './organizer/pages/OrganizerTeam';
import OrganizerOnboarding from './organizer/pages/OrganizerOnboarding';
import OrganizerPlaceholder from './organizer/pages/OrganizerPlaceholder';
import CompsPage from './organizer/pages/CompsPage';
import HoldsPage from './organizer/pages/HoldsPage';
import WillCallPage from './organizer/pages/WillCallPage';
import BoxOfficePage from './organizer/pages/BoxOfficePage';
import SettlementPage from './organizer/pages/SettlementPage';
import OrganizerBrandingPage from './organizer/pages/OrganizerBrandingPage';
import OrganizerEventPagePage from './organizer/pages/OrganizerEventPagePage';
import OrganizerDomainsPage from './organizer/pages/OrganizerDomainsPage';
import PayoutsPage from './organizer/pages/PayoutsPage';
import TaxPage from './organizer/pages/TaxPage';
import AdminDashboard from './admin/pages/AdminDashboard';
import AdminEventsPage from './admin/pages/AdminEventsPage';
import AdminSeatsPage from './admin/pages/AdminSeatsPage';
import AdminUsersPage from './admin/pages/AdminUsersPage';
import AdminVenuesPage from './admin/pages/AdminVenuesPage';
import AdminRefundsPage from './admin/pages/AdminRefundsPage';
import AdminSupportPage from './admin/pages/AdminSupportPage';
import AdminFraudPage from './admin/pages/AdminFraudPage';
import AdminPerformersPage from './admin/pages/AdminPerformersPage';
import AdminOrgApprovalsPage from './admin/pages/AdminOrgApprovalsPage';
import MyRefunds from './pages/MyRefunds';
import OrderHistoryPage from './pages/OrderHistoryPage';
import TicketDetailPage from './pages/TicketDetailPage';
import MySupportTickets from './pages/MySupportTickets';
import SupportPage from './pages/SupportPage';
import SupportTicketDetail from './pages/SupportTicketDetail';

function SessionExpiredBanner() {
  const { sessionExpired, clearSessionExpired } = useAuth();
  if (!sessionExpired) return null;
  return (
    <div className="session-expired-banner" role="alert">
      <span>Your session has expired. Please log in again.</span>
      <button onClick={clearSessionExpired} className="session-expired-dismiss">Dismiss</button>
    </div>
  );
}

function App() {
  return (
    <div className="App">
      <Router>
        <AuthProvider>
          <SessionExpiredBanner />
          <Routes>
            {/* Public & authenticated routes wrapped in MainLayout */}
            <Route element={<MainLayout />}>
              <Route path="/" element={<Home />} />
              <Route path="/events" element={<Events />} />
              <Route path="/events/near-me" element={<EventsNearYou />} />
              <Route path="/events/:eventId" element={<EventDetail />} />
              <Route path="/login" element={<Login />} />
              <Route path="/signup" element={<Signup />} />
              <Route path="/privacy" element={<PrivacyPolicy />} />
              <Route path="/verify" element={<VerifyEmail />} />
              <Route path="/forgot-password" element={<ForgotPassword />} />
              <Route path="/reset-password" element={<ResetPassword />} />

              {/* Authenticated routes */}
              <Route element={<ProtectedRoute />}>
                <Route path="/dashboard" element={<Dashboard />} />
                <Route path="/my-holds" element={<MyHolds />} />
                <Route path="/checkout" element={<Checkout />} />
                <Route path="/my-tickets" element={<MyTickets />} />
                <Route path="/my-tickets/:ticketId" element={<TicketDetailPage />} />
                <Route path="/order-history" element={<OrderHistoryPage />} />
                <Route path="/transfers" element={<TransferRequests />} />
                <Route path="/refunds" element={<MyRefunds />} />
                <Route path="/support" element={<MySupportTickets />} />
                <Route path="/support/new" element={<SupportPage />} />
                <Route path="/support/tickets/:id" element={<SupportTicketDetail />} />
              </Route>
            </Route>

            {/* Admin routes — own layout */}
            <Route element={<AdminRoute />}>
              <Route path="/admin" element={<AdminLayout />}>
                <Route index element={<AdminDashboard />} />
                <Route path="events" element={<AdminEventsPage />} />
                <Route path="events/:eventId/seats" element={<AdminSeatsPage />} />
                <Route path="venues" element={<AdminVenuesPage />} />
                <Route path="users" element={<AdminUsersPage />} />
                <Route path="refunds" element={<AdminRefundsPage />} />
                <Route path="support" element={<AdminSupportPage />} />
                <Route path="fraud" element={<AdminFraudPage />} />
                <Route path="performers" element={<AdminPerformersPage />} />
                <Route path="org-approvals" element={<AdminOrgApprovalsPage />} />
              </Route>
            </Route>

            {/* Organizer routes — own layout, requires org membership.
                Onboarding lives inside the same OrganizationProvider so the
                useOrganization() hook resolves; OrganizerRoute itself routes
                users with zero orgs to /organizer/onboarding. */}
            <Route element={<OrganizerRoute />}>
              <Route path="/organizer/onboarding" element={<OrganizerOnboarding />} />
              {/* Box office runs full-screen (tablet) outside the organizer layout. */}
              <Route path="/organizer/box-office" element={<BoxOfficePage />} />
              <Route path="/organizer" element={<OrganizerLayout />}>
                <Route index element={<OrganizerDashboard />} />
                <Route path="events" element={<OrganizerEventsPage />} />
                <Route path="events/new" element={<OrganizerPlaceholder title="Create event" description="Create-event wizard lands in M2." />} />
                <Route path="events/:eventId" element={<OrganizerEventDetailPage />} />
                <Route path="events/:eventId/page" element={<OrganizerEventPagePage />} />
                <Route path="events/:eventId/comps" element={<CompsPage />} />
                <Route path="events/:eventId/holds" element={<HoldsPage />} />
                <Route path="events/:eventId/will-call" element={<WillCallPage />} />
                <Route path="events/:eventId/settlement" element={<SettlementPage />} />
                <Route path="sales" element={<OrganizerPlaceholder title="Sales" description="Cross-event sales reporting lands in M2." />} />
                <Route path="payouts" element={<PayoutsPage />} />
                <Route path="tax" element={<TaxPage />} />
                <Route path="settings" element={<OrganizerPlaceholder title="Settings" description="Organization details and billing settings land in M2." />} />
                <Route path="settings/branding" element={<OrganizerBrandingPage />} />
                <Route path="settings/domains" element={<OrganizerDomainsPage />} />
                <Route path="team" element={<OrganizerTeam />} />
                <Route path="integrations" element={<OrganizerPlaceholder title="Integrations" description="API keys and webhooks are scheduled for M6." />} />
              </Route>
            </Route>

            {/* Catch-all */}
            <Route path="*" element={<Navigate to="/" />} />
          </Routes>
        </AuthProvider>
      </Router>
    </div>
  );
}

export default App;
