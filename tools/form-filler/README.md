# Google Form filler

A command-line helper that reads a public Google Form, uses Claude with web search to answer each question, and prints a **prefilled link**. Open the link, check every answer, and submit the form yourself. The tool never submits anything.

## Setup

```bash
npm install
export ANTHROPIC_API_KEY=sk-ant-...   # or run `ant auth login`
```

Requires Node 22.18 or later, which runs the TypeScript directly.

## Usage

```bash
npm run fill-form -- "https://docs.google.com/forms/d/e/<form-id>/viewform"
```

Options:

| Flag | Default | Meaning |
|---|---|---|
| `--about notes.txt` | none | Notes about you (name, email, roll number…) used for personal questions. Without them, those questions are left blank. |
| `--effort <level>` | `medium` | How hard Claude thinks: `low`, `medium`, `high`, `xhigh`, `max`. |
| `--model <id>` | `claude-opus-5-5` | Claude model to use. |

The output lists each answer with a confidence level, a short reason and sources, then the questions you need to fill in yourself, then the prefilled link.

## Limits

- Only forms that open without signing in. Forms that need a Google account, or that are closed, can't be read.
- Short answer, paragraph, multiple choice, dropdown, checkboxes, linear scale, date and time questions are supported. Grids and file uploads are listed for you to fill in by hand.
- Answers come from web search and can be wrong. Check them, especially the ones marked low confidence.
- Don't use it on graded tests or exams where outside help isn't allowed.
