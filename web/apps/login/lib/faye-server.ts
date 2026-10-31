/** Faye server base URL, read per request so one build runs against any server. */
export function fayeServerUrl(): string {
  return process.env.FAYE_SERVER_URL ?? "http://localhost:8080";
}
