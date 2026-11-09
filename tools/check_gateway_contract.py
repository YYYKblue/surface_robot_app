"""Validate Android outbound fixtures using the pinned project's actual decoder.

No ROS initialization and no hardware connection. Run from project root.
"""
import json
import argparse
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--reference', default='reference/surface_robot_Lplus_geometry')
parser.add_argument('--forward-boost', action='store_true', help='Also validate capability-gated boost commands on a new gateway')
args = parser.parse_args()
sys.path.insert(0, str(root / args.reference / 'src/f1_operator_gateway'))
from operator_gateway.protocol import decode

fixtures = json.loads((root / 'app/src/test/resources/outbound.json').read_text(encoding='utf-8'))
if args.forward_boost:
    fixtures += json.loads((root / 'app/src/test/resources/outbound-forward-boost.json').read_text(encoding='utf-8'))
for message in fixtures:
    parsed = decode(json.dumps(message, ensure_ascii=False))
    assert parsed['op'] == message['op']
    if message.get('action') == 'set_forward_boost':
        assert parsed == message
print(f'{len(fixtures)} outbound fixtures accepted by {args.reference} decoder; no ROS/hardware used.')
