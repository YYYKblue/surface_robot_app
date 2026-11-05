"""Validate Android outbound fixtures using the pinned project's actual decoder.

No ROS initialization and no hardware connection. Run from project root.
"""
import json
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'reference/surface_robot/src/f1_operator_gateway'))
from operator_gateway.protocol import decode

fixtures = json.loads((root / 'app/src/test/resources/outbound.json').read_text(encoding='utf-8'))
for message in fixtures:
    parsed = decode(json.dumps(message, ensure_ascii=False))
    assert parsed['op'] == message['op']
print(f'{len(fixtures)} outbound fixtures accepted by original gateway decoder; no ROS/hardware used.')
