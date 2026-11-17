import { useState, useEffect, useCallback, useRef } from 'react';
import { useLocation, Link, useNavigate } from 'react-router-dom';
import { loadStripe } from '@stripe/stripe-js';
import { Elements, CardElement, useStripe, useElements } from '@stripe/react-stripe-js';
import api from '../api/client';
import { useAuth } from '../auth/useAuth';
import Recaptcha from '../components/Recaptcha';
import '../styles/Checkout.css';

const stripeEnabled = !!process.env.REACT_APP_STRIPE_PUBLISHABLE_KEY;
const stripePromise = stripeEnabled
  ? loadStripe(process.env.REACT_APP_STRIPE_PUBLISHABLE_KEY)
  : null;

// A Stripe checkout reaches the server only after confirmCardPayment succeeded,
// so its failure means "charged, no order" unless the server says the intent
// itself is unusable: 402 (not succeeded), 422 (card declined) or a refund.
function isPaidButNotOrdered(payload, err) {
  if (!payload.paymentIntentId) return false;
  if (err.status === 402 || err.status === 422) return false;
  return err.code !== 'PAYMENT_REFUNDED';
}

function StripeCardForm({ holds, total, clientSecret, onSuccess, onError, onCancel, submitting, setSubmitting }) {
  const stripe = useStripe();
  const elements = useElements();

  async function handleSubmit(e) {
    e.preventDefault();
    if (!stripe || !elements) return;
    setSubmitting(true);
    const { error, paymentIntent } = await stripe.confirmCardPayment(clientSecret, {
      payment_method: { card: elements.getElement(CardElement) },
    });
    setSubmitting(false);
    // Already charged (e.g. resubmitting after a 409 REQUEST_IN_PROGRESS): Stripe
    // rejects the confirm, but checkout must still be submitted to create the order.
    if (error?.payment_intent?.status === 'succeeded') {
      await onSuccess(error.payment_intent.id);
      return;
    }
    if (error) {
      onError(error.message);
      return;
    }
    if (paymentIntent.status === 'succeeded') {
      await onSuccess(paymentIntent.id);
    }
  }

  return (
    <form className="checkout-payment-form" onSubmit={handleSubmit}>
      <h3>Payment Details</h3>
      <div className="checkout-field">
        <label>Card Details</label>
        <div className="stripe-card-element" style={{ border: '1px solid #ccc', borderRadius: 4, padding: '10px 12px' }}>
          <CardElement options={{ style: { base: { fontSize: '16px', color: '#424770' } } }} />
        </div>
      </div>
      <div className="checkout-actions">
        <button type="button" className="checkout-btn-secondary" onClick={onCancel} disabled={submitting}>
          Cancel
        </button>
        <button type="submit" className="checkout-btn-primary" disabled={submitting || !stripe}>
          Pay ${total.toFixed(2)}
        </button>
      </div>
    </form>
  );
}

function Checkout() {
  useEffect(() => { document.title = 'Checkout | FairTix'; }, []);
  const location = useLocation();
  const navigate = useNavigate();
  const { user } = useAuth();
  const [holds, setHolds] = useState([]);
  const [seatMap, setSeatMap] = useState({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [orderResult, setOrderResult] = useState(null);

  const [cardNumber, setCardNumber] = useState('');
  const [paymentError, setPaymentError] = useState('');
  const [paymentState, setPaymentState] = useState('form'); // form | processing | success | failed | step-up
  const [stepUpAction, setStepUpAction] = useState(null);
  const [stepUpCaptchaToken, setStepUpCaptchaToken] = useState('');
  const [stepUpError, setStepUpError] = useState('');
  const [stepUpSubmitting, setStepUpSubmitting] = useState(false);
  const stepUpCaptchaRef = useRef(null);
  const stepUpModalRef = useRef(null);
  const pendingPayloadRef = useRef(null);
  // Set when the card was charged but checkout did not create the order (busy,
  // server error, expired hold, step-up cancelled...). The customer must not be
  // sent back to the card form or offered Cancel: the order only needs resubmitting.
  const [paidRetryPayload, setPaidRetryPayload] = useState(null);
  const [, setTick] = useState(0);
  const tickRef = useRef(null);
  const holdIdsRef = useRef(location.state?.holdIds || []);

  const [clientSecret, setClientSecret] = useState(null);

  // Countdown timer for hold expiration
  useEffect(() => {
    if (holds.length > 0 && paymentState === 'form') {
      tickRef.current = setInterval(() => setTick((t) => t + 1), 1000);
      return () => clearInterval(tickRef.current);
    }
    return () => {};
  }, [holds.length, paymentState]);

  const fetchConfirmedHolds = useCallback(async () => {
    const passedHoldIds = holdIdsRef.current;
    setError('');
    try {
      let confirmedHolds;
      if (passedHoldIds.length > 0) {
        const fetched = await Promise.all(
          passedHoldIds.map(async (id) => {
            try {
              return await api.get(`/api/holds/${id}`);
            } catch {
              return null;
            }
          })
        );
        confirmedHolds = fetched.filter((h) => h && h.status === 'CONFIRMED');
      } else {
        const data = await api.get('/api/holds?status=CONFIRMED');
        confirmedHolds = data || [];
      }

      if (confirmedHolds.length === 0) {
        setError('No confirmed holds found. Please confirm your holds first.');
        setLoading(false);
        return;
      }

      setHolds(confirmedHolds);

      // Fetch seat details
      const eventIds = [...new Set(confirmedHolds.map((h) => h.eventId))];
      const entries = {};
      await Promise.all(
        eventIds.map(async (eid) => {
          const seats = await api.get(`/api/events/${eid}/seats`);
          for (const s of seats) {
            entries[s.id] = s;
          }
        })
      );
      setSeatMap(entries);
    } catch (err) {
      setError(err.message || 'Failed to load checkout details.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchConfirmedHolds();
  }, [fetchConfirmedHolds]);

  // Fetch Stripe PaymentIntent once holds are loaded
  useEffect(() => {
    if (!stripeEnabled || holds.length === 0 || clientSecret) return;
    api.post('/api/payments/intent', { holdIds: holds.map((h) => h.id) })
      .then((r) => setClientSecret(r.clientSecret))
      .catch(() => setError('Failed to initialize payment. Please try again.'));
  }, [holds, clientSecret]);

  useEffect(() => {
    function onStepUpRequired(e) {
      setStepUpAction(e.detail?.action || 'CHECKOUT');
      setPaymentState('step-up');
    }
    window.addEventListener('auth:step-up-required', onStepUpRequired);
    return () => window.removeEventListener('auth:step-up-required', onStepUpRequired);
  }, []);

  useEffect(() => {
    if (paymentState === 'step-up' && stepUpModalRef.current) {
      stepUpModalRef.current.focus();
    }
  }, [paymentState]);

  function getSeatPrice(hold) {
    const seat = seatMap[hold.seatId];
    return seat?.price ?? 0;
  }

  function calculateTotal() {
    return holds.reduce((sum, hold) => sum + getSeatPrice(hold), 0);
  }

  function formatCard(value) {
    const digits = value.replace(/\D/g, '').slice(0, 16);
    return digits.replace(/(\d{4})(?=\d)/g, '$1 ');
  }

  async function submitPayload(payload) {
    setPaymentState('processing');
    setSubmitting(true);
    try {
      const result = await api.post('/api/payments/checkout', payload);
      setOrderResult(result);
      setPaymentState('success');
    } catch (err) {
      if (err.status === 428) {
        pendingPayloadRef.current = payload;
        return;
      }
      setPaidRetryPayload(isPaidButNotOrdered(payload, err) ? payload : null);
      setPaymentState('failed');
      setPaymentError(
        err.body?.failureReason
          || err.message
          || 'Payment declined. Please check your card details or try a different card.'
      );
    } finally {
      setSubmitting(false);
    }
  }

  async function handleStripeSuccess(paymentIntentId) {
    const payload = { holdIds: holds.map((h) => h.id), paymentIntentId };
    pendingPayloadRef.current = payload;
    await submitPayload(payload);
  }

  async function handlePayment(e) {
    e.preventDefault();
    const digits = cardNumber.replace(/\s/g, '');
    if (digits.length < 16) {
      setPaymentError('Please enter a valid 16-digit card number.');
      return;
    }
    setPaymentError('');

    let simulatedOutcome = 'SUCCESS';
    if (digits.startsWith('4000')) simulatedOutcome = 'FAILURE';
    else if (digits.startsWith('4111')) simulatedOutcome = 'CANCELLED';

    const payload = { holdIds: holds.map((h) => h.id), simulatedOutcome };
    pendingPayloadRef.current = payload;
    await submitPayload(payload);
  }

  async function handleStepUpSubmit(e) {
    e.preventDefault();
    if (!stepUpCaptchaToken) {
      setStepUpError('Please complete the CAPTCHA.');
      return;
    }
    setStepUpError('');
    setStepUpSubmitting(true);
    try {
      await api.post('/auth/step-up/verify', { captchaToken: stepUpCaptchaToken });
      setPaymentState('form');
      setStepUpAction(null);
      setStepUpCaptchaToken('');
      if (pendingPayloadRef.current) {
        await submitPayload(pendingPayloadRef.current);
      }
    } catch (err) {
      setStepUpError(err.message || 'Verification failed. Please try again.');
      if (stepUpCaptchaRef.current) stepUpCaptchaRef.current.reset();
      setStepUpCaptchaToken('');
    } finally {
      setStepUpSubmitting(false);
    }
  }

  function handleStepUpCancel() {
    setStepUpAction(null);
    const pending = pendingPayloadRef.current;
    if (pending?.paymentIntentId) {
      // Already charged: keep the customer on the paid screen, not the card form.
      setPaidRetryPayload(pending);
      setPaymentError('Additional verification is needed before your order can be completed.');
      setPaymentState('failed');
      return;
    }
    setPaymentState('form');
  }

  async function handleCancel() {
    // In simulation mode, record cancellation server-side before navigating
    if (!stripeEnabled && holds.length > 0 && paymentState !== 'failed') {
      try {
        setSubmitting(true);
        await api.post('/api/payments/checkout', {
          holdIds: holds.map((h) => h.id),
          simulatedOutcome: 'CANCELLED',
        });
      } catch {
        // Expected 402 — cancellation is recorded server-side regardless
      } finally {
        setSubmitting(false);
      }
    }
    navigate('/my-holds');
  }

  async function handleRetry() {
    if (paidRetryPayload) {
      const payload = paidRetryPayload;
      setPaidRetryPayload(null);
      setPaymentError('');
      await submitPayload(payload);
      return;
    }
    setPaymentState('form');
    setPaymentError('');
    setCardNumber('');
  }

  if (user && user.emailVerified === false) {
    return (
      <div className="checkout">
        <div className="checkout-error-page">
          <h2>Email verification required</h2>
          <p>You need to verify your email address before purchasing tickets.</p>
          <p>Check your inbox for the verification link, or{' '}
            <Link to="/dashboard">go to your dashboard</Link> to resend it.
          </p>
        </div>
      </div>
    );
  }

  if (loading) return <div className="loading">Loading checkout...</div>;

  if (orderResult) {
    return (
      <div className="checkout">
        <div className="checkout-success">
          <h2>Order Confirmed!</h2>
          <p>Your payment was successful and tickets have been issued.</p>
          <p className="checkout-order-id">Order ID: {orderResult.orderId || orderResult.id}</p>
          {holds.length > 0 && (
            <div className="checkout-success-summary">
              <h3>Order Summary</h3>
              <table className="checkout-table">
                <thead>
                  <tr>
                    <th>Section</th>
                    <th>Row</th>
                    <th>Seat</th>
                    <th>Price</th>
                  </tr>
                </thead>
                <tbody>
                  {holds.map((hold) => {
                    const seat = seatMap[hold.seatId];
                    return (
                      <tr key={hold.id}>
                        <td>{seat ? seat.section : '—'}</td>
                        <td>{seat ? seat.rowLabel : '—'}</td>
                        <td>{seat ? seat.seatNumber : hold.seatId.slice(0, 8) + '...'}</td>
                        <td>${getSeatPrice(hold).toFixed(2)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              <div className="checkout-total">
                <span>{holds.length} seat{holds.length > 1 ? 's' : ''}</span>
                <span className="checkout-price">${calculateTotal().toFixed(2)}</span>
              </div>
            </div>
          )}
          <p className="checkout-email-notice">A confirmation email has been sent to your email address.</p>
          <div className="checkout-success-actions">
            <Link to="/my-tickets" className="checkout-btn-primary">View My Tickets</Link>
            <Link to="/events" className="checkout-btn-secondary">Browse Events</Link>
          </div>
        </div>
      </div>
    );
  }

  if (error && holds.length === 0) {
    return (
    <div className="checkout">
      <div className="checkout-error-page">
        <p>{error}</p>

        <div style={{ display: 'flex', gap: '10px', justifyContent: 'center' }}>
          <button
            onClick={() => {
              setLoading(true);
              fetchConfirmedHolds();
            }}
            className="checkout-btn-primary"
          >
            Retry
          </button>

          <button
            onClick={() => navigate('/my-holds')}
            className="checkout-btn-secondary"
          >
            Back to My Holds
          </button>
        </div>
      </div>
    </div>
    );
  }

  const total = calculateTotal();

  // Check hold expiration
  const now = Date.now();
  const expiringHolds = holds.filter((h) => {
    if (!h.expiresAt || h.status === 'CONFIRMED') return false;
    const remaining = new Date(h.expiresAt).getTime() - now;
    return remaining > 0 && remaining < 120000; // < 2 minutes
  });
  const expiredHolds = holds.filter((h) => {
    if (!h.expiresAt || h.status === 'CONFIRMED') return false;
    return new Date(h.expiresAt).getTime() <= now;
  });

  return (
    <div className="checkout">
      <h2>Checkout</h2>

      {error && <div className="checkout-error">{error}</div>}

      {expiredHolds.length > 0 && (
        <div className="checkout-error">
          {expiredHolds.length} hold{expiredHolds.length > 1 ? 's have' : ' has'} expired.
          Please go back and create new holds.
          <button onClick={() => navigate('/my-holds')} className="checkout-btn-secondary" style={{ marginLeft: '0.5rem' }}>
            Back to My Holds
          </button>
        </div>
      )}

      {expiringHolds.length > 0 && expiredHolds.length === 0 && (
        <div className="checkout-warning">
          {expiringHolds.length} hold{expiringHolds.length > 1 ? 's are' : ' is'} expiring soon. Complete payment quickly.
        </div>
      )}

      <div className="checkout-summary">
        <h3>Order Summary</h3>
        <table className="checkout-table">
          <thead>
            <tr>
              <th>Section</th>
              <th>Row</th>
              <th>Seat</th>
              <th>Price</th>
            </tr>
          </thead>
          <tbody>
            {holds.map((hold) => {
              const seat = seatMap[hold.seatId];
              return (
                <tr key={hold.id}>
                  <td>{seat ? seat.section : '—'}</td>
                  <td>{seat ? seat.rowLabel : '—'}</td>
                  <td>{seat ? seat.seatNumber : hold.seatId.slice(0, 8) + '...'}</td>
                  <td>${getSeatPrice(hold).toFixed(2)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        <div className="checkout-total">
          <span>{holds.length} seat{holds.length > 1 ? 's' : ''}</span>
          <span className="checkout-price">${total.toFixed(2)}</span>
        </div>
      </div>

      {paymentState === 'step-up' && (
        <div
          className="checkout-step-up-modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="step-up-title"
          ref={stepUpModalRef}
          tabIndex={-1}
        >
          <h3 id="step-up-title">Additional Verification Required</h3>
          <p>For your security, please complete the verification below before continuing your {stepUpAction === 'SEAT_HOLD' ? 'seat hold' : 'checkout'}.</p>
          <form onSubmit={handleStepUpSubmit}>
            {stepUpError && <div className="checkout-error">{stepUpError}</div>}
            <Recaptcha ref={stepUpCaptchaRef} onChange={setStepUpCaptchaToken} />
            <div className="checkout-actions">
              <button type="button" className="checkout-btn-secondary" onClick={handleStepUpCancel} disabled={stepUpSubmitting}>
                Cancel
              </button>
              <button type="submit" className="checkout-btn-primary" disabled={stepUpSubmitting || !stepUpCaptchaToken}>
                {stepUpSubmitting ? 'Verifying…' : 'Verify & Continue'}
              </button>
            </div>
          </form>
        </div>
      )}

      {paymentState === 'processing' && (
        <div className="checkout-processing">
          <div className="checkout-spinner" />
          <p>Processing your payment...</p>
        </div>
      )}

      {paymentState === 'failed' && paidRetryPayload && (
        <div className="checkout-payment-failed">
          <div className="checkout-error">
            <p>
              Your payment went through, but your order isn&apos;t complete yet.
              Select Complete Order to finish it — you won&apos;t be charged again.
            </p>
            {paymentError && <p>{paymentError}</p>}
            <p>
              If this keeps failing, contact support and your payment will be refunded.
            </p>
          </div>
          <div className="checkout-actions">
            <button className="checkout-btn-primary" onClick={handleRetry}>
              Complete Order
            </button>
          </div>
        </div>
      )}

      {paymentState === 'failed' && !paidRetryPayload && (
        <div className="checkout-payment-failed">
          <div className="checkout-error">{paymentError}</div>
          <div className="checkout-actions">
            <button className="checkout-btn-secondary" onClick={handleCancel}>
              Cancel
            </button>
            <button className="checkout-btn-primary" onClick={handleRetry}>
              Try Again
            </button>
          </div>
        </div>
      )}

      {paymentState === 'form' && stripeEnabled && (
        clientSecret ? (
          <Elements stripe={stripePromise} options={{ clientSecret }}>
            <StripeCardForm
              holds={holds}
              total={total}
              clientSecret={clientSecret}
              onSuccess={handleStripeSuccess}
              onError={(msg) => { setPaymentError(msg); setPaymentState('failed'); }}
              onCancel={handleCancel}
              submitting={submitting}
              setSubmitting={setSubmitting}
            />
          </Elements>
        ) : (
          <div className="checkout-processing">
            <div className="checkout-spinner" />
            <p>Loading payment form...</p>
          </div>
        )
      )}

      {paymentState === 'form' && !stripeEnabled && (
        <form className="checkout-payment-form" onSubmit={handlePayment}>
          <h3>Payment Details</h3>
          {paymentError && <div className="checkout-error">{paymentError}</div>}
          <div className="checkout-field">
            <label htmlFor="cardNumber">Card Number</label>
            <input
              id="cardNumber"
              type="text"
              placeholder="4242 4242 4242 4242"
              value={cardNumber}
              onChange={(e) => setCardNumber(formatCard(e.target.value))}
              maxLength={19}
              required
              autoComplete="off"
            />
            <span className="checkout-field-hint">Use 4242... for success, 4000... for decline, 4111... for cancel</span>
          </div>
          <div className="checkout-actions">
            <button type="button" className="checkout-btn-secondary" onClick={handleCancel} disabled={submitting}>
              Cancel
            </button>
            <button type="submit" className="checkout-btn-primary" disabled={submitting}>
              Pay ${total.toFixed(2)}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

export default Checkout;
