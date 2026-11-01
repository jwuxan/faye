import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import "./globals.css";

export const metadata: Metadata = { title: "Sign in" };

/** Light only: the sign in screens never follow the OS into dark. */
export const viewport: Viewport = { colorScheme: "light", themeColor: "#ffffff" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" data-theme="light" className="login-theme">
      <body>
        <main className="flex min-h-svh flex-col items-center px-5 pt-[clamp(--spacing(10),12vh,--spacing(32))] pb-16">{children}</main>
      </body>
    </html>
  );
}
