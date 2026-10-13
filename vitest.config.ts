import { defineConfig } from "vitest/config";

// Kept separate from vite.config.ts so tests don't load the React Router / Shopify dev plugins.
export default defineConfig({
  test: {
    include: ["app/**/*.test.ts", "tools/**/*.test.ts"],
    environment: "node",
  },
});
