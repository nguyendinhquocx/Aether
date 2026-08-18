// Lightweight handoff between the Pi extension (index.ts) and the Aether
// Script Mod (aether.ts). Keep this module free of pi/MCP SDK imports so the
// two loaders can share it without pulling each other's dependency graphs.

export type McpAetherServerStatus =
  | "connected"
  | "cached"
  | "failed"
  | "needs-auth"
  | "not-connected"
  | "disabled";

export interface McpAetherServerSnapshot {
  name: string;
  status: McpAetherServerStatus;
  toolCount: number;
  resourceCount?: number;
  failedAgoSeconds?: number;
  disabled: boolean;
}

export interface McpAetherSnapshot {
  ready: boolean;
  configPath: string;
  servers: McpAetherServerSnapshot[];
  totalTools: number;
  totalResources: number;
  connectedCount: number;
  disabledCount: number;
  /** Public Pi tool names registered as direct MCP tools in the current session. */
  toolNames?: string[];
}

export interface McpAetherBridge {
  api?: McpAetherBridgeApi;
  onStatusChanged?: (bridge?: McpAetherBridge | undefined) => void;
  getSnapshot(): McpAetherSnapshot;
  reconnect(serverName: string): Promise<{ ok: boolean; message: string }>;
  reconnectAll(): Promise<{ ok: boolean; message: string }>;
  startAuth(serverName: string): Promise<{ ok: boolean; message: string; authorizationUrl?: string }>;
  completeAuth(serverName: string, input: string): Promise<{ ok: boolean; message: string }>;
  inspect(serverName: string, operation: "tools" | "resources" | "prompts"): Promise<{ ok: boolean; message: string; details?: string }>;
  logout(serverName: string): Promise<{ ok: boolean; message: string }>;
  reload(): Promise<{ ok: boolean; message: string }>;
}

interface McpAetherBridgeApi {
  invalidate?: () => void;
}

interface BridgeState {
  api?: McpAetherBridgeApi;
  onBridge?: (bridge: McpAetherBridge) => void;
}

const BRIDGE_KEY = Symbol.for("pi-mcp-adapter.aether-bridge");
const API_KEY = Symbol.for("pi-mcp-adapter.aether-api");

export function readMcpAetherBridge(): McpAetherBridge | undefined {
  return (globalThis as Record<PropertyKey, unknown>)[ BRIDGE_KEY] as McpAetherBridge | undefined;
}

function readBridgeState(): BridgeState | undefined {
  return (globalThis as Record<PropertyKey, unknown>)[API_KEY] as BridgeState | undefined;
}

/** Installed by the Pi extension. Re-registering replaces any previous activation. */
export function registerMcpAetherBridge(bridge: McpAetherBridge): void {
  (globalThis as Record<PropertyKey, unknown>)[BRIDGE_KEY] = bridge;
  const state = readBridgeState();
  if (!state) return;
  if (state.api !== undefined) bridge.api = state.api;
  if (state.onBridge !== undefined) bridge.onStatusChanged = () => state.onBridge?.(bridge);
  state.onBridge?.(bridge);
  state.api?.invalidate?.();
}

/** Remove a bridge installed by this Pi activation. */
export function unregisterMcpAetherBridge(bridge: McpAetherBridge): void {
  const current = readMcpAetherBridge();
  if (current !== bridge) return;
  delete (globalThis as Record<PropertyKey, unknown>)[BRIDGE_KEY];
}

/** Attach the Aether API half of the handoff when the Script Mod loads. */
export function attachMcpAetherApi(
  api: McpAetherBridgeApi,
  onBridge?: (bridge: McpAetherBridge) => void,
): void {
  (globalThis as Record<PropertyKey, unknown>)[API_KEY] = { api, onBridge };
  const bridge = readMcpAetherBridge();
  if (!bridge) return;
  bridge.api = api;
  if (onBridge !== undefined) bridge.onStatusChanged = () => onBridge?.(bridge);
  onBridge?.(bridge);
  api.invalidate?.();
}

