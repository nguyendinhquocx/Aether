import {
  defineAetherExtension,
  ui,
  type AetherExtensionAPI,
} from "@baimoqilin/aether-extension-api";

const factory = defineAetherExtension((aether) => {
  aether.registerSurface("chat.composer.top", {
    render: ({ storage }) =>
      ui.card([
        ui.text(String(storage.count ?? 0)),
        ui.button("Increment", "increment"),
      ]),
  });
  aether.registerAction("increment", () => {
    const count = aether.storage.get("count", 0) + 1;
    aether.storage.set("count", count);
  });
  aether.registerSettings({
    id: "preferences",
    title: "Preferences",
    sections: [{ settings: [{ id: "enabled", label: "Enabled", type: "toggle", default: true }] }],
  });
  aether.registerComposerMenuItem({ id: "run", title: "Run", action: "run" });
  aether.registerMessageType({ type: "demo", render: ({ message }) => ui.text(String(message.text ?? "")) });
  aether.registerAction("message", () => aether.messages.append("demo", { text: "hello" }));
});

const acceptsApi = (_api: AetherExtensionAPI) => factory;
void acceptsApi;
