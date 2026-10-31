import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./globals.css";

export const metadata: Metadata = { title: "Faye demo client" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>
        <main className="mx-auto grid min-h-svh max-w-2xl content-center gap-6 p-4">{children}</main>
      </body>
    </html>
  );
}
