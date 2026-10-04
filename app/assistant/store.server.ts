import type { AdminApiContext } from "@shopify/shopify-app-react-router/server";

import type { OrderSummary, ProductSummary, StoreApi } from "./tools.server";

/** Removes characters that would change the meaning of a Shopify search query. */
export function sanitizeSearchTerms(raw: string): string {
  return raw
    .replace(/[^\p{L}\p{N}\s\-']/gu, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 100);
}

interface OrderNode {
  name: string;
  email: string | null;
  createdAt: string;
  cancelledAt: string | null;
  displayFinancialStatus: string | null;
  displayFulfillmentStatus: string | null;
  lineItems: { nodes: { title: string; quantity: number }[] };
  fulfillments: {
    trackingInfo: { company: string | null; number: string | null; url: string | null }[];
  }[];
}

interface ProductNode {
  title: string;
  description: string;
  totalInventory: number | null;
  tracksInventory: boolean;
  onlineStoreUrl: string | null;
  priceRangeV2: { minVariantPrice: { amount: string; currencyCode: string } };
}

/** StoreApi backed by the Shopify Admin GraphQL API for one shop. */
export function shopifyStore(admin: AdminApiContext): StoreApi {
  return {
    async findOrder(orderNumber) {
      if (!/^[\p{L}\p{N}-]{1,30}$/u.test(orderNumber)) return null;

      const response = await admin.graphql(
        `#graphql
        query StorePilotFindOrder($query: String!) {
          orders(first: 1, query: $query) {
            nodes {
              name
              email
              createdAt
              cancelledAt
              displayFinancialStatus
              displayFulfillmentStatus
              lineItems(first: 20) { nodes { title quantity } }
              fulfillments(first: 10) { trackingInfo { company number url } }
            }
          }
        }`,
        { variables: { query: `name:#${orderNumber}` } },
      );
      const json = (await response.json()) as {
        data?: { orders: { nodes: OrderNode[] } };
      };
      const order = json.data?.orders.nodes[0];
      if (!order) return null;

      const summary: OrderSummary = {
        name: order.name,
        email: order.email,
        createdAt: order.createdAt,
        cancelled: order.cancelledAt !== null,
        financialStatus: order.displayFinancialStatus,
        fulfillmentStatus: order.displayFulfillmentStatus,
        items: order.lineItems.nodes,
        tracking: order.fulfillments.flatMap((f) => f.trackingInfo),
      };
      return summary;
    },

    async searchProducts(query) {
      const terms = sanitizeSearchTerms(query);
      if (!terms) return [];

      const response = await admin.graphql(
        `#graphql
        query StorePilotSearchProducts($query: String!) {
          products(first: 5, query: $query) {
            nodes {
              title
              description(truncateAt: 300)
              totalInventory
              tracksInventory
              onlineStoreUrl
              priceRangeV2 { minVariantPrice { amount currencyCode } }
            }
          }
        }`,
        { variables: { query: `status:active ${terms}` } },
      );
      const json = (await response.json()) as {
        data?: { products: { nodes: ProductNode[] } };
      };

      return (json.data?.products.nodes ?? []).map(
        (p): ProductSummary => ({
          title: p.title,
          description: p.description,
          price: `${p.priceRangeV2.minVariantPrice.amount} ${p.priceRangeV2.minVariantPrice.currencyCode}`,
          inStock: !p.tracksInventory || (p.totalInventory ?? 0) > 0,
          url: p.onlineStoreUrl,
        }),
      );
    },
  };
}
