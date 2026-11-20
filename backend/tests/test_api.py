import os
import unittest
from secrets import token_hex

from fastapi.testclient import TestClient

from app import app, assess

PAYLOAD = {
    "package_name": "org.example.fixture",
    "sha256": "a" * 64,
    "permissions": ["android.permission.READ_SMS", "android.permission.RECORD_AUDIO"],
    "consent": True,
}


class RiskTests(unittest.TestCase):
    def test_duplicate_custom_and_framework_permissions(self):
        self.assertEqual(assess(["custom.READ_SMS"]), (0, "low", []))
        score, level, evidence = assess(["android.permission.READ_SMS"] * 2)
        self.assertEqual((score, level, len(evidence)), (25, "moderate", 1))

    def test_consent_and_credentials(self):
        client = TestClient(app)
        try:
            self.assertEqual(client.post("/v1/reports", json=PAYLOAD).status_code, 401)
            headers = {"Authorization": f"Bearer {token_hex(32)}"}
            self.assertEqual(client.post("/v1/reports", headers=headers, json={**PAYLOAD, "consent": False}).status_code, 422)
        finally:
            client.close()


@unittest.skipUnless(os.getenv("DATABASE_URL"), "PostgreSQL connection required")
class IntegrationTests(unittest.TestCase):
    def test_create_list_isolate_and_delete(self):
        headers = {"Authorization": f"Bearer {token_hex(32)}"}
        outsider = {"Authorization": f"Bearer {token_hex(32)}"}
        with TestClient(app) as client:
            self.assertEqual(client.get("/health").json(), {"status": "ok"})
            result = client.post("/v1/reports", headers=headers, json=PAYLOAD)
            self.assertEqual(result.status_code, 201, result.text)
            report = result.json()
            self.assertEqual(report["score"], 40)
            path = f"/v1/reports/{report['id']}"
            self.assertEqual(client.get("/v1/reports", headers=headers).json(), [report])
            self.assertEqual(client.get("/v1/reports", headers=outsider).json(), [])
            self.assertEqual(client.delete(path, headers=outsider).status_code, 404)
            self.assertEqual(client.delete(path, headers=headers).status_code, 204)
            self.assertEqual(client.get("/v1/reports", headers=headers).json(), [])
