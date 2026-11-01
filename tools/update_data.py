#!/usr/bin/env python3
"""
从万象棋谱仓库重新生成 App 内置数据。

游戏是周更，这份数据必然会过期。上游仓库更新后（或你换了别的数据源），
在电脑上跑一遍这个脚本，把 app/src/main/assets/data/ 刷新，重新出包即可。

用法：
    git clone --depth 1 https://github.com/the-beating-light-of-the-nail/wanxiang-qipu /tmp/wxq
    python3 tools/update_data.py /tmp/wxq app/src/main/assets/data
"""
import json, os, sys, datetime, subprocess

def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)

    def load(n):
        with open(os.path.join(src, 'data', n + '.json'), encoding='utf-8') as f:
            return json.load(f)

    def dump(n, obj):
        p = os.path.join(out, n + '.json')
        with open(p, 'w', encoding='utf-8') as f:
            json.dump(obj, f, ensure_ascii=False, separators=(',', ':'))
        return os.path.getsize(p)

    heroes_raw  = load('heroes')
    camps_raw   = load('camps')
    comps_raw   = load('comps')
    players_raw = load('players')
    talents_raw = load('talents')
    effects_raw = load('effects')

    heroes = [{
        'id': h['id'], 'n': h['name'], 'q': h.get('quality'),
        'c': h.get('camp'), 'r': h.get('role'), 'kw': h.get('kw') or [],
    } for h in heroes_raw]
    heroes.sort(key=lambda x: (x['c'] or '', -(x['q'] or 0), x['n']))

    by_camp = {}
    for h in heroes:
        by_camp.setdefault(h['c'], []).append(h['n'])
    camps = [{
        'n': c['name'], 'total': c.get('count'),
        'tiers': [] if c['name'] == '无阵营' else [3, 5],
        'members': sorted(by_camp.get(c['name'], [])),
    } for c in camps_raw]

    comps = [{
        'id': c['id'], 'n': c['name'],
        'camps': c.get('camps') or [], 'core': c.get('core') or [],
        'hs': c.get('heroes') or [], 'tim': c.get('timing'),
        'tag': c.get('classificationLabel'), 'player': c.get('player'),
    } for c in comps_raw]

    players = [{
        'n': p['name'],
        'sk': (p.get('skill') or {}).get('t'),
        'skd': (p.get('skill') or {}).get('d'),
    } for p in players_raw]

    talents = [{
        'id': t['id'], 'n': t['name'], 'q': t.get('quality'),
        'st': t.get('stage'), 'g': t.get('grp'), 'd': t.get('desc'),
    } for t in talents_raw]

    effects = [{
        'id': e['id'], 'n': e['name'], 'q': e.get('quality'),
        'cat': e.get('cat'), 'd': e.get('desc'),
    } for e in effects_raw]

    names = {
        'hero':   sorted({h['n'] for h in heroes}),
        'talent': sorted({t['n'] for t in talents}),
        'effect': sorted({e['n'] for e in effects}),
        'player': sorted({p['n'] for p in players}),
    }

    commit = ''
    try:
        commit = subprocess.check_output(
            ['git', '-C', src, 'rev-parse', '--short', 'HEAD'],
            stderr=subprocess.DEVNULL, text=True).strip()
    except Exception:
        pass

    total = 0
    for n, o in [('heroes', heroes), ('camps', camps), ('comps', comps),
                 ('players', players), ('talents', talents),
                 ('effects', effects), ('names', names)]:
        sz = dump(n, o)
        total += sz
        print(f'  {n+".json":14} {sz/1024:7.1f} KB  {len(o)} 条')

    meta = {
        'schema': 1,
        'generatedAt': datetime.date.today().isoformat(),
        'source': 'the-beating-light-of-the-nail/wanxiang-qipu',
        'sourceCommit': commit,
        'counts': {
            'heroes': len(heroes), 'camps': len(camps), 'comps': len(comps),
            'players': len(players), 'talents': len(talents), 'effects': len(effects),
        },
    }
    total += dump('meta', meta)
    print(f'\n合计 {total/1024:.1f} KB，源 commit {commit or "未知"}')
    print('阵营:', ', '.join(f"{c['n']}({c['total']})" for c in camps))
    if len(camps) <= 6:
        print('\n⚠ 只有 6 个阵营，没有建木 —— 上游数据可能还没更新。')

if __name__ == '__main__':
    main()
