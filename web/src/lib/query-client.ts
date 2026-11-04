import { QueryClient } from '@tanstack/react-query';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 1000 * 60 * 5, // 5 minutes
      gcTime: 1000 * 60 * 30, // 30 minutes (formerly cacheTime)
      retry: 3,
      retryDelay: (attemptIndex) => Math.min(1000 * 2 ** attemptIndex, 30000),
      refetchOnWindowFocus: false,
      // Polls (refetchInterval) already pause while the app is backgrounded —
      // this is React Query's default, made explicit so it can't regress.
      refetchIntervalInBackground: false,
    },
    mutations: {
      retry: false,
    },
  },
});
