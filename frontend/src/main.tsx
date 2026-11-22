import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import i18next from 'i18next'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { Toaster } from 'sonner'
import App from './App'
import './index.css'
import { SharePage } from './pages/SharePage'
import { initI18n } from './lib/i18n'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 10_000, retry: 1, refetchOnWindowFocus: true },
  },
})

// Erst die Sprache laden, dann zeichnen – so blitzt kein Text in der falschen Sprache auf.
void initI18n().then(() => {
  // Auch der Server antwortet in der Sprache der Oberfläche: nach einem Wechsel alles neu abfragen,
  // damit seine Texte (Statusmeldungen, Fehlerhinweise) nicht in der alten Sprache stehen bleiben.
  i18next.on('languageChanged', () => void queryClient.invalidateQueries())

  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <Routes>
            {/* geteilte Dokumente: ohne Anmeldung, nur dieses eine Dokument */}
            <Route path="/s/:token" element={<SharePage />} />
            <Route path="*" element={<App />} />
          </Routes>
        </BrowserRouter>
        <Toaster
          position="bottom-right"
          toastOptions={{
            style: {
              background: 'var(--surface)',
              color: 'var(--ink)',
              border: '1px solid var(--line)',
              boxShadow: 'var(--shadow-lg)',
              fontFamily: 'inherit',
            },
          }}
        />
      </QueryClientProvider>
    </StrictMode>,
  )
})
