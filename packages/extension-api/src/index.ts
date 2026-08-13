export type AetherJsonObject = Record<string, unknown>;

export type AetherView =
  | AetherJsonObject
  | AetherView[]
  | string
  | null
  | undefined;

export type AetherRenderContext = AetherJsonObject & {
  extension: {
    id: string;
    name: string;
    path: string;
  };
  storage: AetherJsonObject;
};

export interface AetherSurfaceDefinition {
  id?: string;
  order?: number;
  render?:
    | AetherView
    | ((
      context: AetherRenderContext,
    ) => AetherView | Promise<AetherView>);
  tree?: AetherView;
}

export type AetherSettingType =
  | "text"
  | "password"
  | "textarea"
  | "number"
  | "toggle"
  | "select"
  | "dropdown"
  | "segmented"
  | "tab"
  | "tabs"
  | "slider"
  | "button"
  | "link"
  | "label"
  | "divider"
  | "spacer";

export interface AetherSettingOption {
  value: string;
  label: string;
}

export interface AetherSettingDefinition {
  id: string;
  label?: string;
  description?: string;
  type?: AetherSettingType;
  default?: string | number | boolean;
  placeholder?: string;
  options?: AetherSettingOption[];
  min?: number;
  max?: number;
  step?: number;
  action?: string;
  args?: AetherJsonObject;
  url?: string;
  icon?: string;
  tone?: "primary" | "neutral" | "danger";
  enabled?: boolean;
  multiline?: boolean;
  secret?: boolean;
}

export interface AetherSettingsSection {
  id?: string;
  title?: string;
  description?: string;
  settings: AetherSettingDefinition[];
}

export interface AetherSettingsDefinition {
  id: string;
  title: string;
  subtitle?: string;
  icon?: string;
  order?: number;
  sections: AetherSettingsSection[];
}

export interface AetherPageDefinition extends AetherSurfaceDefinition {
  id: string;
  title: string;
  subtitle?: string;
  icon?: string;
}

export interface AetherComposerMenuItemDefinition {
  id: string;
  title: string;
  subtitle?: string;
  icon?: string;
  order?: number;
  action?: string;
  args?: AetherJsonObject;
  selected?: boolean;
}

export interface AetherMessageTypeDefinition {
  type: string;
  title?: string;
  icon?: string;
  render:
    | AetherView
    | ((context: AetherRenderContext & { message: AetherJsonObject }) => AetherView | Promise<AetherView>);
}

export type AetherComponentMode =
  | "before"
  | "after"
  | "replace"
  | "wrap"
  | "hide";

export interface AetherComponentDefinition extends AetherSurfaceDefinition {
  mode?: AetherComponentMode;
}

export interface AetherActionContext extends AetherRenderContext {
  action: string;
}

export interface AetherEventContext extends AetherRenderContext {
  event: string;
}

export interface AetherUi {
  node(
    type: string,
    properties?: AetherJsonObject,
    children?: AetherView[],
  ): AetherJsonObject;
  text(text: string, properties?: AetherJsonObject): AetherJsonObject;
  code(text: string, properties?: AetherJsonObject): AetherJsonObject;
  column(
    children: AetherView[],
    properties?: AetherJsonObject,
  ): AetherJsonObject;
  row(
    children: AetherView[],
    properties?: AetherJsonObject,
  ): AetherJsonObject;
  box(
    children: AetherView[],
    properties?: AetherJsonObject,
  ): AetherJsonObject;
  card(
    children: AetherView[],
    properties?: AetherJsonObject,
  ): AetherJsonObject;
  button(
    label: string,
    action: string,
    properties?: AetherJsonObject,
  ): AetherJsonObject;
  iconButton(
    icon: string,
    action: string,
    properties?: AetherJsonObject,
  ): AetherJsonObject;
  switch(
    label: string,
    checked: boolean,
    action: string,
    properties?: AetherJsonObject,
  ): AetherJsonObject;
  input(
    value: string,
    action: string,
    properties?: AetherJsonObject,
  ): AetherJsonObject;
  spacer(size?: number, properties?: AetherJsonObject): AetherJsonObject;
  progress(value?: number, properties?: AetherJsonObject): AetherJsonObject;
  web(properties: AetherJsonObject): AetherJsonObject;
  core(properties?: AetherJsonObject): AetherJsonObject;
}

export interface AetherExtensionAPI {
  readonly apiVersion: 2;
  readonly extension: {
    id: string;
    name: string;
    path: string;
  };
  readonly ui: AetherUi;
  readonly host: {
    invoke(
      method: string,
      args?: AetherJsonObject,
    ): Promise<AetherJsonObject>;
  };
  readonly services: {
    list(): Promise<AetherJsonObject>;
    describe(service: string): Promise<AetherJsonObject>;
    invoke(
      service: string,
      method: string,
      args?: AetherJsonObject,
    ): Promise<AetherJsonObject>;
  };
  readonly state: {
    get(path?: string): Promise<AetherJsonObject>;
    patch(path: string, value: unknown): Promise<AetherJsonObject>;
    transaction(
      operations: Array<{
        op?: "set" | "remove";
        path: string;
        value?: unknown;
      }>,
    ): Promise<AetherJsonObject>;
  };
  readonly storage: {
    get<T = unknown>(key: string, fallback?: T): T;
    set(key: string, value: unknown): void;
    delete(key: string): void;
    clear(): void;
    snapshot(): AetherJsonObject;
  };
  readonly messages: {
    append(type: string, payload?: AetherJsonObject, text?: string): Promise<AetherJsonObject>;
  };
  registerSurface(
    slot: string,
    definition:
      | AetherSurfaceDefinition
      | AetherView
      | ((
        context: AetherRenderContext,
      ) => AetherView | Promise<AetherView>),
  ): () => void;
  registerComponent(
    target: string,
    definition:
      | AetherComponentDefinition
      | AetherView
      | ((
        context: AetherRenderContext,
      ) => AetherView | Promise<AetherView>),
  ): () => void;
  registerPage(definition: AetherPageDefinition): () => void;
  registerSettings(definition: AetherSettingsDefinition): () => void;
  registerSettingsPage(definition: AetherSettingsDefinition): () => void;
  registerComposerMenuItem(definition: AetherComposerMenuItemDefinition): () => void;
  registerComposerMenu(definition: AetherComposerMenuItemDefinition): () => void;
  registerMessageType(definition: AetherMessageTypeDefinition): () => void;
  registerCustomMessage(definition: AetherMessageTypeDefinition): () => void;
  registerAction(
    id: string,
    handler: (
      payload: AetherJsonObject,
      context: AetherActionContext,
    ) => unknown | Promise<unknown>,
  ): () => void;
  on(
    event: string,
    handler: (
      payload: AetherJsonObject,
      context: AetherEventContext,
    ) => unknown | Promise<unknown>,
  ): () => void;
  intercept(
    operation: string,
    handler: (
      payload: AetherJsonObject,
      context: AetherEventContext,
    ) => unknown | Promise<unknown>,
  ): () => void;
  invalidate(): void;
  notify(message: string, level?: "info" | "warning" | "error"): void;
}

export type AetherExtensionFactory = (
  api: AetherExtensionAPI,
) =>
  | void
  | (() => void | Promise<void>)
  | Promise<void | (() => void | Promise<void>)>;

export declare const ui: AetherUi;

export declare function defineAetherExtension<
  T extends AetherExtensionFactory,
>(factory: T): T;
