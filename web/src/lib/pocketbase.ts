import PocketBase from 'pocketbase';

const url = import.meta.env.VITE_POCKETBASE_URL;

if (!url) {
  console.warn(
    'VITE_POCKETBASE_URL is not set. Configure it in .env.local (e.g. http://127.0.0.1:8090).'
  );
}

/**
 * The single PocketBase client. Auth lives in localStorage under `pocketbase_auth`
 * and is synchronous (see `pb.authStore`), so session reads no longer await.
 */
export const pb = new PocketBase(url || 'http://127.0.0.1:8090');

// The app issues many identical concurrent queries (dashboard cards + pages).
// React Query already dedupes; PocketBase's own auto-cancel would abort a
// sibling request with the same key, so disable it.
pb.autoCancellation(false);

export default pb;
