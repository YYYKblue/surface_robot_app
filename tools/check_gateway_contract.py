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
parser.add_argument('--task-map-management', action='store_true', help='Validate task_resume, map_delete and map_unload commands')
parser.add_argument('--captured', type=Path, help='Also validate commands captured from Android WebSocket tests')
args = parser.parse_args()
sys.path.insert(0, str(root / args.reference / 'src/f1_operator_gateway'))
from operator_gateway.protocol import decode

fixtures = json.loads((root / 'app/src/test/resources/outbound.json').read_text(encoding='utf-8'))
if args.forward_boost:
    fixtures += json.loads((root / 'app/src/test/resources/outbound-forward-boost.json').read_text(encoding='utf-8'))
if args.continuous_drive:
    fixtures += json.loads((root / 'app/src/test/resources/outbound-continuous-drive.json').read_text(encoding='utf-8'))
if args.task_map_management:
    fixtures += json.loads((root / 'app/src/test/resources/outbound-task-map-management.json').read_text(encoding='utf-8'))
if args.captured:
    fixtures += json.loads(args.captured.read_text(encoding='utf-8'))
for message in fixtures:
    parsed = decode(json.dumps(message, ensure_ascii=False))
    assert parsed['op'] == message['op']
    if message.get('action') in ('set_forward_boost', 'resume', 'delete', 'unload'):
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
if args.task_map_management:
    resume = next(p for p in fixtures if p.get('action') == 'resume')
    deletion = next(p for p in fixtures if p.get('action') == 'delete')
    invalid_messages = [dict(resume, run_id=v) for v in ('', None, 1, 'r' * 81)]
    invalid_messages += [dict(resume, request_id='')]
    invalid_messages += [dict(deletion, snapshot_id=v) for v in ('abc', '../bad', 'A' * 32, True, None)]
    for invalid in invalid_messages:
        try:
            decode(json.dumps(invalid))
        except ValueError:
            pass
        else:
            raise AssertionError(f'Decoder accepted invalid identity: {invalid!r}')
    for confirmation in (False, 'true', 1, None):
        assert decode(json.dumps(dict(deletion, confirm=confirmation)))['confirm'] is False
    print('Resume/map identity: 10 invalid payloads rejected; deletion requires literal true confirmation.')
print(f'{len(fixtures)} outbound fixtures accepted by {args.reference} decoder; no ROS/hardware used.')
