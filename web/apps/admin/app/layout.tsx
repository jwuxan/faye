import { Toaster } from "@faye/ui/components/sonner";
import { TooltipProvider } from "@faye/ui/components/tooltip";
import type { Metadata } from "next";
import { GeistSerif } from "geist/font/serif";
import type { ReactNode } from "react";
import "./globals.css";

export const metadata: Metadata = { title: "Faye admin" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" className={GeistSerif.variable}>
      <body>
        <TooltipProvider delayDuration={300}>{children}</TooltipProvider>
        <Toaster position="bottom-right" />
      </body>
    </html>
  );
}
