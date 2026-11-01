import { setupWorker } from 'msw/browser'
import { getAfterBoothAPIMock } from '../api/generated/afterbooth.msw'
import { requestHandlers } from '../features/request/mocks'
import { allocationHandlers } from '../features/allocation/mocks'

// For development before the backend is ready. Uses the handlers generated from contract/openapi.yaml
export const worker = setupWorker(
  ...requestHandlers,
  ...allocationHandlers,
  ...getAfterBoothAPIMock(),
)
