import Anthropic from "@anthropic-ai/sdk";
import type {
  BetaMessage,
  BetaMessageParam,
  BetaTool,
  BetaToolUnion,
  BetaToolUseBlock,
  MessageCreateParamsNonStreaming,
} from "@anthropic-ai/sdk/resources/beta/messages/messages";

import type { FormQuestion } from "./form.ts";

export const DEFAULT_MODEL = "claude-opus-5-5";

// Searches can pause a turn; a handful of round trips is plenty for one question.
const MAX_ITERATIONS = 6;

export type Effort = "low" | "medium" | "high" | "xhigh" | "max";
export type Confidence = "high" | "medium" | "low" | "skip";

export interface Answer {
  values: string[];
  confidence: Confidence;
  reasoning: string;
  sources: string[];
}

export type CreateMessage = (params: MessageCreateParamsNonStreaming) => Promise<BetaMessage>;

let client: Anthropic | null = null;

function defaultCreate(params: MessageCreateParamsNonStreaming): Promise<BetaMessage> {
  client ??= new Anthropic();
  return client.beta.messages.create(params);
}

const SYSTEM = `You help someone fill in a Google Form. You get one question at a time.

Search the web when the answer is a fact you should verify. Prefer authoritative sources, and check more than one when sources disagree.

When you have an answer, call submit_answer. If the question has options, answer with the option text exactly as given; pick one option unless the question takes several (checkboxes). Dates are YYYY-MM-DD and times are HH:MM (24-hour).

Use confidence "skip" with no values when the question is about the person filling the form (their name, email, ID, opinions, experiences) and the notes about them don't answer it. Don't invent personal details.`;

function submitTool(question: FormQuestion): BetaTool {
  const item =
    question.options.length > 0
      ? { type: "string" as const, enum: question.options }
      : { type: "string" as const };
  return {
    name: "submit_answer",
    description: "Submit the final answer to the form question.",
    strict: true,
    input_schema: {
      type: "object",
      properties: {
        values: {
          type: "array",
          items: item,
          description: "The answer. One entry, except for checkbox questions with several correct options.",
        },
        confidence: { type: "string", enum: ["high", "medium", "low", "skip"] },
        reasoning: { type: "string", description: "One or two sentences on why this is the answer." },
        sources: { type: "array", items: { type: "string" }, description: "URLs the answer is based on." },
      },
      required: ["values", "confidence", "reasoning", "sources"],
      additionalProperties: false,
    },
  };
}

function describe(question: FormQuestion, formTitle: string, aboutMe: string): string {
  const lines = [`Form: ${formTitle}`, `Question: ${question.title}`];
  if (question.description) lines.push(`Details: ${question.description}`);
  lines.push(`Type: ${question.kind}${question.kind === "checkboxes" ? " (select all that apply)" : ""}`);
  if (question.options.length > 0) lines.push(`Options:\n${question.options.map((o) => `- ${o}`).join("\n")}`);
  if (aboutMe) lines.push(`Notes about the person filling the form:\n${aboutMe}`);
  return lines.join("\n\n");
}

/** Matches the model's values to the question's options and drops anything the form can't take. */
export function normalizeAnswer(question: FormQuestion, input: unknown): Answer {
  const raw = (input ?? {}) as Partial<Record<keyof Answer, unknown>>;
  const strings = (value: unknown) =>
    Array.isArray(value) ? value.filter((v): v is string => typeof v === "string") : [];

  let values = strings(raw.values).map((v) => v.trim()).filter(Boolean);
  if (question.options.length > 0) {
    values = values
      .map((v) => question.options.find((o) => o.toLowerCase() === v.toLowerCase()))
      .filter((v): v is string => v !== undefined);
    if (question.kind !== "checkboxes") values = values.slice(0, 1);
  }
  const confidence = ["high", "medium", "low", "skip"].includes(raw.confidence as string)
    ? (raw.confidence as Confidence)
    : "low";

  return {
    values: confidence === "skip" ? [] : values,
    confidence: values.length === 0 ? "skip" : confidence,
    reasoning: typeof raw.reasoning === "string" ? raw.reasoning : "",
    sources: strings(raw.sources),
  };
}

export interface AnswerOptions {
  question: FormQuestion;
  formTitle: string;
  aboutMe?: string;
  create?: CreateMessage;
  model?: string;
  effort?: Effort;
}

/** Researches one question with web search and returns Claude's answer. */
export async function answerQuestion({
  question,
  formTitle,
  aboutMe = "",
  create = defaultCreate,
  model = DEFAULT_MODEL,
  effort = "medium",
}: AnswerOptions): Promise<Answer> {
  const tools: BetaToolUnion[] = [
    { type: "web_search_20260209", name: "web_search", max_uses: 5 },
    submitTool(question),
  ];
  const messages: BetaMessageParam[] = [
    { role: "user", content: describe(question, formTitle, aboutMe) },
  ];
  let nudged = false;

  for (let i = 0; i < MAX_ITERATIONS; i++) {
    const response = await create({
      model,
      max_tokens: 16000,
      system: SYSTEM,
      tools,
      messages,
      output_config: { effort },
      betas: ["server-side-fallback-2026-07-01"],
      fallbacks: "default",
    });

    const submit = response.content.find(
      (block): block is BetaToolUseBlock => block.type === "tool_use" && block.name === "submit_answer",
    );
    if (submit) return normalizeAnswer(question, submit.input);

    if (response.stop_reason === "refusal") break;
    messages.push({ role: "assistant", content: response.content });
    if (response.stop_reason === "pause_turn") continue;
    if (nudged) break;
    nudged = true;
    messages.push({ role: "user", content: "Call submit_answer with your answer now." });
  }

  return { values: [], confidence: "skip", reasoning: "No answer was produced.", sources: [] };
}
