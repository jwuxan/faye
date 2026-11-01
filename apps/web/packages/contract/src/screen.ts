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
  | CodeSentScreen
  | PhoneEnrollScreen
  | QrScreen
  | QrApproveScreen
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
   * Present when creating a passkey rather than using one. The user may skip unless required is set.
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
  /**
   * With register: the step requires a passkey, so the user may not skip (ADR 0038); absent when they may.
   */
  required?: true;
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

/**
 * A one time code Faye sent by email or text message (ADR 0032). Answered with a CodePayload; the resend action sends another. The code itself is never in the screen.
 */
export interface CodeSentScreen {
  type: "code_sent";
  identifier?: string;
  channel: "email" | "sms";
  /**
   * Where the code went, masked (a•••@example.com, ••• ••• 4821).
   */
  to: string;
  /**
   * Seconds until the resend action may send another code; absent when no more may be sent.
   */
  resendIn?: number;
  /**
   * Seconds until the last code sent expires.
   */
  expiresIn: number;
  errors?: Errors;
  attemptsLeft?: AttemptsLeft;
}

/**
 * Adds a phone number for the signed in person (ADR 0032): the send action, with a PhonePayload, texts a code to it; the screen then shows to and expiresIn and is answered with a CodePayload, or with a SkipPayload unless required (ADR 0038).
 */
export interface PhoneEnrollScreen {
  type: "phone_enroll";
  identifier?: string;
  /**
   * The number the last code went to, masked; absent until one was sent.
   */
  to?: string;
  /**
   * Seconds until the send action may send another code; absent when none was sent yet or no more may be sent.
   */
  resendIn?: number;
  /**
   * Seconds until the last code sent expires; absent until one was sent.
   */
  expiresIn?: number;
  /**
   * The step requires a phone, so the person may not skip (ADR 0038); absent when they may.
   */
  required?: true;
  errors?: Errors;
  attemptsLeft?: AttemptsLeft;
}

/**
 * QR code sign in, on the device signing in (ADR 0032): draw url as a QR code and show number; send the poll action every 2 seconds, and once status is approved, expired or refused, answer with an empty payload (ContinuePayload).
 */
export interface QrScreen {
  type: "qr";
  identifier?: string;
  /**
   * What the QR code encodes: the Login UI's /approve/{token}.
   */
  url: string;
  /**
   * Shown beside the QR code; the person picks it on the phone.
   */
  number: number;
  expiresIn: number;
  /**
   * As the last poll read it.
   */
  status: "pending" | "approved" | "refused" | "expired";
  errors?: Errors;
  attemptsLeft?: AttemptsLeft;
}

/**
 * QR code sign in, on the phone (ADR 0032): approves the waiting device once the person picks the number it shows. Answered with a NumberPayload; a wrong number refuses the sign in.
 */
export interface QrApproveScreen {
  type: "qr_approve";
  identifier?: string;
  client?: {
    name?: string;
  };
  /**
   * The waiting device's browser, e.g. Chrome on macOS.
   */
  device?: string;
  /**
   * @minItems 3
   * @maxItems 3
   */
  numbers: [number, number, number];
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

export type SubmitRequest = AnswerRequest | ResendRequest | SendRequest | PollRequest;

/**
 * A screen action (ADR 0032): qr reads whether its QR code was approved.
 */
export interface PollRequest {
  nonce: string;
  action: "poll";
  payload: {};
}

/**
 * A screen action, not an answer (ADR 0032): code_sent sends another code. It runs no handler and spends no attempt; the response is the same screen, with errors [send_limited] when a limit refused it. An action the screen does not take is refused (400, unknown_action).
 */
export interface ResendRequest {
  nonce: string;
  action: "resend";
  payload: {};
}

/**
 * A screen action (ADR 0032): phone_enroll texts a code to the number typed.
 */
export interface SendRequest {
  nonce: string;
  action: "send";
  payload: PhonePayload;
}

/**
 * Answers qr_approve: the number the waiting device shows.
 */
export interface NumberPayload {
  number: number;
}

export interface PhonePayload {
  /**
   * E.164, with the country code.
   */
  phone: string;
}

export interface AnswerRequest {
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
    | NumberPayload
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
 * Declines creating a passkey (passkey screen with register), or adding a phone (phone_enroll); refused when the screen is required.
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
 * Answers error, and qr once its poll read where the QR code is.
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
  error:
    | "flow_not_found"
    | "flow_finished"
    | "flow_expired"
    | "stale_nonce"
    | "conflict"
    | "invalid_submission"
    | "unknown_action"
    | "host_error";
}

/**
 * Contract between the Faye server and the Login UI. GET /api/flows/{flowId} returns a ScreenResponse; POST /api/flows/{flowId}/submit takes a SubmitRequest and returns a ScreenResponse, a RedirectResponse (the host redirects the browser) or a HandoffResponse (the host hands the result to the page that embeds the Login UI). Failures return an ErrorResponse. Both calls need the cookie faye_flow_{flowId} that Faye set when the flow started; without its value the flow is answered as unknown (404, flow_not_found). The root schema accepts any response.
 */
export type ScreenProtocolResponse = ScreenResponse | RedirectResponse | HandoffResponse | ErrorResponse;
