import { api } from '@/services/baseApi'

export interface MasterOption {
  id: number
  code: string
  name: string
}

export interface MasterItem {
  id?: number
  code: string
  name: string
  description?: string
  active?: boolean
}

/** Active master-list entries for dropdowns; every signed-in role may read them. */
const masterDataApi = api.injectEndpoints({
  endpoints: (build) => ({
    masterOptions: build.query<MasterOption[], string>({ query: (list) => `/master-data/${list}/options` }),
  }),
})

export const { useMasterOptionsQuery } = masterDataApi

export function findMasterLabel(list: (MasterOption | MasterItem)[] | undefined, code: string | null | undefined): string {
  if (!code) return '-'
  return list?.find((item) => item.code === code)?.name ?? code
}
