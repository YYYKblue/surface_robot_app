"""Validate Android outbound fixtures using the pinned project's actual decoder.

No ROS initialization and no hardware connection. Run from project root.
"""
import json
import argparse
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--reference', default='reference/surface_robot_develop_20261109')
parser.add_argument('--forward-boost', action='store_true', help='Also validate capability-gated boost commands on a new gateway')
parser.add_argument('--continuous-drive', action='store_true', help='Validate 0..100 integer output on a continuous_drive_percent_v1 gateway')
args = parser.parse_args()
sys.path.insert(0, str(root / args.reference / 'src/f1_operator_gateway'))
from operator_gateway.protocol import decode

fixtures = json.loads((root / 'app/src/test/resources/outbound.json').read_text(encoding='utf-8'))
if args.forward_boost:
    fixtures += json.loads((root / 'app/src/test/resources/outbound-forward-boost.json').read_text(encoding='utf-8'))
if args.continuous_drive:
    fixtures += json.loads((root / 'app/src/test/resources/outbound-continuous-drive.json').read_text(encoding='utf-8'))
for message in fixtures:
    parsed = decode(json.dumps(message, ensure_ascii=False))
    assert parsed['op'] == message['op']
    if message.get('action') == 'set_forward_boost':
        assert parsed == message
    if message['op'] == 'manual_input':
        value = parsed['input']
        assert value.direction == message['direction']
        assert value.speed_percent == message['speed_percent']
        assert value.mode == message['mode']
        assert value.brush_enabled == message['brush_enabled']
        assert value.brush_percent == message['brush_percent']
        assert value.thrust_enabled == message['thrust_enabled']
        assert value.thrust_percent == message['thrust_percent']
if args.continuous_drive:
    sample = next(message for message in fixtures if message['op'] == 'manual_input')
    for speed in range(101):
        assert decode(json.dumps(dict(sample, speed_percent=speed)))['input'].speed_percent == speed
    for invalid in (-1, 101, True, False, 25.0, 37.5, '37', None):
        try:
            decode(json.dumps(dict(sample, speed_percent=invalid)))
        except ValueError:
            pass
        else:
            raise AssertionError(f'Decoder accepted invalid speed: {invalid!r}')
    print('Continuous output: all 101 integer values accepted; 8 invalid values rejected.')
print(f'{len(fixtures)} outbound fixtures accepted by {args.reference} decoder; no ROS/hardware used.')
