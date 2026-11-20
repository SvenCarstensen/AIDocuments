import { useQuery, useQueryClient } from '@tanstack/react-query'
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo } from 'react'
import { api, type AuthStatus, type User } from './api'

interface AuthContextValue {
  user: User
  isAdmin: boolean
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth außerhalb des AuthProvider verwendet')
  return value
}

/** Lädt den Anmeldestatus; die App entscheidet daran zwischen Ersteinrichtung, Anmeldung und Anwendung. */
export function useAuthStatus() {
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: ['auth'], queryFn: api.authStatus, staleTime: Infinity, retry: 1 })

  useEffect(() => {
    // Eine abgelaufene oder beendete Sitzung führt zurück zur Anmeldung.
    const onExpired = () => queryClient.invalidateQueries({ queryKey: ['auth'] })
    window.addEventListener('auth:expired', onExpired)
    return () => window.removeEventListener('auth:expired', onExpired)
  }, [queryClient])

  const setStatus = useCallback(
    (status: AuthStatus) => queryClient.setQueryData(['auth'], status),
    [queryClient],
  )
  return { ...query, setStatus }
}

export function AuthProvider({ user, children }: { user: User; children: ReactNode }) {
  const queryClient = useQueryClient()
  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      isAdmin: user.role === 'ADMIN',
      logout: async () => {
        await api.logout()
        queryClient.clear()
        await queryClient.invalidateQueries({ queryKey: ['auth'] })
      },
    }),
    [user, queryClient],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
