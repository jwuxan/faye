import type { ErrorResponse } from "@faye/contract";

export function errorMessage(code: ErrorResponse["error"]): string {
  switch (code) {
    case "flow_not_found":
    case "flow_finished":
      return "This sign in has expired. Go back to the app and try again.";
    case "flow_expired":
      return "This sign in timed out. Go back to the app and start again.";
    case "stale_nonce":
    case "conflict":
      return "This page is out of date. Reload to continue.";
    case "invalid_submission":
      return "Please fill in the field.";
    case "unknown_action":
      return "That isn't possible on this page. Reload to continue.";
    case "host_error":
      return "We couldn't finish signing you in. Go back to the app and try again.";
  }
}

/** Why the flow came back to a screen; the server sends codes, copy lives here. */
export function screenErrorMessage(code: string, attemptsLeft?: number, screen?: string): string {
  // A code Faye sent (ADR 0032) is the latest one sent, not an authenticator app's
  const sent = screen === "code_sent" || screen === "phone_enroll";
  const base: Record<string, string> = {
    invalid_credentials: "That didn't work. Check what you entered and try again.",
    invalid_code: sent
      ? "That code didn't work. Enter the latest code we sent."
      : "That code didn't work. Enter the current code from your authenticator app.",
    code_expired: "That code has expired. Send a new code.",
    send_limited: "Too many codes were sent. Wait a moment, then try again.",
    qr_expired: "That QR code expired. Scan this new one.",
    qr_not_approved: "That QR code was not approved. Scan this new one, or sign in another way.",
    wrong_number: "That was not the number on your other device.",
    wrong_account: "That isn't the account you're signed in with. Enter your own email address.",
  };
  const message = base[code] ?? "Something went wrong. Try again.";
  return attemptsLeft === undefined ? message : `${message} ${attemptsLeft} ${attemptsLeft === 1 ? "attempt" : "attempts"} left.`;
}

/** Copy for the error screen's code. */
export function terminalErrorMessage(code: string): string {
  const known: Record<string, string> = {
    blocked: "Sign in is not available for this account.",
  };
  return known[code] ?? "We couldn't sign you in.";
}

/** Method picker labels by option id; unknown ids show as written. */
export function methodLabel(id: string): string {
  const known: Record<string, string> = {
    password: "Password",
    passkey: "Passkey",
    totp: "Authenticator app",
    otp: "Authenticator app",
    app: "Authenticator app",
    phone: "Text message",
    sms: "Text message",
    email: "Email",
    qr: "QR code",
    enroll: "Set up an authenticator app",
  };
  return known[id] ?? id;
}

/** What a scope lets the application do, and what it shares; unknown scopes show as written. */
export function scopeLabel(scope: string): { label: string; detail?: string } {
  const known: Record<string, { label: string; detail: string }> = {
    openid: { label: "Know who you are", detail: "Your account's unique identifier" },
    profile: { label: "See your name", detail: "Your name and basic profile details" },
    email: { label: "See your email address", detail: "The email address on your account" },
    offline_access: { label: "Stay connected", detail: "Keep access when you're not using it" },
  };
  return known[scope] ?? { label: scope };
}
