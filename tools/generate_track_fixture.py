"""Replay real upstream centre-target geometry without ROS or hardware."""
import argparse
import json
import math
import sys
from pathlib import Path
from dataclasses import replace

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--reference', default='reference/surface_robot_Lplus_geometry')
args = parser.parse_args()
sys.path.insert(0, str(root / args.reference / 'src/f1_pool_cleaning'))
from f1_pool_cleaning.config import Config
from f1_pool_cleaning.geometry import PoolModel
from f1_pool_cleaning.expected_track import cleaning_segments

c = Config(origin_x_m=.2, origin_y_m=.2, axis_yaw_rad=0, length_m=7.6, width_m=4.6)
walls = {
    'rectangle': [[.2, 4.8], [7.8, 4.8], [7.8, .2], [.2, .2], [.2, 4.8]],
    'l': [[1., 1.], [3., 1.], [3., 2.], [5., 2.], [5., 3.5], [7., 3.5]],
}
cases = []
for kind in ('rectangle', 'l'):
    for variant in ('default', 'rotated_suffix'):
        points = walls[kind]
        config = c
        if variant == 'rotated_suffix':
            points = [[2+x*math.cos(.7)-y*math.sin(.7), 1+x*math.sin(.7)+y*math.cos(.7)] for x, y in points]
            config = replace(c, front_stop_m=.55, wall_target_m=.34)
        build = PoolModel.from_boundary if kind == 'rectangle' else PoolModel.from_polyline
        pool = build(points, wall_target_m=config.wall_target_m)
        required = {e.id: (.9, e.clean_end_s_m) for e in pool.edges[2:]} if variant == 'rotated_suffix' else None
        track = dict(frame_id='map', map_id='fixture-map', basis='boat_center_stop',
                     front_stop_m=config.front_stop_m, wall_target_m=config.wall_target_m,
                     wall_band_min_m=config.side_off_distance_m, wall_band_max_m=config.side_on_distance_m,
                     cleaning_segments=cleaning_segments(pool, config, required), navigation_path=[])
        cases.append(dict(kind=kind, variant=variant, expected_track=track,
                          walls=[dict(edge_id=e.id, start=e.start, end=e.end) for e in pool.edges]))
out = root / 'app/src/test/resources/expected-track.json'
out.write_text(json.dumps(cases, ensure_ascii=False, indent=2, allow_nan=False)+'\n', encoding='utf-8')
print(f'Generated {len(cases)} cases with upstream cleaning_segments; no ROS/hardware used.')
