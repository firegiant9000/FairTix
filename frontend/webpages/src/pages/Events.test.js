import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Events from './Events';
import api from '../api/client';

jest.mock('../api/client', () => ({
  get: jest.fn(),
}));

function eventsPage(events = [], totalPages = 1, totalElements = events.length) {
  return { content: events, page: { totalPages, totalElements } };
}

function renderEvents() {
  return render(
    <MemoryRouter>
      <Events />
    </MemoryRouter>
  );
}

beforeEach(() => jest.clearAllMocks());

test('shows skeleton cards while loading', () => {
  api.get.mockReturnValue(new Promise(() => {}));
  renderEvents();
  expect(document.querySelector('.event-card-skeleton')).toBeInTheDocument();
});

test('renders event titles after fetch resolves', async () => {
  api.get.mockResolvedValue(eventsPage([
    { id: 'e1', title: 'Jazz Night', venue: { name: 'Blue Note' }, startTime: '2026-12-01T20:00:00Z', status: 'ACTIVE' },
    { id: 'e2', title: 'Rock Concert', venue: { name: 'Madison SQ' }, startTime: '2026-12-15T19:00:00Z', status: 'PUBLISHED' },
  ], 1, 2));
  renderEvents();
  await waitFor(() => expect(screen.getByText('Jazz Night')).toBeInTheDocument(), { timeout: 2000 });
  expect(screen.getByText('Rock Concert')).toBeInTheDocument();
});

test('shows "On Sale" badge for ACTIVE events', async () => {
  api.get.mockResolvedValue(eventsPage([
    { id: 'e1', title: 'Jazz Night', venue: { name: 'Blue Note' }, startTime: '2026-12-01T20:00:00Z', status: 'ACTIVE' },
  ]));
  renderEvents();
  await waitFor(() => expect(screen.getByText('On Sale')).toBeInTheDocument(), { timeout: 2000 });
});

test('shows "Coming Soon" badge for PUBLISHED events', async () => {
  api.get.mockResolvedValue(eventsPage([
    { id: 'e2', title: 'Rock Concert', venue: { name: 'Madison SQ' }, startTime: '2026-12-15T19:00:00Z', status: 'PUBLISHED' },
  ]));
  renderEvents();
  await waitFor(() => expect(screen.getByText('Coming Soon')).toBeInTheDocument(), { timeout: 2000 });
});

test('shows empty state when no events match', async () => {
  api.get.mockResolvedValue(eventsPage([]));
  renderEvents();
  await waitFor(() =>
    expect(screen.getByText('No events match your search.')).toBeInTheDocument(),
    { timeout: 2000 }
  );
});

test('shows error message when API call fails', async () => {
  api.get.mockRejectedValue(new Error('Failed to load events'));
  renderEvents();
  await waitFor(() =>
    expect(screen.getByText('Failed to load events')).toBeInTheDocument(),
    { timeout: 2000 }
  );
});

test('search inputs are rendered', () => {
  api.get.mockReturnValue(new Promise(() => {}));
  renderEvents();
  expect(screen.getByPlaceholderText('Search by title...')).toBeInTheDocument();
  expect(screen.getByPlaceholderText('Filter by venue...')).toBeInTheDocument();
});

test('"Show past events" checkbox toggles filter', async () => {
  api.get.mockResolvedValue(eventsPage([]));
  renderEvents();
  const checkbox = screen.getByRole('checkbox', { name: /show past events/i });
  expect(checkbox).not.toBeChecked();
  fireEvent.click(checkbox);
  expect(checkbox).toBeChecked();
});

test('performer filter input is rendered', () => {
  api.get.mockReturnValue(new Promise(() => {}));
  renderEvents();
  expect(screen.getByPlaceholderText('Filter by performer...')).toBeInTheDocument();
});

test('typing in performer filter sends performerName param to API', async () => {
  api.get.mockResolvedValue(eventsPage([]));
  renderEvents();

  const input = screen.getByPlaceholderText('Filter by performer...');
  fireEvent.change(input, { target: { value: 'Adele' } });

  await waitFor(() => {
    const lastCall = api.get.mock.calls[api.get.mock.calls.length - 1][0];
    expect(lastCall).toContain('performerName=Adele');
  }, { timeout: 1000 });
});

test('Near Me button is rendered when geolocation is supported', () => {
  Object.defineProperty(global.navigator, 'geolocation', {
    value: { getCurrentPosition: jest.fn() },
    configurable: true,
  });
  api.get.mockReturnValue(new Promise(() => {}));
  renderEvents();
  expect(screen.getByRole('button', { name: /near me/i })).toBeInTheDocument();
});

test('clicking Near Me calls navigator.geolocation.getCurrentPosition', () => {
  const getCurrentPosition = jest.fn();
  Object.defineProperty(global.navigator, 'geolocation', {
    value: { getCurrentPosition },
    configurable: true,
  });
  api.get.mockReturnValue(new Promise(() => {}));
  renderEvents();

  fireEvent.click(screen.getByRole('button', { name: /near me/i }));

  expect(getCurrentPosition).toHaveBeenCalled();
});

test('successful geolocation fetches from /api/events/nearby with lat/lon', async () => {
  const getCurrentPosition = jest.fn((success) =>
    success({ coords: { latitude: 40.7128, longitude: -74.006 } })
  );
  Object.defineProperty(global.navigator, 'geolocation', {
    value: { getCurrentPosition },
    configurable: true,
  });

  api.get.mockResolvedValue(eventsPage([]));
  renderEvents();

  fireEvent.click(screen.getByRole('button', { name: /near me/i }));

  await waitFor(() => {
    const lastCall = api.get.mock.calls[api.get.mock.calls.length - 1][0];
    expect(lastCall).toContain('/api/events/nearby');
    expect(lastCall).toContain('lat=40.7128');
    expect(lastCall).toContain('lon=-74.006');
  }, { timeout: 1000 });
});

test('denying geolocation shows error message', async () => {
  const getCurrentPosition = jest.fn((_, error) =>
    error(new Error('denied'))
  );
  Object.defineProperty(global.navigator, 'geolocation', {
    value: { getCurrentPosition },
    configurable: true,
  });

  api.get.mockResolvedValue(eventsPage([]));
  renderEvents();

  fireEvent.click(screen.getByRole('button', { name: /near me/i }));

  await waitFor(() =>
    expect(screen.getByText(/location access denied/i)).toBeInTheDocument()
  );
});

test('shows purchase cap badge when maxTicketsPerUser is set', async () => {
  api.get.mockResolvedValue(eventsPage([
    { id: 'e1', title: 'Limited Show', venue: { name: 'Arena' }, startTime: '2026-12-01T20:00:00Z', status: 'ACTIVE', maxTicketsPerUser: 2 },
  ]));
  renderEvents();
  await waitFor(() => expect(screen.getByText('Limit: 2 per person')).toBeInTheDocument(), { timeout: 2000 });
});

test('does not show purchase cap badge when maxTicketsPerUser is not set', async () => {
  api.get.mockResolvedValue(eventsPage([
    { id: 'e1', title: 'Open Show', venue: { name: 'Arena' }, startTime: '2026-12-01T20:00:00Z', status: 'ACTIVE' },
  ]));
  renderEvents();
  await waitFor(() => expect(screen.getByText('Open Show')).toBeInTheDocument(), { timeout: 2000 });
  expect(screen.queryByText(/limit:/i)).not.toBeInTheDocument();
});

test('nearby events display distance label on card', async () => {
  const getCurrentPosition = jest.fn((success) =>
    success({ coords: { latitude: 40.7128, longitude: -74.006 } })
  );
  Object.defineProperty(global.navigator, 'geolocation', {
    value: { getCurrentPosition },
    configurable: true,
  });

  api.get.mockResolvedValue(eventsPage([
    {
      id: 'e1', title: 'Near Concert',
      venue: { name: 'The Venue' },
      startTime: '2026-12-01T20:00:00Z',
      status: 'ACTIVE',
      distanceKm: 12.3,
    },
  ]));
  renderEvents();

  fireEvent.click(screen.getByRole('button', { name: /near me/i }));

  await waitFor(() => expect(screen.getByText('Near Concert')).toBeInTheDocument(), { timeout: 2000 });
  expect(screen.getByText(/12\.3 km away/)).toBeInTheDocument();
});
