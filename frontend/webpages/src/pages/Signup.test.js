import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Signup from './Signup';
import { AuthContext } from '../auth/AuthContext';

jest.mock('react-router-dom', () => ({
  ...jest.requireActual('react-router-dom'),
  useNavigate: () => jest.fn(),
}));

function makeError(status, message = 'error') {
  const err = new Error(message);
  err.status = status;
  return err;
}

function renderSignup(signupFn) {
  const contextValue = {
    user: null,
    signup: signupFn,
    logout: jest.fn(),
    isLoading: false,
    sessionExpired: false,
    clearSessionExpired: jest.fn(),
  };
  return render(
    <MemoryRouter>
      <AuthContext.Provider value={contextValue}>
        <Signup />
      </AuthContext.Provider>
    </MemoryRouter>
  );
}

// A password that satisfies all rules
const VALID_PASSWORD = 'ValidPass1!';

async function fillAndSubmit(signupFn, { email = 'new@example.com', password = VALID_PASSWORD, confirm = VALID_PASSWORD } = {}) {
  renderSignup(signupFn);
  fireEvent.change(screen.getByLabelText(/^email/i), { target: { value: email } });
  fireEvent.change(screen.getByLabelText(/^password/i), { target: { value: password } });
  fireEvent.change(screen.getByLabelText(/confirm password/i), { target: { value: confirm } });
  fireEvent.click(screen.getByRole('button', { name: /sign up/i }));
}

test('shows email already exists on 409', async () => {
  const signupFn = jest.fn().mockRejectedValue(makeError(409));
  await fillAndSubmit(signupFn);
  await waitFor(() =>
    expect(screen.getByText('Email already exists.')).toBeInTheDocument()
  );
});

test('shows invalid details on 400', async () => {
  const signupFn = jest.fn().mockRejectedValue(makeError(400));
  await fillAndSubmit(signupFn);
  await waitFor(() =>
    expect(screen.getByText('Invalid registration details.')).toBeInTheDocument()
  );
});

test('shows registration-closed message on 403', async () => {
  const signupFn = jest.fn().mockRejectedValue(makeError(403));
  await fillAndSubmit(signupFn);
  await waitFor(() =>
    expect(
      screen.getByText('Registration is closed. Contact the organiser for access.')
    ).toBeInTheDocument()
  );
});

test('shows server error message on 500', async () => {
  const signupFn = jest.fn().mockRejectedValue(makeError(500));
  await fillAndSubmit(signupFn);
  await waitFor(() =>
    expect(screen.getByText('Server error. Please try again later.')).toBeInTheDocument()
  );
});

test('shows network error when no status', async () => {
  const signupFn = jest.fn().mockRejectedValue(new Error('fetch failed'));
  await fillAndSubmit(signupFn);
  await waitFor(() =>
    expect(screen.getByText('Network error. Please check your connection.')).toBeInTheDocument()
  );
});

test('shows mismatch error without calling signup', async () => {
  const signupFn = jest.fn();
  await fillAndSubmit(signupFn, { confirm: 'Different1!' });
  await waitFor(() =>
    expect(screen.getByText('Passwords do not match.')).toBeInTheDocument()
  );
  expect(signupFn).not.toHaveBeenCalled();
});

test('calls signup with correct email and password on submit', async () => {
  const signupFn = jest.fn().mockResolvedValue({ userId: '1', email: 'new@example.com', role: 'USER' });
  await fillAndSubmit(signupFn);
  await waitFor(() =>
    expect(signupFn).toHaveBeenCalledWith('new@example.com', VALID_PASSWORD)
  );
});

test('shows password requirements error when rules not met', async () => {
  const signupFn = jest.fn();
  renderSignup(signupFn);
  fireEvent.change(screen.getByLabelText(/^email/i), { target: { value: 'new@example.com' } });
  fireEvent.change(screen.getByLabelText(/^password/i), { target: { value: 'short' } });
  fireEvent.change(screen.getByLabelText(/confirm password/i), { target: { value: 'short' } });
  // Button is disabled when requirements unmet — submit does nothing
  expect(screen.getByRole('button', { name: /sign up/i })).toBeDisabled();
  expect(signupFn).not.toHaveBeenCalled();
});
