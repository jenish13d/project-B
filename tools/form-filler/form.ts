/**
 * Reads a public Google Form's questions from its page HTML and builds a
 * "prefilled" link, so answers can be reviewed in the real form before submitting.
 */

export type QuestionKind =
  | "short"
  | "paragraph"
  | "choice"
  | "dropdown"
  | "checkboxes"
  | "scale"
  | "date"
  | "time"
  | "unsupported";

export interface FormQuestion {
  title: string;
  description: string;
  kind: QuestionKind;
  /** The `entry.<id>` field the answer is submitted under. */
  entryId: number;
  options: string[];
  required: boolean;
}

export interface ParsedForm {
  title: string;
  description: string;
  questions: FormQuestion[];
}

// Google's numeric item types. Missing ones (6 text, 8 section, 11 image, 12 video) aren't questions.
const KIND_BY_TYPE: Record<number, QuestionKind> = {
  0: "short",
  1: "paragraph",
  2: "choice",
  3: "dropdown",
  4: "checkboxes",
  5: "scale",
  7: "unsupported", // grid: one entry per row
  9: "date",
  10: "time",
};

const LOAD_DATA = /FB_PUBLIC_LOAD_DATA_\s*=\s*(\[[\s\S]*?\]);\s*<\/script>/;

export function parseForm(html: string): ParsedForm {
  const match = LOAD_DATA.exec(html);
  if (!match) {
    throw new Error(
      "Couldn't find the form's questions. The form may require sign-in, be closed, or not be a Google Form link.",
    );
  }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const data: any = JSON.parse(match[1]);
  const body = data?.[1];
  if (!Array.isArray(body?.[1])) throw new Error("The form has no questions.");

  const questions: FormQuestion[] = [];
  for (const item of body[1]) {
    const kind = KIND_BY_TYPE[item?.[3]];
    const entries = item?.[4];
    if (!kind || !Array.isArray(entries) || entries.length === 0) continue;

    const [entryId, rawOptions, required] = entries[0];
    const options = Array.isArray(rawOptions)
      ? rawOptions.map((option: unknown[]) => String(option?.[0] ?? "")).filter(Boolean)
      : [];
    questions.push({
      title: String(item[1] ?? "").trim(),
      description: String(item[2] ?? "").trim(),
      kind: entries.length > 1 ? "unsupported" : kind,
      entryId: Number(entryId),
      options,
      required: required === 1,
    });
  }

  return {
    title: String(body[8] ?? data[3] ?? "Untitled form"),
    description: String(body[0] ?? ""),
    questions,
  };
}

/** Normalizes any form URL (including the page fetch's final URL) to its `viewform` address. */
export function viewFormUrl(url: string): string {
  const parsed = new URL(url);
  const match = /^(\/forms\/(?:u\/\d+\/)?d\/(?:e\/)?[\w-]+)/.exec(parsed.pathname);
  if (parsed.hostname !== "docs.google.com" || !match) {
    throw new Error(`Not a Google Form URL: ${url}`);
  }
  return `https://docs.google.com${match[1]}/viewform`;
}

const FORMATS: Partial<Record<QuestionKind, RegExp>> = {
  date: /^\d{4}-\d{2}-\d{2}$/,
  time: /^\d{2}:\d{2}$/,
};

/** Whether `value` can be prefilled for this question. */
export function isPrefillable(question: FormQuestion, value: string): boolean {
  if (question.kind === "unsupported" || value === "") return false;
  if (question.options.length > 0) return question.options.includes(value);
  return FORMATS[question.kind]?.test(value) ?? true;
}

export function buildPrefillUrl(
  viewUrl: string,
  answers: Array<{ question: FormQuestion; values: string[] }>,
): string {
  const url = new URL(viewUrl);
  url.searchParams.set("usp", "pp_url");
  for (const { question, values } of answers) {
    for (const value of values) {
      if (isPrefillable(question, value)) url.searchParams.append(`entry.${question.entryId}`, value);
    }
  }
  return url.toString();
}
