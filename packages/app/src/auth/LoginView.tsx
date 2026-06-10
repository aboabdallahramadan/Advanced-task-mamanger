// packages/app/src/auth/LoginView.tsx
import React, { useState } from 'react';
import { useAuthStore } from './authStore';

interface LoginViewProps {
  onSwitchToRegister: () => void;
}

export function LoginView({ onSwitchToRegister }: LoginViewProps) {
  const login = useAuthStore((s) => s.login);
  const error = useAuthStore((s) => s.error);
  const networkError = useAuthStore((s) => s.networkError);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    try {
      await login(email.trim(), password);
    } catch {
      /* error surfaced via store.error */
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="h-screen flex items-center justify-center bg-surface-950 text-surface-200 select-none">
      <div className="fixed top-0 left-0 right-0 h-10 titlebar-drag-region z-50" />
      <form
        onSubmit={handleSubmit}
        className="w-full max-w-sm bg-surface-900/60 border border-surface-800/60 rounded-2xl p-8 shadow-2xl"
      >
        <h1 className="text-2xl font-bold text-surface-50 mb-1">Welcome back</h1>
        <p className="text-sm text-surface-400 mb-6">Sign in to your TMap account.</p>

        {networkError && (
          <div className="mb-4 rounded-lg border border-amber-700/40 bg-amber-900/30 px-3 py-2 text-sm text-amber-300">
            Couldn&apos;t reach the server. Check your connection and try again.
          </div>
        )}
        {error && !networkError && (
          <div
            role="alert"
            className="mb-4 rounded-lg border border-red-700/40 bg-red-900/30 px-3 py-2 text-sm text-red-300"
          >
            {error}
          </div>
        )}

        <label className="block mb-3">
          <span className="block text-xs font-medium text-surface-400 mb-1">Email</span>
          <input
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={submitting}
            className="input-base w-full"
            placeholder="you@example.com"
          />
        </label>

        <label className="block mb-5">
          <span className="block text-xs font-medium text-surface-400 mb-1">Password</span>
          <input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={submitting}
            className="input-base w-full"
            placeholder="••••••••"
          />
        </label>

        <button type="submit" disabled={submitting} className="btn-primary w-full disabled:opacity-50">
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>

        <p className="mt-5 text-center text-sm text-surface-400">
          Don&apos;t have an account?{' '}
          <button
            type="button"
            onClick={onSwitchToRegister}
            className="text-accent-400 hover:text-accent-300 font-medium"
          >
            Create one
          </button>
        </p>
      </form>
    </div>
  );
}
