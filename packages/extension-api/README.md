# @baimoqilin/aether-extension-api

TypeScript declarations for Aether Script Extensions.

```bash
npm install --save-dev @baimoqilin/aether-extension-api
```

```ts
import { defineAetherExtension, ui } from "@baimoqilin/aether-extension-api";

export const activateAether = defineAetherExtension((aether) => {
  aether.registerSurface("chat.composer.top", {
    render: () => ui.text("Hello from Aether"),
  });
});
```

Extensions register data-only settings schemas. This is the only native page
registration API; extensions cannot create full-screen or drawer pages. Aether renders settings with the
same scaffold, cards, spacing, typography, and controls as its built-in
Settings pages, in a dedicated group between Reliability and Agent Skills.
Values are stored per extension, page, and setting and restored across reloads:

```ts
aether.registerSettings({
  id: "preferences",
  title: "Preferences",
  sections: [{
    title: "General",
    settings: [
      { id: "enabled", label: "Enabled", type: "toggle", default: true },
      { id: "endpoint", label: "Endpoint", type: "text", placeholder: "https://..." },
      { id: "mode", label: "Mode", type: "select", options: [
        { value: "fast", label: "Fast" },
        { value: "quality", label: "Quality" },
      ] },
    ],
  }],
});
```

Settings may optionally contain child categories. The host shows the category
list first and then renders the selected category with the same native controls:

```ts
aether.registerSettings({
  id: "preferences",
  title: "Preferences",
  categories: [
    { id: "general", title: "General", sections: [{ settings: [] }] },
    { id: "advanced", title: "Advanced", sections: [{ settings: [] }] },
  ],
});
```

Supported controls are `text`, `password`, `textarea`, `number`, `toggle`,
`select`/`dropdown`, `segmented`, `tab`/`tabs`, `slider`, `button`, `link`,
`label`, `divider`, and `spacer`. Extensions do not provide page padding, card shapes, typography,
or other page-level styling.

The chat composer plus menu and transcript support extension-owned entries:

```ts
aether.registerComposerMenuItem({
  id: "summarize",
  title: "Summarize thread",
  icon: "auto",
  action: "summarize",
});
aether.registerMessageType({
  type: "summary",
  title: "Summary",
  render: ({ message }) => ui.card([
    ui.text(String(message.title ?? "Summary")),
    ui.text(String(message.body ?? ""), { color: "muted" }),
  ]),
});
aether.registerAction("show-summary", () =>
  aether.messages.append("summary", { title: "Done", body: "Thread summarized." }));
```

This package intentionally contains declarations only. Aether injects the
runtime implementation when it loads an Extension.

The npm package major version matches `aether.apiVersion`. Package `2.x`
describes Script API version 2.
