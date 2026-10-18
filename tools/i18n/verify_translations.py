"""Keep the maintained Chinese UI complete; additional language packs may be partial."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[2]
english = json.loads((root / 'languages/en.json').read_text(encoding='utf-8'))
chinese = json.loads((root / 'languages/zh-CN.json').read_text(encoding='utf-8'))
for kind in ('strings', 'plurals', 'arrays'):
    required = set(english[kind])
    if kind == 'strings':
        required = {key for key in required
                    if english.get('options', {}).get(key, {}).get('translatable', True)}
    missing = required - chinese[kind].keys()
    assert not missing, f'Chinese {kind} missing: {sorted(missing)}'

for key in ('controls_tap', 'controls_drag', 'controls_flick', 'controls_hold'):
    assert chinese['strings'][key] == english['strings'][key], f'Keep note name: {key}'
for key in ('note_types', 'curve_note_types'):
    assert chinese['arrays'][key] == english['arrays'][key], f'Keep note names/order: {key}'
for key in ('controls_event', 'event_type_move_x', 'event_type_move_y',
            'event_type_rotate', 'event_type_alpha', 'event_type_speed'):
    assert chinese['strings'][key] != english['strings'][key], f'Translate event label: {key}'
print('Chinese UI coverage complete; note names and array order preserved')
