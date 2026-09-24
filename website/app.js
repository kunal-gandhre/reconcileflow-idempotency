/*
 * Copyright 2026 ReconcileFlow
 * Author: Kunal Gandhre
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0; see LICENSE.
 * https://www.apache.org/licenses/LICENSE-2.0
 */

/** Browser-only interactions: code tabs, copy feedback and a labeled delivery simulation. */
// Render snippets with textContent so source code is never interpreted as HTML.
const snippets = {
  java: `@KafkaListener(topics = "order-events", groupId = "fulfillment")
@Idempotent(
    key = "#payload",
    namespace = "fulfillment:order-events:v1",
    retention = "24h")
public void process(String eventId) {
    orderService.fulfill(eventId);
}`,
  go: `handler, err := idempotent.Wrap(store, idempotent.Config{
    Namespace: "fulfillment:order-events:v1",
    Lease:     time.Minute,
    Retention: 24 * time.Hour,
}, extractEventID, processOrder)
if err != nil { return err }
// Commit the offset only after handler returns nil.
err = handler(ctx, message.Value)`,
};
let language = "java";
const tabs = [...document.querySelectorAll('[role="tab"]')];
/** Synchronize code, guide link, accessible panel label and the active tab stop. */
function selectLanguage(lang) {
  language = lang;
  for (const tab of tabs) {
    const active = tab.id === `tab-${lang}`;
    tab.setAttribute("aria-selected", String(active));
    tab.tabIndex = active ? 0 : -1;
  }
  document.querySelector("#code").textContent = snippets[lang];
  document
    .querySelector("#code-panel")
    .setAttribute("aria-labelledby", `tab-${lang}`);
  document.querySelector("#code-title").textContent =
    lang === "java"
      ? "Spring Boot · install from source first"
      : "Go · explicit handler middleware";
  document.querySelector(".text-link").href =
    `https://github.com/kunal-gandhre/reconcileflow-idempotency#quick-start--${lang}`;
  document.querySelector("#copy-status").textContent = "";
}
// Support the same language selection through pointer and keyboard interaction.
tabs.forEach((tab, index) => {
  tab.addEventListener("click", () => selectLanguage(tab.id.slice(4)));
  tab.addEventListener("keydown", (event) => {
    if (["ArrowRight", "ArrowLeft", "Home", "End"].includes(event.key)) {
      event.preventDefault();
      const next =
        event.key === "Home" ? 0 : event.key === "End" ? 1 : 1 - index;
      selectLanguage(tabs[next].id.slice(4));
      tabs[next].focus();
    }
  });
});
// Clipboard permission can be denied; keep a readable manual-copy fallback.
document.querySelector("#copy").addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText(snippets[language]);
    document.querySelector("#copy-status").textContent = "Copied to clipboard";
  } catch {
    document.querySelector("#copy-status").textContent =
      "Select and copy the code above.";
  }
});
// This local illustration has no Redis/Kafka connection and sends no payloads to a server.
let delivery = 0;
document.querySelector("#explore").addEventListener("click", () => {
  // Cycle through new -> busy -> completed, then restart on the next click.
  delivery++;
  const first = delivery % 3 === 1;
  const busy = delivery % 3 === 2;
  document.querySelector("#processed").classList.toggle("highlight", first);
  document
    .querySelector("#duplicate")
    .classList.toggle("highlight", !first && !busy);
  document.querySelector("#gate-state").textContent = first
    ? "NEW → PROCESSING"
    : busy
      ? "PROCESSING → RETRY"
      : "DONE → SKIP";
  document.querySelector("#flow-status").textContent = first
    ? "Simulation 1/3 · New event claimed. The handler starts."
    : busy
      ? "Simulation 2/3 · Another delivery arrives while busy. Retry; do not acknowledge."
      : "Simulation 3/3 · Work completed. A later duplicate is skipped.";
  document.querySelector("#explore").textContent =
    delivery % 3 === 0 ? "Restart the flow" : "Next delivery →";
});
// Initialize the panel from the same code path used by later tab changes.
selectLanguage("java");
