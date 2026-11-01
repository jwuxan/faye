"use client";

import { Badge } from "@faye/ui/components/badge";
import { Button } from "@faye/ui/components/button";
import { Checkbox } from "@faye/ui/components/checkbox";
import { Field, FieldContent, FieldDescription, FieldGroup, FieldLabel, FieldLegend, FieldSeparator, FieldSet } from "@faye/ui/components/field";
import { Input } from "@faye/ui/components/input";
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from "@faye/ui/components/input-group";
import { Slider } from "@faye/ui/components/slider";
import { Switch } from "@faye/ui/components/switch";
import { brandStyle, DEFAULT_PRIMARY } from "@faye/ui/lib/brand-style";
import { cn } from "@faye/ui/lib/utils";
import { CircleX, Eye, Lock, LockKeyhole, Mail, RotateCcw } from "lucide-react";
import { type ReactNode, useState } from "react";
import { api, type BlockDefaults as BlockDefaultsValue, type Branding, type Settings as TenantSettings, type SettingsView, type Validation } from "../../../lib/api";
import { PALETTE } from "../../../lib/flow";
import { PageHeader } from "../../shell";
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
        d.notify("ok", "Settings saved");
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

function SettingsFrame({ title, description, form, children }: { title: string; description: string; form: Form; children: ReactNode }) {
  return (
    <div className="flex h-full min-h-0 flex-col">
      <PageHeader tenant={form.d.tenant} title={title} />
      <div className="min-h-0 flex-1 overflow-auto">
        <div className="mx-auto grid max-w-5xl gap-8 px-4 py-5 pb-24 sm:px-6 sm:py-6">
          <div className="grid gap-1">
            <div className="flex items-center gap-2">
              <h1 className="text-lg leading-7 font-semibold tracking-tight">{title}</h1>
              {form.view && (
                <Badge variant="outline" className="gap-1.5 font-normal text-muted-foreground">
                  <span className={cn("size-1.5 rounded-full", form.view.saved ? "bg-accent-ui" : "bg-border")} />
                  {form.view.saved ? "saved in the console" : "from server config"}
                </Badge>
              )}
            </div>
            <p className="text-muted-foreground">{description}</p>
          </div>
          {form.s ? children : <p className="text-muted-foreground">Loading…</p>}
          {form.problems.length > 0 && (
            <ul className="grid gap-px overflow-hidden rounded-md" role="alert">
              {form.problems.map((p, i) => (
                <li key={i} className="flex gap-2 bg-danger/8 px-3 py-2 text-xs">
                  <CircleX className="mt-0.5 size-3.5 shrink-0 text-danger" />
                  {p}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
      <div
        className={cn(
          "flex h-14 shrink-0 items-center gap-2 border-t bg-background px-6 transition-[margin,opacity] duration-200",
          form.dirty ? "opacity-100" : "pointer-events-none -mb-14 opacity-0",
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
    <SettingsFrame title="General" description="Policy every flow of this tenant is checked against. A save is refused if the live version would fail under it." form={form}>
      {s && (
        <FieldGroup className="gap-8">
          <FieldSet>
            <FieldLegend>Sign in policy</FieldLegend>
            <Field orientation="horizontal" className="rounded-lg border p-4">
              <FieldContent>
                <FieldLabel htmlFor="mfa">Require MFA</FieldLabel>
                <FieldDescription className="text-xs">Every successful sign in must reach faye:2fa: two verified factors.</FieldDescription>
              </FieldContent>
              <Switch id="mfa" checked={s.mfaRequired} onCheckedChange={(mfaRequired) => set({ ...s, mfaRequired })} />
            </Field>
          </FieldSet>
          <FieldSeparator />
          <BlockDefaults
            value={s.blocks ?? {}}
            onChange={(blocks) => {
              // Empty is no defaults, as the server sends it
              const { blocks: _, ...rest } = s;
              void _;
              set(Object.keys(blocks).length === 0 ? rest : { ...s, blocks });
            }}
          />
          <FieldSeparator />
          <Allowlist
            title="Handlers"
            description="Built in handlers flows may call. The checker refuses a flow that uses one not allowed."
            items={d.catalog?.handlers.map((h) => ({ name: h.name, note: h.amr.filter((a) => a !== "user").join(" · ") || (h.bindsSubject ? "binds subject" : "") })) ?? []}
            value={s.handlers}
            onChange={(handlers) => set({ ...s, handlers })}
          />
          <FieldSeparator />
          <Allowlist
            title="Screens"
            description="Typed screens flows may show."
            items={d.catalog?.screens.map((x) => ({ name: x.type, note: x.options.join(" · ") })) ?? []}
            value={s.screens}
            onChange={(screens) => set({ ...s, screens })}
          />
          <FieldSeparator />
          <FieldSet>
            <FieldLegend className="flex items-center gap-2">
              Egress
              <Lock className="size-3.5 text-muted-foreground" />
            </FieldLegend>
            <FieldDescription className="text-xs">
              Named HTTP targets this tenant’s hooks may call. Set by the operator in <code className="font-mono">faye.tenants.{d.tenant}.egress</code>, so it cannot be changed here.
            </FieldDescription>
            <div className="flex flex-wrap gap-1.5">
              {(view?.egress.length ?? 0) > 0 ? (
                view!.egress.map((t) => (
                  <Badge key={t} variant="secondary" className="font-mono font-normal">
                    {t}
                  </Badge>
                ))
              ) : (
                <span className="text-xs text-muted-foreground">none</span>
              )}
            </div>
          </FieldSet>
        </FieldGroup>
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
    <FieldSet>
      <FieldLegend>Block defaults</FieldLegend>
      <FieldDescription className="text-xs">
        What each block does unless a flow says otherwise. Applies the next time a flow is validated or published; published versions keep theirs.
      </FieldDescription>
      <div className="grid gap-2">
        {blocks.map((b) => (
          <Field key={b.id} orientation="horizontal">
            <FieldLabel htmlFor={`attempts-${b.id}`} className="w-40 font-normal">
              {b.name} attempts{b.role === "identifier" ? " (with password)" : ""}
            </FieldLabel>
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
          </Field>
        ))}
        {identifier?.id && (
          <Field orientation="horizontal">
            <FieldLabel htmlFor="passkey-autofill" className="w-40 font-normal">
              Passkey autofill
            </FieldLabel>
            <Switch
              id="passkey-autofill"
              checked={value[identifier.id]?.passkey === true}
              onCheckedChange={(on) => put(identifier.id!, "passkey", on ? true : undefined)}
            />
          </Field>
        )}
      </div>
    </FieldSet>
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
    <FieldSet>
      <div className="flex items-center gap-2">
        <FieldLegend className="mb-0">{title}</FieldLegend>
        <span className="text-xs text-muted-foreground">{value === null ? "all allowed" : `${value.length} of ${all.length} allowed`}</span>
        {value !== null && (
          <Button variant="ghost" size="xs" className="ml-auto text-muted-foreground" onClick={() => onChange(null)}>
            <RotateCcw />
            Allow all
          </Button>
        )}
      </div>
      <FieldDescription className="text-xs">{description}</FieldDescription>
      <div className="grid overflow-hidden rounded-lg border sm:grid-cols-2">
        {items.map((item, i) => {
          const on = allowed.includes(item.name);
          const id = `${title}-${item.name}`;
          return (
            <Field
              key={item.name}
              orientation="horizontal"
              className={cn("gap-3 px-3 py-2.5 transition-colors hover:bg-accent/50", i % 2 === 0 && "sm:border-r", i < items.length - (items.length % 2 === 0 ? 2 : 1) && "border-b")}
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
    </FieldSet>
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
  const logoProblem = branding.logoUrl && !branding.logoUrl.startsWith("https://") ? "Logos load over https only" : null;
  const rememberMe = s?.rememberMe === true;
  return (
    <SettingsFrame title="Login page" description="What people see when they sign in to this tenant's applications: its brand, and the options the screens offer. Screens stay typed templates; only these change." form={form}>
      {s && (
        <div className="grid items-start gap-8 lg:grid-cols-[1fr_--spacing(85)]">
          <FieldGroup className="gap-8">
            <FieldSet>
              <FieldLegend>Brand</FieldLegend>
              <FieldGroup>
                <Field>
                  <FieldLabel htmlFor="brand-name">Name</FieldLabel>
                  <Input id="brand-name" value={branding.name ?? ""} maxLength={64} placeholder="Acme" onChange={(e) => patch({ name: e.target.value })} />
                  <FieldDescription className="text-xs">Shown at the top of every screen and in its footer: “© Acme. All rights reserved.” Empty shows neither.</FieldDescription>
                </Field>
                <Field data-invalid={logoProblem !== null}>
                  <FieldLabel htmlFor="brand-logo">Logo URL</FieldLabel>
                  <Input
                    id="brand-logo"
                    className="font-mono text-xs md:text-xs"
                    value={branding.logoUrl ?? ""}
                    placeholder="https://"
                    aria-invalid={logoProblem !== null}
                    onChange={(e) => patch({ logoUrl: e.target.value })}
                  />
                  <FieldDescription className={cn("text-xs", logoProblem && "text-danger")}>{logoProblem ?? "Shown 36 px tall above every screen, at its own width."}</FieldDescription>
                </Field>
                <Field>
                  <FieldLabel htmlFor="brand-color">Primary color</FieldLabel>
                  <InputGroup className="w-48">
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
                  <FieldDescription className="text-xs">Buttons and focus rings. #rrggbb.</FieldDescription>
                </Field>
                <Field>
                  <div className="flex items-center">
                    <FieldLabel htmlFor="brand-radius">Corner radius</FieldLabel>
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
                  <FieldDescription className="text-xs">Cards, fields and buttons; 0 is square.</FieldDescription>
                </Field>
              </FieldGroup>
            </FieldSet>
            <FieldSeparator />
            <FieldSet>
              <FieldLegend>Sign in options</FieldLegend>
              <div className="divide-y rounded-xl border">
                <Option
                  id="opt-show-password"
                  label="Show password button"
                  description="Password fields get an eye button that shows what was typed."
                  checked={branding.showPassword === true}
                  onChange={(on) => patch({ showPassword: on ? true : undefined })}
                />
                <Option
                  id="opt-remember"
                  label="Keep me signed in"
                  description="A checkbox on the first screen. When ticked, the authorization server remembers the sign in for 30 days, and skips it next time if one factor is enough for the application. Applies to sign ins that start after you save."
                  checked={rememberMe}
                  onChange={(on) => set({ ...s, rememberMe: on })}
                />
                <Option
                  id="opt-sign-up"
                  label="Sign up button"
                  description="A Sign up button under the first screen. Sign up is not supported yet: the button tells people so."
                  checked={branding.signUp === true}
                  onChange={(on) => patch({ signUp: on ? true : undefined })}
                />
              </div>
            </FieldSet>
          </FieldGroup>
          <div className="grid gap-2 lg:sticky lg:top-0">
            <span className="text-xs font-medium text-muted-foreground">Preview</span>
            <LoginPreview branding={branding} rememberMe={rememberMe} />
          </div>
        </div>
      )}
    </SettingsFrame>
  );
}

/** One switch of the login screens, as a row of a bordered list. */
function Option({ id, label, description, checked, onChange }: { id: string; label: string; description: string; checked: boolean; onChange: (on: boolean) => void }) {
  return (
    <Field orientation="horizontal" className="gap-4 px-4 py-3">
      <FieldContent>
        <FieldLabel htmlFor={id}>{label}</FieldLabel>
        <FieldDescription className="text-xs">{description}</FieldDescription>
      </FieldContent>
      <Switch id={id} checked={checked} onCheckedChange={onChange} />
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
      <div className="login-theme grid min-h-105 grid-rows-[1fr_auto] justify-items-center px-6 pt-9 pb-4">
      <div style={brandStyle({ ...branding, radius: branding.radius ?? DEFAULT_RADIUS })} className="grid w-full max-w-65 content-start gap-4">
        {(logo || branding.name) && (
          <div className="flex items-center justify-center gap-2 pb-1">
            {logo && (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={logo} alt="" className="h-6 w-auto object-contain" />
            )}
            {branding.name && <span className="text-base font-extrabold tracking-tight">{branding.name}</span>}
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
            <span className="h-px bg-border" />
            <span className="grid h-9 place-items-center rounded-lg border border-input text-xs font-semibold text-primary">Sign up</span>
          </>
        )}
      </div>
      {branding.name && <span className="pt-6 text-xs text-muted-foreground">© {branding.name}. All rights reserved.</span>}
      </div>
    </div>
  );
}
