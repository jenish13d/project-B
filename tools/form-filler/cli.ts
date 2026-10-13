import { readFile } from "node:fs/promises";
import { parseArgs } from "node:util";

import { answerQuestion, type Answer, type Effort } from "./answer.ts";
import { buildPrefillUrl, parseForm, viewFormUrl, type FormQuestion } from "./form.ts";

const USAGE = `Usage: npm run fill-form -- <google-form-url> [--about notes.txt] [--effort low|medium|high|xhigh|max] [--model id]

Researches each question with Claude + web search and prints a prefilled link.
Nothing is submitted: open the link, check every answer, then submit yourself.`;

const EFFORTS: Effort[] = ["low", "medium", "high", "xhigh", "max"];
const CONCURRENCY = 4;

async function mapPool<T, R>(items: T[], limit: number, fn: (item: T) => Promise<R>): Promise<R[]> {
  const results = new Array<R>(items.length);
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const index = next++;
      results[index] = await fn(items[index]);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}

async function main() {
  const { values: flags, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      about: { type: "string" },
      effort: { type: "string", default: "medium" },
      model: { type: "string" },
      help: { type: "boolean", short: "h" },
    },
  });
  const [formUrl] = positionals;
  const effort = flags.effort as Effort;
  if (flags.help || !formUrl || !EFFORTS.includes(effort)) {
    console.log(USAGE);
    process.exit(flags.help ? 0 : 1);
  }

  const aboutMe = flags.about ? (await readFile(flags.about, "utf8")).trim() : "";
  const page = await fetch(formUrl, { redirect: "follow" });
  if (!page.ok) throw new Error(`Couldn't load the form (HTTP ${page.status}).`);
  const viewUrl = viewFormUrl(page.url);
  const form = parseForm(await page.text());

  const supported = form.questions.filter((q) => q.kind !== "unsupported");
  console.log(`${form.title}: researching ${supported.length} question(s)…\n`);

  const answered = await mapPool(supported, CONCURRENCY, async (question) => {
    let answer: Answer;
    try {
      answer = await answerQuestion({ question, formTitle: form.title, aboutMe, effort, model: flags.model });
    } catch (error) {
      answer = { values: [], confidence: "skip", reasoning: `Failed: ${(error as Error).message}`, sources: [] };
    }
    return { question, ...answer };
  });

  const manual: FormQuestion[] = form.questions.filter((q) => q.kind === "unsupported");
  answered.forEach(({ question, values, confidence, reasoning, sources }, i) => {
    console.log(`${i + 1}. ${question.title}${question.required ? " *" : ""}`);
    if (confidence === "skip") {
      console.log(`   → (left blank) ${reasoning}`);
      manual.push(question);
    } else {
      console.log(`   → ${values.join(", ")}  [${confidence} confidence]`);
      if (reasoning) console.log(`     ${reasoning}`);
      for (const source of sources.slice(0, 3)) console.log(`     ${source}`);
    }
    console.log();
  });

  if (manual.length > 0) {
    console.log("Fill these in yourself:");
    for (const question of manual) console.log(`  - ${question.title}`);
    console.log();
  }
  console.log("Open this link, review the answers, then submit:");
  console.log(buildPrefillUrl(viewUrl, answered));
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
});
