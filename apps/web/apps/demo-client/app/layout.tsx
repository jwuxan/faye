import { APPEARANCE_SCRIPT } from "@faye/ui/lib/appearance-script";
import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./globals.css";

export const metadata: Metadata = { title: "Faye demo client" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        {/* Light or dark before first paint (data-theme), so the page never flashes the other theme */}
        <script dangerouslySetInnerHTML={{ __html: APPEARANCE_SCRIPT }} />
      </head>
      <body>
        <main className="mx-auto grid min-h-svh max-w-2xl grid-cols-[minmax(0,1fr)] content-center gap-6 p-4">{children}</main>
      </body>
    </html>
  );
}
