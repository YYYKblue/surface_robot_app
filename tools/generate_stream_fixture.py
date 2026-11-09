"""Use the pinned gateway's map encoder, track recorder and diagnostics (no ROS)."""
import argparse
import json
import sys
from array import array
from pathlib import Path
from types import SimpleNamespace as NS
from unittest.mock import patch

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--reference', default='reference/surface_robot_develop_20261109')
args = parser.parse_args()
sys.path.insert(0, str(root / args.reference / 'src/f1_operator_gateway'))
from operator_gateway.map_stream import encode_map
from operator_gateway.tracks import TaskTrack
from operator_gateway.diagnostics import DiagnosticHistory

grid = NS(header=NS(frame_id='map'), info=NS(width=4, height=3, resolution=.05,
    origin=NS(position=NS(x=-1., y=2.), orientation=NS(x=0., y=0., z=0., w=1.))),
    data=array('b', [-1, 0, 50, 51, 100, 0, -1, 100, 0, 0, 0, 100]))
_, encoded = encode_map(grid)
encoded['revision'] = 7
track = TaskTrack()
for second, x, y in [(1., .1, .2), (1.3, .2, .2), (2.4, .4, .2), (2.7, .5, .2)]:
    track.update('fixture-map', 'fixture-run', dict(x=x, y=y,
        stamp=dict(sec=int(second), nanosec=round((second-int(second))*1e9))), recording=True, epoch='runtime-a')
with patch('operator_gateway.diagnostics.time.time', return_value=1794204000.125):
    history = DiagnosticHistory()
    history.session = 'fixture-stream'
    history.record('gateway', 'input_timeout', input_age_ms=310., input_timeout_ms=250.)

frames = [
    dict(type='hello', protocol='f1-operator-v1', session_id='fixture-stream', client_id='fixture-client',
        capabilities=['task_selection', 'initial_pose', 'start_conditions', 'task_tracks', 'diagnostic_history']),
    dict(type='map', runtime_session_id='runtime-a', map=encoded),
    dict(type='tracks', session_id='fixture-stream', tasks=dict(rectangle=track.snapshot())),
    dict(type='diagnostics', diagnostics=history.view()),
    dict(type='telemetry', stream=dict(session='fixture-stream', sequence=1, server_queue_ms=35.),
        control_fresh=True, active_client='fixture-client', task_busy=False, operation_pending=False,
        runtime=dict(session_id='runtime-a', mode='localization', map_available=True),
        localization=dict(map_id='fixture-map', fresh=True),
        tasks=dict(rectangle=dict(task_type='rectangle', phase='STOPPED', fresh=True,
            map_id='fixture-map', run_id='fixture-run'))),
]
out = root / 'app/src/test/resources/gateway-stream.json'
out.write_text(json.dumps(frames, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(f'Generated {len(frames)} messages; map/track/diagnostics from upstream pure Python code; no ROS/hardware.')
