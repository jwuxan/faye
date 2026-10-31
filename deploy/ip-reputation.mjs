// A stand in for an IP reputation service, for the risk hook demo (tenant acme): Faye's
// FAYE_IP_REPUTATION_URL egress target, run by compose's demo service (or deploy/dev.sh). Not a real
// scorer: GET /score?ip=...&user_agent=... answers {"score": 90} when the user agent contains
// "suspicious" or the IP is in 203.0.113.0/24 (TEST-NET-3, what the Admin UI's "High risk IP"
// preview uses), else {"score": 10}.
import { createServer } from "node:http";

createServer((req, res) => {
  const url = new URL(req.url ?? "/", "http://stub");
  if (url.pathname !== "/score") {
    res.writeHead(404).end();
    return;
  }
  const risky =
    (url.searchParams.get("user_agent") ?? "").toLowerCase().includes("suspicious") ||
    (url.searchParams.get("ip") ?? "").startsWith("203.0.113.");
  const score = risky ? 90 : 10;
  console.log(`ip-reputation: score ${score} for ${url.search}`);
  res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify({ score }));
}).listen(8000, process.env.HOST ?? "::", () => console.log("ip-reputation stub on :8000"));
