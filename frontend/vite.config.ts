import { fileURLToPath } from 'node:url';
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';

const here = (p: string) => fileURLToPath(new URL(p, import.meta.url));

/**
 * phase 24: in `--mode demo` the real REST/WS clients are swapped for fixture-backed ones at bundle time.
 * A resolveId hook (not a string alias) so every spelling of the import (`./api/rest`, `../api/rest`) is caught.
 */
function demoSwap(): Plugin {
  const swaps: Record<string, string> = {
    [here('./src/api/rest.ts')]: here('./src/api/rest.demo.ts'),
    [here('./src/api/ws.ts')]: here('./src/api/ws.demo.ts'),
  };
  return {
    name: 'artus-demo-swap',
    enforce: 'pre',
    async resolveId(source, importer, options) {
      if (!importer || !source.startsWith('.')) return null;
      const resolved = await this.resolve(source, importer, { ...options, skipSelf: true });
      return resolved && swaps[resolved.id] ? swaps[resolved.id] : null;
    },
  };
}

export default defineConfig(({ mode }) => {
  const demo = mode === 'demo';
  return {
    plugins: [react(), ...(demo ? [demoSwap()] : [])],
    // demo: relative, so the bundle is a self-contained static site under any path (the site serves it at /demo/)
    base: demo ? './' : '/',
    server: {
      port: 5173,
      proxy: {
        '/api': 'http://localhost:8080',
        '/ws': { target: 'ws://localhost:8080', ws: true },
      },
    },
    build: {
      outDir: demo ? 'dist-demo' : 'dist',
      sourcemap: false,
    },
  };
});
