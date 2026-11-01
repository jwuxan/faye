import { APPEARANCE_SCRIPT } from "@faye/ui/lib/appearance-script";
import { RootProvider } from "fumadocs-ui/provider/next";
import type { Metadata } from "next";
import localFont from "next/font/local";
import type { ReactNode } from "react";
import "./docs.css";

const inter = localFont({
  src: "../../node_modules/@fontsource-variable/inter/files/inter-latin-wght-normal.woff2",
  weight: "100 900",
  display: "swap",
  variable: "--font-inter",
});

const paperMono = localFont({
  src: "../../node_modules/paper-mono/fonts/webfonts/PaperMono[wght].woff2",
  weight: "100 800",
  display: "swap",
  variable: "--font-paper-mono",
  adjustFontFallback: false,
});

export const metadata: Metadata = {
  title: { default: "Faye docs", template: "%s · Faye docs" },
  description: "Faye is a sign in flow engine that sits in front of an OAuth2 authorization server.",
};

/**
 * The docs are a root layout of their own, beside the console's: Fumadocs brings its own theme and
 * type scale, and a separate root keeps them off the console. Going between the two is a full
 * page load. Light or dark is the console's own choice (APPEARANCE_SCRIPT and the same
 * localStorage key), so switching it here switches the console too.
 */
export default function DocsRootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" className={`${inter.variable} ${paperMono.variable}`} suppressHydrationWarning>
      <head>
        {/* Light or dark before first paint (data-theme and the class "dark"), shared with the console */}
        <script dangerouslySetInnerHTML={{ __html: APPEARANCE_SCRIPT }} />
      </head>
      <body className="flex min-h-screen flex-col">
        {/* The console's appearance script owns light and dark, so Fumadocs' next-themes stays off */}
        <RootProvider theme={{ enabled: false }}>{children}</RootProvider>
      </body>
    </html>
  );
}
