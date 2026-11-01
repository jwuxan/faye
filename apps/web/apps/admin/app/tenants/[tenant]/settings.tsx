"use client";

import { Badge } from "@faye/ui/components/badge";
import { Button } from "@faye/ui/components/button";
import { Checkbox } from "@faye/ui/components/checkbox";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@faye/ui/components/field";
import { Input } from "@faye/ui/components/input";
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from "@faye/ui/components/input-group";
import { Separator } from "@faye/ui/components/separator";
import { Slider } from "@faye/ui/components/slider";
import { Switch } from "@faye/ui/components/switch";
import { brandStyle, DEFAULT_PRIMARY } from "@faye/ui/lib/brand-style";
import { cn } from "@faye/ui/lib/utils";
import { CircleX, Eye, Lock, LockKeyhole, Mail, RotateCcw } from "lucide-react";
import { type ReactNode, useState } from "react";
import { api, type BlockDefaults as BlockDefaultsValue, type Branding, type Settings as TenantSettings, type SettingsView, type Validation } from "../../../lib/api";
import { PALETTE } from "../../../lib/flow";
import { type FlowLimits, type FlowLimitsView, fromMinutes, kindLabel, limitProblems, toMinutes, withLimit } from "../../../lib/flow-settings";
import { PageHeader } from "../../shell";
import { Help, PaneBar, PanelSection } from "../../ui";
import { useDraft } from "./draft";

/** Edits a copy of the tenant's settings; Save sends them all, and the server refuses a save the live flow would fail. */
function useSettingsForm() {
  const d = useDraft();
  const view = d.settings;
  const [edited, setEdited] = useState<TenantSettings | null>(null);
  const [problems, setProblems] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const s = edited ?? view?.settings ?? null;
  const dirty = !!view && !!edited && JSON.stringify(edited) !== JSON.stringify(view.settings);

  const save = async () => {
    if (!edited) return;
    setProblems([]);
    setSaving(true);
    try {
      const { status, body } = await api<SettingsView & Validation & { error?: string }>(`tenants/${d.tenant}/settings`, { method: "PUT", json: edited });
      if (status === 200) {
        d.setSettings(body);
        setEdited(null);
      } else if (status === 422) setProblems(body.errors.map((e) => e.message));
      else setProblems([body.error ?? `HTTP ${status}`]);
    } catch (e) {
      d.fail(e);
    }
    setSaving(false);
  };
  const discard = () => {
    setEdited(null);
    setProblems([]);
  };
  return { d, view, s, set: setEdited, dirty, save, discard, problems, saving };
}

type Form = ReturnType<typeof useSettingsForm>;

function SettingsFrame({ title, description, form, children }: { title: string; description?: string; form: Form; children: ReactNode }) {
  return (
    <div className="flex h-full min-h-0 flex-col">
      <PageHeader tenant={form.d.tenant} title={title}>
        {form.view && (
          <Badge variant="outline" className="gap-1.5 font-normal text-muted-foreground">
            <span className={cn("size-1.5 rounded-full", form.view.saved ? "bg-accent-ui" : "bg-border")} />
            {form.view.saved ? "Saved in console" : "From server config"}
          </Badge>
        )}
        {description && <Help>{description}</Help>}
      </PageHeader>
      <div className="min-h-0 flex-1 overflow-x-hidden overflow-y-auto">
        {form.s ? (
          children
        ) : (
          <p className="p-4 text-muted-foreground">Loading…</p>
        )}
        {form.problems.length > 0 && (
          <section className="border-b px-4 pt-4 pb-5">
            <ul className="grid max-w-3xl gap-px overflow-hidden rounded-md" role="alert">
              {form.problems.map((p, i) => (
                <li key={i} className="flex gap-2 bg-danger/8 px-3 py-2 text-xs">
                  <CircleX className="mt-0.5 size-3.5 shrink-0 text-danger" />
                  {p}
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>
      <div
        className={cn(
          "flex h-12 shrink-0 items-center gap-2 border-t bg-background px-4 transition-[margin,opacity] duration-200",
          form.dirty ? "opacity-100" : "pointer-events-none -mb-12 opacity-0",
        )}
        aria-hidden={!form.dirty}
      >
        <span className="text-sm text-muted-foreground">Unsaved changes</span>
        <Button variant="outline" size="sm" className="ml-auto" onClick={form.discard} tabIndex={form.dirty ? 0 : -1}>
          Discard
        </Button>
        <Button size="sm" onClick={form.save} disabled={form.saving} tabIndex={form.dirty ? 0 : -1}>
          {form.saving ? "Saving…" : "Save changes"}
        </Button>
      </div>
    </div>
  );
}

/** Tenant policy: MFA, which handlers and screens flows may use, and the egress the operator granted. */
export function GeneralSettings() {
  const form = useSettingsForm();
  const { s, set, d, view } = form;
  return (
    <SettingsFrame title="General" description="Every flow of this tenant is validated against these settings. Save is rejected if the live version would fail them." form={form}>
      {s && (
        <>
          <PanelSection title="Sign in policy" contentClassName="max-w-3xl">
            <div className="divide-y rounded-lg border">
              <Option
                id="mfa"
                label="Require MFA"
                description="Successful sign ins must verify two factors (faye:2fa)."
                control={<Switch id="mfa" checked={s.mfaRequired} onCheckedChange={(mfaRequired) => set({ ...s, mfaRequired })} />}
              />
            </div>
          </PanelSection>
          <BlockDefaults
            value={s.blocks ?? {}}
            onChange={(blocks) => {
              // Empty is no defaults, as the server sends it
              const { blocks: _, ...rest } = s;
              void _;
              set(Object.keys(blocks).length === 0 ? rest : { ...s, blocks });
            }}
          />
          <Allowlist
            title="Handlers"
            description="Handlers flows may call. A flow using any other fails validation."
            items={d.catalog?.handlers.map((h) => ({ name: h.name, note: h.amr.filter((a) => a !== "user").join(" · ") || (h.bindsSubject ? "binds subject" : "") })) ?? []}
            value={s.handlers}
            onChange={(handlers) => set({ ...s, handlers })}
          />
          <Allowlist
            title="Screens"
            description="Screens flows may show. A flow using any other fails validation."
            items={d.catalog?.screens.map((x) => ({ name: x.type, note: x.options.join(" · ") })) ?? []}
            value={s.screens}
            onChange={(screens) => set({ ...s, screens })}
          />
          <PanelSection
            title="Egress"
            help={
              <>
                Named HTTP targets this tenant’s hooks may call. Set in <code className="font-mono">faye.tenants.{d.tenant}.egress</code> in the server config; read only here.
              </>
            }
            contentClassName="max-w-3xl"
          >
            <div className="flex flex-wrap items-center gap-1.5">
              <Lock className="size-3.5 text-muted-foreground" />
              {(view?.egress.length ?? 0) > 0 ? (
                view!.egress.map((t) => (
                  <Badge key={t} variant="secondary" className="font-mono font-normal">
                    {t}
                  </Badge>
                ))
              ) : (
                <span className="text-xs text-muted-foreground">None</span>
              )}
            </div>
          </PanelSection>
        </>
      )}
    </SettingsFrame>
  );
}

/**
 * Defaults for the blocks' settings (ADR 0030): what a step gets unless its flow says otherwise
 * under `with`. Empty keeps the block's own. Applied when a flow is next validated or published.
 */
function BlockDefaults({ value, onChange }: { value: BlockDefaultsValue; onChange: (v: BlockDefaultsValue) => void }) {
  const put = (id: string, name: string, v: string | number | boolean | undefined) => {
    const block = { ...(value[id] ?? {}) };
    if (v === undefined) delete block[name];
    else block[name] = v;
    const next = { ...value };
    if (Object.keys(block).length === 0) delete next[id];
    else next[id] = block;
    onChange(next);
  };
  const blocks = PALETTE.filter((b) => b.id !== undefined && b.settings?.attempts !== undefined);
  const identifier = PALETTE.find((b) => b.role === "identifier");
  return (
    <PanelSection title="Block defaults" help="Used where a flow sets no value. Applies at the next validation or publish; published versions keep their values." contentClassName="max-w-3xl">
      <div className="divide-y rounded-lg border">
        {blocks.map((b) => (
          <Option
            key={b.id}
            id={`attempts-${b.id}`}
            label={`${b.name} attempts${b.role === "identifier" ? " (with password)" : ""}`}
            control={
              <Input
                id={`attempts-${b.id}`}
                type="number"
                min={1}
                className="h-8 w-20 font-mono text-xs"
                placeholder={String(b.settings!.attempts)}
                value={value[b.id!]?.attempts === undefined ? "" : String(value[b.id!]!.attempts)}
                onChange={(e) => {
                  const n = Number(e.target.value);
                  put(b.id!, "attempts", e.target.value === "" || !Number.isInteger(n) || n < 1 ? undefined : n);
                }}
              />
            }
          />
        ))}
        {identifier?.id && (
          <Option
            id="passkey-autofill"
            label="Passkey autofill"
            control={<Switch id="passkey-autofill" checked={value[identifier.id]?.passkey === true} onCheckedChange={(on) => put(identifier.id!, "passkey", on ? true : undefined)} />}
          />
        )}
      </div>
    </PanelSection>
  );
}

function Allowlist({
  title,
  description,
  items,
  value,
  onChange,
}: {
  title: string;
  description: string;
  items: { name: string; note: string }[];
  value: string[] | null;
  onChange: (v: string[] | null) => void;
}) {
  const all = items.map((i) => i.name);
  const allowed = value ?? all;
  return (
    <PanelSection
      title={title}
      help={description}
      action={
        <span className="flex items-center gap-2">
          <Badge variant="outline" className="h-5 font-normal tabular-nums">{value === null ? "All allowed" : `${value.length} of ${all.length} allowed`}</Badge>
          {value !== null && (
            <Button variant="ghost" size="xs" className="text-muted-foreground" onClick={() => onChange(null)}>
              <RotateCcw />
              Allow all
            </Button>
          )}
        </span>
      }
      contentClassName="max-w-3xl"
    >
      <div className="grid overflow-hidden rounded-lg border sm:grid-cols-2">
        {items.map((item, i) => {
          const on = allowed.includes(item.name);
          const id = `${title}-${item.name}`;
          return (
            <Field
              key={item.name}
              orientation="horizontal"
              className={cn("gap-3 px-4 py-3 transition-colors hover:bg-accent/50", i % 2 === 0 && "sm:border-r", i < items.length - (items.length % 2 === 0 ? 2 : 1) && "border-b")}
            >
              <Checkbox
                id={id}
                checked={on}
                onCheckedChange={(c) => {
                  const next = c ? [...allowed, item.name] : allowed.filter((v) => v !== item.name);
                  onChange(next.length === all.length ? null : next);
                }}
              />
              <FieldContent className="min-w-0 gap-0.5">
                <FieldLabel htmlFor={id} className={cn("font-mono text-xs font-normal", !on && "text-muted-foreground line-through")}>
                  {item.name}
                </FieldLabel>
                {item.note && <span className="truncate font-mono text-xs text-muted-foreground">{item.note}</span>}
              </FieldContent>
            </Field>
          );
        })}
      </div>
    </PanelSection>
  );
}

const DEFAULT_RADIUS = 6;

/** The tenant's login screens: its brand, and the options they offer, with a live preview (ADR 0033). */
export function LoginPageSettings() {
  const form = useSettingsForm();
  const { s, set } = form;
  const branding: Branding = s?.branding ?? {};
  const patch = (p: Partial<Branding>) => {
    if (!s) return;
    const next = { ...branding, ...p };
    for (const k of Object.keys(p) as (keyof Branding)[]) if (p[k] === undefined || p[k] === "") delete next[k];
    set({ ...s, branding: next });
  };
  const logoProblem = branding.logoUrl && !branding.logoUrl.startsWith("https://") ? "Must start with https://" : null;
  const rememberMe = s?.rememberMe === true;
  return (
    <SettingsFrame title="Customization" form={form}>
      {s && (
        <div className="grid min-h-full lg:grid-cols-[minmax(0,1fr)_auto]">
          <div className="min-w-0">
            <PanelSection title="Brand" contentClassName="max-w-3xl">
              <div className="grid gap-4">
                <Field>
                  <div className="flex items-center gap-1">
                    <FieldLabel htmlFor="brand-name">Name</FieldLabel>
                    <Help>Shown at the top of every screen and in the footer: “© Acme. All rights reserved.” Leave empty to hide both.</Help>
                  </div>
                  <Input id="brand-name" className="h-8" value={branding.name ?? ""} maxLength={64} placeholder="Acme" onChange={(e) => patch({ name: e.target.value })} />
                </Field>
                <Field data-invalid={logoProblem !== null}>
                  <div className="flex items-center gap-1">
                    <FieldLabel htmlFor="brand-logo">Logo URL</FieldLabel>
                    <Help>Shown 36 px tall above every screen.</Help>
                  </div>
                  <Input
                    id="brand-logo"
                    className="h-8 font-mono text-xs md:text-xs"
                    value={branding.logoUrl ?? ""}
                    placeholder="https://"
                    aria-invalid={logoProblem !== null}
                    onChange={(e) => patch({ logoUrl: e.target.value })}
                  />
                  {logoProblem && <FieldDescription className="text-xs text-danger">{logoProblem}</FieldDescription>}
                </Field>
                <Field>
                  <div className="flex items-center gap-1">
                    <FieldLabel htmlFor="brand-color">Primary color</FieldLabel>
                    <Help>Buttons and focus rings. #rrggbb.</Help>
                  </div>
                  <InputGroup className="h-8 w-48">
                    <InputGroupAddon>
                      <input
                        type="color"
                        aria-label="Pick primary color"
                        className="size-5 cursor-pointer rounded-xs border-0 bg-transparent p-0 ring-1 ring-foreground/15 [&::-webkit-color-swatch]:rounded-xs [&::-webkit-color-swatch]:border-0 [&::-webkit-color-swatch-wrapper]:p-0 [&::-moz-color-swatch]:rounded-xs [&::-moz-color-swatch]:border-0"
                        value={branding.primaryColor ?? DEFAULT_PRIMARY}
                        onChange={(e) => patch({ primaryColor: e.target.value })}
                      />
                    </InputGroupAddon>
                    <InputGroupInput
                      id="brand-color"
                      className="font-mono text-xs md:text-xs"
                      value={branding.primaryColor ?? ""}
                      placeholder="default"
                      maxLength={7}
                      onChange={(e) => patch({ primaryColor: e.target.value.toLowerCase() })}
                    />
                    {branding.primaryColor && (
                      <InputGroupAddon align="inline-end">
                        <InputGroupButton size="icon-xs" aria-label="Reset color" onClick={() => patch({ primaryColor: undefined })}>
                          <RotateCcw />
                        </InputGroupButton>
                      </InputGroupAddon>
                    )}
                  </InputGroup>
                </Field>
                <Field>
                  <div className="flex items-center gap-1">
                    <FieldLabel htmlFor="brand-radius">Corner radius</FieldLabel>
                    <Help>Cards, fields and buttons; 0 is square.</Help>
                    <span className="ml-auto font-mono text-xs text-muted-foreground">{branding.radius ?? DEFAULT_RADIUS} px</span>
                  </div>
                  <Slider
                    id="brand-radius"
                    min={0}
                    max={24}
                    step={1}
                    value={[branding.radius ?? DEFAULT_RADIUS]}
                    onValueChange={([radius]) => patch({ radius: radius === DEFAULT_RADIUS ? undefined : radius })}
                    className="py-1.5"
                  />
                </Field>
              </div>
            </PanelSection>
            <PanelSection title="Sign in options" contentClassName="max-w-3xl">
              <div className="divide-y rounded-lg border">
                <Option
                  id="opt-show-password"
                  label="Show password button"
                  description="Adds a button that reveals the typed password."
                  control={<Switch id="opt-show-password" checked={branding.showPassword === true} onCheckedChange={(on) => patch({ showPassword: on ? true : undefined })} />}
                />
                <Option
                  id="opt-remember"
                  label="Keep me signed in"
                  description="A checkbox on the first screen."
                  help="When checked, the authorization server remembers the sign in for 30 days and skips it next time if one factor meets the application's requirement. Applies to sign ins started after saving."
                  control={<Switch id="opt-remember" checked={rememberMe} onCheckedChange={(on) => set({ ...s, rememberMe: on })} />}
                />
                <Option
                  id="opt-sign-up"
                  label="Sign up button"
                  description="A button under the first screen."
                  help="Sign up is not supported yet; the button shows a notice saying so."
                  control={<Switch id="opt-sign-up" checked={branding.signUp === true} onCheckedChange={(on) => patch({ signUp: on ? true : undefined })} />}
                />
              </div>
            </PanelSection>
          </div>
          <aside className="self-stretch border-t bg-surface-2 lg:w-112 lg:border-t-0 lg:border-l">
            <PaneBar>
              <span className="text-xs font-medium">Preview</span>
            </PaneBar>
            <div className="sticky top-0 p-4">
              <LoginPreview branding={branding} rememberMe={rememberMe} />
            </div>
          </aside>
        </div>
      )}
    </SettingsFrame>
  );
}

/** One row of a bordered list: its label and description on the left, its control on the right. */
function Option({ id, label, description, help, control }: { id: string; label: string; description?: string; help?: ReactNode; control: ReactNode }) {
  return (
    <Field orientation="horizontal" className="gap-4 px-4 py-3 has-[>[data-slot=field-content]]:items-center">
      <FieldContent>
        <div className="flex items-center gap-1">
          <FieldLabel htmlFor={id}>{label}</FieldLabel>
          {help && <Help>{help}</Help>}
        </div>
        {description && <FieldDescription className="text-xs">{description}</FieldDescription>}
      </FieldContent>
      <div className="flex min-h-8 items-center">{control}</div>
    </Field>
  );
}

function LoginPreview({ branding, rememberMe }: { branding: Branding; rememberMe: boolean }) {
  const logo = branding.logoUrl?.startsWith("https://") ? branding.logoUrl : null;
  const field = "flex h-9 items-center overflow-hidden rounded-lg border border-input text-xs text-muted-foreground";
  const cell = "grid h-full w-9 shrink-0 place-items-center border-r bg-muted";
  // Mirrors the login screens on their own theme (login-theme.css): light only, a white page, one centered column, icon cell fields
  return (
    <div className="overflow-hidden rounded-xl border" aria-label="Preview of the first login screen">
      <div className="login-theme font-login grid min-h-105 grid-rows-[1fr_auto] justify-items-center px-6 pt-9 pb-4">
      <div style={brandStyle({ ...branding, radius: branding.radius ?? DEFAULT_RADIUS })} className="grid w-full max-w-80 content-start gap-4">
        {(logo || branding.name) && (
          <div className="flex items-center justify-center gap-2 pb-1">
            {logo ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={logo} alt={branding.name ?? ""} className="h-6 w-auto object-contain" />
            ) : <span className="text-base font-extrabold tracking-tight">{branding.name}</span>}
          </div>
        )}
        <div className="grid gap-1.5">
          <span className={field}>
            <span className={cell}>
              <Mail className="size-3.5" strokeWidth={1.75} />
            </span>
            <span className="px-2.5">Email</span>
          </span>
          <span className={field}>
            <span className={cell}>
              <LockKeyhole className="size-3.5" strokeWidth={1.75} />
            </span>
            <span className="flex-1 px-2.5">Password</span>
            {branding.showPassword && <Eye className="mr-2.5 size-3.5" strokeWidth={1.75} />}
          </span>
        </div>
        {rememberMe && (
          <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <span className="size-3 rounded-xs border border-input" />
            Keep me signed in
          </span>
        )}
        <span className="grid h-9 place-items-center rounded-lg bg-primary text-sm font-bold text-primary-foreground shadow-press">Sign in</span>
        {branding.signUp && (
          <>
            <Separator />
            <span className="grid h-9 place-items-center rounded-lg border border-input text-xs font-semibold text-primary">Sign up</span>
          </>
        )}
      </div>
      {branding.name && <span className="pt-6 text-xs text-muted-foreground">© {branding.name}. All rights reserved.</span>}
      </div>
    </div>
  );
}

/**
 * How long a person has to finish each kind of flow (ADR 0035), for the Login flow's Settings tab:
 * a time limit from its start and an idle timeout from the last answer, one row per entry kind the
 * server runs, so sign up appears here once the engine runs it. Blank keeps the deployment's limit.
 * These are tenant settings, not part of the draft: Save applies them to flows that start after it,
 * without a publish.
 */
export function FlowTimeLimits() {
  const form = useSettingsForm();
  const { s, set, view } = form;
  const limits = view?.flowLimits;
  if (!s || !limits) return null;
  const flows = s.flows ?? {};
  const change = (kind: string, name: keyof FlowLimits, text: string) => {
    const next = withLimit(flows, kind, name, fromMinutes(text));
    // Empty is no flow settings, as the server sends it
    const { flows: _, ...rest } = s;
    void _;
    set(Object.keys(next).length === 0 ? rest : { ...s, flows: next });
  };
  const invalid = limits.kinds.some((k) => Object.keys(limitProblems(flows[k] ?? {}, limits)).length > 0);
  const max = limits.maxSeconds / 60;
  return (
    <div className="grid gap-3">
      <p className="text-xs text-muted-foreground">
        Applies to sign ins started after saving; no publish needed. Leave blank for the deployment limit, {max} min (<code className="font-mono">faye.flow-ttl</code>).
      </p>
      {limits.kinds.map((kind) => (
        <KindLimits key={kind} kind={kind} value={flows[kind] ?? {}} limits={limits} onChange={(name, text) => change(kind, name, text)} />
      ))}
      {form.problems.length > 0 && (
        <ul className="grid gap-px overflow-hidden rounded-md" role="alert">
          {form.problems.map((p, i) => (
            <li key={i} className="flex gap-2 bg-danger/8 px-2 py-1.5 text-xs">
              <CircleX className="mt-0.5 size-3.5 shrink-0 text-danger" />
              {p}
            </li>
          ))}
        </ul>
      )}
      {form.dirty && (
        <div className="flex animate-in items-center gap-2 fade-in-0">
          <span className="text-xs text-muted-foreground">Unsaved changes</span>
          <Button variant="outline" size="sm" className="ml-auto h-7 text-xs" onClick={form.discard}>
            Discard
          </Button>
          <Button size="sm" className="h-7 text-xs" onClick={form.save} disabled={form.saving || invalid}>
            {form.saving ? "Saving…" : "Save limits"}
          </Button>
        </div>
      )}
    </div>
  );
}

function KindLimits({ kind, value, limits, onChange }: { kind: string; value: FlowLimits; limits: FlowLimitsView; onChange: (name: keyof FlowLimits, text: string) => void }) {
  const label = kindLabel(kind);
  const problems = limitProblems(value, limits);
  const max = limits.maxSeconds / 60;
  const fields: { name: keyof FlowLimits; title: string; placeholder: string }[] = [
    { name: "lifetimeSeconds", title: "Time limit", placeholder: String(max) },
    { name: "idleTimeoutSeconds", title: "Idle timeout", placeholder: "none" },
  ];
  return (
    <fieldset className="grid gap-2 rounded-md border px-2.5 pt-1.5 pb-2.5">
      <legend className="flex items-center gap-1.5 px-1 text-xs font-medium" title={label.description}>
        {label.title}
        <code className="font-mono font-normal text-muted-foreground">{kind}</code>
      </legend>
      {fields.map((f) => {
        const id = `${kind}-${f.name}`;
        const problem = problems[f.name];
        return (
          <div key={f.name} className="grid grid-cols-[--spacing(22)_1fr] items-center gap-x-3 gap-y-1">
            <label htmlFor={id} className="text-xs text-muted-foreground">
              {f.title}
            </label>
            <InputGroup className="h-7 w-28">
              <InputGroupInput
                id={id}
                type="number"
                min={limits.minSeconds / 60}
                max={max}
                step={1}
                className="font-mono text-xs md:text-xs"
                placeholder={f.placeholder}
                aria-invalid={problem !== undefined}
                value={toMinutes(value[f.name])}
                onChange={(e) => onChange(f.name, e.target.value)}
              />
              <InputGroupAddon align="inline-end" className="text-xs text-muted-foreground">
                min
              </InputGroupAddon>
            </InputGroup>
            {problem && <span className="col-start-2 text-xs text-danger">{problem}</span>}
          </div>
        );
      })}
    </fieldset>
  );
}
