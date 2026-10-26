"""Suggest only same-artist, same-title/format candidates; never relax production matching."""
import json
import re
import sys
import unicodedata
from pathlib import Path


def identity(value):
    return ''.join(c for c in unicodedata.normalize('NFKC', value).lower() if c.isalnum())


def title(value):
    value = unicodedata.normalize('NFKC', value).lower()
    # These changes only nominate a manual-review candidate, never an automatic match.
    value = re.sub(r'\(explicit\)|\[explicit\]', '', value)
    value = re.sub(r'\s*-\s*(single|ep)$', '', value)
    return value


if __name__ == '__main__':
    records = [json.loads(line) for line in Path(sys.argv[1]).read_text(encoding='utf-8').splitlines()]
    plan = []
    for record in records:
        if record['status'] not in {'unmatched', 'query_failed'}:
            continue
        selected = {}
        for c in record['candidates']:
            if identity(c.get('artistName', '')) != identity(record['artist']):
                continue
            a, b = title(record['album']), title(c.get('name', ''))
            same = identity(a) == identity(b)
            reordered = sorted(re.findall(r'\w+', a)) == sorted(re.findall(r'\w+', b))
            if not (same or reordered):
                continue
            if c['id'] not in selected:
                selected[c['id']] = {**c, 'localKey': identity(record['artist']) + '|' + identity(record['album']),
                                     'localArtist': record['artist'], 'localAlbum': record['album'],
                                     'reviewReason': '同名多个 ID 或地区差异' if identity(record['album']) == identity(c['name']) else '专辑名或发行格式有差异'}
        # Keep multiple IDs visible; a title match alone does not identify a release.
        plan.extend(list(selected.values())[:4])
    Path(sys.argv[2]).write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Candidate checks:', len(plan), 'local albums:', len({x['localKey'] for x in plan}))
