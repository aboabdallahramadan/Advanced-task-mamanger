import sharedConfig from '@tmap/app/tailwind.config.js';

/** @type {import('tailwindcss').Config} */
export default {
  presets: [sharedConfig],
  content: [
    './index.html',
    './src/**/*.{js,ts,jsx,tsx}',
    '../../packages/app/src/**/*.{js,ts,jsx,tsx}',
  ],
};
