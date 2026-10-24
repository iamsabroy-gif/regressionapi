"""Mock server: POST /generateAccessToken -> bearer token, POST /getCustDetails -> email + mobile.

Run: python3 mock/mock_server.py [port]   (default 8089)
"""
import json
import re
import secrets
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN_TTL_SECONDS = 3600
tokens = {}  # token -> expiry (epoch seconds)

# Fixed customers; any other 6-digit id gets a derived email and mobile.
CUSTOMERS = {"123456": {"Email": "john.doe@example.com", "Mobile": "9876543210"}}


def customer(cust_id):
    return CUSTOMERS.get(cust_id) or {
        "Email": f"cust{cust_id}@example.com",
        "Mobile": "9" + cust_id.zfill(9)[-9:],
    }


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def read_json(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        try:
            return json.loads(raw) if raw.strip() else {}
        except ValueError as e:
            self.reply(400, {"error": "INVALID_JSON", "message": str(e)})
            return None

    def do_POST(self):
        body = self.read_json()
        if body is None:
            return

        if self.path == "/generateAccessToken":
            token = secrets.token_urlsafe(32)
            tokens[token] = time.time() + TOKEN_TTL_SECONDS
            return self.reply(200, {"access_token": token, "token_type": "Bearer",
                                    "expires_in": TOKEN_TTL_SECONDS})

        if self.path == "/getCustDetails":
            auth = self.headers.get("Authorization", "")
            token = auth[7:] if auth.startswith("Bearer ") else ""
            if tokens.get(token, 0) < time.time():
                return self.reply(401, {"error": "INVALID_TOKEN",
                                        "message": "Missing, unknown or expired bearer token"})
            cust_id = str(body.get("CustId", ""))
            if not re.fullmatch(r"\d{6}", cust_id):
                return self.reply(400, {"error": "INVALID_CUSTID",
                                        "message": "CustId must be exactly 6 digits"})
            return self.reply(200, {"CustId": cust_id, "AccountNum": body.get("accountNum"),
                                    **customer(cust_id)})

        self.reply(404, {"error": "NOT_FOUND", "message": self.path})


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8089
    print(f"Mock server on http://localhost:{port}")
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
