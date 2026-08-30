from concurrent.futures import ThreadPoolExecutor, TimeoutError
import hashlib
import hmac
import json
import threading

import pytest

from fubon_broker_service.bank_balance import BankBalanceService
from fubon_broker_service.realized_gain import RealizedGainService
from fubon_broker_service.sdk_gateway import SdkGateway
from fubon_broker_service.settlement import SettlementService
from fubon_broker_service.trades import TradeReadService

from accounting_fixtures import NOW, bank_row, realized_row, settlement_data, settlement_row
from helpers import TOKEN, account, filled_trade_row, ready_config, response
from test_sdk_gateway import FakeSdk


@pytest.mark.parametrize("method,args,service_type", [
    ("read_bank_balance", (), BankBalanceService),
    ("read_settlement", ("3d",), SettlementService),
    ("read_realized_gains", (), RealizedGainService),
    ("read_filled_trades", ("2026-08-21", "2026-08-21"), TradeReadService),
])
def test_retry_captures_response_account_and_rotated_hmac_key_from_one_session(tmp_path, method, args, service_type):
    loader = ready_config(tmp_path)
    events, created = [], []
    token_after = "ROTATED_FAKE_INTERNAL_TOKEN"
    class RotatingSdk(FakeSdk):
        def __init__(self, first):
            super().__init__(events)
            self.first = first
            self.identity = account() if first else account("002", "00007654321")

        def apikey_login(self, *_args):
            events.append("login")
            return response([self.identity])

        def result(self, data):
            if self.first:
                (tmp_path / "shared" / "internal-service-token").write_text(token_after)
                return response(None, success=False, code=401)
            return response(data)

        def bank_remain(self, selected):
            assert selected is self.identity
            return self.result(bank_row(branch_no=selected.branch_no, account=selected.account))

        def query_settlement(self, selected, range_param):
            assert selected is self.identity and range_param == "3d"
            return self.result(settlement_data([settlement_row()], branch_no=selected.branch_no, account=selected.account))

        def realized_gains_and_loses(self, selected):
            assert selected is self.identity
            return self.result([realized_row(branch_no=selected.branch_no, account=selected.account)])

        def filled_history(self, selected, start, end):
            assert selected is self.identity and start == end == "20260821"
            return self.result([filled_trade_row(branch=selected.branch_no, account_number=selected.account)])

    def factory():
        sdk = RotatingSdk(not created)
        created.append(sdk)
        return sdk
    gateway = SdkGateway(loader, sdk_factory=factory, sleeper=lambda _delay: None)
    try:
        captured = getattr(gateway, method)(*args)
        assert captured.account.branch_no == "002" and captured.account.account_number == "00007654321"
        assert captured.internal_token == token_after and captured.response.is_success is True
        assert events.count("login") == 2
        expected = hmac.new(token_after.encode(), b"002:00007654321", hashlib.sha256).hexdigest()[:24]
        if service_type is TradeReadService:
            normalized = service_type(gateway).read(*args)
        else:
            normalized = service_type(gateway, now=lambda: NOW).read()
        assert normalized["accountFingerprint"] == expected
        serialized = json.dumps(normalized)
        assert token_after not in serialized and "00007654321" not in serialized
        assert token_after not in repr(captured) and "00007654321" not in repr(captured)
        assert all(set(sdk.stock.accessed) <= {"filled_history"} for sdk in created)
    finally:
        gateway.shutdown()


def test_market_session_rotation_cannot_mix_an_inflight_account_response_and_token(tmp_path):
    underlying = ready_config(tmp_path)
    bank_entered, release_bank, rotated_config_seen = threading.Event(), threading.Event(), threading.Event()
    events, created = [], []
    class Loader:
        def load(self):
            config = underlying.load()
            if config.internal_service_token != TOKEN:
                rotated_config_seen.set()
            return config
    class BlockingBank(FakeSdk):
        def bank_remain(self, selected):
            bank_entered.set()
            assert release_bank.wait(2)
            return response(bank_row(branch_no=selected.branch_no, account=selected.account))
    def factory():
        sdk = BlockingBank(events) if not created else FakeSdk(events)
        created.append(sdk)
        return sdk
    gateway = SdkGateway(Loader(), sdk_factory=factory, sleeper=lambda _delay: None)
    try:
        with ThreadPoolExecutor(max_workers=2) as workers:
            bank = workers.submit(gateway.read_bank_balance)
            assert bank_entered.wait(1)
            (tmp_path / "shared" / "internal-service-token").write_text("OTHER_FAKE_TOKEN")
            quote = workers.submit(gateway.quote, "2330")
            assert rotated_config_seen.wait(1)
            try:
                with pytest.raises(TimeoutError):
                    quote.result(timeout=0.05)
                assert events.count("login") == 1
            finally:
                release_bank.set()
            result = bank.result(timeout=1)
            quote.result(timeout=1)
        assert result.internal_token == TOKEN and result.account.account_number == "00001234567"
        assert events.count("login") == 2
    finally:
        release_bank.set()
        gateway.shutdown()
