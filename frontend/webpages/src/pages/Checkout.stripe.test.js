import React from 'react';
import { render, screen, fireEvent, waitFor, act, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AuthContext } from '../auth/AuthContext';
import api from '../api/client';

// Checkout reads REACT_APP_STRIPE_PUBLISHABLE_KEY at module load, so it is
// required in beforeAll after the key is set (imports would be hoisted above it).
let Checkout;
const originalKey = process.env.REACT_APP_STRIPE_PUBLISHABLE_KEY;

const mockConfirmCardPayment = jest.fn();
const mockNavigate = jest.fn();

jest.mock('@stripe/stripe-js', () => ({
  loadStripe: jest.fn(() => Promise.resolve({})),
}));

jest.mock('@stripe/react-stripe-js', () => ({
  Elements: ({ children }) => children,
  CardElement: () => null,
  useStripe: () => ({ confirmCardPayment: mockConfirmCardPayment }),
  useElements: () => ({ getElement: () => ({}) }),
}));

jest.mock('../components/Recaptcha', () => () => null);

jest.mock('../api/client', () => ({
  get: jest.fn(),
  post: jest.fn(),
}));

jest.mock('react-router-dom', () => ({
  ...jest.requireActual('react-router-dom'),
  useNavigate: () => mockNavigate,
  useLocation: () => ({ state: { holdIds: ['hold-1'] } }),
}));

const user = { userId: 'u1', email: 'test@example.com', role: 'USER', emailVerified: true };
const holdData = { id: 'hold-1', seatId: 'seat-1', eventId: 'event-1', status: 'CONFIRMED', expiresAt: '2099-12-31T23:59:59Z' };
const seatData = { id: 'seat-1', section: 'A', rowLabel: '1', seatNumber: '5', price: 50 };
const busyError = { status: 409, code: 'REQUEST_IN_PROGRESS', message: 'Another request is in progress.' };

function renderCheckout() {
  return render(
    <MemoryRouter>
      <AuthContext.Provider value={{ user, login: jest.fn(), logout: jest.fn(), isLoading: false, sessionExpired: false, clearSessionExpired: jest.fn() }}>
        <Checkout />
      </AuthContext.Provider>
    </MemoryRouter>
  );
}

function checkoutCalls() {
  return api.post.mock.calls.filter(([url]) => url === '/api/payments/checkout');
}

// Answers the PaymentIntent request, then hands each checkout POST the next outcome.
function mockPost(...checkoutOutcomes) {
  api.post.mockImplementation((url) => {
    if (url === '/api/payments/intent') return Promise.resolve({ clientSecret: 'pi_123_secret_abc' });
    const outcome = checkoutOutcomes.shift();
    return outcome instanceof Error || outcome?.status ? Promise.reject(outcome) : Promise.resolve(outcome);
  });
}

async function clickPay() {
  const pay = await screen.findByRole('button', { name: /pay \$50\.00/i });
  fireEvent.click(pay);
}

beforeAll(() => {
  process.env.REACT_APP_STRIPE_PUBLISHABLE_KEY = 'pk_test_dummy';
  Checkout = require('./Checkout').default;
});

afterAll(() => {
  if (originalKey === undefined) delete process.env.REACT_APP_STRIPE_PUBLISHABLE_KEY;
  else process.env.REACT_APP_STRIPE_PUBLISHABLE_KEY = originalKey;
});

beforeEach(() => {
  jest.clearAllMocks();
  api.get.mockImplementation((url) => {
    if (url.includes('/api/holds/')) return Promise.resolve(holdData);
    if (url.includes('/seats')) return Promise.resolve([seatData]);
    return Promise.resolve([]);
  });
});

test('confirm error on an already-succeeded PaymentIntent still submits checkout', async () => {
  mockPost({ orderId: 'order-1' });
  mockConfirmCardPayment.mockResolvedValue({
    error: {
      code: 'payment_intent_unexpected_state',
      message: 'You cannot confirm this PaymentIntent because it has already succeeded.',
      payment_intent: { id: 'pi_123', status: 'succeeded' },
    },
  });

  renderCheckout();
  await clickPay();

  expect(await screen.findByText('Order Confirmed!')).toBeInTheDocument();
  expect(checkoutCalls()).toEqual([
    ['/api/payments/checkout', { holdIds: ['hold-1'], paymentIntentId: 'pi_123' }],
  ]);
});

test('confirm error without a succeeded PaymentIntent shows the error and does not submit checkout', async () => {
  mockPost();
  mockConfirmCardPayment.mockResolvedValue({
    error: {
      code: 'card_declined',
      message: 'Your card was declined.',
      payment_intent: { id: 'pi_123', status: 'requires_payment_method' },
    },
  });

  renderCheckout();
  await clickPay();

  expect(await screen.findByText('Your card was declined.')).toBeInTheDocument();
  expect(checkoutCalls()).toHaveLength(0);
});

test('a busy 409 after payment says the charge went through and offers no Cancel', async () => {
  mockPost(busyError);
  mockConfirmCardPayment.mockResolvedValue({ paymentIntent: { id: 'pi_123', status: 'succeeded' } });

  renderCheckout();
  await clickPay();

  expect(await screen.findByText(/your payment went through/i)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /complete order/i })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /cancel/i })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /try again/i })).not.toBeInTheDocument();
});

test('a paid resubmit that fails with a server error stays on the paid screen', async () => {
  mockPost(busyError, { status: 500, message: 'Order could not be created.' });
  mockConfirmCardPayment.mockResolvedValue({ paymentIntent: { id: 'pi_123', status: 'succeeded' } });

  renderCheckout();
  await clickPay();
  fireEvent.click(await screen.findByRole('button', { name: /complete order/i }));

  expect(await screen.findByText('Order could not be created.')).toBeInTheDocument();
  expect(screen.getByText(/your payment went through/i)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /complete order/i })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /cancel/i })).not.toBeInTheDocument();
});

test('cancelling step-up after a paid checkout goes to the paid screen, not the card form', async () => {
  mockPost({ status: 428, code: 'STEP_UP_REQUIRED' }, { orderId: 'order-4' });
  mockConfirmCardPayment.mockResolvedValue({ paymentIntent: { id: 'pi_123', status: 'succeeded' } });

  renderCheckout();
  await clickPay();
  await waitFor(() => expect(checkoutCalls()).toHaveLength(1));
  act(() => {
    window.dispatchEvent(new CustomEvent('auth:step-up-required', { detail: { action: 'CHECKOUT' } }));
  });
  const dialog = await screen.findByRole('dialog');
  fireEvent.click(within(dialog).getByRole('button', { name: /cancel/i }));

  expect(await screen.findByText(/your payment went through/i)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /pay \$50\.00/i })).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: /complete order/i }));
  expect(await screen.findByText('Order Confirmed!')).toBeInTheDocument();
  expect(mockConfirmCardPayment).toHaveBeenCalledTimes(1);
});

test('Complete Order after a second busy 409 can be clicked again', async () => {
  mockPost(busyError, busyError, { orderId: 'order-3' });
  mockConfirmCardPayment.mockResolvedValue({ paymentIntent: { id: 'pi_123', status: 'succeeded' } });

  renderCheckout();
  await clickPay();

  fireEvent.click(await screen.findByRole('button', { name: /complete order/i }));
  fireEvent.click(await screen.findByRole('button', { name: /complete order/i }));

  expect(await screen.findByText('Order Confirmed!')).toBeInTheDocument();
  expect(mockConfirmCardPayment).toHaveBeenCalledTimes(1);
  expect(checkoutCalls()).toHaveLength(3);
});

test('Complete Order after a busy 409 resubmits the paid checkout without asking for the card again', async () => {
  mockPost(busyError, { orderId: 'order-2' });
  mockConfirmCardPayment.mockResolvedValue({ paymentIntent: { id: 'pi_123', status: 'succeeded' } });

  renderCheckout();
  await clickPay();

  fireEvent.click(await screen.findByRole('button', { name: /complete order/i }));

  expect(await screen.findByText('Order Confirmed!')).toBeInTheDocument();
  expect(mockConfirmCardPayment).toHaveBeenCalledTimes(1);
  expect(checkoutCalls()).toEqual([
    ['/api/payments/checkout', { holdIds: ['hold-1'], paymentIntentId: 'pi_123' }],
    ['/api/payments/checkout', { holdIds: ['hold-1'], paymentIntentId: 'pi_123' }],
  ]);
});

test('a 402 after the charge (amount mismatch) stays on the paid screen', async () => {
  mockPost({ status: 402, message: 'Stripe payment has not succeeded' });
  mockConfirmCardPayment.mockResolvedValue({ paymentIntent: { id: 'pi_123', status: 'succeeded' } });

  renderCheckout();
  await clickPay();

  expect(await screen.findByText(/your payment went through/i)).toBeInTheDocument();
  expect(screen.getByText(/amount charged doesn't match your order total/i)).toBeInTheDocument();
  expect(screen.queryByText('Stripe payment has not succeeded')).not.toBeInTheDocument();
  expect(screen.getByRole('link', { name: /my tickets/i })).toHaveAttribute('href', '/my-tickets');
  expect(screen.queryByRole('button', { name: /cancel/i })).not.toBeInTheDocument();
});

test('a refunded payment gets a final screen with no retry', async () => {
  const message = "This payment has been refunded or disputed, so it can't complete an order. Please contact support.";
  mockPost({ status: 409, code: 'PAYMENT_REFUNDED', message });
  mockConfirmCardPayment.mockResolvedValue({ paymentIntent: { id: 'pi_123', status: 'succeeded' } });

  renderCheckout();
  await clickPay();

  expect(await screen.findByText(message)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /try again|complete order/i })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /back to my holds/i }));
  expect(mockNavigate).toHaveBeenCalledWith('/my-holds');
});

test('an unusable payment gets its own final screen that does not claim the payment went through', async () => {
  mockPost({
    status: 409,
    code: 'PAYMENT_NOT_USABLE',
    message: "This payment can't be used for this checkout. Please start checkout again.",
  });
  mockConfirmCardPayment.mockResolvedValue({ paymentIntent: { id: 'pi_123', status: 'succeeded' } });

  renderCheckout();
  await clickPay();

  expect(await screen.findByText(/this payment can't be used to complete this order/i)).toBeInTheDocument();
  expect(screen.getByText(/if you were charged, contact support/i)).toBeInTheDocument();
  expect(screen.queryByText(/your payment went through/i)).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /try again|complete order/i })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /back to my holds/i }));
  expect(mockNavigate).toHaveBeenCalledWith('/my-holds');
  expect(checkoutCalls()).toHaveLength(1);
});

test('an event cancelled while paying gets a final screen that points to a refund', async () => {
  mockPost({
    status: 409,
    code: 'EVENT_NOT_ON_SALE',
    message: 'This event is cancelled and no longer on sale',
  });
  mockConfirmCardPayment.mockResolvedValue({ paymentIntent: { id: 'pi_123', status: 'succeeded' } });

  renderCheckout();
  await clickPay();

  expect(await screen.findByText(/this event is no longer on sale/i)).toBeInTheDocument();
  expect(screen.getByText(/if you were charged, contact support/i)).toBeInTheDocument();
  expect(screen.queryByText(/your payment went through/i)).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /try again|complete order/i })).not.toBeInTheDocument();
  expect(checkoutCalls()).toHaveLength(1);
});

test('a payment not bound to this checkout gets a final screen with no retry and no pay-again prompt', async () => {
  mockPost({
    status: 409,
    code: 'PAYMENT_NOT_BOUND',
    message: 'This payment was not made for these seats. Contact support for a refund.',
  });
  mockConfirmCardPayment.mockResolvedValue({ paymentIntent: { id: 'pi_123', status: 'succeeded' } });

  renderCheckout();
  await clickPay();

  expect(await screen.findByText(/this payment can't be used to complete this order/i)).toBeInTheDocument();
  expect(screen.getByText(/contact support about this payment instead of paying again/i)).toBeInTheDocument();
  expect(screen.queryByText(/start checkout again/i)).not.toBeInTheDocument();
  expect(screen.queryByText(/your payment went through/i)).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /try again|complete order/i })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /back to my holds/i }));
  expect(mockNavigate).toHaveBeenCalledWith('/my-holds');
  expect(checkoutCalls()).toHaveLength(1);
});

test('Try Again after a card decline returns to the card form', async () => {
  mockPost({ status: 422, code: 'CARD_DECLINED', message: 'Your card was declined.' });
  mockConfirmCardPayment.mockResolvedValue({ paymentIntent: { id: 'pi_123', status: 'succeeded' } });

  renderCheckout();
  await clickPay();

  fireEvent.click(await screen.findByRole('button', { name: /try again/i }));

  expect(await screen.findByRole('button', { name: /pay \$50\.00/i })).toBeInTheDocument();
  expect(checkoutCalls()).toHaveLength(1);
});
