import { DraftCategory } from '../../api/generated/model'

export const CATEGORY_LABEL: Record<DraftCategory, string> = {
  DISPLAY_RACK: 'Display rack',
  STORAGE_BIN: 'Storage bin',
  FOLDING_TABLE: 'Folding table',
  UNKNOWN: 'Unclassified',
}

export const CATEGORY_COLOR: Record<DraftCategory, { box: string; dot: string }> = {
  DISPLAY_RACK: { box: 'border-sky-500 bg-sky-500/15', dot: 'bg-sky-500' },
  STORAGE_BIN: { box: 'border-amber-500 bg-amber-500/15', dot: 'bg-amber-500' },
  FOLDING_TABLE: { box: 'border-violet-500 bg-violet-500/15', dot: 'bg-violet-500' },
  UNKNOWN: { box: 'border-red-500 bg-red-500/15', dot: 'bg-red-500' },
}

export const CATEGORIES = Object.keys(CATEGORY_LABEL) as DraftCategory[]
