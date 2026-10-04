import type { ShopSettings } from "@prisma/client";

import db from "../db.server";
import type { AssistantSettings } from "./prompt.server";

/** Returns the shop's settings, creating the default row on first use. */
export function getShopSettings(shop: string): Promise<ShopSettings> {
  return db.shopSettings.upsert({
    where: { shop },
    create: { shop },
    update: {},
  });
}

export function toAssistantSettings(row: ShopSettings): AssistantSettings {
  return {
    shopName: row.storeName || row.shop.replace(/\.myshopify\.com$/, ""),
    assistantName: row.assistantName,
    tone: row.tone,
    policies: row.policies,
    faq: row.faq,
    extraRules: row.extraRules,
  };
}
