import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import electronSimple from 'vite-plugin-electron/simple';
import path from 'path';

export default defineConfig({
    plugins: [
        react(),
        electronSimple({
            main: {
                entry: 'electron/main.ts',
                onstart(args) {
                    // Remove ELECTRON_RUN_AS_NODE from env so Electron starts in app mode
                    const env = { ...process.env };
                    delete env.ELECTRON_RUN_AS_NODE;
                    args.startup(['.', '--no-sandbox'], { env });
                },
                vite: {
                    build: {
                        outDir: 'dist-electron',
                        rollupOptions: {
                            external: ['electron', 'path', 'fs', 'os', 'url', 'sql.js', 'uuid'],
                        },
                    },
                },
            },
            preload: {
                input: 'electron/preload.ts',
                vite: {
                    build: {
                        outDir: 'dist-electron',
                        rollupOptions: {
                            external: ['electron'],
                        },
                    },
                },
            },
        }),
    ],
    resolve: {
        alias: {
            '@': path.resolve(__dirname, './src'),
        },
    },
    build: {
        outDir: 'dist',
    },
});
