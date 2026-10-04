# StorePilot

An AI customer-support assistant for Shopify stores. Shoppers chat with it on the store; it answers
from the store's own policies, FAQ and product catalog, looks up orders ("where is my order?"), and
passes anything it can't handle to the store's team.

The business model is **one shared core plus per-store customization**: every store runs the same
app, and each store's tone, policies, FAQ and extra rules are configured in the app's settings page.

## What's in this first version

| Part | Where | What it does |
| --- | --- | --- |
| Chat widget | `extensions/storepilot-chat/` | Theme app embed: a chat bubble on the storefront. The merchant turns it on in the theme editor. |
| Chat endpoint | `app/routes/proxy.chat.tsx` | Receives shopper messages through a Shopify [app proxy](https://shopify.dev/docs/apps/build/online-store/app-proxies) (`/apps/storepilot/chat`), runs the assistant, saves the conversation. |
| Assistant | `app/assistant/` | Calls Claude with three tools: `lookup_order`, `search_products`, `request_human_handoff`. |
| Admin dashboard | `app/routes/app._index.tsx` | Last 30 days: conversations, replies, resolved vs. handed off, plus a setup checklist. |
| Settings | `app/routes/app.settings.tsx` | Per-store assistant name, tone, policies, FAQ, extra instructions, on/off switch. |
| Conversations | `app/routes/app.conversations.tsx` | Transcripts, with a filter for conversations that need the team. |
| Privacy webhooks | `app/routes/webhooks.compliance.tsx` | Mandatory GDPR/CCPA webhooks for the App Store. |

### How a message is answered

1. The widget POSTs `{ conversationId, message }` to `/apps/storepilot/chat`. Shopify signs the request and forwards it to the app.
2. The app loads the store's settings and the last 20 messages of the conversation.
3. `runAssistant` sends them to Claude. If Claude calls a tool, the app runs it against the store's Admin API and sends the result back, until Claude replies (max 6 rounds).
4. The reply and any handoff are saved and returned to the widget.

### Safety choices

- **Order lookups need the order number and the checkout email.** A wrong email and a missing order get the same answer, so the chat can't be used to find other people's orders. The email is never sent to the model.
- Messages are capped at 1,000 characters and conversations at 40 messages, to limit abuse of a public endpoint that costs money per call.
- The system prompt tells the model to treat customer messages and tool results as data, never as instructions.
- The assistant can't refund, cancel or edit orders; it hands those off.

## Running it locally

You need Node 22.12+, a free [Shopify Partner account](https://partners.shopify.com), a development store, and an [Anthropic API key](https://console.anthropic.com).

```bash
npm install
npm run config:link        # connect this code to an app in your Partner account
export ANTHROPIC_API_KEY=sk-ant-...
npm run dev                # Shopify CLI: tunnel, install on the dev store, hot reload
```

Then in the dev store: open the app, fill in **Assistant settings**, and turn on the widget in
**Online Store → Themes → Customize → App embeds → StorePilot chat**.

### Environment variables

| Variable | Required | Default | Notes |
| --- | --- | --- | --- |
| `ANTHROPIC_API_KEY` | yes | | Claude API key. |
| `ASSISTANT_MODEL` | no | `claude-opus-5-5` | Claude model used for replies. |
| `ASSISTANT_EFFORT` | no | `low` | `low`/`medium`/`high`/`xhigh`/`max`. Low keeps chat fast and cheap. |
| `SHOPIFY_API_KEY`, `SHOPIFY_API_SECRET`, `SHOPIFY_APP_URL`, `SCOPES` | yes | | Set by the Shopify CLI in dev; set them yourself in production. |

### Checks

```bash
npm run lint
npm run typecheck
npm test
```

## Before launching to real stores

- **Protected customer data.** Reading order emails needs Shopify's [protected customer data](https://shopify.dev/docs/apps/launch/protected-customer-data) approval for a public app. Development stores work without it.
- **Orders older than 60 days** need the `read_all_orders` scope, which Shopify must approve.
- **Database.** The template uses SQLite. Switch `prisma/schema.prisma` to Postgres (e.g. a free Neon or Supabase tier) before hosting.
- **Billing.** Add plans with the [Shopify Billing API](https://shopify.dev/docs/apps/launch/billing) so stores pay through their Shopify invoice.
- **Handoff emails.** Handoffs are listed on the Conversations page; sending them to `handoffEmail` automatically needs an email provider (e.g. Resend or Postmark).
- **Rate limiting** per store and per visitor on the chat endpoint.

## Credits

Built on [Shopify's React Router app template](https://github.com/Shopify/shopify-app-template-react-router) (MIT, see `LICENSE-shopify-template.md`).
