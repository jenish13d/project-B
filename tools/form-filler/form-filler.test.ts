import { describe, expect, it } from "vitest";
import type { BetaMessage, MessageCreateParamsNonStreaming } from "@anthropic-ai/sdk/resources/beta/messages/messages";

import { answerQuestion, normalizeAnswer } from "./answer.ts";
import { buildPrefillUrl, parseForm, viewFormUrl, type FormQuestion } from "./form.ts";

const LOAD_DATA = [
  null,
  [
    "A short quiz",
    [
      [1, "What is the capital of France?", null, 2, [[111, [["Paris"], ["Lyon"], ["", null, null, null, true]], 1]]],
      [2, "Your name", null, 0, [[222, null, 0]]],
      [3, "Section two", null, 8, null],
      [4, "Prime numbers", "Pick all", 4, [[444, [["2"], ["4"], ["7"]], 0]]],
      [5, "Rate it", null, 7, [[555, [["A"]], 0], [556, [["A"]], 0]]],
      [6, "When?", null, 9, [[666, null, 0]]],
    ],
    null, null, null, null, null, null,
    "Geography quiz",
  ],
  "/forms",
  "quiz-file",
];
const HTML = `<html><script>var FB_PUBLIC_LOAD_DATA_ = ${JSON.stringify(LOAD_DATA)};</script></html>`;
const VIEW_URL = "https://docs.google.com/forms/d/e/abc_123/viewform";

describe("parseForm", () => {
  const form = parseForm(HTML);

  it("reads the title and skips non-question items", () => {
    expect(form.title).toBe("Geography quiz");
    expect(form.questions.map((q) => q.entryId)).toEqual([111, 222, 444, 555, 666]);
  });

  it("reads kinds, options and required flags, dropping the 'Other' option", () => {
    expect(form.questions[0]).toMatchObject({ kind: "choice", options: ["Paris", "Lyon"], required: true });
    expect(form.questions[1]).toMatchObject({ kind: "short", options: [], required: false });
    expect(form.questions[3].kind).toBe("unsupported");
  });

  it("explains when the page has no form data", () => {
    expect(() => parseForm("<html>Sign in</html>")).toThrow(/require sign-in/);
  });
});

describe("viewFormUrl", () => {
  it("normalizes form URLs", () => {
    expect(viewFormUrl(`${VIEW_URL}?usp=sf_link`)).toBe(VIEW_URL);
    expect(viewFormUrl("https://docs.google.com/forms/d/e/abc_123/formResponse")).toBe(VIEW_URL);
  });

  it("rejects other URLs", () => {
    expect(() => viewFormUrl("https://example.com/forms/d/e/abc/viewform")).toThrow();
  });
});

describe("buildPrefillUrl", () => {
  const [capital, name, primes, grid, when] = parseForm(HTML).questions;

  it("adds each valid answer and repeats checkbox entries", () => {
    const url = new URL(
      buildPrefillUrl(VIEW_URL, [
        { question: capital, values: ["Paris"] },
        { question: name, values: ["Ada"] },
        { question: primes, values: ["2", "7"] },
      ]),
    );
    expect(url.searchParams.get("usp")).toBe("pp_url");
    expect(url.searchParams.get("entry.111")).toBe("Paris");
    expect(url.searchParams.get("entry.222")).toBe("Ada");
    expect(url.searchParams.getAll("entry.444")).toEqual(["2", "7"]);
  });

  it("drops values the form can't take", () => {
    const url = new URL(
      buildPrefillUrl(VIEW_URL, [
        { question: capital, values: ["Berlin"] },
        { question: grid, values: ["A"] },
        { question: when, values: ["next Tuesday"] },
      ]),
    );
    expect([...url.searchParams.keys()]).toEqual(["usp"]);
  });
});

const choice: FormQuestion = {
  title: "Capital of France?",
  description: "",
  kind: "choice",
  entryId: 1,
  options: ["Paris", "Lyon"],
  required: true,
};

describe("normalizeAnswer", () => {
  it("matches options case-insensitively and keeps one for single-choice questions", () => {
    const answer = normalizeAnswer(choice, { values: ["paris", "Lyon"], confidence: "high", reasoning: "", sources: [] });
    expect(answer).toMatchObject({ values: ["Paris"], confidence: "high" });
  });

  it("marks answers with no usable value as skipped", () => {
    expect(normalizeAnswer(choice, { values: ["Rome"], confidence: "high" }).confidence).toBe("skip");
    expect(normalizeAnswer(choice, { values: ["Paris"], confidence: "skip" }).values).toEqual([]);
  });
});

function message(stop_reason: BetaMessage["stop_reason"], content: unknown[]): BetaMessage {
  return { stop_reason, content } as BetaMessage;
}

describe("answerQuestion", () => {
  it("resumes paused turns and returns the submitted answer", async () => {
    const calls: MessageCreateParamsNonStreaming[] = [];
    const replies = [
      message("pause_turn", [{ type: "server_tool_use", id: "s1", name: "web_search", input: {} }]),
      message("tool_use", [
        {
          type: "tool_use",
          id: "t1",
          name: "submit_answer",
          input: { values: ["Paris"], confidence: "high", reasoning: "It is.", sources: ["https://x"] },
        },
      ]),
    ];
    const answer = await answerQuestion({
      question: choice,
      formTitle: "Quiz",
      create: async (params) => {
        calls.push(structuredClone(params));
        return replies.shift()!;
      },
    });

    expect(answer).toEqual({ values: ["Paris"], confidence: "high", reasoning: "It is.", sources: ["https://x"] });
    expect(calls).toHaveLength(2);
    expect(calls[1].messages).toHaveLength(2);
  });

  it("nudges once, then gives up without an answer", async () => {
    let count = 0;
    const answer = await answerQuestion({
      question: choice,
      formTitle: "Quiz",
      create: async () => {
        count++;
        return message("end_turn", [{ type: "text", text: "Paris, I think." }]);
      },
    });
    expect(count).toBe(2);
    expect(answer.confidence).toBe("skip");
  });
});
