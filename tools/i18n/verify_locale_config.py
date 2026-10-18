"""Check that AGP discovers generated packs and that release excludes pseudo locales."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
expected = {p.stem for p in (root / 'languages').glob('*.json')}
for variant in ('debug', 'release'):
    path = root / f'app/build/generated/res/localeConfig/{variant}/xml/_generated_res_locale_config.xml'
    actual = {node.attrib['{http://schemas.android.com/apk/res/android}name'] for node in ET.parse(path).getroot()}
    allowed = expected | ({'en-XA', 'ar-XB'} if variant == 'debug' else set())
    assert expected <= actual <= allowed, (variant, expected, actual)
    # addGeneratedSourceDirectory lets AGP choose the task's registered output.
    generated = root / f'app/build/generated/res/generate{variant.capitalize()}I18nResources'
    dirs = {p.name for p in generated.iterdir() if p.is_dir()}
    assert dirs == {'values' if tag == 'en' else 'values-b+' + tag.replace('-', '+') for tag in expected}, dirs
    print(f'{variant}: generated packs and LocaleConfig agree ({", ".join(sorted(actual))})')
