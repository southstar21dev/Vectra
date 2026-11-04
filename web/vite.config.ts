import { defineConfig, loadEnv } from 'vite'
import { configDefaults } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { VitePWA } from 'vite-plugin-pwa'
import path from 'path'
import { readFileSync } from 'node:fs'
import { radarProxyPlugin } from './vite-plugins/radar-proxy.ts'

// package.json is the single source of truth for the app version (what the
// PWA and Settings → About show).
const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf-8'))

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // .env / .env.local / .env.[mode] — prefix '' so non-VITE_ vars are included.
  const env = loadEnv(mode, process.cwd(), '');

  return {
    define: {
      // Injected into the client bundle as `__APP_VERSION__` (see
      // src/lib/app-version.ts).
      __APP_VERSION__: JSON.stringify(env.VITE_APP_VERSION || pkg.version || '0.0.0'),
    },
    server: {
      host: true, // Exposes Vite dev server to local network (Wi-Fi)
      port: 5173,
    },
    plugins: [
      radarProxyPlugin(),
      react(),
      tailwindcss(),
      VitePWA({
        strategies: 'injectManifest',
        srcDir: 'src',
        filename: 'sw.ts',
        registerType: 'autoUpdate',
        devOptions: {
          enabled: true,
          type: 'module',
        },
        includeAssets: ['icons/*.png', 'sounds/*.mp3'],
        manifest: false, // Use public/manifest.json
        injectManifest: {
          globPatterns: ['**/*.{js,css,html,ico,png,svg,woff2,pbf,mp3}'],
          // Catalog JSON is runtime-fetched and cached by the SW's
          // NetworkFirst route instead. Precaching it would make every daily
          // data refresh change sw.js and reload installed PWAs.
          globIgnores: ['**/catalog/**'],
        },
      }),
    ],
    resolve: {
      alias: {
        '@': path.resolve(import.meta.dirname, './src'),
      },
    },
    optimizeDeps: {
      exclude: ['maplibre-gl'],
    },
    // Vitest: `vitest list` otherwise picks up stale worktree copies under
    // .worktrees (git-ignored) and inflates the suite with tests running
    // against an older source snapshot. TZ is pinned because the app's domain
    // (schedule, transit) reasons in Budapest wall-clock time.
    test: {
      exclude: [...configDefaults.exclude, '**/.worktrees/**', '**/.freebuff/**'],
      env: {
        TZ: 'Europe/Budapest',
      },
    },
    // Installed-PWA audience is all modern browsers: es2022 target keeps the
    // output smaller (less transpilation), and vendor chunking lets the PWA
    // cache react/framer-motion once instead of per release.
    build: {
      target: 'es2022',
      chunkSizeWarningLimit: 1000,
      rollupOptions: {
        output: {
          manualChunks(id: string) {
            if (id.includes('node_modules/react') || id.includes('node_modules/react-dom')) return 'react';
            if (id.includes('node_modules/framer-motion')) return 'motion';
            if (id.includes('node_modules/maplibre-gl')) return 'maplibre-core';
            // Merge the small UI primitives into one chunk. Scoped to
            // components/ui only — feature dialogs/widgets import maplibre and
            // must stay in their own (lazy) chunks.
            if (id.includes('/src/components/ui/') || id.includes('node_modules/@radix-ui')) {
              return 'ui-shared-primitives';
            }
          },
        },
      },
    },
  };
})
