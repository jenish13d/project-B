import { describe, expect, it } from "vitest";

import { executeTool, normalizeOrderNumber, type OrderSummary, type StoreApi } from "../tools.server";
import { sanitizeSearchTerms } from "../store.server";

const order: OrderSummary = {
  name: "#1001",
  email: "Jane@Example.com",
  createdAt: "2026-09-30T10:00:00Z",
  cancelled: false,
  financialStatus: "PAID",
  fulfillmentStatus: "FULFILLED",
  items: [{ title: "Linen shirt", quantity: 1 }],
  tracking: [{ company: "UPS", number: "1Z999", url: "https://ups.example/1Z999" }],
};

function fakeStore(overrides: Partial<StoreApi> = {}): StoreApi {
  return {
    findOrder: async (n) => (n === "1001" ? order : null),
    searchProducts: async () => [],
    ...overrides,
  };
}

describe("normalizeOrderNumber", () => {
  it("strips the hash and spaces", () => {
    expect(normalizeOrderNumber(" #10 01 ")).toBe("1001");
  });
});

describe("lookup_order", () => {
  it("returns the order without the email when the email matches (case-insensitive)", async () => {
    const out = await executeTool("lookup_order", { order_number: "#1001", email: "jane@example.com " }, fakeStore());
    const parsed = JSON.parse(out.content);
    expect(parsed.name).toBe("#1001");
    expect(parsed.tracking[0].number).toBe("1Z999");
    expect(parsed).not.toHaveProperty("email");
  });

  it("gives the same answer for a wrong email and a missing order", async () => {
    const wrongEmail = await executeTool("lookup_order", { order_number: "1001", email: "x@y.com" }, fakeStore());
    const missing = await executeTool("lookup_order", { order_number: "9999", email: "jane@example.com" }, fakeStore());
    expect(wrongEmail.content).toBe(missing.content);
    expect(wrongEmail.content).not.toContain("Linen");
  });

  it("refuses orders that have no email on file", async () => {
    const store = fakeStore({ findOrder: async () => ({ ...order, email: null }) });
    const out = await executeTool("lookup_order", { order_number: "1001", email: "jane@example.com" }, store);
    expect(out.content).toMatch(/No order matches/);
  });

  it("errors when an argument is missing", async () => {
    const out = await executeTool("lookup_order", { order_number: "1001" }, fakeStore());
    expect(out.isError).toBe(true);
  });
});

describe("search_products", () => {
  it("returns products as JSON", async () => {
    const store = fakeStore({
      searchProducts: async () => [
        { title: "Linen shirt", description: "Breathable", price: "49.00 USD", inStock: true, url: null },
      ],
    });
    const out = await executeTool("search_products", { query: "linen" }, store);
    expect(JSON.parse(out.content)[0].title).toBe("Linen shirt");
  });

  it("says when nothing matches", async () => {
    const out = await executeTool("search_products", { query: "kayak" }, fakeStore());
    expect(out.content).toBe("No matching products found.");
  });
});

describe("request_human_handoff", () => {
  it("records the handoff with the email", async () => {
    const out = await executeTool("request_human_handoff", { reason: "Wants a refund", customer_email: "a@b.com" }, fakeStore());
    expect(out.handoff).toEqual({ reason: "Wants a refund", customerEmail: "a@b.com" });
  });

  it("treats an empty email as missing and asks for one", async () => {
    const out = await executeTool("request_human_handoff", { reason: "Complaint", customer_email: "" }, fakeStore());
    expect(out.handoff?.customerEmail).toBeNull();
    expect(out.content).toMatch(/Ask the customer for their email/);
  });
});

describe("sanitizeSearchTerms", () => {
  it("removes Shopify search syntax characters", () => {
    expect(sanitizeSearchTerms('red shoes" OR status:draft (x)')).toBe("red shoes OR status draft x");
  });
});
