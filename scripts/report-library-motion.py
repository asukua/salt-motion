"""Build a readable audit and a local playlist from completed, read-only checks."""
import json
import re
import sys
import unicodedata
from collections import Counter, defaultdict
from datetime import datetime, timezone, timedelta
from pathlib import Path


def identity(value):
    return ''.join(c for c in unicodedata.normalize('NFKC', value).lower() if c.isalnum())


def key(artist, album):
    return identity(artist) + '|' + identity(album)


def rows(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()] if path.exists() else []


def cell(value):
    return str(value).replace('|', '\\|').replace('\n', ' ').replace('\r', ' ')


def file_link(track):
    label = cell(track['title'] or Path(track['path']).stem).replace('[', '\\[').replace(']', '\\]')
    return '[' + label + '](<' + Path(track['path']).as_posix() + '>)'


if __name__ == '__main__':
    root = Path(sys.argv[1])
    inventory = json.loads((root / 'inventory.json').read_text(encoding='utf-8'))
    results = {key(r['artist'], r['album']): r for r in rows(root / 'results.ndjson')}
    if len(results) != inventory['albumCount']:
        raise SystemExit('Full scan is incomplete; do not publish a partial report as complete.')
    candidates = defaultdict(list)
    for r in rows(root / 'candidate-results.ndjson'):
        if r['status'] == 'candidate_motion':
            candidates[r['localKey']].append(r)
    labels = {'confirmed': '确认有动态，可自动匹配', 'motion_found': '有动态入口，视频清单待核验',
              'unmatched': '未匹配到明确专辑', 'query_failed': '查询失败，未确认',
              'no_verified_square_motion': '已匹配专辑，未取得可用方形动态'}
    groups = []
    confirmed_tracks = []
    candidate_tracks = []
    for group in inventory['groups']:
        k = key(group['artist'], group['album'])
        r = results[k]
        related = candidates[k]
        label = labels.get(r['status'], r['status'])
        if r['status'] != 'confirmed' and related:
            label = '相关专辑有动态，需确认版本并手动绑定'
            candidate_tracks.extend(group['tracks'])
        if r['status'] == 'confirmed':
            confirmed_tracks.extend(group['tracks'])
        groups.append({**group, 'result': r, 'label': label, 'reviewCandidates': related})
    summary = {'checkedAt': datetime.now(timezone(timedelta(hours=8))).isoformat(),
               'musicRoot': inventory['musicRoot'], 'trackCount': inventory['trackCount'], 'albumCount': inventory['albumCount'],
               'confirmedAlbums': sum(g['result']['status'] == 'confirmed' for g in groups),
               'confirmedTracks': len(confirmed_tracks), 'candidateAlbums': sum(bool(g['reviewCandidates']) for g in groups),
               'candidateTracks': len(candidate_tracks), 'missingTagTracks': len(inventory['unclassified']),
               'albumStatuses': dict(Counter(r['status'] for r in results.values()))}
    (root / 'report.json').write_text(json.dumps({'summary': summary, 'albums': groups, 'missingTags': inventory['unclassified']}, ensure_ascii=False, indent=2), encoding='utf-8')
    lines = ['# 本地歌曲动态专辑封面检查', '', '检查时间：' + summary['checkedAt'], '',
             f"检查 {summary['trackCount']} 个音频文件，按标签整理出 {summary['albumCount']} 张专辑；{summary['missingTagTracks']} 个文件缺少专辑或艺人标签。", '',
             f"**确认可自动匹配：{summary['confirmedAlbums']} 张专辑 / {summary['confirmedTracks']} 首。另有 {summary['candidateAlbums']} 张本地专辑 / {summary['candidateTracks']} 首找到相关动态素材，需要确认发行版本并手动绑定。**", '',
             '使用 Salt Motion 0.1.7 的生产匹配与选流代码，按美区、日区、国区回退。确认项已通过 AM 专辑身份、方形动态入口及最高分辨率 SDR 视频清单核验；没有逐首在播放器窗口播放。', '',
             '候选项保留多 ID、Single/EP、Explicit 等名称差异，未修改自动匹配规则或播放器配置。未匹配及查询失败的项目仍属未确认，不能据此断言 AM 没有任何动态素材。', '',
             '## 确认可自动匹配的歌曲', '', '| 歌曲（点击打开本地文件） | 歌曲艺人 | 专辑 | 视频尺寸 | AM 专辑 |', '| --- | --- | --- | --- | --- |']
    for g in groups:
        r = g['result']
        if r['status'] != 'confirmed':
            continue
        for t in g['tracks']:
            lines.append(f"| {file_link(t)} | {cell(t['artist'])} | {cell(g['album'])} | {r['width']}×{r['height']} | [{r['albumId']}]({r['sourceUrl']}) |")
    lines += ['', '同一专辑的歌曲共用动态封面。可导入本目录的「已确认动态封面歌曲.m3u8」逐首检查。', '',
              '## 有动态素材的候选：需要确认版本', '',
              '以下 AM 候选本身有可用动态视频，但现有证据未把本地发行版本唯一确定。请先比较版本，再选择一个 ID；同一专辑多个候选不可同时写入绑定。', '',
              '| 本地专辑 | 本地歌曲 | 有动态的 AM 候选 | 差异 / 待确认点 |', '| --- | --- | --- | --- |']
    bindings = ['# 建议绑定候选：需先核对版本；同一左侧只能选一个 ID。', '# 此文件未写入播放器配置。', '']
    for g in groups:
        if not g['reviewCandidates']:
            continue
        choices = []
        reasons = []
        for c in g['reviewCandidates']:
            choices.append(f"{cell(c['name'])} / ID {c['id']} / {c['width']}×{c['height']} / {c.get('releaseDate', '')} / {c.get('trackCount', '')} 曲")
            reasons.append(c['reviewReason'])
            bindings += ['# AM: ' + c['name'] + '；' + c.get('releaseDate', '') + '；' + c['reviewReason'],
                         g['artist'] + ' | ' + g['album'] + ' = ' + c['id'], '']
        lines.append('| ' + cell(g['artist'] + ' / ' + g['album']) + ' | ' + '、'.join(cell(t['title'] or Path(t['path']).stem) for t in g['tracks']) + ' | ' + '<br>'.join(choices) + ' | ' + '；'.join(dict.fromkeys(reasons)) + ' |')
    lines += ['', '## 其余专辑的检查状态', '', '| 艺人 | 专辑 | 本地歌曲数 | 状态 |', '| --- | --- | ---: | --- |']
    for g in groups:
        if g['result']['status'] == 'confirmed' or g['reviewCandidates']:
            continue
        lines.append(f"| {cell(g['artist'])} | {cell(g['album'])} | {len(g['tracks'])} | {g['label']} |")
    lines += ['', '## 标签缺失文件', '']
    for t in inventory['unclassified']:
        lines += ['- ' + Path(t['path']).name + '：专辑=' + (t['album'] or '空') + '；艺人=' + (t['lookupArtist'] or '空')]
    lines += ['', '## 复核数据', '', 'inventory.json 为只读标签清单；results.ndjson 为每张专辑的检查点，含逐区状态；candidate-results.ndjson 为人工候选的独立核验。report.json 汇总各首歌与结果。查询记录不包含令牌、音频或歌词。', '']
    (root / '动态封面检查报告.md').write_text('\n'.join(lines), encoding='utf-8')
    playlist = ['#EXTM3U']
    for t in confirmed_tracks:
        playlist += ['#EXTINF:' + str(round(t.get('duration', -1))) + ',' + (t['artist'] + ' - ' + (t['title'] or Path(t['path']).stem)).replace('\n', ' '), t['path']]
    (root / '已确认动态封面歌曲.m3u8').write_text('\n'.join(playlist) + '\n', encoding='utf-8-sig')
    (root / '待确认的手动绑定候选.txt').write_text('\n'.join(bindings), encoding='utf-8-sig')
    print(json.dumps(summary, ensure_ascii=False, indent=2))
