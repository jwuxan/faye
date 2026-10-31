/** Faye server base URL, read per request so one build runs against any server. */
export function fayeServerUrl(): string {
  return process.env.FAYE_SERVER_URL ?? "http://localhost:8080";
}

/**
 * The cookie that binds a flow to the browser that started it. Faye's /login sets it; this
 * server forwards it, and only it, on every call it makes for that flow (ADR 0024).
 */
export function flowCookie(flowId: string): string {
  return `faye_flow_${flowId}`;
}

/** The Cookie header to send Faye for a flow, from the cookies the browser sent here. */
export function bindingHeader(flowId: string, value: string | undefined): Record<string, string> {
  return value === undefined ? {} : { cookie: `${flowCookie(flowId)}=${value}` };
}
