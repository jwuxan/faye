import { APPEARANCE_SCRIPT } from "@faye/ui/lib/appearance-script";
import { Toaster } from "@faye/ui/components/sonner";
import { TooltipProvider } from "@faye/ui/components/tooltip";
import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./globals.css";

export const metadata: Metadata = { title: "Faye admin" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
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
