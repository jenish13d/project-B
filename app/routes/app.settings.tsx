import { useEffect } from "react";
import type { ActionFunctionArgs, HeadersFunction, LoaderFunctionArgs } from "react-router";
import { Form, useActionData, useLoaderData, useNavigation } from "react-router";
import { useAppBridge } from "@shopify/app-bridge-react";
import { boundary } from "@shopify/shopify-app-react-router/server";

import { getShopSettings } from "../assistant/settings.server";
import db from "../db.server";
import { authenticate } from "../shopify.server";

const TEXT_LIMIT = 20000;

export const loader = async ({ request }: LoaderFunctionArgs) => {
  const { session, admin } = await authenticate.admin(request);
  let settings = await getShopSettings(session.shop);

  // Prefill the store name from Shopify the first time.
  if (!settings.storeName) {
    const response = await admin.graphql(`#graphql
      query StorePilotShopName { shop { name } }`);
    const json = (await response.json()) as { data?: { shop: { name: string } } };
    const name = json.data?.shop.name;
    if (name) {
      settings = await db.shopSettings.update({
        where: { shop: session.shop },
        data: { storeName: name },
      });
    }
  }
  return settings;
};

export const action = async ({ request }: ActionFunctionArgs) => {
  const { session } = await authenticate.admin(request);
  const form = await request.formData();
  const text = (key: string, max = TEXT_LIMIT) =>
    String(form.get(key) ?? "").trim().slice(0, max);

  await db.shopSettings.update({
    where: { shop: session.shop },
    data: {
      enabled: form.get("enabled") === "on",
      storeName: text("storeName", 100),
      assistantName: text("assistantName", 60) || "Support Assistant",
      tone: text("tone", 200) || "friendly and concise",
      policies: text("policies"),
      faq: text("faq"),
      extraRules: text("extraRules"),
      handoffEmail: text("handoffEmail", 200),
    },
  });
  return { saved: true };
};

export default function Settings() {
  const settings = useLoaderData<typeof loader>();
  const result = useActionData<typeof action>();
  const navigation = useNavigation();
  const shopify = useAppBridge();
  const saving = navigation.state === "submitting";

  useEffect(() => {
    if (result?.saved) shopify.toast.show("Settings saved");
  }, [result, shopify]);

  return (
    <s-page heading="Assistant settings">
      <Form method="post">
        <s-stack direction="block" gap="base">
          <s-section heading="Basics">
            <s-stack direction="block" gap="base">
              <s-checkbox
                name="enabled"
                label="Assistant is on"
                defaultChecked={settings.enabled}
              />
              <s-text-field name="storeName" label="Store name" defaultValue={settings.storeName} />
              <s-text-field
                name="assistantName"
                label="Assistant name"
                details="Shown to shoppers in the chat window."
                defaultValue={settings.assistantName}
              />
              <s-text-field
                name="tone"
                label="Tone of voice"
                details="For example: friendly and concise, formal, playful with emojis."
                defaultValue={settings.tone}
              />
              <s-email-field
                name="handoffEmail"
                label="Team email for handoffs"
                details="Where questions the assistant can't answer should go."
                defaultValue={settings.handoffEmail}
              />
            </s-stack>
          </s-section>

          <s-section heading="What the assistant knows">
            <s-stack direction="block" gap="base">
              <s-text-area
                name="policies"
                label="Store policies"
                details="Shipping times and costs, returns, exchanges, warranty. Paste them as you'd tell a new employee."
                rows={8}
                defaultValue={settings.policies}
              />
              <s-text-area
                name="faq"
                label="Frequently asked questions"
                details="Questions you get all the time, with your answers."
                rows={8}
                defaultValue={settings.faq}
              />
              <s-text-area
                name="extraRules"
                label="Extra instructions"
                details="Anything specific to your store, e.g. 'Always suggest the size guide for shoe questions.'"
                rows={4}
                defaultValue={settings.extraRules}
              />
            </s-stack>
          </s-section>

          <s-button type="submit" variant="primary" {...(saving ? { loading: true } : {})}>
            Save
          </s-button>
        </s-stack>
      </Form>
    </s-page>
  );
}

export const headers: HeadersFunction = (headersArgs) => {
  return boundary.headers(headersArgs);
};
