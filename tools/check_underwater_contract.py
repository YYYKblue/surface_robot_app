"""Check Android underwater output against the pinned upstream decoder, without hardware."""
import argparse
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--reference', default='reference/surface_robot_op2_underwater_20261112')
parser.add_argument('--captured', type=Path, help='Additional packets captured from Android WebSocket tests')
args = parser.parse_args()
sys.path.insert(0, str(root / args.reference))
from under_control.protocol import decode_request

fixtures = json.loads((root / 'app/src/test/resources/outbound-underwater.json').read_text(encoding='utf-8'))
if args.captured:
    fixtures += json.loads(args.captured.read_text(encoding='utf-8'))
for message in fixtures:
    assert decode_request(json.dumps(message, ensure_ascii=False)) == message
    assert message['op'] in ('hello', 'manual_input', 'manual_stop')
    assert not any(key in message for key in ('brush_enabled', 'thrust_enabled', 'task_type', 'mode'))

sample = next(message for message in fixtures if message['op'] == 'manual_input')
for speed in range(101):
    assert decode_request(json.dumps(dict(sample, speed_percent=speed)))['speed_percent'] == speed

invalid = []
for field, values in (
    ('speed_percent', (-1, 101, True, False, 25.0, '25', None)),
    ('seq', (-1, 2**64, True, 1.0, '1', None)),
    ('control_generation', (0, -1, 2**63, True, 1.0, '1', None)),
    ('direction', ('UP', '', None, 1)),
    ('protocol', ('f1-operator-v1', '', None)),
    ('device', ('f103', '', None)),
):
    invalid.extend(dict(sample, **{field: value}) for value in values)
hello = next(message for message in fixtures if message['op'] == 'hello')
invalid.extend(dict(hello, version=value) for value in (0, 2, True, '1', 1.0, None))
stop = next(message for message in fixtures if message['op'] == 'manual_stop')
invalid.extend(dict(stop, reason=value) for value in ('', 'x' * 81, 1, None))
invalid.extend(dict(sample, op=value) for value in ('task', 'mapping', 'initial_pose'))
for message in invalid:
    try:
        decode_request(json.dumps(message))
    except ValueError:
        pass
    else:
        raise AssertionError(f'Decoder accepted invalid packet: {message!r}')
for raw in ('[]', '{"protocol":"under-operator-v1","protocol":"under-operator-v1"}', 'x' * 4097):
    try:
        decode_request(raw)
    except ValueError:
        pass
    else:
        raise AssertionError(f'Decoder accepted malformed packet: {raw[:100]!r}')
print(f'{len(fixtures)} underwater outbound packets accepted by {args.reference}; all 101 integer speeds accepted.')
print(f'{len(invalid) + 3} malformed or out-of-range payloads rejected; no ROS, serial port or hardware used.')
