import { api } from '../app/store'

export interface MasterOption {
  id: number
  code: string
  name: string
}

/** Active master-list entries for dropdowns; every signed-in role may read them. */
const masterDataApi = api.injectEndpoints({
  endpoints: (build) => ({
    masterOptions: build.query<MasterOption[], string>({ query: (list) => `/master-data/${list}/options` }),
  }),
})

export const { useMasterOptionsQuery } = masterDataApi
