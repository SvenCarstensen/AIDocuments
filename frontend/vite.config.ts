import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { thirdPartyNotices } from './vite-plugins/third-party-notices.ts'

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    // Lizenzhinweise aller gebündelten Pakete → dist/THIRD-PARTY-NOTICES.txt
    thirdPartyNotices({
      // per CSS-@import eingebunden, tauchen deshalb nicht unter den JavaScript-Modulen auf; von Tailwind landen die
      // Grundstile (Preflight) im ausgelieferten Stylesheet
      extraPackages: ['@fontsource-variable/inter', 'tailwindcss'],
      bundledDirectories: [
        {
          name: 'PDF.js (with PDFium, OpenJPEG, QuickJS, qcms and the Foxit and Liberation fonts)',
          license: 'Apache-2.0; bundled parts BSD, MIT, OFL-1.1, CC0',
          url: 'https://github.com/mozilla/pdf.js',
          dir: 'public/pdfjs',
        },
      ],
    }),
  ],
  server: {
    port: 5173,
    // Im Entwicklungsmodus leitet Vite alle API-Aufrufe an das Spring-Boot-Backend weiter.
    // AIDOCS_BACKEND erlaubt ein anderes Ziel, z. B. eine zweite Instanz zum Ausprobieren.
    proxy: {
      '/api': {
        target: process.env.AIDOCS_BACKEND ?? 'http://127.0.0.1:8080',
        // Die Adresse aus dem Browser weitergeben (die Kurzform '/api': url ersetzt sie durch die des Backends):
        // Daraus leitet der Server die Passkey-Domain und den erwarteten Ursprung ab.
        changeOrigin: false,
      },
    },
  },
})
