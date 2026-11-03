import { defineConfig } from 'orval'

// contract/openapi.yaml → fetch client + MSW mocks. Generated files are not committed (npm run gen:api)
export default defineConfig({
  afterbooth: {
    input: { target: '../contract/openapi.yaml' },
    output: {
      mode: 'single',
      target: 'src/api/generated/afterbooth.ts',
      schemas: 'src/api/generated/model',
      client: 'fetch',
      // makes the required Idempotency-Key header a typed argument, so a forgotten key fails at compile time
      headers: true,
      override: {
        mutator: { path: 'src/api/http.ts', name: 'http' },
        // These reads use http's thrown errors; other operations retain explicit status narrowing.
        operations: {
          listOrganizations: { fetch: { forceSuccessResponse: true } },
          listEvents: { fetch: { forceSuccessResponse: true } },
          listItems: { fetch: { forceSuccessResponse: true } },
          listReservations: { fetch: { forceSuccessResponse: true } },
        },
      },
      mock: true,
      clean: true,
    },
  },
})
