import Anthropic from "@anthropic-ai/sdk";
import type {
  BetaMessage,
  BetaMessageParam,
  BetaToolResultBlockParam,
  BetaToolUseBlock,
  MessageCreateParamsNonStreaming,
} from "@anthropic-ai/sdk/resources/beta/messages/messages";

import { buildSystemPrompt, type AssistantSettings } from "./prompt.server";
import { TOOLS, executeTool, type HandoffRequest, type StoreApi } from "./tools.server";

export const DEFAULT_MODEL = "claude-opus-5-5";

// Each tool round trip is one API call; a support question rarely needs more than two or three.
const MAX_ITERATIONS = 6;

export type Effort = "low" | "medium" | "high" | "xhigh" | "max";

export interface ChatTurn {
  role: "customer" | "assistant";
  content: string;
}

export interface AssistantReply {
  reply: string;
  handoff: HandoffRequest | null;
}

/** The one SDK call the loop makes; injectable so tests don't hit the API. */
export type CreateMessage = (params: MessageCreateParamsNonStreaming) => Promise<BetaMessage>;

let client: Anthropic | null = null;

function defaultCreate(params: MessageCreateParamsNonStreaming): Promise<BetaMessage> {
  client ??= new Anthropic();
  return client.beta.messages.create(params);
}

function effortFromEnv(): Effort {
  const value = process.env.ASSISTANT_EFFORT;
  return value === "medium" || value === "high" || value === "xhigh" || value === "max"
    ? value
    : "low";
}

export const FALLBACK_REPLY =
  "Sorry, I can't help with that here. I can pass your question to the store team if you'd like.";

function textOf(message: BetaMessage): string {
  return message.content
    .filter((block) => block.type === "text")
    .map((block) => (block.type === "text" ? block.text : ""))
    .join("")
    .trim();
}

export interface RunAssistantOptions {
  settings: AssistantSettings;
  history: ChatTurn[];
  customerMessage: string;
  store: StoreApi;
  create?: CreateMessage;
  model?: string;
  effort?: Effort;
}

/**
 * Answers one customer message: sends the conversation to Claude, runs any tool
 * calls against the store, and loops until Claude produces a reply.
 */
export async function runAssistant({
  settings,
  history,
  customerMessage,
  store,
  create = defaultCreate,
  model = process.env.ASSISTANT_MODEL || DEFAULT_MODEL,
  effort = effortFromEnv(),
}: RunAssistantOptions): Promise<AssistantReply> {
  const messages: BetaMessageParam[] = [
    ...history.map(
      (turn): BetaMessageParam => ({
        role: turn.role === "customer" ? "user" : "assistant",
        content: turn.content,
      }),
    ),
    { role: "user", content: customerMessage },
  ];
  const system = buildSystemPrompt(settings);
  let handoff: HandoffRequest | null = null;

  for (let i = 0; i < MAX_ITERATIONS; i++) {
    const response = await create({
      model,
      max_tokens: 16000,
      system,
      tools: TOOLS,
      messages,
      output_config: { effort },
      // Caches tools + system prompt + history, so follow-up messages in a chat are cheap.
      cache_control: { type: "ephemeral" },
      // On a safety decline, let the API retry on its recommended fallback model.
      betas: ["server-side-fallback-2026-07-01"],
      fallbacks: "default",
    });

    switch (response.stop_reason) {
      case "refusal":
        return { reply: FALLBACK_REPLY, handoff };

      case "pause_turn":
        messages.push({ role: "assistant", content: response.content });
        continue;

      case "tool_use": {
        messages.push({ role: "assistant", content: response.content });
        const calls = response.content.filter(
          (block): block is BetaToolUseBlock => block.type === "tool_use",
        );
        const results: BetaToolResultBlockParam[] = await Promise.all(
          calls.map(async (call) => {
            let outcome;
            try {
              outcome = await executeTool(call.name, call.input, store);
            } catch (error) {
              console.error(`Tool ${call.name} failed`, error);
              outcome = { content: "The lookup failed. Try again or offer a handoff.", isError: true };
            }
            if (outcome.handoff) handoff = outcome.handoff;
            return {
              type: "tool_result",
              tool_use_id: call.id,
              content: outcome.content,
              ...(outcome.isError ? { is_error: true } : {}),
            };
          }),
        );
        messages.push({ role: "user", content: results });
        continue;
      }

      default: {
        // end_turn, max_tokens, stop_sequence: use whatever text came back.
        const reply = textOf(response);
        return { reply: reply || FALLBACK_REPLY, handoff };
      }
    }
  }

  console.warn(`Assistant hit the ${MAX_ITERATIONS}-iteration limit`);
  return { reply: FALLBACK_REPLY, handoff };
}
