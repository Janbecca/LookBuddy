import { create } from 'zustand';
import type { TokenPair, User } from '../types';

const STORAGE_KEY = 'lookbuddy.auth.v1';

interface AuthState {
  accessToken: string | null;
  refreshToken: string | null;
  user: User | null;
  setTokens: (tokens: TokenPair) => void;
  clear: () => void;
}

function restore(): Pick<AuthState, 'accessToken' | 'refreshToken' | 'user'> {
  try {
    const value = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}');
    // Legacy installation/email sessions must log into the unified identity;
    // do not carry their history into a different username.
    if (typeof value.user?.username !== 'string' || !value.user.username.trim()) {
      return { accessToken: null, refreshToken: null, user: null };
    }
    return { accessToken: value.accessToken ?? null, refreshToken: value.refreshToken ?? null, user: value.user ?? null };
  } catch {
    return { accessToken: null, refreshToken: null, user: null };
  }
}

export const useAuth = create<AuthState>((set) => ({
  ...restore(),
  setTokens: (tokens) => {
    const next = { accessToken: tokens.accessToken, refreshToken: tokens.refreshToken, user: tokens.user };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    set(next);
  },
  clear: () => {
    localStorage.removeItem(STORAGE_KEY);
    set({ accessToken: null, refreshToken: null, user: null });
  },
}));

