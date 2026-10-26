"""Read audio tags without modifying music; group with the plugin's album identity."""
import argparse
import json
import unicodedata
from pathlib import Path
import mutagen


def identity(value):
    return ''.join(c for c in unicodedata.normalize('NFKC', value).lower() if c.isalnum())


def inventory(root):
    tracks = []
    for path in sorted(root.rglob('*')):
        if path.suffix.lower() not in {'.flac', '.mp3', '.wav', '.m4a', '.ogg', '.opus', '.aiff', '.wma'}:
            continue
        track = {'path': str(path), 'title': '', 'artist': '', 'albumArtist': '', 'album': '', 'isrc': '', 'date': ''}
        try:
            audio = mutagen.File(path, easy=True)
            if audio is None:
                raise ValueError('Unrecognized audio')
            tags = audio.tags or {}
            def first(*names):
                for name in names:
                    value = tags.get(name)
                    if value:
                        if hasattr(value, 'text'):
                            value = value.text
                        return str(value[0] if isinstance(value, (list, tuple)) else value).strip()
                return ''
            track.update(title=first('title', 'TIT2'), artist=first('artist', 'TPE1'),
                         albumArtist=first('albumartist', 'album artist', 'TPE2'), album=first('album', 'TALB'),
                         isrc=first('isrc', 'TSRC'), date=first('date', 'TDRC', 'TYER'),
                         duration=round(audio.info.length, 3))
        except Exception as error:
            track['readError'] = type(error).__name__ + ': ' + str(error)
        track['lookupArtist'] = track['albumArtist'] or track['artist']
        tracks.append(track)
    groups = {}
    for track in tracks:
        key = (identity(track['lookupArtist']), identity(track['album']))
        if not all(key):
            track['tagIssue'] = 'Missing album or artist'
            continue
        group = groups.setdefault(key, {'artist': track['lookupArtist'], 'album': track['album'], 'tracks': []})
        group['tracks'].append(track)
    return {'musicRoot': str(root), 'trackCount': len(tracks), 'albumCount': len(groups),
            'groups': list(groups.values()), 'unclassified': [t for t in tracks if t.get('tagIssue')]}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('music_root', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    data = inventory(args.music_root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: data[k] for k in ('trackCount', 'albumCount')}, ensure_ascii=False))
    print('Missing tags:', len(data['unclassified']), 'Read errors:', sum('readError' in t for g in data['groups'] for t in g['tracks']))
