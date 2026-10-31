#!/usr/bin/env bash
# Run the atom the way the runner does, against a Tomcat Manager endpoint that is written from the protocol,
# not from the atom: no Jenkins, no container.
#
# What is being verified
#   · the WAR bytes that arrive hash to the WAR on the workspace (the manager computes its own SHA-256);
#   · the context path is derived the way Tomcat derives it, and the update flag is what the inputs asked for;
#   · success is the readback: a 200 "OK" with the application missing or stopped must fail the step
#     (a real Chinese-locale Tomcat answers 200 + "失败 - ..." and registers a corrupt WAR as "stopped");
#   · every failure path has its own assertion: 401, 404 (wrong manager path), "already exists" with update=false,
#     500, input refusals that must not upload anything;
#   · a manager that accepts the WAR but writes it short is caught by the digest comparison, and that run is
#     expected to be red, which is what makes the digest check worth anything;
#   · with REAL_DEPLOY_URL / REAL_DEPLOY_USER / REAL_DEPLOY_PASSWORD set, the same atom is pointed at a real
#     Tomcat and the deployed application is fetched back over HTTP.
set -euo pipefail
cd "$(dirname "$0")/.."

export BUILD_NUMBER=42
./build.sh >/dev/null
jar="$(cd dist/deploy-war && pwd)/deploy-war.jar"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
jar_bin="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
fail() { echo "smoke failed: $*" >&2; exit 1; }
ok() { echo "ok: $*"; }

server_pid=""
stop_server() {
  if [ -n "$server_pid" ]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
    server_pid=""
  fi
}
trap stop_server EXIT

start_server() { # start_server <state-dir> [flags...]
  local state="$1"; shift
  python3 tools/fake_manager.py --state "$state" --port 0 "$@" >"$state.log" 2>&1 &
  server_pid=$!
  local i
  for i in $(seq 1 200); do
    if grep -q '"port"' "$state.log" 2>/dev/null; then break; fi
    sleep 0.05
  done
  grep -q '"port"' "$state.log" || fail "fake manager did not start: $(cat "$state.log")"
  port=$(python3 -c 'import json,sys;print(json.loads(open(sys.argv[1]).readline())["port"])' "$state.log")
}

make_war() { # make_war <path> <marker>
  local target="$1" marker="$2" stage
  stage="$(mktemp -d)"
  printf '<html><body>%s</body></html>' "$marker" >"$stage/index.html"
  printf 'w:%s' "$marker" >"$stage/payload.txt"
  mkdir -p "$(dirname "$target")"
  ( cd "$stage" && "$jar_bin" cf "$target" index.html payload.txt )
}

write_input() { printf '%s' "$2" >"$1/input.json"; }

run_atom() { # run_atom <dir>
  local dir="$1"
  ( cd "$dir" && env atom_data_dir="$dir" atom_data_input=input.json atom_data_output=output.json \
      atom_workspace="$dir" "$java_bin" -cp "$jar" com.jenkinschina.atom.sdk.AtomRunner ) \
    >"$dir/log.txt" 2>&1
}

secret() { printf '{"password":"%s"}' "$1" >"$2/.secrets.json"; }

check_ok() { # check_ok <dir> — independent recomputation, compared with output.json
  local dir="$1"
  python3 tools/check.py --input "$dir/input.json" --output "$dir/output.json" \
    --workspace "$dir" --state "$dir/server" --expect success \
    || fail "independent check disagreed in $dir"
}

check_error() { # check_error <dir> <code> <message-fragment>
  local dir="$1" code="$2" message="$3"
  python3 tools/check.py --input "$dir/input.json" --output "$dir/output.json" \
    --workspace "$dir" --state "$dir/server" --expect error --code "$code" --message "$message" \
    || fail "expected error $code ($message) in $dir, got: $(head -c 400 "$dir/output.json")"
}

expect_error() { # expect_error <dir> <code> <message> — the atom must exit non-zero
  if run_atom "$1"; then fail "atom succeeded, expected error $2 ($1/log.txt)"; fi
  check_error "$1" "$2" "$3"
}

#################################################################### 1. two WARs, derived context paths, zh locale
d=$(mktemp -d); make_war "$d/target/app.war" alpha; make_war "$d/target/shop-v2.war" beta
start_server "$d/server"
write_input "$d" "{\"url\":\"http://127.0.0.1:$port\",\"username\":\"deployer\",\"war\":\"target/*.war\"}"
secret s3cret-deploy "$d"
run_atom "$d" || fail "two-war deploy failed: $(tail -5 "$d/log.txt")"
check_ok "$d"
grep -q '"contextPaths":{"type":"string","value":"/app,/shop-v2"}' "$d/output.json" \
  || fail "contextPaths not /app,/shop-v2"
python3 - "$d" <<'PY' || fail "the deployed bytes are not the war from the workspace"
import hashlib, json, sys, os
d = sys.argv[1]
registry = json.load(open(os.path.join(d, "server", "apps.json")))
for ctx, war in (("/app", "app.war"), ("/shop-v2", "shop-v2.war")):
    disk = hashlib.sha256(open(os.path.join(d, "target", war), "rb").read()).hexdigest()
    assert registry[ctx]["sha256"] == disk, (ctx, registry[ctx]["sha256"], disk)
print("  digests match")
PY
stop_server; ok "two WARs deployed at derived context paths, bytes verified by the manager"

#################################################################### 2. explicit context path, full manager URL
d=$(mktemp -d); make_war "$d/build/chosen.war" gamma
start_server "$d/server"
write_input "$d" "{\"url\":\"http://127.0.0.1:$port/manager/text\",\"username\":\"deployer\",\"war\":\"build/chosen.war\",\"contextPath\":\"/chosen\"}"
secret s3cret-deploy "$d"
run_atom "$d" || fail "explicit context deploy failed: $(tail -5 "$d/log.txt")"
check_ok "$d"
grep -q '/chosen=running' "$d/output.json" || fail "states missing /chosen=running"
stop_server; ok "explicit context path used, a full /manager/text URL accepted"

#################################################################### 3. ROOT.war -> /
d=$(mktemp -d); make_war "$d/ROOT.war" rootapp
start_server "$d/server"
write_input "$d" "{\"url\":\"http://127.0.0.1:$port\",\"username\":\"deployer\",\"war\":\"ROOT.war\"}"
secret s3cret-deploy "$d"
run_atom "$d" || fail "ROOT.war deploy failed: $(tail -5 "$d/log.txt")"
check_ok "$d"
grep -q '/=running' "$d/output.json" || fail "ROOT.war did not land at /"
stop_server; ok "ROOT.war deploys at / (Tomcat's root convention)"

#################################################################### 4. input refusals upload nothing
d=$(mktemp -d); make_war "$d/a.war" one; make_war "$d/b.war" two
start_server "$d/server"
write_input "$d" "{\"url\":\"http://127.0.0.1:$port\",\"username\":\"deployer\",\"war\":\"*.war\",\"contextPath\":\"/one\"}"
secret s3cret-deploy "$d"
expect_error "$d" 40008 "one context path cannot name them all"
stop_server; ok "two archives with one explicit context path refused before any upload"

d=$(mktemp -d); mkdir -p "$d/target"; start_server "$d/server"
write_input "$d" "{\"url\":\"http://127.0.0.1:$port\",\"username\":\"deployer\",\"war\":\"target/*.war\"}"
secret s3cret-deploy "$d"
expect_error "$d" 40007 "no WAR/EAR matched"
stop_server; ok "empty glob refused"

d=$(mktemp -d); make_war "$d/a.war" one; start_server "$d/server"
write_input "$d" "{\"url\":\"http://127.0.0.1:$port\",\"username\":\"deployer\",\"war\":\"a.war\",\"contextPath\":\"no-slash\"}"
secret s3cret-deploy "$d"
expect_error "$d" 40009 "must start with"
stop_server; ok "context path without a leading slash refused before upload"

d=$(mktemp -d); make_war "$d/a.war" one; start_server "$d/server"
write_input "$d" "{\"url\":\"127.0.0.1:$port\",\"username\":\"deployer\",\"war\":\"a.war\"}"
secret s3cret-deploy "$d"
expect_error "$d" 40006 "must start with http"
stop_server; ok "scheme-less url refused"

#################################################################### 5. the container answers, and it says no
d=$(mktemp -d); make_war "$d/a.war" one; start_server "$d/server"
write_input "$d" "{\"url\":\"http://127.0.0.1:$port\",\"username\":\"deployer\",\"war\":\"a.war\"}"
secret wrong-password "$d"
expect_error "$d" 40011 "rejected the credentials"
grep -q '"authorized": false' "$d/server/requests.jsonl" || fail "the manager did not see the wrong password"
stop_server; ok "401 from the manager becomes a USER error naming the manager-script role"

d=$(mktemp -d); make_war "$d/a.war" one; start_server "$d/server" --deny
write_input "$d" "{\"url\":\"http://127.0.0.1:$port\",\"username\":\"deployer\",\"war\":\"a.war\"}"
secret s3cret-deploy "$d"
expect_error "$d" 40011 "rejected the credentials"
stop_server; ok "--deny server: 401 classified as USER, not as a container fault"

d=$(mktemp -d); make_war "$d/a.war" one; start_server "$d/server" --manager-path /other/manager
write_input "$d" "{\"url\":\"http://127.0.0.1:$port\",\"username\":\"deployer\",\"war\":\"a.war\"}"
secret s3cret-deploy "$d"
expect_error "$d" 40013 "no manager application at"
stop_server; ok "a wrong manager path becomes a 404 hint about the URL, not a silent no-op"

d=$(mktemp -d); make_war "$d/a.war" one; start_server "$d/server"
write_input "$d" "{\"url\":\"http://127.0.0.1:$port\",\"username\":\"deployer\",\"war\":\"a.war\",\"update\":\"false\"}"
secret s3cret-deploy "$d"
run_atom "$d" || fail "first deploy with update=false failed: $(tail -5 "$d/log.txt")"
check_ok "$d"
secret s3cret-deploy "$d"   # the runner deletes .secrets.json after each run
if run_atom "$d"; then fail "second deploy with update=false reported success"; fi
check_error "$d" 40016 "已存在"
stop_server; ok "re-deploy with update=false: 200 plus localised '失败 - 已存在' turned into a USER error"

d=$(mktemp -d); make_war "$d/a.war" one; start_server "$d/server" --status 500
write_input "$d" "{\"url\":\"http://127.0.0.1:$port\",\"username\":\"deployer\",\"war\":\"a.war\"}"
secret s3cret-deploy "$d"
expect_error "$d" 40014 "HTTP 500"
stop_server; ok "container 500 classified as THIRD_PART"

#################################################################### 6. 200 OK is not success: the readback decides
d=$(mktemp -d); make_war "$d/a.war" one; start_server "$d/server" --no-register
write_input "$d" "{\"url\":\"http://127.0.0.1:$port\",\"username\":\"deployer\",\"war\":\"a.war\"}"
secret s3cret-deploy "$d"
expect_error "$d" 40020 "nothing was deployed"
stop_server; ok "an OK reply with an empty application list fails the step"

d=$(mktemp -d); make_war "$d/a.war" one; start_server "$d/server" --stopped
write_input "$d" "{\"url\":\"http://127.0.0.1:$port\",\"username\":\"deployer\",\"war\":\"a.war\"}"
secret s3cret-deploy "$d"
expect_error "$d" 40021 "state 'stopped'"
stop_server; ok "an OK reply with the application stopped fails the step (the corrupt-WAR shape)"

#################################################################### 7. the digest check has teeth
d=$(mktemp -d); make_war "$d/a.war" one; start_server "$d/server" --short-write
write_input "$d" "{\"url\":\"http://127.0.0.1:$port\",\"username\":\"deployer\",\"war\":\"a.war\"}"
secret s3cret-deploy "$d"
run_atom "$d" || fail "atom failed against the short-writing manager, which it cannot detect: $(tail -3 "$d/log.txt")"
if python3 tools/check.py --input "$d/input.json" --output "$d/output.json" --workspace "$d" \
     --state "$d/server" --expect success 2>"$d/check.err"; then
  fail "the digest comparison did not notice the manager storing 100 bytes fewer"
fi
grep -q "hashes to" "$d/check.err" || fail "check failed for the wrong reason: $(cat "$d/check.err")"
stop_server; ok "a short-written WAR is caught by the independent digest comparison"

#################################################################### 8. locale and placeholders
d=$(mktemp -d); make_war "$d/a.war" one; start_server "$d/server" --locale en
write_input "$d" "{\"url\":\"http://127.0.0.1:$port\",\"username\":\"deployer\",\"war\":\"a.war\",\"contextPath\":\"/app-\${BUILD_NUMBER}\",\"BUILD_NUMBER\":\"42\"}"
secret s3cret-deploy "$d"
run_atom "$d" || fail "en-locale deploy failed: $(tail -5 "$d/log.txt")"
check_ok "$d"
grep -q '"contextPaths":{"type":"string","value":"/app-42"}' "$d/output.json" || fail "placeholder not expanded"
stop_server; ok "en-locale manager, BUILD_NUMBER placeholder expanded into the context path"

#################################################################### 9. a real container, when one is offered
if [ -n "${REAL_DEPLOY_URL:-}" ]; then
  d=$(mktemp -d); make_war "$d/dist/real.war" "real-$RANDOM"
  write_input "$d" "{\"url\":\"$REAL_DEPLOY_URL\",\"username\":\"$REAL_DEPLOY_USER\",\"war\":\"dist/real.war\",\"contextPath\":\"/atom-smoke\"}"
  printf '{"password":"%s"}' "$REAL_DEPLOY_PASSWORD" >"$d/.secrets.json"
  run_atom "$d" || fail "real container deploy failed: $(tail -10 "$d/log.txt")"
  grep -q '/atom-smoke=running' "$d/output.json" || fail "real container: /atom-smoke not running"
  base="${REAL_DEPLOY_URL%/manager*}"
  served="$(curl -sS "$base/atom-smoke/payload.txt")"
  case "$served" in w:real-*) : ;; *) fail "real container served '$served', not the deployed war";; esac
  curl -sS -u "$REAL_DEPLOY_USER:$REAL_DEPLOY_PASSWORD" "$base/manager/text/undeploy?path=/atom-smoke" >/dev/null
  ok "real container: deployed, served $served, undeployed again"
fi

echo "smoke: all scenarios passed"
