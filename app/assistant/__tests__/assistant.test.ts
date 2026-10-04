import { describe, expect, it } from "vitest";
import type {
  BetaMessage,
  MessageCreateParamsNonStreaming,
} from "@anthropic-ai/sdk/resources/beta/messages/messages";

import { FALLBACK_REPLY, runAssistant } from "../assistant.server";
import type { AssistantSettings } from "../prompt.server";
import { buildSystemPrompt } from "../prompt.server";
import type { StoreApi } from "../tools.server";

const settings: AssistantSettings = {
  shopName: "Linen & Co",
  assistantName: "Lina",
  tone: "warm",
  policies: "Free returns within 30 days.",
  faq: "",
  extraRules: "",
};

const store: StoreApi = {
  findOrder: async () => null,
  searchProducts: async () => [
    { title: "Linen shirt", description: "", price: "49.00 USD", inStock: true, url: null },
  ],
};

function message(partial: Partial<BetaMessage>): BetaMessage {
  return {
    id: "msg_test",
    type: "message",
    role: "assistant",
    model: "claude-opus-5-5",
    content: [],
    stop_reason: "end_turn",
    stop_sequence: null,
    usage: { input_tokens: 1, output_tokens: 1 },
    ...partial,
  } as BetaMessage;
}

/** Returns the scripted responses in order and records every request. */
function scripted(responses: BetaMessage[]) {
  const calls: MessageCreateParamsNonStreaming[] = [];
  const create = async (params: MessageCreateParamsNonStreaming) => {
    calls.push(structuredClone(params));
    const next = responses.shift();
    if (!next) throw new Error("No more scripted responses");
    return next;
  };
  return { create, calls };
}

describe("buildSystemPrompt", () => {
  it("includes the store's own content and omits empty sections", () => {
    const prompt = buildSystemPrompt(settings);
    expect(prompt).toContain("Lina");
    expect(prompt).toContain("Free returns within 30 days.");
    expect(prompt).not.toContain("Frequently asked questions");
  });

  it("is deterministic so it can be cached", () => {
    expect(buildSystemPrompt(settings)).toBe(buildSystemPrompt(settings));
  });
});

describe("runAssistant", () => {
  it("runs a tool and returns the final text", async () => {
    const { create, calls } = scripted([
      message({
        stop_reason: "tool_use",
        content: [{ type: "tool_use", id: "tu_1", name: "search_products", input: { query: "linen" } }],
      } as Partial<BetaMessage>),
      message({ content: [{ type: "text", text: "Our linen shirt is 49 USD.", citations: null }] }),
    ]);

    const result = await runAssistant({
      settings,
      history: [],
      customerMessage: "Do you sell linen shirts?",
      store,
      create,
      model: "claude-opus-5-5",
      effort: "low",
    });

    expect(result).toEqual({ reply: "Our linen shirt is 49 USD.", handoff: null });
    expect(calls).toHaveLength(2);
    expect(calls[0].messages).toEqual([{ role: "user", content: "Do you sell linen shirts?" }]);
    const toolResult = calls[1].messages[2].content as { type: string; content: string }[];
    expect(toolResult[0].type).toBe("tool_result");
    expect(toolResult[0].content).toContain("Linen shirt");
  });

  it("sends history with the right roles", async () => {
    const { create, calls } = scripted([message({ content: [{ type: "text", text: "Sure.", citations: null }] })]);
    await runAssistant({
      settings,
      history: [
        { role: "customer", content: "Hi" },
        { role: "assistant", content: "Hello!" },
      ],
      customerMessage: "Thanks",
      store,
      create,
    });
    expect(calls[0].messages.map((m) => m.role)).toEqual(["user", "assistant", "user"]);
  });

  it("reports a handoff", async () => {
    const { create } = scripted([
      message({
        stop_reason: "tool_use",
        content: [
          {
            type: "tool_use",
            id: "tu_1",
            name: "request_human_handoff",
            input: { reason: "Refund request", customer_email: "a@b.com" },
          },
        ],
      } as Partial<BetaMessage>),
      message({ content: [{ type: "text", text: "I've passed this to the team.", citations: null }] }),
    ]);
    const result = await runAssistant({ settings, history: [], customerMessage: "Refund please", store, create });
    expect(result.handoff).toEqual({ reason: "Refund request", customerEmail: "a@b.com" });
  });

  it("returns the fallback reply on a refusal", async () => {
    const { create } = scripted([message({ stop_reason: "refusal" })]);
    const result = await runAssistant({ settings, history: [], customerMessage: "…", store, create });
    expect(result.reply).toBe(FALLBACK_REPLY);
  });

  it("turns a failing tool into an error result instead of crashing", async () => {
    const broken: StoreApi = {
      ...store,
      searchProducts: async () => {
        throw new Error("Shopify down");
      },
    };
    const { create, calls } = scripted([
      message({
        stop_reason: "tool_use",
        content: [{ type: "tool_use", id: "tu_1", name: "search_products", input: { query: "x" } }],
      } as Partial<BetaMessage>),
      message({ content: [{ type: "text", text: "Sorry, try again later.", citations: null }] }),
    ]);
    const result = await runAssistant({ settings, history: [], customerMessage: "x?", store: broken, create });
    expect(result.reply).toBe("Sorry, try again later.");
    const toolResult = calls[1].messages[2].content as { is_error?: boolean }[];
    expect(toolResult[0].is_error).toBe(true);
  });
});
