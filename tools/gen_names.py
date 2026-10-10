import sys, re, zipfile, os
work = sys.argv[1]; out = sys.argv[2]
MEMBER_PREFIX = ('net/minecraft/client/model/', 'net/minecraft/util/Mth', 'net/minecraft/core/Direction',
                 'net/minecraft/resources/ResourceLocation', 'net/minecraft/resources/Identifier')
PRIM = {'void':'V','boolean':'Z','byte':'B','char':'C','short':'S','int':'I','long':'J','float':'F','double':'D'}
def jdesc(t, rev):
    dims = 0
    while t.endswith('[]'): t = t[:-2]; dims += 1
    d = PRIM.get(t)
    if d is None:
        t = t.replace('.', '/'); d = 'L' + rev.get(t, t) + ';'
    return '[' * dims + d
def parse_moj(path):
    cls = {}  
    raw = []
    cur = None
    for line in open(path, encoding='utf-8'):
        if line.startswith('#'): continue
        if not line.startswith(' '):
            m = re.match(r'(\S+) -> (\S+):', line)
            if m:
                cur = m.group(2).replace('.', '/'); cls[cur] = m.group(1).replace('.', '/')
            continue
        m = re.match(r'\s+(?:\d+:\d+:)?(\S+) ([^\s(]+)(\(.*\))?(?::\d+:\d+)? -> (\S+)', line)
        if m: raw.append((cur, m.group(1), m.group(2), m.group(3), m.group(4)))
    rev = {v: k for k, v in cls.items()}
    mem = {}
    for cur, ret, name, args, obf in raw:
        if args is None:
            mem.setdefault((cur, obf, 'F'), set()).add(name)
        else:
            a = args[1:-1]
            desc = '(' + ''.join(jdesc(x, rev) for x in (a.split(',') if a else [])) + ')' + jdesc(ret, rev)
            mem.setdefault((cur, obf + desc, 'M'), set()).add(name)
    return cls, mem
res = {}
def add(k, a, b):
    if a != b: res[(k, a)] = b
for ver in ('1.20.1', '1.21.1'):
    cls, mem = parse_moj(os.path.join(work, f'map-{ver}.txt'))
    
    with zipfile.ZipFile(os.path.join(work, f'int-{ver}.jar')) as z:
        lines = z.read('mappings/mappings.tiny').decode().splitlines()
    cur = None
    for l in lines[1:]:
        p = l.split('\t')
        if p[0] == 'c':
            cur = p[1]; moj = cls.get(cur)
            if moj: add('C', p[2], moj)
        elif len(p) >= 5 and p[1] in ('m', 'f') and cur in cls:
            moj = cls[cur]
            if not moj.startswith(MEMBER_PREFIX): continue
            names = mem.get((cur, p[3] + (p[2] if p[1] == 'm' else ''), p[1].upper()))
            if names and len(names) == 1: add(p[1].upper(), p[4], next(iter(names)))
    
    zp = os.path.join(work, f'mcp-{ver}.zip')
    if os.path.exists(zp):
        with zipfile.ZipFile(zp) as z:
            lines = z.read('config/joined.tsrg').decode().splitlines()
        cur = None
        for l in lines[1:]:
            if not l.startswith('\t'):
                cur = l.split(' ')[0]; continue
            if l.startswith('\t\t'): continue
            p = l.strip().split(' ')
            if cur not in cls or not cls[cur].startswith(MEMBER_PREFIX): continue
            if len(p) == 4 and p[1].startswith('('):
                names = mem.get((cur, p[0] + p[1], 'M'))
                if names and len(names) == 1 and p[2].startswith('m_'): add('M', p[2], next(iter(names)))
            elif len(p) == 3:
                names = mem.get((cur, p[0], 'F'))
                if names and len(names) == 1 and p[1].startswith('f_'): add('F', p[1], next(iter(names)))
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, 'w', encoding='utf-8') as f:
    for (k, a), b in sorted(res.items()):
        f.write(f'{k}\t{a}\t{b}\n')
print(len(res), 'entries')
