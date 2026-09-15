#!/usr/bin/env python3
"""Validate the specification companion examples; not an application test suite.

Python 3.10+, standard library only. Read-only: no application services are started.
"""
from __future__ import annotations

import hashlib
import json
import math
import sys
import uuid
from decimal import Decimal
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
EXAMPLES = ROOT / 'examples'


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def reject_nonfinite(value: str) -> None:
    raise ValueError(f'Non-finite JSON number: {value}')


def read_json(path: Path) -> Any:
    with path.open(encoding='utf-8') as stream:
        return json.load(stream, parse_constant=reject_nonfinite)


def uuid_ok(value: str) -> None:
    uuid.UUID(value)


def coordinate(point: list[float]) -> None:
    require(len(point) == 2, f'Expected a 2D position, got {point!r}')
    require(all(isinstance(v, (float, int)) and not isinstance(v, bool)
                and math.isfinite(v) for v in point), 'Non-finite position')
    require(-180 <= point[0] <= 180 and -90 <= point[1] <= 90,
            'Coordinates are outside WGS84 longitude/latitude ranges')


def geometry(obj: dict[str, Any]) -> None:
    kind, coords = obj['type'], obj['coordinates']
    if kind == 'Point':
        coordinate(coords)
    elif kind == 'LineString':
        require(len(coords) >= 2, 'LineString too short')
        for p in coords:
            coordinate(p)
        require(any(coords[0] != p for p in coords[1:]), 'Zero-length line')
    elif kind == 'Polygon':
        require(bool(coords), 'Polygon has no rings')
        for ring in coords:
            require(len(ring) >= 4 and ring[0] == ring[-1], 'Unclosed ring')
            for p in ring:
                coordinate(p)
    else:
        raise ValueError(f'Unexpected geometry in this small fixture pack: {kind}')


def side_connected(points: list[list[int]]) -> bool:
    cells = {tuple(p) for p in points}
    if len(cells) != 5:
        return False
    pending = [next(iter(cells))]
    visited: set[tuple[int, int]] = set()
    while pending:
        x, y = pending.pop()
        if (x, y) in visited:
            continue
        visited.add((x, y))
        pending.extend(p for p in ((x-1, y), (x+1, y), (x, y-1), (x, y+1))
                       if p in cells and p not in visited)
    return len(visited) == 5


def distance_entropy(points: list[list[float]]) -> float:
    distances = [math.dist(a, b) for i, a in enumerate(points)
                 for j, b in enumerate(points) if i != j]
    total = sum(distances)
    require(total > 0, 'Degenerate entropy input')
    return -sum((d / total) * math.log2(d / total) for d in distances if d > 0)


def main() -> int:
    for path in sorted(ROOT.rglob('*.json')) + sorted(ROOT.rglob('*.geojson')):
        read_json(path)
    print('PASS: all JSON/GeoJSON files parse; no NaN or Infinity.')

    manifest = read_json(EXAMPLES / 'manifest.demo.json')
    require(manifest['source_type'] == 'synthetic', 'Missing synthetic manifest label')
    require(manifest['is_real_city_data'] is False, 'Misleading real-data label')
    for item in manifest['files']:
        path = (EXAMPLES / item['path']).resolve()
        require(path.is_relative_to(EXAMPLES.resolve()), 'Manifest path traversal')
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        require(actual == item['sha256'], f'Hash mismatch: {item["path"]}')
    print(f'PASS: {len(manifest["files"])} manifest file hashes match.')

    data = read_json(EXAMPLES / 'canonical_features.demo.geojson')
    require(data['type'] == 'FeatureCollection', 'Expected FeatureCollection')
    features = data['features']
    by_id = {f['id']: f for f in features}
    require(len(by_id) == len(features), 'Duplicate feature ID')
    for feature in features:
        uuid_ok(feature['id'])
        geometry(feature['geometry'])
        props = feature['properties']
        uuid_ok(props['logical_id'])
        require(props['source_type'] == 'synthetic', 'Missing synthetic feature label')
        require(props['dataset_version_id'] == manifest['dataset_version_id'],
                'Dataset version mismatch')
        attrs = props['attributes']
        if props['kind'] == 'network_edge':
            for name in ('from_node_id', 'to_node_id'):
                require(attrs[name] in by_id, 'Dangling network node reference')
                require(by_id[attrs[name]]['properties']['kind'] == 'network_node',
                        'Endpoint is not a node')
            require(feature['geometry']['coordinates'][0] ==
                    by_id[attrs['from_node_id']]['geometry']['coordinates'],
                    'From-node coordinates differ')
            require(feature['geometry']['coordinates'][-1] ==
                    by_id[attrs['to_node_id']]['geometry']['coordinates'],
                    'To-node coordinates differ')
        elif props['kind'] == 'connection_candidate':
            require(attrs['network_node_id'] in by_id, 'Candidate references missing node')
        elif props['kind'] == 'crossing_portal':
            for target in attrs['applies_to_feature_ids']:
                require(target in by_id and by_id[target]['properties']['kind'] == 'road',
                        'Portal does not reference a road')
            geometry(attrs['footprint_wgs84'])
        elif props['kind'] == 'entry_gate':
            require(attrs['target_building_id'] in by_id, 'Entry gate target missing')
    print(f'PASS: {len(features)} synthetic features have valid basic geometry structure, '
          'unique IDs and consistent references.')

    rules = read_json(EXAMPLES / 'rule_profile.demo.json')
    costs = read_json(EXAMPLES / 'cost_catalog.demo.json')
    scenario = read_json(EXAMPLES / 'scenario.demo.json')
    require(rules['status'] == 'demo', 'Rule profile is not labelled demo')
    require(costs['estimate_status'] == 'synthetic', 'Prices are not labelled synthetic')
    require(scenario['source_type'] == 'synthetic', 'Scenario is not labelled synthetic')
    require(scenario['rule_profile_version_id'] == rules['id'], 'Wrong rules reference')
    require(scenario['cost_catalog_version_id'] == costs['id'], 'Wrong costs reference')
    require(scenario['target_building_id'] in by_id, 'Target missing')
    require(scenario['entry_gate_id'] in by_id, 'Entry gate missing')
    for candidate in scenario['connection_candidate_ids']:
        require(candidate in by_id and by_id[candidate]['properties']['kind'] ==
                'connection_candidate', 'Scenario candidate missing/wrong type')
    require(scenario['corridor_width_m'] > 0, 'Invalid corridor width')
    coordinate(scenario['entry_point_wgs84'])
    geometry(scenario['search_settings']['aoi_wgs84'])
    for item in costs['items']:
        require(Decimal(item['rate']) >= 0, 'Negative demo price')
    request = read_json(EXAMPLES / 'run_request.demo.json')
    require(request['path'].endswith(f'/{scenario["id"]}/runs'), 'Run path revision mismatch')
    print('PASS: scenario, profiles, candidate references and HTTP example are consistent.')

    mapping = read_json(EXAMPLES / 'import_mapping.demo.json')
    raw = read_json(EXAMPLES / mapping['source_filename'])
    transform = mapping['fields']['height_m']['transforms'][1]
    height = Decimal(raw['features'][0]['properties']['HEIGHT_CM']) * Decimal(transform['factor'])
    require(height == Decimal('12'), 'Incorrect demo cm-to-m mapping')
    print('PASS: declared unit conversion maps 1200 cm to 12 m.')

    algorithm = read_json(ROOT / 'tests/fixtures/algorithm_cases.json')
    require(algorithm['coordinate_semantics'] == 'LOCAL_METERS_NOT_GEOJSON',
            'Local algorithm fixtures must not pretend to be GeoJSON')
    require(len({c['id'] for c in algorithm['cases']}) == len(algorithm['cases']),
            'Duplicate algorithm fixture ID')
    direct = algorithm['cases'][0]
    require(math.isclose(math.dist(direct['start_m'], direct['goal_m']),
                         direct['expected']['length_m']), 'Wrong direct-path reference length')
    print('PASS: algorithm fixture structure and direct-distance reference are consistent. '
          'No route solver is implemented or tested by this script.')

    research = read_json(ROOT / 'tests/fixtures/entropy_research.json')
    require(research['use_in_routing'] is False, 'Research must not control routing')
    points = research['coordinates']
    require(not side_connected(points['wrong_T_from_screenshot']),
            'Incorrect T should fail side connectivity')
    require(side_connected(points['correct_T']) and side_connected(points['U']),
            'Correct figures should be side-connected')
    values = {name: distance_entropy(p) for name, p in points.items()}
    for name, value in values.items():
        require(math.isclose(value, research['reference_entropy_bits'][name], abs_tol=1e-12),
                'Incorrect entropy reference')
    require(math.isclose(values['correct_T'], values['U'], abs_tol=1e-12),
            'T/U entropy equality not reproduced')
    scaled = [[10*x, 10*y] for x, y in points['X']]
    require(math.isclose(distance_entropy(scaled), values['X'], abs_tol=1e-12),
            'Entropy should be invariant under uniform scale')
    print('PASS: independent entropy reproduction confirms the disconnected T, T/U collision '
          'and scale invariance. This is not a hydraulic calculation.')
    print('DONE: companion examples validated. Application implementation, engineering '
          'validity and performance are NOT validated by this script.')
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, TypeError, ArithmeticError) as exc:
        print(f'FAIL: {exc}', file=sys.stderr)
        raise SystemExit(1)
