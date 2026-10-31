"use client";

import { Breadcrumb, BreadcrumbItem, BreadcrumbLink, BreadcrumbList, BreadcrumbPage, BreadcrumbSeparator } from "@faye/ui/components/breadcrumb";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@faye/ui/components/dropdown-menu";
import { Kbd, KbdGroup } from "@faye/ui/components/kbd";
import { Separator } from "@faye/ui/components/separator";
import {
  Sidebar,
  SidebarContent,
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
  Building2,
  Check,
  ChevronsUpDown,
  GitCommitHorizontal,
  LayoutList,
  type LucideIcon,
  Palette,
  Settings2,
  SquareCode,
  Users,
  Workflow,
} from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

export type TenantView = { id: string; current: string | null };

type NavItem = { title: string; href: string; icon: LucideIcon; exact?: boolean };

/** What the console can do for one tenant. Applications and users are read only (ADRs 0014, 0015). */
function navigation(tenant: string): { label: string; items: NavItem[] }[] {
  const base = `/tenants/${encodeURIComponent(tenant)}`;
  return [
    {
      label: "Build",
      items: [
        { title: "Login flow", href: `${base}/flow`, icon: Workflow },
        { title: "Hooks", href: `${base}/hooks`, icon: SquareCode },
        { title: "Versions", href: `${base}/versions`, icon: GitCommitHorizontal },
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
        { title: "Branding", href: `${base}/settings/branding`, icon: Palette },
      ],
    },
  ];
}

/** The console frame: a collapsible sidebar (⌘B), the page beside it, and a footer below the page. */
export function Shell({
  tenant,
  tenants,
  defaultOpen,
  children,
}: {
  /** The tenant being worked on, or null on the tenant list. */
  tenant: string | null;
  tenants: TenantView[];
  defaultOpen: boolean;
  children: ReactNode;
}) {
  const pathname = usePathname();
  return (
    <SidebarProvider defaultOpen={defaultOpen} className="h-svh min-h-0">
      <Sidebar collapsible="icon">
        <SidebarHeader>
          <TenantSwitcher tenant={tenant} tenants={tenants} />
        </SidebarHeader>
        <SidebarContent>
          {tenant === null ? (
            <SidebarGroup>
              <SidebarGroupLabel>Workspace</SidebarGroupLabel>
              <SidebarMenu>
                <SidebarMenuItem>
                  <SidebarMenuButton asChild isActive tooltip="Tenants">
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
              <SidebarGroup key={group.label}>
                <SidebarGroupLabel>{group.label}</SidebarGroupLabel>
                <SidebarMenu>
                  {group.items.map((item) => {
                    const active = item.exact ? pathname === item.href : pathname === item.href || pathname.startsWith(`${item.href}/`);
                    return (
                      <SidebarMenuItem key={item.href}>
                        <SidebarMenuButton asChild isActive={active} tooltip={item.title}>
                          <Link href={item.href}>
                            <item.icon />
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
        <SidebarRail />
      </Sidebar>
      <SidebarInset className="min-h-0 min-w-0 overflow-hidden">
        {children}
        <footer className="shrink-0 border-t px-3 py-1.5 text-[11px] text-muted-foreground">Faye @ 2026</footer>
      </SidebarInset>
    </SidebarProvider>
  );
}

/** The Faye mark, and which tenant the console is working on. */
function TenantSwitcher({ tenant, tenants }: { tenant: string | null; tenants: TenantView[] }) {
  const { isMobile } = useSidebar();
  return (
    <SidebarMenu>
      <SidebarMenuItem>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <SidebarMenuButton size="lg" className="data-[state=open]:bg-sidebar-accent data-[state=open]:text-sidebar-accent-foreground">
              <span className="grid aspect-square size-8 place-items-center rounded-md bg-sidebar-primary font-serif text-[18px] leading-none text-sidebar-primary-foreground">
                f
              </span>
              <span className="grid flex-1 text-left leading-tight">
                <span className="truncate font-serif text-[15px]">Faye</span>
                <span className="truncate font-mono text-xs text-muted-foreground">{tenant ?? "all tenants"}</span>
              </span>
              <ChevronsUpDown className="ml-auto" />
            </SidebarMenuButton>
          </DropdownMenuTrigger>
          <DropdownMenuContent className="min-w-56 rounded-lg" align="start" side={isMobile ? "bottom" : "right"} sideOffset={4}>
            <DropdownMenuLabel className="text-xs text-muted-foreground">Tenants</DropdownMenuLabel>
            {tenants.map((t) => (
              <DropdownMenuItem key={t.id} asChild className="gap-2">
                <Link href={`/tenants/${encodeURIComponent(t.id)}/flow`}>
                  <Building2 className="text-muted-foreground" />
                  <span className="font-mono">{t.id}</span>
                  {t.id === tenant ? (
                    <Check className="ml-auto" />
                  ) : (
                    t.current && <span className="ml-auto font-mono text-[11px] text-muted-foreground">{t.current.slice(7, 13)}</span>
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

/** The bar on top of every page: sidebar toggle, where you are, and the page's actions. */
export function PageHeader({ tenant, title, children }: { tenant?: string; title: string; children?: ReactNode }) {
  return (
    <header className="flex h-12 shrink-0 items-center gap-2 border-b px-3">
      <Tooltip>
        <TooltipTrigger asChild>
          <SidebarTrigger className="-ml-0.5" />
        </TooltipTrigger>
        <TooltipContent side="bottom" className="flex items-center gap-2">
          Toggle sidebar
          <KbdGroup>
            <Kbd>⌘</Kbd>
            <Kbd>B</Kbd>
          </KbdGroup>
        </TooltipContent>
      </Tooltip>
      <Separator orientation="vertical" className="mr-1 data-[orientation=vertical]:h-4" />
      <Breadcrumb className="min-w-0">
        <BreadcrumbList className="flex-nowrap">
          {tenant && (
            <>
              <BreadcrumbItem className="hidden md:inline-flex">
                <BreadcrumbLink asChild>
                  <Link href={`/tenants/${encodeURIComponent(tenant)}/flow`} className="font-mono">
                    {tenant}
                  </Link>
                </BreadcrumbLink>
              </BreadcrumbItem>
              <BreadcrumbSeparator className="hidden md:block" />
            </>
          )}
          <BreadcrumbItem>
            <BreadcrumbPage className="truncate font-medium">{title}</BreadcrumbPage>
          </BreadcrumbItem>
        </BreadcrumbList>
      </Breadcrumb>
      <div className="ml-auto flex min-w-0 items-center gap-2">{children}</div>
    </header>
  );
}
