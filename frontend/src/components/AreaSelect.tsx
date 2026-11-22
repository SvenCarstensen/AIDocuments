import { useTranslation } from 'react-i18next'
import { areaName, useAreas } from '../lib/areas'
import { Field, Select } from './ui'

/** Auswahl des Zielbereichs (Postfach, Eingangsordner); ohne weitere Bereiche nicht nötig und nicht angezeigt. */
export function AreaSelect({ value, onChange, hint, disabled }: { value: string; onChange: (id: string) => void; hint?: string; disabled?: boolean }) {
  const { t } = useTranslation()
  const areas = useAreas()
  if (!areas.data || areas.data.length < 2) return null
  return (
    <Field label={t('areas.target')} hint={hint}>
      {(id) => (
        <Select id={id} value={value} onChange={(e) => onChange(e.target.value)} disabled={disabled}>
          {areas.data.map((area) => (
            <option key={area.id} value={area.id}>
              {areaName(area)}
            </option>
          ))}
        </Select>
      )}
    </Field>
  )
}
