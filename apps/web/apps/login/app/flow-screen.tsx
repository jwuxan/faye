"use client";

import type {
  AnswerRequest,
  CodeSentScreen,
  ConsentScreen,
  HandoffResponse,
  IdentifierScreen,
  MethodPickerScreen,
  OtpEnrollScreen,
  PasskeyScreen,
  PhoneEnrollScreen,
  PollRequest,
  QrApproveScreen,
  QrScreen,
  ResendRequest,
  ScreenProtocolResponse,
  ScreenResponse,
  SendRequest,
  SubmitRequest,
} from "@faye/contract";
import { Button } from "@faye/ui/components/button";
import { Checkbox } from "@faye/ui/components/checkbox";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@faye/ui/components/dialog";
import { CopyButton } from "@faye/ui/components/copy-button";
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from "@faye/ui/components/input-group";
import { InputOTP, InputOTPGroup, InputOTPSlot } from "@faye/ui/components/input-otp";
import { Label } from "@faye/ui/components/label";
import { Separator } from "@faye/ui/components/separator";
import { Spinner } from "@faye/ui/components/spinner";
import { brandStyle } from "@faye/ui/lib/brand-style";
import { cn } from "@faye/ui/lib/utils";
import {
  ChevronRight,
  CircleAlert,
  CircleCheck,
  Eye,
  EyeOff,
  Fingerprint,
  IdCard,
  KeyRound,
  LockKeyhole,
  type LucideIcon,
  Mail,
  MessageSquareText,
  RefreshCw,
  Smartphone,
  UserRound,
} from "lucide-react";
import Image from "next/image";
import { useRouter } from "next/navigation";
import {
  type CSSProperties,
  type FormEvent,
  type ReactNode,
  createContext,
  useContext,
  useEffect,
  useEffectEvent,
  useRef,
  useState,
} from "react";
import { encode as qrEncode } from "uqr";
import {
  errorMessage,
  methodLabel,
  scopeLabel,
  screenErrorMessage,
  terminalErrorMessage,
} from "../lib/messages";
import { isNavigable } from "../lib/redirect";
import { autofillAvailable, cancelPasskeyCeremony, createPasskey, getPasskey } from "../lib/webauthn";

type Payload = AnswerRequest["payload"];
/** A screen action (ADR 0032): answered by the server with the same screen, never moving the flow. */
type Action = Omit<ResendRequest, "nonce"> | Omit<SendRequest, "nonce"> | Omit<PollRequest, "nonce">;
type Act = (action: Action) => Promise<void>;

/**
 * Gives a host's handoff result to the page that opened this one in a popup, and only at the
 * configured origin: the result may be a one time code, so it never goes to an unknown origin.
 * The sign in screens refuse to be framed, so there is no parent to post to.
 */
function handOff(result: HandoffResponse["result"], embedOrigin: string | null): boolean {
  if (!embedOrigin || !window.opener) return false;
  (window.opener as Window).postMessage({ type: "faye:handoff", result }, embedOrigin);
  return true;
}
type Send = (payload: Payload) => Promise<void>;

/**
 * Renders whichever typed screen the server sent; the UI never sees the flow itself.
 *
 * @param embedOrigin the origin of the page that opened this one, for a host that hands off its
 *   result rather than redirecting; null posts nothing
 */
export function FlowScreen({ initial, embedOrigin }: { initial: ScreenResponse; embedOrigin: string | null }) {
  const router = useRouter();
  const [current, setCurrent] = useState(initial);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [handedOff, setHandedOff] = useState(false);
  const [approval, setApproval] = useState<"approved" | "refused" | null>(null);
  // Counts the screens the server sent: an action answers with the same screen, whose countdowns restart
  const [received, setReceived] = useState(0);
  const { screen } = current;

  // The address bar follows the screen the server sent, never the other way round (ADR 0029).
  // Replaced, not pushed: an answered screen's nonce is spent, so there is no going back to it.
  useEffect(() => {
    const url = `${current.path}?flow=${encodeURIComponent(current.flowId)}`;
    if (window.location.pathname + window.location.search !== url) window.history.replaceState(null, "", url);
  }, [current.path, current.flowId]);

  async function send(payload: Payload) {
    await post({ nonce: current.nonce, action: "submit", payload });
  }

  async function act(action: Action) {
    await post({ nonce: current.nonce, ...action });
  }

  async function post(request: SubmitRequest) {
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
        // A failed sign in stays on Faye: the host was told, and /error says so (ADR 0031)
        if (body.failed) {
          router.replace("/error?reason=failed");
          return;
        }
        if (isNavigable(body.redirectTo)) {
          window.location.assign(body.redirectTo);
          return;
        }
        setError(errorMessage("host_error"));
        setPending(false);
        return;
      }
      if ("result" in body) {
        // The phone's side of a QR code sign in: nothing to hand off, the other device continues (ADR 0032)
        const qr = (body.result as { qr?: unknown }).qr;
        if (qr === "approved" || qr === "refused") {
          setApproval(qr);
          setPending(false);
          return;
        }
        // Nowhere to deliver it: the person must start again from the app, not wait on "Done"
        if (!handOff(body.result, embedOrigin)) {
          setError("Sign in finished, but the app that started it could not be reached. Start again from the app.");
          setPending(false);
          return;
        }
        setHandedOff(true);
        setPending(false);
        return;
      }
      if ("error" in body) setError(errorMessage(body.error, body.reference));
      else {
        setCurrent(body);
        setReceived((n) => n + 1);
      }
    } catch {
      setError("Something went wrong. Try again.");
    }
    setPending(false);
  }

  if (approval) {
    return (
      <div className="grid w-full max-w-100 justify-items-center gap-3 text-center">
        {approval === "approved" ? (
          <CircleCheck className="size-10 text-primary" strokeWidth={1.75} aria-hidden />
        ) : (
          <CircleAlert className="size-10 text-destructive" strokeWidth={1.75} aria-hidden />
        )}
        <h1 className={heading}>{approval === "approved" ? "Approved" : "Not approved"}</h1>
        <p className="text-muted-foreground">
          {approval === "approved"
            ? "Return to your other device to continue."
            : "Nothing was signed in. Start again on your other device."}
        </p>
      </div>
    );
  }

  if (handedOff) {
    return (
      <div className="grid w-full max-w-100 justify-items-center gap-3 text-center">
        <CircleCheck className="size-10 text-primary" strokeWidth={1.75} aria-hidden />
        <h1 className={heading}>Done</h1>
        <p className="text-muted-foreground">You can return to the app.</p>
      </div>
    );
  }

  const failure = screen.errors?.[0];
  const message = error ?? (failure ? screenErrorMessage(failure.code, screen.attemptsLeft, screen.type) : null);
  const props = { send, pending, invalid: message !== null };

  const { branding } = current;
  const options = { showPassword: branding?.showPassword === true, signUp: branding?.signUp === true, style: brandStyle(branding) };
  return (
    <Options.Provider value={options}>
    <div className="grid w-full max-w-100 gap-8" style={brandStyle(branding)}>
      <div className="grid gap-5">
        {branding && (branding.name || branding.logoUrl) && (
          <div className="flex items-center justify-center gap-2.5">
            {/* unoptimized: the browser loads the logo itself; this server never fetches tenant URLs */}
            {branding.logoUrl ? (
              <Image src={branding.logoUrl} alt={branding.name ?? ""} width={144} height={36} unoptimized className="h-9 w-auto" referrerPolicy="no-referrer" />
            ) : (
              <span className="text-xl font-extrabold tracking-tight">{branding.name}</span>
            )}
          </div>
        )}
        {screen.type === "consent" ? (
          <ConsentHeader screen={screen} />
        ) : (
          <header className="grid justify-items-center gap-2.5 text-center">
            {/* The first screen leads with the tenant's name and the fields, as a store's sign in does */}
            <h1 className={screen.type === "identifier" ? "sr-only" : heading}>{title(current)}</h1>
            {screen.type !== "identifier" && screen.identifier && <IdentifierChip value={screen.identifier} />}
          </header>
        )}
      </div>
      {/* key remounts the content, so each new screen starts empty and takes focus */}
      <div key={current.nonce} className="grid gap-3">
        {screen.type === "identifier" &&
          (screen.password ? <IdentifierPassword screen={screen} {...props} /> : <Identifier screen={screen} {...props} />)}
        {screen.type === "password" && (
          <TextField name="password" label="Password" icon={LockKeyhole} type="password" autoComplete="current-password" submit="Sign in" {...props} />
        )}
        {screen.type === "otp" && <CodeField {...props} />}
        {screen.type === "otp_enroll" && <OtpEnroll screen={screen} {...props} />}
        {screen.type === "code_sent" && <CodeSent screen={screen} act={act} received={received} {...props} />}
        {screen.type === "phone_enroll" && <PhoneEnroll screen={screen} act={act} received={received} {...props} />}
        {screen.type === "qr" && <QrSignIn screen={screen} act={act} {...props} />}
        {screen.type === "qr_approve" && <QrApprove screen={screen} {...props} />}
        {screen.type === "method_picker" && <MethodPicker screen={screen} {...props} />}
        {screen.type === "passkey" && <Passkey screen={screen} {...props} />}
        {screen.type === "consent" && <Consent screen={screen} {...props} />}
        {screen.type === "error" && (
          <>
            <p className="text-center text-muted-foreground">{terminalErrorMessage(screen.code)}</p>
            <Button className={primary} onClick={() => send({})} disabled={pending}>
              Back to the app
            </Button>
          </>
        )}
        <p id={ERROR_ID} className="flex items-start gap-1.5 text-sm text-pretty text-destructive empty:hidden" role="alert" aria-live="assertive">
          {message && <CircleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />}
          {message}
        </p>
      </div>
      {screen.type === "consent" && (
        <p className="-mt-2 text-center text-xs text-muted-foreground">
          Client ID <span className="font-mono">{screen.client.id}</span>
        </p>
      )}
    </div>
    {branding?.name && <Footer name={branding.name} />}
    </Options.Provider>
  );
}

/** What the tenant turned on for its screens (ADR 0033). */
const Options = createContext<{ showPassword: boolean; signUp: boolean; style: CSSProperties | undefined }>({
  showPassword: false,
  signUp: false,
  style: undefined,
});

/** At the foot of every screen of a tenant with a name. */
export function Footer({ name }: { name: string }) {
  return (
    <footer className="mt-auto pt-16 text-center text-xs text-muted-foreground">
      © {name}. All rights reserved.
    </footer>
  );
}

/** The screen title. */
const heading = "text-xl font-bold tracking-tight text-balance";
/** The one strong action of a screen: full width, tall, bold. */
const focus = "focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background";
export const primary = cn(
  "h-12 w-full rounded-lg text-base font-bold shadow-press hover:bg-primary-hover active:translate-y-px",
  "disabled:bg-primary-disabled disabled:opacity-100 disabled:shadow-none",
  focus,
);
/** Every other action: outlined, in the primary color. */
const secondary = cn("h-12 w-full rounded-lg border-input text-base font-semibold text-primary shadow-none hover:bg-accent hover:text-primary active:translate-y-px", focus);
/** Shown in a submit button while the server answers. */
function Busy({ pending }: { pending: boolean }) {
  return pending ? <Spinner className="size-4.5" aria-hidden /> : null;
}
/** The error under a screen's fields, which the fields name as their description. */
const ERROR_ID = "screen-error";

/** Who is signing in, under the title of every screen after the first. */
function IdentifierChip({ value }: { value: string }) {
  return (
    <span className="inline-flex max-w-full items-center gap-1.5 rounded-full border bg-muted py-1 pr-3 pl-2 text-sm text-muted-foreground">
      <UserRound className="size-3.5 shrink-0" strokeWidth={2} aria-hidden />
      <span className="truncate font-mono">{value}</span>
    </span>
  );
}

/** A tall field with its icon in a cell of its own; the label is the placeholder, and named for assistive tech. */
function IconInput({
  icon: Icon,
  label,
  className,
  reveal = false,
  ...props
}: { icon: LucideIcon; label: string; reveal?: boolean } & React.ComponentProps<"input"> & { id: string }) {
  // A password the person may show: the field turns to text while shown, never on its own
  const [shown, setShown] = useState(false);
  return (
    <>
      <Label htmlFor={props.id} className="sr-only">
        {label}
      </Label>
      <InputGroup className="h-12 overflow-hidden rounded-lg bg-background shadow-none has-[[data-slot=input-group-control]:focus-visible]:ring-3">
        <InputGroupAddon className="h-full w-12 self-stretch border-r bg-muted pr-0 pl-0 text-muted-foreground">
          <Icon className="size-4.5" strokeWidth={1.75} />
        </InputGroupAddon>
        <InputGroupInput
          placeholder={label}
          className={cn("h-full px-3.5 text-base md:text-base placeholder:text-muted-foreground", className)}
          {...props}
          type={reveal && shown ? "text" : props.type}
        />
        {reveal && (
          <InputGroupAddon align="inline-end" className="pr-2">
            <InputGroupButton
              size="icon-sm"
              aria-label={shown ? "Hide password" : "Show password"}
              aria-pressed={shown}
              aria-controls={props.id}
              onClick={() => setShown(!shown)}
              className="size-9 rounded-md text-muted-foreground hover:text-foreground"
            >
              {shown ? <EyeOff className="size-4.5" strokeWidth={1.75} /> : <Eye className="size-4.5" strokeWidth={1.75} />}
            </InputGroupButton>
          </InputGroupAddon>
        )}
      </InputGroup>
    </>
  );
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
    case "code_sent":
      return screen.channel === "sms" ? "Check your phone" : "Check your email";
    case "phone_enroll":
      return "Add a phone number";
    case "qr":
      return "Scan with your phone";
    case "qr_approve":
      return screen.client?.name ? `Sign in to ${screen.client.name}?` : "Approve this sign in?";
    case "consent":
      return `${screen.client.name ?? screen.client.id} wants to access your account`;
    case "error":
      return "Can't sign in";
  }
}

type FieldProps = { send: Send; pending: boolean; invalid: boolean };

/** One labelled field and its submit button; focus moves to the field on each new screen. */
function TextField({
  name,
  label,
  icon,
  type,
  autoComplete,
  defaultValue,
  inputMode,
  pattern,
  send,
  pending,
  invalid,
  submit,
  extra,
  beforeSubmit,
  children,
}: FieldProps & {
  name: "identifier" | "password" | "code";
  label: string;
  icon: LucideIcon;
  type: string;
  autoComplete: string;
  defaultValue?: string;
  inputMode?: "numeric";
  pattern?: string;
  /** The submit button's label. */
  submit: string;
  /** Sent with the field's value (remember, when the screen offers it). */
  extra?: { remember: boolean };
  /** Between the field and the submit button. */
  beforeSubmit?: ReactNode;
  children?: ReactNode;
}) {
  const { showPassword } = useContext(Options);
  const input = useRef<HTMLInputElement>(null);
  // autoFocus does not apply to server rendered markup
  useEffect(() => input.current?.focus(), []);

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = String(new FormData(event.currentTarget).get(name) ?? "");
    void send(
      name === "identifier" ? { identifier: value, ...extra } : name === "password" ? { password: value } : { code: value },
    );
  }

  return (
    <form onSubmit={onSubmit} className="grid gap-3">
      <div className="grid">
        <IconInput
          icon={icon}
          label={label}
          ref={input}
          id={name}
          name={name}
          type={type}
          autoComplete={autoComplete}
          defaultValue={defaultValue}
          inputMode={inputMode}
          pattern={pattern}
          className={name === "code" ? "font-mono tracking-widest" : undefined}
          reveal={name === "password" && showPassword}
          aria-invalid={invalid || undefined}
          aria-describedby={invalid ? ERROR_ID : undefined}
          readOnly={pending}
          required
        />
      </div>
      {beforeSubmit}
      <Button type="submit" className={primary} disabled={pending} aria-busy={pending}>
        <Busy pending={pending} />
        {submit}
      </Button>
      {children}
    </form>
  );
}

/** Six digit boxes for a TOTP code; a complete code submits itself, as does a pasted one. */
function CodeField({ send, pending, invalid }: FieldProps) {
  const [code, setCode] = useState("");
  const input = useRef<HTMLInputElement>(null);
  // autoFocus does not apply to server rendered markup
  useEffect(() => input.current?.focus(), []);

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void send({ code });
  }

  return (
    <form onSubmit={onSubmit} className="grid gap-4">
      <div className="grid gap-1.5">
        <Label htmlFor="code" className="text-sm font-normal text-muted-foreground">
          6 digit code
        </Label>
        <InputOTP
          ref={input}
          containerClassName="w-full"
          id="code"
          name="code"
          maxLength={6}
          pattern="^[0-9]*$"
          value={code}
          onChange={setCode}
          onComplete={(value: string) => void send({ code: value })}
          disabled={pending}
          aria-invalid={invalid || undefined}
          aria-describedby={invalid ? ERROR_ID : undefined}
          required
        >
          <InputOTPGroup className="grid w-full grid-cols-6 gap-2">
            {[0, 1, 2, 3, 4, 5].map((i) => (
              <InputOTPSlot
                key={i}
                index={i}
                aria-invalid={invalid || undefined}
                className="h-12 w-full rounded-lg border border-input font-mono text-xl font-semibold shadow-none first:rounded-l-lg last:rounded-r-lg sm:h-13"
              />
            ))}
          </InputOTPGroup>
        </InputOTP>
      </div>
      <Button type="submit" className={primary} disabled={pending || code.length < 6} aria-busy={pending}>
        <Busy pending={pending} />
        Verify
      </Button>
    </form>
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
  const [remember, setRemember] = useState(false);
  // Sent with every answer of a screen that offers it, so the box always means what it shows
  const extra = screen.remember ? { remember } : undefined;
  // Not a dependency: the autofill request must survive re-renders, or the browser drops it.
  const onPasskey = useEffectEvent((passkey: Record<string, unknown>) => send({ passkey, ...extra }));

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
    if (passkey) await send({ passkey, ...extra });
  }

  return (
    <TextField
      name="identifier"
      label="Email"
      icon={Mail}
      type="email"
      autoComplete={challenge ? "username webauthn" : "username"}
      defaultValue={screen.identifier}
      submit="Continue"
      extra={extra}
      beforeSubmit={screen.remember && <RememberMe checked={remember} onChange={setRemember} disabled={props.pending} />}
      {...props}
    >
      <MoreWays>
        {challenge && (
          <Button type="button" variant="outline" className={secondary} onClick={askForPasskey} disabled={props.pending}>
            <Fingerprint className="size-4.5" strokeWidth={1.75} />
            Sign in with a passkey
          </Button>
        )}
      </MoreWays>
    </TextField>
  );
}

/** The identifier and the password in one form, when the flow asks for both at once. */
function IdentifierPassword({ screen, send, pending, invalid }: FieldProps & { screen: IdentifierScreen }) {
  const { showPassword } = useContext(Options);
  const [remember, setRemember] = useState(false);
  const first = useRef<HTMLInputElement>(null);
  useEffect(() => first.current?.focus(), []);

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    void send({
      identifier: String(form.get("identifier") ?? ""),
      password: String(form.get("password") ?? ""),
      ...(screen.remember ? { remember } : {}),
    });
  }

  return (
    <form onSubmit={onSubmit} className="grid gap-3">
      <div className="grid gap-2">
        <IconInput icon={Mail} label="Email" ref={first} id="identifier" name="identifier" type="email" autoComplete="username"
          defaultValue={screen.identifier} aria-invalid={invalid || undefined} aria-describedby={invalid ? ERROR_ID : undefined} readOnly={pending} required />
        <IconInput icon={LockKeyhole} label="Password" id="password" name="password" type="password" autoComplete="current-password"
          reveal={showPassword} aria-invalid={invalid || undefined} aria-describedby={invalid ? ERROR_ID : undefined} readOnly={pending} required />
      </div>
      {screen.remember && <RememberMe checked={remember} onChange={setRemember} disabled={pending} />}
      <Button type="submit" className={primary} disabled={pending} aria-busy={pending}>
        <Busy pending={pending} />
        Sign in
      </Button>
      <MoreWays />
    </form>
  );
}

/** "Keep me signed in": the person's choice, sent with the answer (ADR 0033). */
function RememberMe({ checked, onChange, disabled }: { checked: boolean; onChange: (on: boolean) => void; disabled: boolean }) {
  return (
    <div className="flex items-center gap-2 py-0.5">
      <Checkbox
        id="remember"
        checked={checked}
        onCheckedChange={(v) => onChange(v === true)}
        disabled={disabled}
        className="size-4.5 rounded-xs border-input data-[state=checked]:border-primary"
      />
      <Label htmlFor="remember" className="cursor-pointer text-base font-normal text-muted-foreground">
        Keep me signed in
      </Label>
    </div>
  );
}

/**
 * Below the first screen's main action, under a rule: other ways in (a passkey) and, when the
 * tenant turns it on, sign up. Nothing renders when there is neither.
 */
function MoreWays({ children }: { children?: ReactNode }) {
  const { signUp } = useContext(Options);
  if (!children && !signUp) return null;
  return (
    <>
      <Separator className="my-3" />
      {children}
      {signUp && <SignUp />}
    </>
  );
}

/** Sign up is not supported yet: the button says so instead of doing nothing (ADR 0033). */
function SignUp() {
  // The dialog renders in a portal, outside the column that carries the tenant's color and radius
  const { style } = useContext(Options);
  return (
    <Dialog>
      <DialogTrigger asChild>
        <Button type="button" variant="outline" className={secondary}>
          Sign up
        </Button>
      </DialogTrigger>
      <DialogContent style={style} className="max-w-90 gap-5 rounded-xl p-6 sm:max-w-90" showCloseButton={false}>
        <DialogHeader className="gap-2 text-left">
          <DialogTitle className="text-lg font-bold">Sign up isn&apos;t available yet</DialogTitle>
          <DialogDescription className="text-base leading-5">
            You can&apos;t create an account here yet. If you already have one, sign in with it.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <DialogClose asChild>
            <Button className={primary}>OK</Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function MethodPicker({ screen, send, pending }: FieldProps & { screen: MethodPickerScreen }) {
  const first = useRef<HTMLButtonElement>(null);
  useEffect(() => first.current?.focus(), []);
  return (
    <div className="grid gap-2" role="group" aria-label="Methods">
      {screen.options.map((id, i) => (
        <Button key={id} ref={i === 0 ? first : undefined} variant="outline" disabled={pending}
          className="group h-14 w-full justify-between rounded-lg border-input px-4 text-base font-semibold shadow-none hover:border-primary/50 hover:bg-accent"
          onClick={() => send({ method: id })}>
          {methodLabel(id)}
          <ChevronRight className="size-4.5 text-muted-foreground transition-transform group-hover:translate-x-0.5 group-hover:text-primary" />
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
      <span className="mx-auto mb-1 grid size-16 place-items-center rounded-2xl bg-accent text-primary" aria-hidden>
        <Fingerprint className="size-8" strokeWidth={1.5} />
      </span>
      {register && (
        <p className="text-center text-muted-foreground">
          Next time, sign in with your fingerprint, face or device PIN instead of a password.
        </p>
      )}
      <Button className={primary} onClick={run} disabled={pending} autoFocus>
        {register ? "Create a passkey" : "Use passkey"}
      </Button>
      {/* A step that requires a passkey offers no way around it (ADR 0038) */}
      {register && !screen.required && (
        <Button variant="ghost" className="h-10 text-base text-muted-foreground hover:bg-transparent hover:text-foreground" onClick={() => send({ skip: true })} disabled={pending}>
          Not now
        </Button>
      )}
      {failed && <p className="text-center text-sm text-muted-foreground">The passkey request was cancelled. Try again.</p>}
    </>
  );
}

function OtpEnroll({ screen, ...props }: FieldProps & { screen: OtpEnrollScreen }) {
  return (
    <>
      <p className="text-center text-muted-foreground">
        Scan this code with your authenticator app, then enter the 6 digit code it shows.
      </p>
      <div className="mx-auto rounded-xl border p-2">
        <QrCode value={screen.uri} label="QR code for your authenticator app" />
      </div>
      <div className="grid gap-1.5">
        <p className="text-sm text-muted-foreground">Or enter this key:</p>
        <div className="flex items-center gap-1 rounded-lg border bg-muted py-1.5 pr-1.5 pl-3.5">
          <code className="min-w-0 flex-1 font-mono text-sm break-all">{screen.secret}</code>
          <CopyButton value={screen.secret} label="Copy key" />
        </div>
      </div>
      <Separator className="my-2" />
      <CodeField {...props} />
    </>
  );
}

/** Where the code went, and a button that sends another once the server allows it (ADR 0032). */
function CodeSent({ screen, act, received, ...props }: FieldProps & { screen: CodeSentScreen; act: Act; received: number }) {
  const Icon = screen.channel === "sms" ? MessageSquareText : Mail;
  return (
    <>
      <span className="mx-auto mb-1 grid size-16 place-items-center rounded-2xl bg-accent text-primary" aria-hidden>
        <Icon className="size-8" strokeWidth={1.5} />
      </span>
      <p className="text-center text-muted-foreground">
        We sent a 6 digit code to <span className="font-mono text-foreground">{screen.to}</span>. It expires in{" "}
        {Math.max(1, Math.ceil(screen.expiresIn / 60))} {Math.ceil(screen.expiresIn / 60) === 1 ? "minute" : "minutes"}.
      </p>
      <CodeField {...props} />
      <Resend key={received} after={screen.resendIn} pending={props.pending} onResend={() => act({ action: "resend", payload: {} })} />
    </>
  );
}

/**
 * Counts down `after` seconds from when it mounts (its caller remounts it for each screen the server
 * sends), then offers to send another code; with `after` absent, no more can be sent.
 */
function Resend({ after, pending, onResend }: { after: number | undefined; pending: boolean; onResend: () => void }) {
  const [left, setLeft] = useState(after ?? 0);
  useEffect(() => {
    if (!after) return;
    const started = Date.now();
    const timer = window.setInterval(() => {
      const remaining = Math.max(0, after - Math.floor((Date.now() - started) / 1000));
      setLeft(remaining);
      if (remaining === 0) window.clearInterval(timer);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [after]);
  if (after === undefined) {
    return <p className="text-center text-sm text-muted-foreground">No more codes can be sent for this sign in.</p>;
  }
  return (
    <Button variant="ghost" className="h-10 text-base text-muted-foreground hover:bg-transparent hover:text-foreground" onClick={onResend} disabled={pending || left > 0}>
      {left > 0 ? `Send a new code in ${left}s` : "Send a new code"}
    </Button>
  );
}

/**
 * QR code sign in on the device signing in (ADR 0032): the code and its number; every 2 seconds a
 * poll asks whether the phone approved, and once it is decided the screen is answered.
 */
function QrSignIn({ screen, act, send, pending }: FieldProps & { screen: QrScreen; act: Act }) {
  // Each poll answers with a new screen object, which schedules the next; a decided one is answered once
  const step = useEffectEvent(() => {
    if (screen.status !== "pending") void send({});
    else void act({ action: "poll", payload: {} });
  });
  useEffect(() => {
    const timer = window.setTimeout(step, screen.status === "pending" ? 2000 : 0);
    return () => window.clearTimeout(timer);
  }, [screen]);
  return (
    <>
      <p className="text-center text-muted-foreground">
        Scan this code with your phone&apos;s camera and sign in there. When asked, pick the number shown here.
      </p>
      <div className="mx-auto rounded-xl border p-2">
        <QrCode value={screen.url} label="QR code to sign in with your phone" />
      </div>
      <p className="text-center">
        <span className="text-sm text-muted-foreground">Your number</span>
        <span className="block font-mono text-xl font-bold tracking-widest" aria-live="polite">
          {screen.number}
        </span>
      </p>
      <p className="flex items-center justify-center gap-2 text-sm text-muted-foreground" role="status">
        <Spinner aria-hidden />
        {pending ? "Checking…" : "Waiting for your phone"}
      </p>
      <p className="text-center text-xs text-muted-foreground">
        Only approve a code you scanned yourself, on a screen in front of you.
      </p>
    </>
  );
}

/** On the phone: which device asks, and the number it shows among three. */
function QrApprove({ screen, send, pending }: FieldProps & { screen: QrApproveScreen }) {
  return (
    <>
      {screen.device && (
        <p className="text-center text-muted-foreground">
          A sign in is waiting on <span className="font-medium text-foreground">{screen.device}</span>.
        </p>
      )}
      <p className="text-center text-muted-foreground">Pick the number that device shows. If you did not start it, pick none and close this page.</p>
      <div className="grid grid-cols-3 gap-3" role="group" aria-label="Numbers">
        {screen.numbers.map((n) => (
          <Button key={n} variant="outline" className={cn(secondary, "font-mono text-xl")} disabled={pending} onClick={() => void send({ number: n })}>
            {n}
          </Button>
        ))}
      </div>
    </>
  );
}

/** A phone number as the server takes it (E.164). */
const E164 = /^\+[1-9][0-9]{6,14}$/;

/** A number with its country code, texted a code; the code proves the person holds it. */
function PhoneEnroll({ screen, act, received, ...props }: FieldProps & { screen: PhoneEnrollScreen; act: Act; received: number }) {
  const [phone, setPhone] = useState("");
  const [editing, setEditing] = useState(screen.to === undefined);
  const [malformed, setMalformed] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (editing) input.current?.focus();
  }, [editing]);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const typed = phone.replace(/[\s().-]/g, "");
    // The server takes only E.164; say so here rather than send what it refuses
    setMalformed(!E164.test(typed));
    if (!E164.test(typed)) return;
    await act({ action: "send", payload: { phone: typed } });
    setEditing(false);
  }

  if (editing || screen.to === undefined) {
    return (
      <form onSubmit={onSubmit} className="grid gap-3">
        <p className="text-center text-muted-foreground">We&apos;ll text a code to this number. Include the country code.</p>
        <IconInput
          icon={Smartphone}
          label="Phone number, e.g. +1 555 123 4567"
          ref={input}
          id="phone"
          name="phone"
          type="tel"
          autoComplete="tel"
          value={phone}
          onChange={(e) => setPhone(e.target.value)}
          aria-invalid={props.invalid || malformed || undefined}
          aria-describedby={malformed ? "phone-hint" : props.invalid ? ERROR_ID : undefined}
          readOnly={props.pending}
          required
        />
        {malformed && (
          <p id="phone-hint" className="text-sm text-destructive" role="alert">
            Enter the number with its country code, starting with +.
          </p>
        )}
        <Button type="submit" className={primary} disabled={props.pending} aria-busy={props.pending}>
          <Busy pending={props.pending} />
          Text me a code
        </Button>
        {!screen.required && (
          <Button type="button" variant="ghost" className="h-10 text-base text-muted-foreground hover:bg-transparent hover:text-foreground" onClick={() => void props.send({ skip: true })} disabled={props.pending}>
            Not now
          </Button>
        )}
      </form>
    );
  }
  return (
    <>
      <p className="text-center text-muted-foreground">
        We texted a 6 digit code to <span className="font-mono text-foreground">{screen.to}</span>.
      </p>
      <CodeField {...props} />
      <Resend
        key={received}
        after={screen.resendIn}
        pending={props.pending}
        onResend={() => act({ action: "send", payload: { phone: phone.replace(/[\s().-]/g, "") } })}
      />
      <Button variant="ghost" className="h-10 text-base text-muted-foreground hover:bg-transparent hover:text-foreground" onClick={() => setEditing(true)} disabled={props.pending}>
        Use a different number
      </Button>
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
      className="size-44 bg-background" shapeRendering="crispEdges">
      <path d={path} className="fill-foreground" />
    </svg>
  );
}

/** Who is asking for access to whose account: the application's initial beside the person's. */
function ConsentHeader({ screen }: { screen: ConsentScreen }) {
  const name = screen.client.name ?? screen.client.id;
  return (
    <header className="grid justify-items-center gap-3 text-center">
      <div className="mb-1 flex items-center gap-3" aria-hidden>
        <span className="grid size-14 place-items-center rounded-2xl bg-primary text-xl font-bold text-primary-foreground uppercase shadow-lift">
          {name.charAt(0)}
        </span>
        <span className="flex gap-1">
          {[0, 1, 2].map((i) => (
            <span key={i} className="size-1 rounded-full bg-muted-foreground/40" />
          ))}
        </span>
        <span className="grid size-14 place-items-center rounded-full border bg-muted text-muted-foreground">
          <UserRound className="size-6" strokeWidth={1.75} />
        </span>
      </div>
      <h1 className={heading}>{name} wants to access your account</h1>
      {screen.identifier && <IdentifierChip value={screen.identifier} />}
    </header>
  );
}

const SCOPE_ICONS: Record<string, LucideIcon> = { openid: UserRound, profile: IdCard, email: Mail, offline_access: RefreshCw };

function Consent({ screen, send, pending }: FieldProps & { screen: ConsentScreen }) {
  const name = screen.client.name ?? screen.client.id;
  return (
    <>
      <div className="grid gap-2">
        <p className="text-base font-semibold">This will allow {name} to:</p>
        <ul className="divide-y rounded-xl border">
          {screen.scopes.map((scope) => {
            const { label, detail } = scopeLabel(scope);
            const Icon = SCOPE_ICONS[scope] ?? KeyRound;
            return (
              <li key={scope} className="flex items-start gap-3 px-4 py-3">
                <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-accent text-primary">
                  <Icon className="size-4" strokeWidth={2} />
                </span>
                <span className="grid gap-0.5">
                  <span className="text-base font-semibold">{label}</span>
                  {detail && <span className="text-sm text-muted-foreground">{detail}</span>}
                </span>
              </li>
            );
          })}
        </ul>
      </div>
      <p className="text-sm text-muted-foreground">
        Only allow this if you trust {name}. It will receive the information listed above.
      </p>
      <div className="mt-1 grid grid-cols-2 gap-2">
        <Button variant="outline" className={cn(secondary, "text-foreground hover:text-foreground")} onClick={() => send({ decision: "deny" })} disabled={pending}>
          Cancel
        </Button>
        <Button className={primary} onClick={() => send({ decision: "allow" })} disabled={pending} autoFocus>
          Allow
        </Button>
      </div>
    </>
  );
}
