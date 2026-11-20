import { useCallback, useState } from 'react'

/** Hell/Dunkel-Umschaltung; die Wahl bleibt im Browser gespeichert, sonst gilt die Systemeinstellung. */
export function useTheme() {
  const [dark, setDark] = useState(() => document.documentElement.classList.contains('dark'))
  const toggle = useCallback(() => {
    setDark((current) => {
      const next = !current
      document.documentElement.classList.toggle('dark', next)
      try {
        localStorage.setItem('theme', next ? 'dark' : 'light')
      } catch {
        /* privater Modus – dann eben nur für diese Sitzung */
      }
      return next
    })
  }, [])
  return { dark, toggle }
}
