import { APPEARANCE_SCRIPT } from "@faye/ui/lib/appearance-script";
import { Toaster } from "@faye/ui/components/sonner";
import { TooltipProvider } from "@faye/ui/components/tooltip";
import type { Metadata } from "next";
import localFont from "next/font/local";
import type { ReactNode } from "react";
import "./globals.css";

const inter = localFont({
  src: "../node_modules/@fontsource-variable/inter/files/inter-latin-wght-normal.woff2",
  weight: "100 900",
  display: "swap",
  variable: "--font-inter",
});

const paperMono = localFont({
  src: "../node_modules/paper-mono/fonts/webfonts/PaperMono[wght].woff2",
  weight: "100 800",
  display: "swap",
  variable: "--font-paper-mono",
  adjustFontFallback: false,
});

export const metadata: Metadata = { title: "Faye admin" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" className={`${inter.variable} ${paperMono.variable}`} suppressHydrationWarning>
      <head>
        {/* Light or dark before first paint (data-theme), so the page never flashes the other theme */}
        <script dangerouslySetInnerHTML={{ __html: APPEARANCE_SCRIPT }} />
      </head>
      <body>
        <TooltipProvider delayDuration={300}>{children}</TooltipProvider>
        <Toaster position="bottom-right" />
      </body>
    </html>
  );
}
