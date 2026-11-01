import type { CSSProperties } from "react";

/** The login screens' own blue when a tenant sets none (--primary in login-theme.css). */
export const DEFAULT_PRIMARY = "#0073e9";

/**
 * Maps a tenant's branding onto the login theme's tokens: the primary color (with black or white
 * text, whichever reads better on it) and the corner radius. Layout stays the same for every tenant.
 */
export function brandStyle(branding: { primaryColor?: string; radius?: number } | undefined): CSSProperties | undefined {
  if (!branding) return undefined;
  const style: Record<string, string> = {};
  if (branding.primaryColor) {
    const [r, g, b] = [1, 3, 5].map((i) => parseInt(branding.primaryColor!.slice(i, i + 2), 16) / 255);
    const luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
    style["--primary"] = branding.primaryColor;
    style["--ring"] = branding.primaryColor;
    style["--primary-foreground"] = luminance > 0.55 ? "oklch(0.145 0 0)" : "oklch(1 0 0)";
  }
  if (branding.radius !== undefined) style["--radius"] = `${branding.radius}px`;
  return style as CSSProperties;
}
