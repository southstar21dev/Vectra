import { useEffect, type ReactNode } from 'react';
import { useAuth } from './use-auth';
import { PageLoader } from '@/components/ui/page-loader';

interface AuthGuardProps {
  children: ReactNode;
  fallback?: ReactNode;
}

export function AuthGuard({ children, fallback }: AuthGuardProps) {
  const { isAuthenticated, isLoading } = useAuth();
  const shouldRedirect = !isLoading && !isAuthenticated && !fallback;

  // Navigation is a side effect — never performed during render.
  useEffect(() => {
    if (shouldRedirect) window.location.href = '/login';
  }, [shouldRedirect]);

  if (isLoading) {
    return <PageLoader />;
  }

  if (!isAuthenticated) {
    if (fallback) return <>{fallback}</>;
    return null;
  }

  return <>{children}</>;
}
