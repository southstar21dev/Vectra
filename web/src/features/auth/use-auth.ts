import { useCallback } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import { sessionQueryOptions, profileQueryOptions } from './auth.queries';
import {
  changeEmail,
  changePassword,
  deleteUserAccount,
  loginWithPassword,
  signOut,
  signupWithPassword,
  updateUserProfile,
} from './auth-mutations';

export function useAuth() {
  const queryClient = useQueryClient();

  const { data: session, isLoading: sessionLoading } = useQuery(sessionQueryOptions);
  const { data: profile } = useQuery({
    ...profileQueryOptions,
    enabled: !!session,
  });

  // Cache invalidation on auth-store changes lives in ONE module-level
  // subscription (auth-store-sync.ts, installed from main.tsx), not here:
  // this hook has ~15 consumers.

  const loginMutation = useMutation({
    mutationFn: loginWithPassword,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.auth.root });
      queryClient.invalidateQueries({ queryKey: queryKeys.profile });
    },
  });

  const signupMutation = useMutation({
    mutationFn: signupWithPassword,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.auth.root });
      queryClient.invalidateQueries({ queryKey: queryKeys.profile });
    },
  });

  const updateProfileMutation = useMutation({
    mutationFn: updateUserProfile,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.profile });
    },
  });

  // Sensitive account changes verify the current password first by re-running
  // the password auth (which also refreshes the local auth token/record).

  const updateEmailMutation = useMutation({
    mutationFn: changeEmail,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.auth.root });
      queryClient.invalidateQueries({ queryKey: queryKeys.profile });
    },
  });

  const updatePasswordMutation = useMutation({
    mutationFn: changePassword,
  });

  const deleteAccountMutation = useMutation({
    mutationFn: deleteUserAccount,
  });

  const logout = useCallback(() => signOut(), []);

  return {
    session,
    profile,
    user: session?.record ?? null,
    isAuthenticated: !!session,
    isLoading: sessionLoading,
    login: loginMutation.mutateAsync,
    loginError: loginMutation.error,
    loginPending: loginMutation.isPending,
    signup: signupMutation.mutateAsync,
    signupError: signupMutation.error,
    signupPending: signupMutation.isPending,
    updateProfile: updateProfileMutation.mutateAsync,
    updateEmail: updateEmailMutation.mutateAsync,
    updateEmailPending: updateEmailMutation.isPending,
    updateEmailError: updateEmailMutation.error,
    updatePassword: updatePasswordMutation.mutateAsync,
    updatePasswordPending: updatePasswordMutation.isPending,
    updatePasswordError: updatePasswordMutation.error,
    deleteAccount: deleteAccountMutation.mutateAsync,
    deleteAccountPending: deleteAccountMutation.isPending,
    deleteAccountError: deleteAccountMutation.error,
    logout,
  };
}
