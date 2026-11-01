"use client";

import { Breadcrumb, BreadcrumbItem, BreadcrumbLink, BreadcrumbList, BreadcrumbPage, BreadcrumbSeparator } from "@faye/ui/components/breadcrumb";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@faye/ui/components/dropdown-menu";
import { FayeLogo } from "@faye/ui/components/faye-logo";
import { Button } from "@faye/ui/components/button";
import { Kbd, KbdGroup } from "@faye/ui/components/kbd";
import { Separator } from "@faye/ui/components/separator";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  SidebarRail,
  SidebarTrigger,
  useSidebar,
} from "@faye/ui/components/sidebar";
import { Tooltip, TooltipContent, TooltipTrigger } from "@faye/ui/components/tooltip";
import {
  AppWindow,
  BookOpen,
  Check,
  ChevronsUpDown,
  GitCommitHorizontal,
  LayoutList,
  Monitor,
  Moon,
  Palette,
  type LucideIcon,
  Search,
  Settings2,
  Sun,
  SquareCode,
  Users,
  Workflow,
} from "lucide-react";
import { type Appearance, setAppearance, useAppearance } from "@faye/ui/lib/appearance";
import { cn } from "@faye/ui/lib/utils";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { type CSSProperties, type ReactNode, useState } from "react";
import { toneOf } from "../lib/kinds";
import type { DocSection } from "../lib/docs";
import { Tile } from "./ui";
import { CommandPalette, type PaletteGroup, ShortcutSheet } from "./command-palette";

export type TenantView = { id: string; current: string | null };

/** A sidebar link: muted icon, which darkens with the text when the link is the current page. */
const NAV_BUTTON = "h-8 rounded-lg text-sm [&>svg]:text-muted-foreground data-[active=true]:[&>svg]:text-foreground";

type NavItem = { title: string; href: string; icon: LucideIcon; exact?: boolean };

/** What the console can do for one tenant. Applications and users are read only (ADRs 0014, 0015). */
function navigation(tenant: string): { label: string; items: NavItem[] }[] {
  const base = `/tenants/${encodeURIComponent(tenant)}`;
  return [
    {
      label: "Sign in",
      items: [
        { title: "Login flow", href: `${base}/flow`, icon: Workflow },
        { title: "Hooks", href: `${base}/hooks`, icon: SquareCode },
        { title: "Versions", href: `${base}/versions`, icon: GitCommitHorizontal },
        { title: "Customization", href: `${base}/settings/login`, icon: Palette },
      ],
    },
    {
      label: "Directory",
      items: [
        { title: "Applications", href: `${base}/applications`, icon: AppWindow },
        { title: "Users", href: `${base}/users`, icon: Users },
      ],
    },
    {
      label: "Settings",
      items: [
        { title: "General", href: `${base}/settings`, icon: Settings2, exact: true },
      ],
    },
  ];
}

/** The console frame: a gray, collapsible sidebar (⌘B) with the mark and search on top, docs, tenant and appearance at the foot, the page on a rounded sheet beside it, the ⌘K palette and the hold ⌘ shortcut sheet. */
export function Shell({
  tenant,
  tenants,
  defaultOpen,
  docs = [],
  children,
}: {
  /** The tenant being worked on, or null on the tenant list. */
  tenant: string | null;
  tenants: TenantView[];
  defaultOpen: boolean;
  /** The docs index for the ⌘K palette's Docs group (docIndex in lib/docs). */
  docs?: DocSection[];
  children: ReactNode;
}) {
  const pathname = usePathname();
  const [palette, setPalette] = useState(false);
  return (
    <SidebarProvider defaultOpen={defaultOpen} className="h-svh min-h-0" style={{ "--sidebar-width": "15rem" } as CSSProperties}>
      <Sidebar collapsible="icon" variant="inset">
        <SidebarHeader className="gap-2.5 pb-1">
          <div className="flex h-9 items-center gap-1 pl-1.5 group-data-[collapsible=icon]:pl-1">
            <Link href="/" aria-label="Faye: all tenants" className="rounded-md outline-none focus-visible:ring-2 focus-visible:ring-sidebar-ring">
              <FayeLogo className="h-6 w-auto group-data-[collapsible=icon]:h-auto group-data-[collapsible=icon]:w-6" />
            </Link>
            <Tooltip>
              <TooltipTrigger asChild>
                <SidebarTrigger className="ml-auto size-7 shrink-0 text-muted-foreground hover:text-foreground group-data-[collapsible=icon]:hidden" />
              </TooltipTrigger>
              <TooltipContent side="bottom" className="flex items-center gap-2">
                Hide sidebar
                <KbdGroup>
                  <Kbd>⌘</Kbd>
                  <Kbd>B</Kbd>
                </KbdGroup>
              </TooltipContent>
            </Tooltip>
          </div>
          <SidebarMenu>
            <SidebarMenuItem>
              <SidebarMenuButton
                variant="outline"
                tooltip="Search ⌘K"
                onClick={() => setPalette(true)}
                className="h-8 rounded-full bg-background pr-1.5 text-muted-foreground hover:bg-background hover:text-foreground hover:ring-1 hover:ring-input group-data-[collapsible=icon]:rounded-lg"
              >
                <Search />
                <span className="min-w-0 truncate whitespace-nowrap">Search or jump to…</span>
                <Kbd className="ml-auto h-5 shrink-0 rounded-full px-1.5 text-xs group-data-[collapsible=icon]:hidden">⌘K</Kbd>
              </SidebarMenuButton>
            </SidebarMenuItem>
          </SidebarMenu>
        </SidebarHeader>
        <SidebarContent className="gap-0">
          {tenant === null ? (
            <SidebarGroup>
              <SidebarGroupLabel>Workspace</SidebarGroupLabel>
              <SidebarMenu>
                <SidebarMenuItem>
                  <SidebarMenuButton asChild isActive={pathname === "/"} tooltip="Tenants">
                    <Link href="/">
                      <LayoutList />
                      <span>Tenants</span>
                    </Link>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              </SidebarMenu>
            </SidebarGroup>
          ) : (
            navigation(tenant).map((group) => (
              <SidebarGroup key={group.label} className="py-1.5">
                <SidebarGroupLabel className="h-7">{group.label}</SidebarGroupLabel>
                <SidebarMenu className="gap-0.5">
                  {group.items.map((item) => {
                    const active = item.exact ? pathname === item.href : pathname === item.href || pathname.startsWith(`${item.href}/`);
                    return (
                      <SidebarMenuItem key={item.href}>
                        <SidebarMenuButton asChild isActive={active} tooltip={item.title} className={NAV_BUTTON}>
                          <Link href={item.href}>
                            <item.icon strokeWidth={1.75} />
                            <span>{item.title}</span>
                          </Link>
                        </SidebarMenuButton>
                      </SidebarMenuItem>
                    );
                  })}
                </SidebarMenu>
              </SidebarGroup>
            ))
          )}
        </SidebarContent>
        <SidebarFooter className="gap-1">
          <SidebarMenu className="gap-0.5">
            <SidebarMenuItem>
              <SidebarMenuButton asChild isActive={pathname.startsWith("/docs")} tooltip="Docs" className={NAV_BUTTON}>
                <Link href="/docs">
                  <BookOpen strokeWidth={1.75} />
                  <span>Docs</span>
                </Link>
              </SidebarMenuButton>
            </SidebarMenuItem>
          </SidebarMenu>
          <div className="flex items-center gap-1 group-data-[collapsible=icon]:flex-col-reverse">
            <TenantSwitcher tenant={tenant} tenants={tenants} />
            <AppearanceMenu />
          </div>
        </SidebarFooter>
        <SidebarRail />
      </Sidebar>
      <SidebarInset className="min-h-0 min-w-0 overflow-hidden md:peer-data-[variant=inset]:shadow-sheet dark:md:peer-data-[variant=inset]:shadow-sheet-flat">
        {children}
      </SidebarInset>
      <CommandPalette groups={paletteGroups(tenant, docs)} tenants={tenants.map((t) => t.id)} open={palette} setOpen={setPalette} />
      <ShortcutSheet />
    </SidebarProvider>
  );
}

/** The palette's groups: the tenant's pages, then every doc page under a Docs group. */
function paletteGroups(tenant: string | null, docs: DocSection[]): PaletteGroup[] {
  const groups = tenant === null ? [] : navigation(tenant);
  const pages = docs.flatMap((section) => section.pages);
  if (pages.length > 0) groups.push({ label: "Docs", items: pages.map((p) => ({ title: p.title, href: p.href, icon: BookOpen })) });
  return groups;
}

const APPEARANCES: { value: Appearance; label: string; icon: LucideIcon }[] = [
  { value: "light", label: "Light", icon: Sun },
  { value: "dark", label: "Dark", icon: Moon },
  { value: "system", label: "System", icon: Monitor },
];

/** Theme change crossfades the whole console where view transitions exist; elsewhere it snaps. */
function changeAppearance(next: Appearance) {
  const doc = document as Document & { startViewTransition?: (update: () => void) => unknown };
  if (doc.startViewTransition) doc.startViewTransition(() => setAppearance(next));
  else setAppearance(next);
}

/** Light, dark, or whatever the OS uses; kept in this browser. One icon: the current appearance's. */
function AppearanceMenu() {
  const { isMobile } = useSidebar();
  const { appearance } = useAppearance();
  const current = APPEARANCES.find((a) => a.value === appearance) ?? APPEARANCES[2];
  return (
    <DropdownMenu>
      <Tooltip>
        <TooltipTrigger asChild>
          <DropdownMenuTrigger asChild>
            <Button
              variant="ghost"
              size="icon"
              aria-label={`Appearance: ${current.label}`}
              className="size-8 shrink-0 rounded-lg text-muted-foreground hover:bg-sidebar-accent hover:text-foreground data-[state=open]:bg-sidebar-accent"
            >
              <current.icon strokeWidth={1.75} />
            </Button>
          </DropdownMenuTrigger>
        </TooltipTrigger>
        <TooltipContent side={isMobile ? "top" : "right"}>Appearance: {current.label}</TooltipContent>
      </Tooltip>
      <DropdownMenuContent className="min-w-40 rounded-lg" align="end" side={isMobile ? "top" : "right"} sideOffset={4}>
        <DropdownMenuLabel className="text-xs text-muted-foreground">Appearance</DropdownMenuLabel>
        <DropdownMenuRadioGroup value={appearance} onValueChange={(v) => changeAppearance(v as Appearance)}>
          {APPEARANCES.map((a) => (
            <DropdownMenuRadioItem key={a.value} value={a.value} className="gap-2">
              <a.icon className="text-muted-foreground" />
              {a.label}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** Which tenant the console is working on, at the foot of the sidebar. */
function TenantSwitcher({ tenant, tenants }: { tenant: string | null; tenants: TenantView[] }) {
  const { isMobile } = useSidebar();
  return (
    <SidebarMenu className="min-w-0 flex-1">
      <SidebarMenuItem>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <SidebarMenuButton
              tooltip={tenant ? `Tenant ${tenant}` : "All tenants"}
              className="h-8 gap-2 rounded-lg data-[state=open]:bg-sidebar-accent data-[state=open]:text-sidebar-accent-foreground"
            >
              {tenant ? <Tile letter={tenant} tone={toneOf(tenant)} size="sm" /> : <LayoutList strokeWidth={1.75} className="text-muted-foreground" />}
              <span className={cn("min-w-0 flex-1 truncate", tenant && "font-mono text-xs")}>{tenant ?? "All tenants"}</span>
              <ChevronsUpDown className="size-3.5! text-muted-foreground" />
            </SidebarMenuButton>
          </DropdownMenuTrigger>
          <DropdownMenuContent className="min-w-56 rounded-xl" align="end" side={isMobile ? "top" : "right"} sideOffset={4}>
            <DropdownMenuLabel className="text-xs text-muted-foreground">Tenants</DropdownMenuLabel>
            {tenants.map((t) => (
              <DropdownMenuItem key={t.id} asChild className="gap-2">
                <Link href={`/tenants/${encodeURIComponent(t.id)}/flow`}>
                  <Tile letter={t.id} tone={toneOf(t.id)} size="sm" />
                  <span className="font-mono">{t.id}</span>
                  {t.id === tenant ? (
                    <Check className="ml-auto" />
                  ) : (
                    t.current && <span className="ml-auto font-mono text-xs text-muted-foreground">{t.current.slice(7, 13)}</span>
                  )}
                </Link>
              </DropdownMenuItem>
            ))}
            <DropdownMenuSeparator />
            <DropdownMenuItem asChild className="gap-2">
              <Link href="/">
                <LayoutList className="text-muted-foreground" />
                All tenants
              </Link>
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </SidebarMenuItem>
    </SidebarMenu>
  );
}

/** The bar on top of every page: where you are, and the page's actions; the sidebar toggle only while the sidebar is hidden. */
export function PageHeader({ tenant, title, children }: { tenant?: string; title: string; children?: ReactNode }) {
  const { state, isMobile } = useSidebar();
  const hidden = isMobile || state === "collapsed";
  return (
    <header className="flex h-11 shrink-0 items-center gap-2 border-b px-4">
      {hidden && (
        <>
          <Tooltip>
            <TooltipTrigger asChild>
              <SidebarTrigger className="-ml-0.5 size-7 text-muted-foreground hover:text-foreground" />
            </TooltipTrigger>
            <TooltipContent side="bottom" className="flex items-center gap-2">
              Show sidebar
              <KbdGroup>
                <Kbd>⌘</Kbd>
                <Kbd>B</Kbd>
              </KbdGroup>
            </TooltipContent>
          </Tooltip>
          <Separator orientation="vertical" className="mr-1 data-[orientation=vertical]:h-4" />
        </>
      )}
      <Breadcrumb className="min-w-0">
        <BreadcrumbList className="flex-nowrap gap-1 sm:gap-1.5">
          {tenant && (
            <>
              <BreadcrumbItem className="hidden md:inline-flex">
                <BreadcrumbLink asChild>
                  <Link href={`/tenants/${encodeURIComponent(tenant)}/flow`} className="font-mono text-xs">
                    {tenant}
                  </Link>
                </BreadcrumbLink>
              </BreadcrumbItem>
              <BreadcrumbSeparator className="hidden md:block [&>svg]:size-3" />
            </>
          )}
          <BreadcrumbItem>
            <BreadcrumbPage className="truncate text-base font-semibold tracking-tight">{title}</BreadcrumbPage>
          </BreadcrumbItem>
        </BreadcrumbList>
      </Breadcrumb>
      <div className="ml-auto flex min-w-0 items-center gap-2">{children}</div>
    </header>
  );
}
