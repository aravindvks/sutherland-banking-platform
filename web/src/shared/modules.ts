// Module switches (phase A): the BFF lists the modules in its build at GET /v1/modules, and the screens show only
// those modules' tabs, panels and demo buttons. Unknown (an older BFF, or the call failed) = show everything.

export type Modules = ReadonlySet<string> | null;

export const has = (modules: Modules, name: string) => modules === null || modules.has(name);

export const toModules = (body: { modules?: string[] } | null | undefined): Modules =>
  body && Array.isArray(body.modules) ? new Set(body.modules) : null;
