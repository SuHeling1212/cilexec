#!/usr/bin/env python3
# ==============================================================================
# CilExec Market — one-shot package upload for external developers
#
# Uploads a package database to a CilExec market over HTTP. A publish token from
# the market administrator is required; the token is sent only over HTTPS or a
# trusted network.
#
# Usage: python3 MarketPublish.py [options] <package.db>
#   --url BASE         market URL (default: http://127.0.0.1:8787)
#   --token TOKEN      publish token (required; also read from CILEXEC_MARKET_TOKEN)
#   --summary TEXT     override the package summary
#   --description TEXT override the package description
#   --tags a,b,c       override the package tags
# ==============================================================================
import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request


def parse_args(argv):
    parser = argparse.ArgumentParser(
        prog="MarketPublish.py",
        description="Upload a package database to a CilExec market.")
    parser.add_argument("package", metavar="package.db",
                        help="package database to publish")
    parser.add_argument("--url", default="http://127.0.0.1:8787",
                        help="market base URL (default: http://127.0.0.1:8787)")
    parser.add_argument("--token", default=None,
                        help="publish token (default: $CILEXEC_MARKET_TOKEN)")
    parser.add_argument("--summary", default=None)
    parser.add_argument("--description", default=None)
    parser.add_argument("--tags", default=None)
    return parser.parse_args(argv)


def main(argv):
    args = parse_args(argv)
    token = args.token or __import__("os").environ.get("CILEXEC_MARKET_TOKEN")
    if not token:
        print("Error: --token is required (or set CILEXEC_MARKET_TOKEN)", file=sys.stderr)
        return 2
    try:
        with open(args.package, "rb") as source:
            payload = source.read()
    except OSError as failure:
        print(f"Error: cannot read {args.package}: {failure}", file=sys.stderr)
        return 2

    query = {}
    if args.summary is not None:
        query["summary"] = args.summary
    if args.description is not None:
        query["description"] = args.description
    if args.tags is not None:
        query["tags"] = args.tags
    suffix = "?" + urllib.parse.urlencode(query) if query else ""
    url = args.url.rstrip("/") + "/market/v1/publish" + suffix

    request = urllib.request.Request(url, data=payload, method="POST")
    request.add_header("Authorization", "Bearer " + token)
    request.add_header("Content-Type", "application/vnd.sqlite3")
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            result = json.loads(response.read().decode("utf-8"))
            print(f"Published {result.get('coordinate', '?')}")
            print(f"sha256={result.get('sha256', '?')}")
            print(f"Stored at: {result.get('storedAt', '?')}")
            return 0
    except urllib.error.HTTPError as failure:
        body = failure.read().decode("utf-8", errors="replace").strip()
        print(f"Error {failure.code}: {body or failure.reason}", file=sys.stderr)
        return 1
    except urllib.error.URLError as failure:
        print(f"Error: cannot reach {url}: {failure.reason}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
