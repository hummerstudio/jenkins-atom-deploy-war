#!/usr/bin/env python3
"""Second implementation: recompute what the atom should have done, then compare with what it reported.

The atom is Java. This is Python, written from the same rules but not sharing a line with it: the glob walk,
the context-path derivation, the update flag, the URL the request goes to, and what "success" means. It reads
three sources of truth and they have to agree:

  · the manager's request log (tools/fake_manager.py) -- what was actually sent, and the SHA-256 of the bytes
    that arrived;
  · the manager's application registry -- what the container believes it has, and in which state;
  · output.json -- what the atom told Jenkins.

With --expect error it checks the opposite: that the atom failed, with the documented error code, and that
no deploy request was accepted after the point of failure.
"""
import argparse
import hashlib
import json
import os
import re
import sys


def ant_to_regex(pattern):
    out = []
    for i, part in enumerate(pattern.split("/")):
        if part == "**":
            out.append("(?:[^/]+/)*" if i < len(pattern.split("/")) - 1 else ".*")
        else:
            out.append(re.escape(part).replace(r"\*", "[^/]*").replace(r"\?", "[^/]") + ("/" if i < len(pattern.split("/")) - 1 else ""))
    return "^" + "".join(out) + "$"


def find_wars(workspace, pattern):
    found = []
    expressions = [re.compile(ant_to_regex(p.strip())) for p in pattern.split(",") if p.strip()]
    for root, _dirs, files in os.walk(workspace):
        for name in files:
            rel = os.path.relpath(os.path.join(root, name), workspace).replace(os.sep, "/")
            if any(e.match(rel) for e in expressions):
                found.append(rel)
    return sorted(found)


def derive_context(war):
    name = war.rsplit("/", 1)[-1]
    for extension in (".war", ".ear"):
        if name.lower().endswith(extension):
            name = name[: -len(extension)]
    return "/" if name.lower() == "root" else "/" + name


def expand(value, params):
    def replace(match):
        name = match.group(1) or match.group(2)
        if name not in params or str(params[name]) == "":
            raise SystemExit("check: %r refers to ${%s}, which is not set" % (value, name))
        return str(params[name])
    return re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_.]*)}|\$([A-Za-z_][A-Za-z0-9_]*)", replace, value)


def normalize_context(value):
    value = value.strip()
    if value in ("", "/", "ROOT"):
        return "/"
    if not value.startswith("/"):
        raise SystemExit("check: explicit contextPath does not start with '/'")
    return value.rstrip("/")


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def expected_manager_path(url, manager_path):
    """The URL rule, written out again: an address that already carries /manager keeps it."""
    trimmed = url.rstrip("/")
    from urllib.parse import urlparse
    path = urlparse(trimmed).path or ""
    if "/manager" in path:
        base = trimmed
        if manager_path and not base.endswith(manager_path):
            base = base + (manager_path if manager_path.startswith("/") else "/" + manager_path)
        return base, base + "/deploy", base + "/list"
    suffix = manager_path or "/manager/text"
    if not suffix.startswith("/"):
        suffix = "/" + suffix
    base = trimmed + suffix
    return base, base + "/deploy", base + "/list"


def load_requests(state):
    path = os.path.join(state, "requests.jsonl")
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


def field(data, key):
    """output.json keeps typed entries: {"type": "string", "value": ...}."""
    entry = data.get(key)
    if isinstance(entry, dict):
        return entry.get("value")
    return entry


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--workspace", required=True)
    parser.add_argument("--state", required=True)
    parser.add_argument("--expect", choices=["success", "error"], default="success")
    parser.add_argument("--code", type=int, default=0)
    parser.add_argument("--message", default="")
    args = parser.parse_args()

    params = json.load(open(args.input, encoding="utf-8"))
    output = json.load(open(args.output, encoding="utf-8"))
    requests = load_requests(args.state)
    failures = []

    def fail(message):
        failures.append(message)

    if args.expect == "error":
        if output.get("status") == "success":
            fail("atom reported success, expected error %d" % args.code)
        if args.code and output.get("errorCode") != args.code:
            fail("errorCode is %r, expected %d (%s)" % (output.get("errorCode"), args.code, output.get("message")))
        if args.message and args.message not in (output.get("message") or ""):
            fail("message %r does not contain %r" % (output.get("message"), args.message))
        accepted = [r for r in requests if r.get("method") == "PUT" and r.get("authorized")]
        if args.code == 40008 or args.code == 40009 or args.code == 40005 or args.code == 40007:
            # refused before anything was uploaded
            if accepted:
                fail("a request was uploaded although the atom should have refused the input: %r" % accepted)
    else:
        war_pattern = params.get("war", "**/*.war")
        wars = find_wars(args.workspace, war_pattern)
        explicit = normalize_context(expand(params["contextPath"], params)) if params.get("contextPath") else None
        expected = []
        for war in wars:
            ctx = explicit if explicit else derive_context(war)
            expected.append({
                "war": war,
                "context": ctx,
                "sha256": digest(os.path.join(args.workspace, war)),
                "update": str(params.get("update", "true")).lower() in ("true", "1", "yes", ""),
            })
        _base, deploy_url, _list_url = expected_manager_path(
            params.get("url", ""), params.get("managerPath", "") or "")
        from urllib.parse import urlparse
        deploy_path = urlparse(deploy_url).path

        sent = [r for r in requests if r.get("method") == "PUT" and r.get("authorized")]
        if len(sent) != len(expected):
            fail("manager saw %d authorised deploy requests, expected %d" % (len(sent), len(expected)))
        by_context = {}
        for request in sent:
            if request.get("path") != deploy_path:
                fail("deploy went to %r, expected %r" % (request.get("path"), deploy_path))
            by_context[request["params"]["path"]] = request
        for item in expected:
            # ROOT travels as an empty path= value; the container displays it as "/"
            wire = "" if item["context"] == "/" else item["context"]
            request = by_context.get(wire)
            if request is None:
                fail("no deploy request for context %r (saw %r)" % (item["context"], sorted(by_context)))
                continue
            if request.get("sha256") != item["sha256"]:
                fail("bytes that arrived for %s hash to %s, the war on disk is %s"
                     % (item["context"], request.get("sha256"), item["sha256"]))
            if request.get("params", {}).get("update") != item["update"]:
                fail("update flag for %s was %r, expected %r"
                     % (item["context"], request.get("params", {}).get("update"), item["update"]))

        # what the container believes, read from its own registry rather than from the atom's log line
        registry_path = os.path.join(args.state, "apps.json")
        registry = json.load(open(registry_path, encoding="utf-8")) if os.path.exists(registry_path) else {}
        for item in expected:
            applet = registry.get(item["context"])
            if applet is None:
                fail("container has no application at %r" % item["context"])
            elif applet.get("state") != "running":
                fail("container has %r in state %r" % (item["context"], applet.get("state")))
            elif applet.get("sha256") != item["sha256"]:
                fail("what the container stored for %r hashes to %s, the war on disk is %s"
                     % (item["context"], applet.get("sha256"), item["sha256"]))

        data = output.get("data") or {}
        if output.get("status") != "success":
            fail("atom status is %r: %s" % (output.get("status"), output.get("message")))
        if str(field(data, "deployed")) != str(len(expected)):
            fail("deployed=%r, expected %d" % (field(data, "deployed"), len(expected)))
        reported = [c for c in (field(data, "contextPaths") or "").split(",") if c]
        if sorted(reported) != sorted(item["context"] for item in expected):
            fail("contextPaths=%r, expected %r" % (reported, [item["context"] for item in expected]))
        for item in expected:
            if ("%s=running" % item["context"]) not in (field(data, "states") or ""):
                fail("states=%r does not say %s=running" % (field(data, "states"), item["context"]))
        if field(data, "verified") != "true":
            fail("verified=%r" % field(data, "verified"))
        if not (field(data, "managerUrl") or "").rstrip("/").endswith(deploy_url.rstrip("/").replace("/deploy", "")):
            fail("managerUrl=%r does not match the expected manager base" % field(data, "managerUrl"))

    if failures:
        for message in failures:
            print("  check: " + message, file=sys.stderr)
        return 1
    print("check: ok (%s)" % ("error %d" % args.code if args.expect == "error" else "%d archive(s)" % len(expected)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
