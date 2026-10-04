import type { HeadersFunction, LoaderFunctionArgs } from "react-router";
import { useLoaderData } from "react-router";
import { boundary } from "@shopify/shopify-app-react-router/server";

import db from "../db.server";
import { authenticate } from "../shopify.server";

export const loader = async ({ request }: LoaderFunctionArgs) => {
  const { session } = await authenticate.admin(request);
  const onlyHandoffs = new URL(request.url).searchParams.get("filter") === "handoffs";

  const conversations = await db.conversation.findMany({
    where: { shop: session.shop, ...(onlyHandoffs ? { handedOff: true } : {}) },
    orderBy: { updatedAt: "desc" },
    take: 50,
    include: { messages: { orderBy: { createdAt: "asc" } } },
  });

  return {
    onlyHandoffs,
    conversations: conversations.map((c) => ({
      id: c.id,
      updatedAt: c.updatedAt.toISOString(),
      handedOff: c.handedOff,
      handoffReason: c.handoffReason,
      customerEmail: c.customerEmail,
      messages: c.messages.map((m) => ({ id: m.id, role: m.role, content: m.content })),
    })),
  };
};

export default function Conversations() {
  const { conversations, onlyHandoffs } = useLoaderData<typeof loader>();

  return (
    <s-page heading="Conversations">
      <s-section>
        <s-stack direction="inline" gap="base">
          <s-link href="/app/conversations">All</s-link>
          <s-link href="/app/conversations?filter=handoffs">Needs your team</s-link>
        </s-stack>
      </s-section>

      {conversations.length === 0 && (
        <s-section>
          <s-paragraph>
            {onlyHandoffs
              ? "Nothing waiting for your team."
              : "No conversations yet. They will appear here once shoppers use the chat."}
          </s-paragraph>
        </s-section>
      )}

      {conversations.map((c) => (
        <s-section key={c.id} heading={new Date(c.updatedAt).toLocaleString()}>
          <s-stack direction="block" gap="small-200">
            {c.handedOff && (
              <s-banner tone="warning" heading="Passed to your team">
                {c.handoffReason}
                {c.customerEmail ? ` — reply to ${c.customerEmail}` : " — no email given"}
              </s-banner>
            )}
            {c.messages.map((m) => (
              <s-paragraph key={m.id}>
                <s-text type="strong">{m.role === "customer" ? "Shopper" : "Assistant"}: </s-text>
                {m.content}
              </s-paragraph>
            ))}
          </s-stack>
        </s-section>
      ))}
    </s-page>
  );
}

export const headers: HeadersFunction = (headersArgs) => {
  return boundary.headers(headersArgs);
};
