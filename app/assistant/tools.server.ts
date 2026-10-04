import type { BetaTool } from "@anthropic-ai/sdk/resources/beta/messages/messages";

/**
 * Everything the assistant can look up in a store. The Shopify-backed
 * implementation lives in store.server.ts; tests pass a fake.
 */
export interface StoreApi {
  findOrder(orderNumber: string): Promise<OrderSummary | null>;
  searchProducts(query: string): Promise<ProductSummary[]>;
}

export interface OrderSummary {
  name: string;
  email: string | null;
  createdAt: string;
  cancelled: boolean;
  financialStatus: string | null;
  fulfillmentStatus: string | null;
  items: { title: string; quantity: number }[];
  tracking: { company: string | null; number: string | null; url: string | null }[];
}

export interface ProductSummary {
  title: string;
  description: string;
  price: string | null;
  inStock: boolean;
  url: string | null;
}

/** What the tool loop needs to know about a handoff request. */
export interface HandoffRequest {
  reason: string;
  customerEmail: string | null;
}

export const TOOLS: BetaTool[] = [
  {
    name: "lookup_order",
    description:
      "Look up the status, items and tracking of a customer's order. Requires BOTH the order number " +
      "and the email address used at checkout; ask the customer for whichever is missing before calling. " +
      "Never guess either value.",
    strict: true,
    input_schema: {
      type: "object",
      properties: {
        order_number: {
          type: "string",
          description: "Order number as the customer gave it, e.g. '1001' or '#1001'.",
        },
        email: {
          type: "string",
          description: "Email address the customer used when placing the order.",
        },
      },
      required: ["order_number", "email"],
      additionalProperties: false,
    },
  },
  {
    name: "search_products",
    description:
      "Search the store's active products by keywords. Use it for questions about what the store sells, " +
      "prices, availability or product details. Returns up to 5 matches.",
    strict: true,
    input_schema: {
      type: "object",
      properties: {
        query: {
          type: "string",
          description: "A few keywords, e.g. 'black running shoes'.",
        },
      },
      required: ["query"],
      additionalProperties: false,
    },
  },
  {
    name: "request_human_handoff",
    description:
      "Pass the conversation to the store's team. Use it when the customer asks for a person, when they " +
      "want something you cannot do (refunds, address changes, cancellations, complaints), or when you " +
      "are not confident in the answer.",
    strict: true,
    input_schema: {
      type: "object",
      properties: {
        reason: {
          type: "string",
          description: "One sentence for the store team explaining what the customer needs.",
        },
        customer_email: {
          type: "string",
          description: "Customer's email if they have given it, otherwise an empty string.",
        },
      },
      required: ["reason", "customer_email"],
      additionalProperties: false,
    },
  },
];

/** Strips '#', spaces and a leading store prefix the customer may have typed. */
export function normalizeOrderNumber(raw: string): string {
  return raw.trim().replace(/^#/, "").replace(/\s+/g, "");
}

function sameEmail(a: string, b: string): boolean {
  return a.trim().toLowerCase() === b.trim().toLowerCase();
}

export interface ToolOutcome {
  /** JSON or text handed back to the model as the tool_result. */
  content: string;
  isError?: boolean;
  handoff?: HandoffRequest;
}

function asString(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

export async function executeTool(
  name: string,
  input: unknown,
  store: StoreApi,
): Promise<ToolOutcome> {
  const args = (input ?? {}) as Record<string, unknown>;

  switch (name) {
    case "lookup_order": {
      const orderNumber = asString(args.order_number);
      const email = asString(args.email);
      if (!orderNumber || !email) {
        return { content: "Both order_number and email are required.", isError: true };
      }
      const order = await store.findOrder(normalizeOrderNumber(orderNumber));
      // Same answer for "no such order" and "wrong email", so the chat can't be
      // used to discover which order numbers exist.
      if (!order || !order.email || !sameEmail(order.email, email)) {
        return {
          content:
            "No order matches that order number and email. Ask the customer to double-check both.",
        };
      }
      // The email stays server-side; the model only needs the order details.
      return { content: JSON.stringify({ ...order, email: undefined }) };
    }

    case "search_products": {
      const query = asString(args.query);
      if (!query) return { content: "query is required.", isError: true };
      const products = await store.searchProducts(query);
      if (products.length === 0) {
        return { content: "No matching products found." };
      }
      return { content: JSON.stringify(products) };
    }

    case "request_human_handoff": {
      const reason = asString(args.reason) ?? "Customer asked for help";
      const customerEmail = asString(args.customer_email)?.trim() || null;
      return {
        content: customerEmail
          ? "Handoff recorded. Tell the customer the team will reply by email."
          : "Handoff recorded, but there is no email to reply to. Ask the customer for their email address.",
        handoff: { reason, customerEmail },
      };
    }

    default:
      return { content: `Unknown tool: ${name}`, isError: true };
  }
}
