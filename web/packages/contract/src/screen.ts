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
  screen: Screen;
  branding?: Branding;
}

/**
 * The tenant's branding, when it has any. Appearance only: layout is not tenant configurable.
 */
export interface Branding {
  /**
   * Shown above every screen.
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
  redirectTo: string;
}

export interface ErrorResponse {
  error: "flow_not_found" | "flow_finished" | "stale_nonce" | "conflict" | "invalid_submission";
}

/**
 * Contract between the Faye server and the Login UI. GET /api/flows/{flowId} returns a ScreenResponse; POST /api/flows/{flowId}/submit takes a SubmitRequest and returns a ScreenResponse or a RedirectResponse. Failures return an ErrorResponse. The root schema accepts any response.
 */
export type ScreenProtocolResponse = ScreenResponse | RedirectResponse | ErrorResponse;
