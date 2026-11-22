import { useQuery } from '@tanstack/react-query'
import { t } from 'i18next'
import { api, type Area } from './api'

/** Die Bereiche, die der Angemeldete sieht (Admins: alle). */
export function useAreas() {
  return useQuery({ queryKey: ['areas'], queryFn: api.areas, staleTime: 60_000 })
}

/** Der allgemeine Bereich hat ohne eigenen Namen den übersetzten. */
export function areaName(area: Pick<Area, 'name'> | undefined | null): string {
  return area?.name?.trim() || t('areas.general')
}

/** Name zu einer Bereichs-ID – unbekannte (nicht sichtbare) zeigen die ID nicht, sondern einen Platzhalter. */
export function areaNameById(areas: Area[] | undefined, id: string | null | undefined): string {
  const area = areas?.find((a) => a.id === id)
  return area ? areaName(area) : id === 'general' ? t('areas.general') : t('areas.unknown')
}
