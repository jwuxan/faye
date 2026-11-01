/* Generated from schema/screen.schema.json by `pnpm generate`. Do not edit. */

export interface ScreenResponse {
  flowId: string;
  /**
   * Echo in the next SubmitRequest; single use.
   */
  nonce: string;
  /**
   * Innermost enclosing step id, when there is one.
   */
  step?: string;
  /**
   * Where the Login UI shows this screen, with ?flow={flowId} (ADR 0029). A label only: the flow decides the screen, so a browser at another path is shown this screen and moved to this path.
   */
  path: string;
  screen: Screen;
  branding?: Branding;
}

/**
 * How the tenant dresses its login screens, when it sets any of it: appearance and two optional controls (ADR 0033). The layout is not tenant configurable.
 */
export interface Branding {
  /**
   * Shown above every screen, and in the footer of every screen.
   */
  name?: string;
  /**
   * Logo image.
   */
  logoUrl?: string;
  /**
   * Primary button and focus ring.
   */
  primaryColor?: string;
  /**
   * Corner radius in px.
   */
  radius?: number;
  /**
   * Password fields have a button that shows what was typed.
   */
  showPassword?: true;
  /**
   * The identifier screen shows a sign up button. Sign up is not supported yet: the button says so.
   */
  signUp?: true;
}

export type Screen =
  | IdentifierScreen
  | PasswordScreen
  | MethodPickerScreen
  | PasskeyScreen
  | OtpScreen
  | OtpEnrollScreen
  | ConsentScreen
  | ErrorScreen;

export interface ScreenError {
  /**
   * Why the flow came back to this screen, e.g. invalid_credentials, invalid_code. Copy lives in the Login UI.
   */
  code: string;
}

/**
 * Failures that sent the flow back to this screen.
 */
export type Errors = ScreenError[];

/**
 * Attempts left after the failure in errors.
 */
export type AttemptsLeft = number;

export interface IdentifierScreen {
  type: "identifier";
  /**
   * Prefill, from login_hint.
   */
  identifier?: string;
  /**
   * Present when passkey autofill (WebAuthn conditional mediation) is offered. Offered to every account alike.
   */
  passkey?: {
    /**
     * base64url WebAuthn challenge.
     */
    challenge: string;
  };
  /**
   * Present when the screen asks for the password with the identifier: answer with IdentifierPasswordPayload. Never offered with passkey.
   */
  password?: true;
  /**
   * Present when the tenant offers "keep me signed in": the answer may carry remember (ADR 0033).
   */
  remember?: true;
  errors?: Errors;
  attemptsLeft?: AttemptsLeft;
}

export interface PasswordScreen {
  type: "password";
  /**
   * The identifier being signed in, shown as an editable chip.
   */
  identifier?: string;
  errors?: Errors;
  attemptsLeft?: AttemptsLeft;
}

export interface MethodPickerScreen {
  type: "method_picker";
  identifier?: string;
  /**
   * Option ids of the enabled choose options, in flow order. Labels live in the Login UI.
   *
   * @minItems 2
   */
  options: [string, string, ...string[]];
  errors?: Errors;
  attemptsLeft?: AttemptsLeft;
}

export interface PasskeyScreen {
  type: "passkey";
  identifier?: string;
  /**
   * base64url WebAuthn challenge.
   */
  challenge: string;
  /**
   * Present when creating a passkey rather than using one. The user may skip.
   */
  register?: {
    /**
     * base64url user handle.
     */
    userId: string;
    userName: string;
    displayName: string;
    /**
     * COSE algorithm ids the server verifies, in order of preference.
     *
     * @minItems 1
     */
    algorithms: [number, ...number[]];
  };
  errors?: Errors;
  attemptsLeft?: AttemptsLeft;
}

export interface OtpScreen {
  type: "otp";
  identifier?: string;
  errors?: Errors;
  attemptsLeft?: AttemptsLeft;
}

export interface OtpEnrollScreen {
  type: "otp_enroll";
  identifier?: string;
  /**
   * base32 TOTP secret, for manual entry.
   */
  secret: string;
  /**
   * otpauth:// URI, shown as a QR code.
   */
  uri: string;
  errors?: Errors;
  attemptsLeft?: AttemptsLeft;
}

export interface ConsentScreen {
  type: "consent";
  identifier?: string;
  client: {
    id: string;
    name?: string;
  };
  scopes: string[];
  errors?: Errors;
  attemptsLeft?: AttemptsLeft;
}

export interface ErrorScreen {
  type: "error";
  identifier?: string;
  /**
   * Safe error code; copy lives in the Login UI.
   */
  code: string;
  errors?: Errors;
  attemptsLeft?: AttemptsLeft;
}

export interface SubmitRequest {
  nonce: string;
  action: "submit";
  payload:
    | IdentifierPayload
    | IdentifierPasswordPayload
    | PasswordPayload
    | PasskeyPayload
    | SkipPayload
    | MethodPayload
    | CodePayload
    | ConsentPayload
    | ContinuePayload;
}

export interface IdentifierPayload {
  identifier: string;
  /**
   * Answers identifier when it offers remember: whether the person asked to stay signed in on this browser (ADR 0033).
   */
  remember?: boolean;
}

/**
 * Answers identifier when it asks for the password.
 */
export interface IdentifierPasswordPayload {
  identifier: string;
  password: string;
  /**
   * Answers identifier when it offers remember: whether the person asked to stay signed in on this browser (ADR 0033).
   */
  remember?: boolean;
}

export interface PasswordPayload {
  password: string;
}

/**
 * Answers passkey, or identifier when it offers autofill.
 */
export interface PasskeyPayload {
  /**
   * The browser's PublicKeyCredential as JSON (base64url fields).
   */
  passkey: {};
  /**
   * Answers identifier when it offers remember: whether the person asked to stay signed in on this browser (ADR 0033).
   */
  remember?: boolean;
}

/**
 * Declines creating a passkey (passkey screen with register).
 */
export interface SkipPayload {
  skip: true;
}

export interface MethodPayload {
  method: string;
}

/**
 * Answers otp and otp_enroll.
 */
export interface CodePayload {
  code: string;
}

export interface ConsentPayload {
  decision: "allow" | "deny";
}

/**
 * Answers error.
 */
export interface ContinuePayload {}

export interface RedirectResponse {
  /**
   * Where the browser goes next: always an absolute http or https URL, so the Login UI can hand it to window.location (ADR 0007).
   */
  redirectTo: string;
  /**
   * Present, and true, only when the sign in failed: the host has been told, and the Login UI shows /error?reason=failed instead of following redirectTo (ADR 0031). Never set for a consent the person denied, which goes back to the app.
   */
  failed?: true;
}

/**
 * A finished flow whose host does not redirect: result is the host's own (for example a one time code its system exchanges server to server for the outcome), handed to the page that embeds the Login UI.
 */
export interface HandoffResponse {
  result: {};
}

export interface ErrorResponse {
  error: "flow_not_found" | "flow_finished" | "stale_nonce" | "conflict" | "invalid_submission" | "host_error";
}

/**
 * Contract between the Faye server and the Login UI. GET /api/flows/{flowId} returns a ScreenResponse; POST /api/flows/{flowId}/submit takes a SubmitRequest and returns a ScreenResponse, a RedirectResponse (the host redirects the browser) or a HandoffResponse (the host hands the result to the page that embeds the Login UI). Failures return an ErrorResponse. Both calls need the cookie faye_flow_{flowId} that Faye set when the flow started; without its value the flow is answered as unknown (404, flow_not_found). The root schema accepts any response.
 */
export type ScreenProtocolResponse = ScreenResponse | RedirectResponse | HandoffResponse | ErrorResponse;
