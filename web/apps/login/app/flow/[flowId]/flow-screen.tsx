"use client";

import type {
  Branding,
  ConsentScreen,
  HandoffResponse,
  IdentifierScreen,
  MethodPickerScreen,
  OtpEnrollScreen,
  PasskeyScreen,
  ScreenProtocolResponse,
  ScreenResponse,
  SubmitRequest,
} from "@faye/contract";
import { Button } from "@faye/ui/components/button";
import { Card, CardContent, CardHeader, CardTitle } from "@faye/ui/components/card";
import { Input } from "@faye/ui/components/input";
import { Label } from "@faye/ui/components/label";
import Image from "next/image";
import { type CSSProperties, type FormEvent, useEffect, useEffectEvent, useRef, useState } from "react";
import { encode as qrEncode } from "uqr";
import {
  errorMessage,
  methodLabel,
  scopeLabel,
  screenErrorMessage,
  terminalErrorMessage,
} from "../../../lib/messages";
import { autofillAvailable, cancelPasskeyCeremony, createPasskey, getPasskey } from "../../../lib/webauthn";

type Payload = SubmitRequest["payload"];

/**
 * Gives a host's handoff result to the page that embeds or opened this one, and only when its
 * origin is configured: the result may be a one time code, so it never goes to an unknown origin.
 */
function handOff(result: HandoffResponse["result"], embedOrigin: string | null) {
  if (!embedOrigin) return;
  const target = window.parent !== window ? window.parent : window.opener;
  if (target) (target as Window).postMessage({ type: "faye:handoff", result }, embedOrigin);
}
type Send = (payload: Payload) => Promise<void>;

/**
 * Renders whichever typed screen the server sent; the UI never sees the flow itself.
 *
 * @param embedOrigin the origin of the page that embeds this one, for a host that hands off its
 *   result rather than redirecting; null posts nothing
 */
export function FlowScreen({ initial, embedOrigin }: { initial: ScreenResponse; embedOrigin: string | null }) {
  const [current, setCurrent] = useState(initial);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [handedOff, setHandedOff] = useState(false);
  const { screen } = current;

  async function send(payload: Payload) {
    const request: SubmitRequest = { nonce: current.nonce, action: "submit", payload };
    setPending(true);
    setError(null);
    try {
      const res = await fetch(`/api/flows/${encodeURIComponent(current.flowId)}/submit`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(request),
      });
      const body = (await res.json()) as ScreenProtocolResponse;
      if ("redirectTo" in body) {
        window.location.assign(body.redirectTo);
        return;
      }
      if ("result" in body) {
        handOff(body.result, embedOrigin);
        setHandedOff(true);
        setPending(false);
        return;
      }
      if ("error" in body) setError(errorMessage(body.error));
      else setCurrent(body);
    } catch {
      setError("Something went wrong. Try again.");
    }
    setPending(false);
  }

  if (handedOff) {
    return (
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>Done</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">You can return to the app.</p>
        </CardContent>
      </Card>
    );
  }

  const failure = screen.errors?.[0];
  const message = error ?? (failure ? screenErrorMessage(failure.code, screen.attemptsLeft) : null);
  const props = { send, pending, invalid: message !== null };

  const { branding } = current;
  return (
    <div className="grid w-full max-w-sm gap-4" style={brandStyle(branding)}>
      {branding && (branding.name || branding.logoUrl) && (
        <div className="flex items-center justify-center gap-2">
          {/* unoptimized: the browser loads the logo itself; this server never fetches tenant URLs */}
          {branding.logoUrl && (
            <Image src={branding.logoUrl} alt="" width={128} height={32} unoptimized className="h-8 w-auto" referrerPolicy="no-referrer" />
          )}
          {branding.name && <span className="text-lg font-semibold">{branding.name}</span>}
        </div>
      )}
      <Card className="w-full">
        <CardHeader>
          <CardTitle>{title(current)}</CardTitle>
          {screen.type !== "identifier" && screen.identifier && (
            <span className="font-mono text-sm text-muted-foreground">{screen.identifier}</span>
          )}
        </CardHeader>
        {/* key remounts the content, so each new screen starts empty and takes focus */}
        <CardContent key={current.nonce} className="grid gap-4">
          {screen.type === "identifier" && <Identifier screen={screen} {...props} />}
          {screen.type === "password" && (
            <TextField name="password" label="Password" type="password" autoComplete="current-password" {...props} />
          )}
          {screen.type === "otp" && <CodeField {...props} />}
          {screen.type === "otp_enroll" && <OtpEnroll screen={screen} {...props} />}
          {screen.type === "method_picker" && <MethodPicker screen={screen} {...props} />}
          {screen.type === "passkey" && <Passkey screen={screen} {...props} />}
          {screen.type === "consent" && <Consent screen={screen} {...props} />}
          {screen.type === "error" && (
            <>
              <p className="text-sm">{terminalErrorMessage(screen.code)}</p>
              <Button onClick={() => send({})} disabled={pending}>
                Back to the app
              </Button>
            </>
          )}
          <p className="text-sm text-destructive empty:hidden" role="alert" aria-live="assertive">
            {message}
          </p>
        </CardContent>
      </Card>
    </div>
  );
}

/**
 * Maps a tenant's branding onto the theme tokens: the primary color (with black or white text,
 * whichever reads better on it) and the corner radius. Layout stays the same for every tenant.
 */
function brandStyle(branding: Branding | undefined): CSSProperties | undefined {
  if (!branding) return undefined;
  const style: Record<string, string> = {};
  if (branding.primaryColor) {
    const [r, g, b] = [1, 3, 5].map((i) => parseInt(branding.primaryColor!.slice(i, i + 2), 16) / 255);
    const luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
    style["--primary"] = branding.primaryColor;
    style["--ring"] = branding.primaryColor;
    style["--primary-foreground"] = luminance > 0.55 ? "#0a0a0a" : "#ffffff";
  }
  if (branding.radius !== undefined) style["--radius"] = `${branding.radius}px`;
  return style as CSSProperties;
}

function title({ screen }: ScreenResponse): string {
  switch (screen.type) {
    case "identifier":
      return "Sign in";
    case "password":
      return "Enter your password";
    case "method_picker":
      return "Choose how to continue";
    case "passkey":
      return screen.register ? "Create a passkey" : "Use your passkey";
    case "otp":
      return "Enter your code";
    case "otp_enroll":
      return "Set up an authenticator app";
    case "consent":
      return `${screen.client.name ?? screen.client.id} wants to`;
    case "error":
      return "Can't sign in";
  }
}

type FieldProps = { send: Send; pending: boolean; invalid: boolean };

/** One labelled field and a Continue button; focus moves to the field on each new screen. */
function TextField({
  name,
  label,
  type,
  autoComplete,
  defaultValue,
  inputMode,
  pattern,
  send,
  pending,
  invalid,
  children,
}: FieldProps & {
  name: "identifier" | "password" | "code";
  label: string;
  type: string;
  autoComplete: string;
  defaultValue?: string;
  inputMode?: "numeric";
  pattern?: string;
  children?: React.ReactNode;
}) {
  const input = useRef<HTMLInputElement>(null);
  // autoFocus does not apply to server rendered markup
  useEffect(() => input.current?.focus(), []);

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = String(new FormData(event.currentTarget).get(name) ?? "");
    void send(name === "identifier" ? { identifier: value } : name === "password" ? { password: value } : { code: value });
  }

  return (
    <form onSubmit={onSubmit} className="grid gap-4">
      <div className="grid gap-2">
        <Label htmlFor={name}>{label}</Label>
        <Input
          ref={input}
          id={name}
          name={name}
          type={type}
          autoComplete={autoComplete}
          defaultValue={defaultValue}
          inputMode={inputMode}
          pattern={pattern}
          className={name === "code" ? "font-mono tracking-widest" : undefined}
          aria-invalid={invalid || undefined}
          required
        />
      </div>
      <Button type="submit" disabled={pending}>
        Continue
      </Button>
      {children}
    </form>
  );
}

function CodeField(props: FieldProps) {
  return (
    <TextField
      name="code"
      label="6 digit code"
      type="text"
      inputMode="numeric"
      pattern="[0-9]{6}"
      autoComplete="one-time-code"
      {...props}
    />
  );
}

/**
 * The identifier field. When the server offers passkeys, a passkey picked from the field's
 * autofill signs in directly, and a button asks for one explicitly. Both are shown to every
 * account, so they reveal nothing about which accounts have a passkey.
 */
function Identifier({ screen, ...props }: FieldProps & { screen: IdentifierScreen }) {
  const { send } = props;
  const challenge = screen.passkey?.challenge;
  // Not a dependency: the autofill request must survive re-renders, or the browser drops it.
  const onPasskey = useEffectEvent((passkey: Record<string, unknown>) => send({ passkey }));

  useEffect(() => {
    if (!challenge) return;
    void (async () => {
      if (!(await autofillAvailable())) return;
      const passkey = await getPasskey(challenge, { conditional: true });
      if (passkey) await onPasskey(passkey);
    })();
    return () => cancelPasskeyCeremony();
  }, [challenge]);

  async function askForPasskey() {
    if (!challenge) return;
    cancelPasskeyCeremony(); // one WebAuthn request at a time
    const passkey = await getPasskey(challenge);
    if (passkey) await send({ passkey });
  }

  return (
    <TextField
      name="identifier"
      label="Email"
      type="email"
      autoComplete={challenge ? "username webauthn" : "username"}
      defaultValue={screen.identifier}
      {...props}
    >
      {challenge && (
        <Button type="button" variant="outline" onClick={askForPasskey} disabled={props.pending}>
          Sign in with a passkey
        </Button>
      )}
    </TextField>
  );
}

function MethodPicker({ screen, send, pending }: FieldProps & { screen: MethodPickerScreen }) {
  const first = useRef<HTMLButtonElement>(null);
  useEffect(() => first.current?.focus(), []);
  return (
    <div className="grid gap-2" role="group" aria-label="Methods">
      {screen.options.map((id, i) => (
        <Button key={id} ref={i === 0 ? first : undefined} variant="outline" disabled={pending}
          onClick={() => send({ method: id })}>
          {methodLabel(id)}
        </Button>
      ))}
    </div>
  );
}

function Passkey({ screen, send, pending }: FieldProps & { screen: PasskeyScreen }) {
  const [failed, setFailed] = useState(false);
  const register = screen.register;

  async function run() {
    setFailed(false);
    const passkey = register ? await createPasskey(screen.challenge, register) : await getPasskey(screen.challenge);
    if (passkey) await send({ passkey });
    else setFailed(true);
  }

  return (
    <>
      {register && (
        <p className="text-sm text-muted-foreground">
          Next time, sign in with your fingerprint, face or device PIN instead of a password.
        </p>
      )}
      <Button onClick={run} disabled={pending} autoFocus>
        {register ? "Create a passkey" : "Use passkey"}
      </Button>
      {register && (
        <Button variant="link" onClick={() => send({ skip: true })} disabled={pending}>
          Not now
        </Button>
      )}
      {failed && <p className="text-sm text-muted-foreground">The passkey request was cancelled. Try again.</p>}
    </>
  );
}

function OtpEnroll({ screen, ...props }: FieldProps & { screen: OtpEnrollScreen }) {
  return (
    <>
      <p className="text-sm text-muted-foreground">
        Scan this code with your authenticator app, then enter the 6 digit code it shows.
      </p>
      <QrCode value={screen.uri} label="QR code for your authenticator app" />
      <p className="text-sm">
        Or enter this key: <span className="font-mono break-all">{screen.secret}</span>
      </p>
      <CodeField {...props} />
    </>
  );
}

function QrCode({ value, label }: { value: string; label: string }) {
  const { data, size } = qrEncode(value);
  const quiet = 4;
  let path = "";
  data.forEach((row, y) => row.forEach((dark, x) => {
    if (dark) path += `M${x + quiet} ${y + quiet}h1v1h-1z`;
  }));
  return (
    <svg role="img" aria-label={label} viewBox={`0 0 ${size + quiet * 2} ${size + quiet * 2}`}
      className="mx-auto size-48 rounded-md bg-white" shapeRendering="crispEdges">
      <path d={path} fill="black" />
    </svg>
  );
}

function Consent({ screen, send, pending }: FieldProps & { screen: ConsentScreen }) {
  return (
    <>
      <ul className="grid gap-1 text-sm">
        {screen.scopes.map((scope) => (
          <li key={scope}>{scopeLabel(scope)}</li>
        ))}
      </ul>
      <div className="grid grid-cols-2 gap-2">
        <Button variant="outline" onClick={() => send({ decision: "deny" })} disabled={pending}>
          Deny
        </Button>
        <Button onClick={() => send({ decision: "allow" })} disabled={pending} autoFocus>
          Allow
        </Button>
      </div>
    </>
  );
}
