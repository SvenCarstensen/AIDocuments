import { useTranslation } from 'react-i18next'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/AppShell'
import { ConfirmCodeHost } from './components/ConfirmCodeHost'
import { Button, Logo, Spinner } from './components/ui'
import { api, errorMessage } from './lib/api'
import { AuthProvider, useAuthStatus } from './lib/auth'
import { LoginPage, SetupPage, TwoFactorRequiredPage } from './pages/AuthPages'
import { DocumentPage } from './pages/DocumentPage'
import { DocumentsPage } from './pages/DocumentsPage'
import { DuplicatePage } from './pages/DuplicatePage'
import { HistoryPage } from './pages/HistoryPage'
import { SettingsPage } from './pages/SettingsPage'

export default function App() {
  const { t } = useTranslation()
  const auth = useAuthStatus()

  if (auth.isPending) {
    return (
      <div className="flex min-h-dvh items-center justify-center">
        <Spinner className="size-7" />
      </div>
    )
  }
  if (auth.isError || !auth.data) {
    return (
      <div className="flex min-h-dvh flex-col items-center justify-center gap-4 px-6 text-center">
        <Logo className="size-12" />
        <div>
          <h1 className="text-lg font-semibold">{t('errors.noConnection')}</h1>
          <p className="mt-1 text-sm text-muted">{errorMessage(auth.error)}</p>
        </div>
        <Button onClick={() => auth.refetch()} loading={auth.isFetching}>
          {t('common.retry')}
        </Button>
      </div>
    )
  }
  if (auth.data.setupRequired) {
    return <SetupPage onDone={(user) => auth.setStatus({ setupRequired: false, user })} />
  }
  if (!auth.data.user) {
    return <LoginPage onDone={(user) => auth.setStatus({ setupRequired: false, user })} />
  }
  if (auth.data.user.role === 'ADMIN' && !auth.data.user.twoFactorEnabled) {
    // Pflicht für Administratoren – der Server lässt bis dahin ohnehin nichts anderes zu
    return (
      <TwoFactorRequiredPage
        onDone={() => auth.refetch()}
        onLogout={() => {
          api.logout().finally(() => auth.setStatus({ setupRequired: false, user: null }))
        }}
      />
    )
  }

  const isAdmin = auth.data.user.role === 'ADMIN'
  return (
    <AuthProvider user={auth.data.user}>
      <ConfirmCodeHost />
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/documents" element={<DocumentsPage />} />
          <Route path="/documents/:id" element={<DocumentPage />} />
          <Route path="/history" element={<HistoryPage />} />
          <Route path="/duplicates/:id" element={<DuplicatePage />} />
          {isAdmin && <Route path="/settings/:section?" element={<SettingsPage />} />}
          <Route path="*" element={<Navigate to="/documents" replace />} />
        </Route>
      </Routes>
    </AuthProvider>
  )
}
