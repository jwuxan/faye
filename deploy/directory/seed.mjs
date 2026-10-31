// Seeds each tenant's user pool: 1000 accounts over the two tenants, each with a random password
// and some with TOTP. Runs once, in compose's demo service (or from deploy/dev.sh), and writes
// nothing to the repo:
//
//   $DATABASE           the SQLite database Faye and Hydra share (ADR 0017): Faye's tables, which
//                       Faye itself migrates (Flyway, ADR 0020), and the accounts in faye_user,
//                       with Argon2id hashes and TOTP seeds, never a password
//   $SECRETS/users.csv  tenant,identifier,password,totp_secret for people trying sign ins; only this
//                       seed, bootstrap and smoke.sh read the secrets volume, Faye does not
//
// The seed waits until Faye has migrated the schema: faye_schema_history exists, its latest
// migration succeeded, and faye_user exists. Rerunning does nothing once faye_user has accounts,
// so passwords stay stable across restarts. Delete the volumes (docker compose down -v) to get
// new ones.
import { argon2Sync, randomBytes, randomUUID } from "node:crypto";
import { chmodSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { DatabaseSync } from "node:sqlite";

const database = process.env.DATABASE ?? "/data/faye.db";
const secrets = process.env.SECRETS ?? "/secrets";
mkdirSync(dirname(database), { recursive: true });
const db = new DatabaseSync(database);
db.exec("PRAGMA journal_mode = WAL; PRAGMA busy_timeout = 10000;");

/** Faye has migrated when its history table's latest migration succeeded and faye_user exists. */
function migrated() {
  const tables = new Set(
    db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all().map((r) => r.name),
  );
  if (!tables.has("faye_schema_history") || !tables.has("faye_user")) return false;
  const latest = db
    .prepare("SELECT success FROM faye_schema_history ORDER BY installed_rank DESC LIMIT 1")
    .get();
  return latest?.success === 1;
}

const deadline = Date.now() + 180_000;
while (!migrated()) {
  if (Date.now() > deadline) {
    console.error(`${database}: Faye has not migrated the schema; start Faye first`);
    process.exit(1);
  }
  await new Promise((resolve) => setTimeout(resolve, 1000));
}
if (db.prepare("SELECT COUNT(*) AS n FROM faye_user").get().n > 0) {
  console.log(`${database} has accounts; keeping them`);
  db.close();
  process.exit(0);
}

/** Tenant, its email domain, and how many accounts it gets. */
const TENANTS = [
  ["acme", "acme.example", 600],
  ["bank", "bank.example", 400],
];
const FIRST = ["Ava", "Ben", "Chloe", "Daniel", "Elena", "Felix", "Grace", "Hiro", "Isla", "Jonas", "Kai", "Lena", "Mateo", "Nora", "Omar", "Priya", "Quinn", "Rosa", "Sven", "Tara", "Umar", "Vera", "Wen", "Yara", "Zane"];
const LAST = ["Park", "Silva", "Novak", "Chen", "Okafor", "Müller", "Rossi", "Kim", "Haddad", "Larsen", "Ito", "Costa", "Nguyen", "Sato", "Kowalski", "Moreau", "Singh", "Lopez", "Berg", "Ali"];

// Spring Security's Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(), which Faye verifies with
const ARGON2 = { parallelism: 1, tagLength: 32, memory: 16384, passes: 2 };
const b64 = (bytes) => Buffer.from(bytes).toString("base64").replace(/=+$/, "");
function hash(password) {
  const nonce = randomBytes(16);
  const tag = argon2Sync("argon2id", { message: password, nonce, ...ARGON2 });
  return `$argon2id$v=19$m=${ARGON2.memory},t=${ARGON2.passes},p=${ARGON2.parallelism}$${b64(nonce)}$${b64(tag)}`;
}

const BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
function base32(bytes) {
  let bits = 0;
  let value = 0;
  let text = "";
  for (const byte of bytes) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      text += BASE32[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  return bits > 0 ? text + BASE32[(value << (5 - bits)) & 31] : text;
}

/** 16 random characters from a set without look-alikes. */
function password() {
  const set = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  return [...randomBytes(16)].map((b) => set[b % set.length]).join("");
}

const accounts = [];
const rows = ["tenant,identifier,password,totp_secret"];
let n = 0;
for (const [tenant, domain, count] of TENANTS) {
  for (let i = 1; i <= count; i++, n++) {
    const first = FIRST[n % FIRST.length];
    const last = LAST[Math.floor(n / FIRST.length) % LAST.length];
    const identifier = `${first}.${last}.${String(i).padStart(4, "0")}@${domain}`
      .toLowerCase()
      .normalize("NFKD")
      .replace(/[̀-ͯ]/g, "");
    const secret = password();
    // A third of the accounts have an authenticator app enrolled
    const totp = i % 3 === 0 ? base32(randomBytes(20)) : null;
    accounts.push([randomUUID(), identifier, `${first} ${last}`, tenant, hash(secret), totp]);
    rows.push([tenant, identifier, secret, totp ?? ""].join(","));
  }
}
// Hashed above, so the write lock Faye and Hydra wait on is held only for the inserts
const insert = db.prepare(
  "INSERT INTO faye_user (subject, identifier, name, tenant, email_verified, password_hash, totp_secret) VALUES (?, ?, ?, ?, 1, ?, ?)",
);
db.exec("BEGIN");
for (const account of accounts) insert.run(...account);
db.exec("COMMIT");
db.close();

mkdirSync(secrets, { recursive: true });
chmodSync(secrets, 0o700);
writeFileSync(join(secrets, "users.csv"), rows.join("\n") + "\n", { mode: 0o600 });
console.log(`seeded ${n} users into ${database}; their passwords are in ${join(secrets, "users.csv")}`);
