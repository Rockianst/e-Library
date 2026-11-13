#!/usr/bin/env python3
"""Against a running E-Library server: python3 scripts/smoke.py [base_url]."""

import json
import sys
import uuid
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def main():
    base = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")

    def request(method, path, payload=None, reader=None, expected=200):
        headers = {"Accept": "application/json"}
        if reader:
            headers["X-Reader-Id"] = reader
        data = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = Request(base + path, data=data, headers=headers, method=method)
        try:
            response = urlopen(req, timeout=15)
        except HTTPError as error:
            response = error
        with response:
            raw = response.read()
            result = json.loads(raw) if raw else None
            if response.code != expected:
                raise RuntimeError(f"{method} {path}: expected {expected}, got {response.code}: {result}")
            return result

    reader = request("POST", "/readers", {"name": "HTTP 验证读者"}, expected=201)["id"]
    prefix = "SMOKE-" + uuid.uuid4().hex
    book = request("POST", "/books", {
        "title": prefix, "author": "Eric Evans", "category": "技术",
        "barcodes": [prefix + "-1", prefix + "-2"],
    }, expected=201)
    found = request("GET", "/books?" + urlencode({"q": prefix, "category": "技术"}))
    assert found["totalElements"] == 1 and found["content"][0]["availableCopies"] == 2
    loan = request("POST", "/loans", {"bookId": book["id"]}, reader, 201)
    assert loan["status"] == "ACTIVE" and loan["renewCount"] == 0
    detail = request("GET", "/books/" + book["id"])
    assert detail["availableCopies"] == 1
    renewed = request("POST", "/loans/" + loan["id"] + "/renewal", reader=reader)
    assert renewed["renewCount"] == 1 and renewed["dueDate"] > loan["dueDate"]
    rejected = request("POST", "/loans/" + loan["id"] + "/renewal", reader=reader, expected=409)
    assert rejected["code"] == "RENEWAL_NOT_ALLOWED"
    returned = request("POST", "/loans/" + loan["id"] + "/return", reader=reader)
    assert returned["status"] == "RETURNED" and returned["returnedAt"]
    rejected = request("POST", "/loans/" + loan["id"] + "/return", reader=reader, expected=409)
    assert rejected["code"] == "LOAN_NOT_ACTIVE"
    assert request("GET", "/books/" + book["id"])["availableCopies"] == 2
    history = request("GET", "/readers/me/loans?status=returned", reader=reader)
    assert history["totalElements"] == 1 and history["content"][0]["id"] == loan["id"]
    print("HTTP smoke passed: search, borrow, renewal, return, inventory, errors, history")
    print(json.dumps({"readerId": reader, "bookId": book["id"], "loanId": loan["id"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
