import React, { useState, useMemo, useEffect } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useAuth } from '../auth/useAuth';
import api from '../api/client';
import '../styles/Login.css';

const PASSWORD_RULES = [
  { key: 'length', label: 'At least 8 characters', test: (p) => p.length >= 8 },
  { key: 'upper', label: 'Uppercase letter', test: (p) => /[A-Z]/.test(p) },
  { key: 'lower', label: 'Lowercase letter', test: (p) => /[a-z]/.test(p) },
  { key: 'digit', label: 'A digit', test: (p) => /\d/.test(p) },
  { key: 'special', label: 'Special character', test: (p) => /[^A-Za-z0-9]/.test(p) },
];

function Signup() {
  useEffect(() => { document.title = 'Create Account | FairTix'; }, []);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [registered, setRegistered] = useState(false);
  const [resendLoading, setResendLoading] = useState(false);
  const [resendMessage, setResendMessage] = useState('');
  const { signup, user } = useAuth();
  const passwordChecks = useMemo(
    () => PASSWORD_RULES.map((rule) => ({ ...rule, passed: rule.test(password) })),
    [password]
  );
  const allPasswordRequirementsMet = passwordChecks.every((c) => c.passed);

  if (user) {
    return <Navigate to="/dashboard" replace />;
  }

  async function handleSubmit(e) {
    e.preventDefault();
    setError('');

    if (!allPasswordRequirementsMet) {
      setError('Password does not meet all requirements.');
      return;
    }

    if (password !== confirmPassword) {
      setError('Passwords do not match.');
      return;
    }

    setLoading(true);
    try {
      await signup(email, password);
      setRegistered(true);
    } catch (err) {
      if (err.status === 409) {
        setError('Email already exists.');
      } else if (err.status === 400) {
        setError('Invalid registration details.');
      } else if (err.status === 403) {
        setError('Registration is closed. Contact the organiser for access.');
      } else if (err.status >= 500) {
        setError('Server error. Please try again later.');
      } else if (err.status) {
        setError('Registration failed. Please try again.');
      } else {
        setError('Network error. Please check your connection.');
      }
    } finally {
      setLoading(false);
    }
  }

  async function handleResend() {
    setResendLoading(true);
    setResendMessage('');
    try {
      await api.post('/auth/resend-verification');
      setResendMessage('Verification email sent. Please check your inbox.');
    } catch {
      setResendMessage('Could not send email. Please try again later.');
    } finally {
      setResendLoading(false);
    }
  }

  if (registered) {
    return (
      <div className="login-page">
        <h2>Check your email</h2>
        <p>We sent a verification link to <strong>{email}</strong>. Click it to activate your account.</p>
        <p>Didn't receive it?{' '}
          <button
            onClick={handleResend}
            disabled={resendLoading}
            style={{ background: 'none', border: 'none', color: 'inherit', cursor: 'pointer', textDecoration: 'underline', padding: 0 }}
          >
            {resendLoading ? 'Sending...' : 'Resend'}
          </button>
        </p>
        {resendMessage && <p>{resendMessage}</p>}
        <p><Link to="/dashboard">Continue to dashboard</Link></p>
      </div>
    );
  }

  return (
    <div className="login-page">
      <h2>Sign Up</h2>
      <form onSubmit={handleSubmit} className="login-form">
        {error && <div className="error-message">{error}</div>}
        <div className="form-group">
          <label htmlFor="email">Email</label>
          <input
            id="email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </div>
        <div className="form-group">
          <label htmlFor="password">Password</label>
          <input
            id="password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            minLength={8}
          />
          {password.length > 0 && (
            <ul className="password-strength">
              {passwordChecks.map((check) => (
                <li key={check.key} className={check.passed ? 'met' : 'unmet'}>
                  {check.passed ? '\u2713' : '\u2717'} {check.label}
                </li>
              ))}
            </ul>
          )}
        </div>
        <div className="form-group">
          <label htmlFor="confirmPassword">Confirm password</label>
          <input
            id="confirmPassword"
            type="password"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
            required
          />
        </div>
        <button type="submit" disabled={loading || !allPasswordRequirementsMet}>
          {loading ? 'Signing up...' : 'Sign Up'}
        </button>
      </form>
      <p className="form-link">
        Already have an account? <Link to="/login">Log in</Link>
      </p>
    </div>
  );
}

export default Signup;
