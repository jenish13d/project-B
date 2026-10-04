# StorePilot

AI customer-support assistant for Shopify stores, built on Shopify's React Router app template.
See README.md for the architecture and setup.

- Assistant logic lives in `app/assistant/` and has unit tests in `app/assistant/__tests__/` (`npm test`).
  Keep Shopify calls behind the `StoreApi` interface so the loop stays testable without a store.
- Before pushing, run `npm run lint`, `npm run typecheck` and `npm test`.
- Use the [Shopify AI Toolkit](https://shopify.dev/docs/apps/build/ai-toolkit) for Shopify API and platform work.
