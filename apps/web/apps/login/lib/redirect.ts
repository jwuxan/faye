/**
 * Whether the browser may go to a redirect the server sent: an absolute http or https URL with a
 * host and no whitespace or control characters. The server checks this before it answers
 * (FlowController.checked); the Login UI checks again before handing the value to
 * window.location, so a javascript: or data: URL can never run in this origin (ADR 0007, F15).
 */
export function isNavigable(url: string): boolean {
  if (!/^https?:\/\/[^\s\u0000-\u001f\u007f]+$/i.test(url)) return false;
  try {
    const parsed = new URL(url);
    return (parsed.protocol === "https:" || parsed.protocol === "http:") && parsed.hostname !== "";
  } catch {
    return false;
  }
}

/** The largest body the submit proxy forwards, as the server's BodyLimit. */
export const MAX_SUBMIT_BYTES = 64 * 1024;
