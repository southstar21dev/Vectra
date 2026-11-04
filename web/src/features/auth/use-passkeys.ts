import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import { pb } from '@/lib/pocketbase';
import {
  isPasskeySupported,
  listPasskeys,
  registerPasskey,
  signInWithPasskey,
  deletePasskey,
  type PasskeyError,
} from './passkeys';

export function usePasskeys() {
  const queryClient = useQueryClient();
  const [isSupported, setIsSupported] = useState<boolean | null>(null);

  useEffect(() => {
    let active = true;
    void isPasskeySupported().then((ok) => {
      if (active) setIsSupported(ok);
    });
    return () => {
      active = false;
    };
  }, []);

  const listQuery = useQuery({
    queryKey: queryKeys.passkeys,
    queryFn: listPasskeys,
    // The login form uses this hook too; listing needs a session (401 otherwise).
    enabled: isSupported !== false && pb.authStore.isValid,
  });

  const registerMutation = useMutation({
    mutationFn: registerPasskey,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.passkeys }),
  });

  const deleteMutation = useMutation({
    mutationFn: deletePasskey,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.passkeys }),
  });

  const signInMutation = useMutation({
    mutationFn: async () => {
      await signInWithPasskey();
    },
  });

  return {
    isSupported,
    passkeys: listQuery.data,
    passkeysLoading: listQuery.isLoading,
    passkeysError: listQuery.isError,
    refetchPasskeys: listQuery.refetch,
    register: registerMutation.mutateAsync,
    registerPending: registerMutation.isPending,
    registerError: registerMutation.error as PasskeyError | null,
    signIn: signInMutation.mutateAsync,
    signInPending: signInMutation.isPending,
    signInError: signInMutation.error as PasskeyError | null,
    deletePasskey: deleteMutation.mutateAsync,
    deletePending: deleteMutation.isPending,
  };
}
