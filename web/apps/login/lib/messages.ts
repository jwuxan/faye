import type { ErrorResponse } from "@faye/contract";

export function errorMessage(code: ErrorResponse["error"]): string {
  switch (code) {
    case "flow_not_found":
    case "flow_finished":
      return "This sign in has expired. Go back to the app and try again.";
    case "stale_nonce":
    case "conflict":
      return "This page is out of date. Reload to continue.";
    case "invalid_submission":
      return "Please fill in the field.";
    case "host_error":
      return "We couldn't finish signing you in. Go back to the app and try again.";
  }
}

/** Why the flow came back to a screen; the server sends codes, copy lives here. */
export function screenErrorMessage(code: string, attemptsLeft?: number): string {
  const base: Record<string, string> = {
    invalid_credentials: "That didn't work. Check what you entered and try again.",
    invalid_code: "That code didn't work. Enter the current code from your authenticator app.",
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
    enroll: "Set up an authenticator app",
  };
  return known[id] ?? id;
}

export function scopeLabel(scope: string): string {
  const known: Record<string, string> = {
    openid: "Know who you are",
    profile: "See your name",
    email: "See your email address",
    offline_access: "Stay connected when you're not using it",
  };
  return known[scope] ?? scope;
}
