"""Fail-closed, read-only normalized boundary for the Yuanta SPARK API.

No real `YuantaSparkAPI.dll` or official account is available in this
codebase — see spec/tasks/t364_yuanta_broker_service.md. The real gateway
implementation in sdk_gateway.py is never exercised by the automated tests;
tests exclusively drive a FakeYuantaSparkGateway test double.
"""

__version__ = "1.0.0"
