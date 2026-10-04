import type { ActionFunctionArgs } from "react-router";

import { runAssistant } from "../assistant/assistant.server";
import { getShopSettings, toAssistantSettings } from "../assistant/settings.server";
import { shopifyStore } from "../assistant/store.server";
import db from "../db.server";
import { authenticate } from "../shopify.server";

// Storefront chat endpoint. Shoppers reach it through the app proxy at
// https://<store>/apps/storepilot/chat, so Shopify signs every request.

const MAX_MESSAGE_LENGTH = 1000;
const MAX_MESSAGES_PER_CONVERSATION = 40;
const HISTORY_TURNS = 20;

function reply(body: { conversationId: string | null; reply: string }, status = 200) {
  return Response.json(body, { status });
}

export const action = async ({ request }: ActionFunctionArgs) => {
  const { session, admin } = await authenticate.public.appProxy(request);
  if (!session || !admin) {
    return new Response("App not installed", { status: 404 });
  }
  const shop = session.shop;

  let body: { conversationId?: unknown; message?: unknown };
  try {
    body = await request.json();
  } catch {
    return new Response("Invalid JSON", { status: 400 });
  }
  const message = typeof body.message === "string" ? body.message.trim() : "";
  if (!message || message.length > MAX_MESSAGE_LENGTH) {
    return new Response(`Message must be 1-${MAX_MESSAGE_LENGTH} characters`, { status: 400 });
  }

  const settings = await getShopSettings(shop);
  if (!settings.enabled) {
    return reply({ conversationId: null, reply: "Chat is currently unavailable." });
  }

  const existing =
    typeof body.conversationId === "string"
      ? await db.conversation.findFirst({ where: { id: body.conversationId, shop } })
      : null;
  const conversation = existing ?? (await db.conversation.create({ data: { shop } }));

  const messageCount = await db.chatMessage.count({ where: { conversationId: conversation.id } });
  if (messageCount >= MAX_MESSAGES_PER_CONVERSATION) {
    return reply({
      conversationId: conversation.id,
      reply: "This chat has reached its limit. Please start a new chat or contact the store by email.",
    });
  }

  const history = (
    await db.chatMessage.findMany({
      where: { conversationId: conversation.id },
      orderBy: { createdAt: "desc" },
      take: HISTORY_TURNS,
    })
  )
    .reverse()
    .map((m) => ({ role: m.role as "customer" | "assistant", content: m.content }));
  // The API requires the conversation to start with the customer.
  while (history.length && history[0].role !== "customer") history.shift();

  let result;
  try {
    result = await runAssistant({
      settings: toAssistantSettings(settings),
      history,
      customerMessage: message,
      store: shopifyStore(admin),
    });
  } catch (error) {
    console.error(`Assistant failed for ${shop}`, error);
    return reply(
      {
        conversationId: conversation.id,
        reply: "Sorry, I'm having trouble right now. Please try again in a moment.",
      },
      503,
    );
  }

  await db.$transaction([
    db.chatMessage.create({
      data: { conversationId: conversation.id, role: "customer", content: message },
    }),
    db.chatMessage.create({
      data: { conversationId: conversation.id, role: "assistant", content: result.reply },
    }),
    db.conversation.update({
      where: { id: conversation.id },
      data: result.handoff
        ? {
            handedOff: true,
            handoffReason: result.handoff.reason,
            customerEmail: result.handoff.customerEmail ?? conversation.customerEmail,
          }
        : { updatedAt: new Date() },
    }),
  ]);

  return reply({ conversationId: conversation.id, reply: result.reply });
};

export const loader = () => new Response("Method not allowed", { status: 405 });
