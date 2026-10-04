import type { ActionFunctionArgs } from "react-router";

import db from "../db.server";
import { authenticate } from "../shopify.server";

// Mandatory privacy webhooks (GDPR / CCPA) for App Store apps.
// See https://shopify.dev/docs/apps/build/privacy-law-compliance
export const action = async ({ request }: ActionFunctionArgs) => {
  const { shop, topic, payload } = await authenticate.webhook(request);
  console.log(`Received ${topic} webhook for ${shop}`);

  switch (topic) {
    case "CUSTOMERS_DATA_REQUEST":
      // Conversations are visible to the merchant on the Conversations page,
      // so they can answer the request from there. Nothing to send here.
      break;

    case "CUSTOMERS_REDACT": {
      const email = (payload as { customer?: { email?: string } }).customer?.email;
      if (email) {
        await db.conversation.deleteMany({ where: { shop, customerEmail: email } });
      }
      break;
    }

    case "SHOP_REDACT":
      await db.conversation.deleteMany({ where: { shop } });
      await db.shopSettings.deleteMany({ where: { shop } });
      break;
  }

  return new Response();
};
