import type { HeadersFunction, LoaderFunctionArgs } from "react-router";
import { useLoaderData } from "react-router";
import { boundary } from "@shopify/shopify-app-react-router/server";

import { getShopSettings } from "../assistant/settings.server";
import db from "../db.server";
import { authenticate } from "../shopify.server";

const DAY_MS = 24 * 60 * 60 * 1000;

export const loader = async ({ request }: LoaderFunctionArgs) => {
  const { session } = await authenticate.admin(request);
  const shop = session.shop;
  const since = new Date(Date.now() - 30 * DAY_MS);

  const [settings, conversations, answered, handoffs] = await Promise.all([
    getShopSettings(shop),
    db.conversation.count({ where: { shop, createdAt: { gte: since } } }),
    db.chatMessage.count({
      where: { role: "assistant", createdAt: { gte: since }, conversation: { shop } },
    }),
    db.conversation.count({ where: { shop, handedOff: true, createdAt: { gte: since } } }),
  ]);

  return {
    enabled: settings.enabled,
    hasPolicies: settings.policies.trim().length > 0,
    hasFaq: settings.faq.trim().length > 0,
    conversations,
    answered,
    handoffs,
  };
};

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <s-box padding="base" borderWidth="base" borderRadius="base">
      <s-stack direction="block" gap="small-200">
        <s-text color="subdued">{label}</s-text>
        <s-heading>{value.toLocaleString()}</s-heading>
      </s-stack>
    </s-box>
  );
}

export default function Index() {
  const data = useLoaderData<typeof loader>();
  const resolved = Math.max(data.conversations - data.handoffs, 0);

  return (
    <s-page heading="StorePilot">
      <s-section heading="Last 30 days">
        <s-grid gridTemplateColumns="repeat(auto-fit, minmax(160px, 1fr))" gap="base">
          <Stat label="Conversations" value={data.conversations} />
          <Stat label="Replies sent by the assistant" value={data.answered} />
          <Stat label="Resolved without your team" value={resolved} />
          <Stat label="Passed to your team" value={data.handoffs} />
        </s-grid>
      </s-section>

      <s-section heading="Setup">
        <s-unordered-list>
          <s-list-item>
            {data.hasPolicies ? "✅" : "⬜"} Add your shipping and return policies in{" "}
            <s-link href="/app/settings">Assistant settings</s-link>
          </s-list-item>
          <s-list-item>
            {data.hasFaq ? "✅" : "⬜"} Add answers to your most common questions
          </s-list-item>
          <s-list-item>
            Turn on the chat widget: Online Store → Themes → Customize → App embeds → StorePilot chat
          </s-list-item>
          <s-list-item>
            {data.enabled ? "✅ Assistant is on" : "⬜ Assistant is switched off in settings"}
          </s-list-item>
        </s-unordered-list>
      </s-section>
    </s-page>
  );
}

export const headers: HeadersFunction = (headersArgs) => {
  return boundary.headers(headersArgs);
};
