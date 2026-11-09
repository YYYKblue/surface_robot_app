"""Generate replay fixtures with the upstream pure Python recorder; no ROS."""
import argparse
import json
import sys
from pathlib import Path
from unittest.mock import patch

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--reference', default='reference/surface_robot_develop_20261109')
args = parser.parse_args()
sys.path.insert(0, str(root / args.reference / 'src/f1_operator_gateway'))
from operator_gateway.diagnostics import DiagnosticHistory

def frame(history):
    return json.loads(json.dumps(dict(type='telemetry', control_fresh=True, diagnostics=history.view())))

with patch('operator_gateway.diagnostics.time.time', return_value=1794096000.125):
    history = DiagnosticHistory()
    history.session = 'fixture-gateway-1'
    history.record('gateway', 'input_timeout', input_age_ms=310.0, input_timeout_ms=250.0, had_lease=True)
    first = frame(history)
    history.bridge(dict(state='FAULT', source='NANO', owner='', motion_authorized=False,
                        reason='ACK progress timeout', last_stop_reason='ACK progress timeout',
                        ctrl_age_sec=.02, ack_age_sec=.35, command_remaining_sec=0,
                        reconnects=1, max_tx_gap_sec=.21, rejected_commands=3))
    second = frame(history)
    for i in range(55):
        history.record('gateway', 'app_disconnected', input_age_ms=i, input_timeout_ms=250)
    overrun = frame(history)
    restart = DiagnosticHistory()
    restart.session = 'fixture-gateway-2'
    restart.record('bridge', 'CTRL timeout', ctrl_age_sec=.5)
    frames = [first, first, second, overrun, frame(restart)]

out = root / 'app/src/test/resources/gateway-diagnostics.json'
out.write_text(json.dumps(frames, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'Generated {len(frames)} telemetry frames using the upstream DiagnosticHistory.')
