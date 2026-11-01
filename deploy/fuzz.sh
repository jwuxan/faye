#!/usr/bin/env bash
# Coverage guided fuzzing of every harness (ADR 0007), one JVM per harness since Jazzer fuzzes one
# test per run. ./gradlew build already replays every stored input; this searches for new ones.
#
#   deploy/fuzz.sh [duration per harness, default 2m] [harness filter, default all]
#
# A finding fails the run. Jazzer writes the input that found it as crash-* in the harness's inputs
# directory (git ignored); turn it into a named regression test before fixing, and do not attach it
# to a public log or issue (ADR 0007, Triage). The corpus grows in each module's .cifuzz-corpus/.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)

duration="${1:-2m}"
filter="${2:-}"

# build          module       test (class.method)
harnesses=(
  "src           faye-core    faye.ir.IrCodecFuzzTest.readFlow"
  "src           faye-core    faye.dsl.FlowCompilerFuzzTest.compile"
  "src           faye-engine  faye.engine.PredicatesFuzzTest.predicates"
  "src           faye-engine  faye.engine.PredicatesFuzzTest.acr"
  "src           faye-engine  faye.engine.StoredStateFuzzTest.storedState"
  "src           faye-engine  faye.check.EngineFuzzTest.loginStateMachine"
  "src           faye-engine  faye.handlers.BuiltinHandlersFuzzTest.submissions"
  "apps/server   faye-identity faye.identity.TotpFuzzTest.base32"
  "apps/server   faye-identity faye.identity.TotpFuzzTest.totp"
  "apps/server   faye-app     faye.app.FlowApiFuzzTest.screenApi"
  "apps/server   faye-app     faye.app.QrGrantsFuzzTest.grantStore"
)

failed=()
for h in "${harnesses[@]}"; do
  read -r dir module test <<<"$h"
  [[ -n "$filter" && "$test" != *"$filter"* ]] && continue
  echo "== $test ($duration)"
  if ! (cd "$root/$dir" && JAZZER_FUZZ=1 JAZZER_MAX_DURATION="$duration" ./gradlew --console=plain -q ":$module:test" --tests "$test"); then
    failed+=("$test")
  fi
done

if ((${#failed[@]} > 0)); then
  echo "findings in: ${failed[*]}" >&2
  exit 1
fi
echo "no findings"
