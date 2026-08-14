import { readFile } from "node:fs/promises";
import { build } from "esbuild";

const nodeBundleExtensionModules = {
  name: "node-bundle-extension-modules",
  setup(context) {
    context.onLoad(
      { filter: /@earendil-works\/pi-coding-agent\/dist\/core\/extensions\/loader\.js$/ },
      async ({ path }) => {
        const source = await readFile(path, "utf8");
        const original = "...(isBunBinary\n            ? { virtualModules: VIRTUAL_MODULES, tryNative: false }";
        const replacement = "...(true\n            ? { virtualModules: VIRTUAL_MODULES, tryNative: false }";
        if (!source.includes(original)) {
          throw new Error("Pi extension loader structure changed; update the Node bundle patch.");
        }
        return {
          contents: source.replace(original, replacement),
          loader: "js",
        };
      },
    );
  },
};

await build({
  entryPoints: ["src/bridge.ts"],
  bundle: true,
  platform: "node",
  format: "esm",
  target: "node22.19",
  banner: {
    js: "import { createRequire as __aetherCreateRequire } from 'node:module';const require = __aetherCreateRequire(import.meta.url);",
  },
  outfile: "dist/bridge.mjs",
  plugins: [nodeBundleExtensionModules],
});
