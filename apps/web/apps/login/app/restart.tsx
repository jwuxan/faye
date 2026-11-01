"use client";

import { Button } from "@faye/ui/components/button";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { primary } from "./flow-screen";

const SECONDS = 5;

/** After an error, counts down and goes to /login, which starts a new sign in. */
export function Restart() {
  const router = useRouter();
  const [left, setLeft] = useState(SECONDS);
  useEffect(() => {
    if (left === 0) {
      router.replace("/login");
      return;
    }
    const timer = setTimeout(() => setLeft(left - 1), 1000);
    return () => clearTimeout(timer);
  }, [left, router]);
  return (
    <div className="grid gap-3">
      <Button className={primary} onClick={() => router.replace("/login")}>
        Start now
      </Button>
      <p className="text-center text-sm text-muted-foreground" aria-live="polite">
        Starting a new sign in in {left}s
      </p>
    </div>
  );
}
