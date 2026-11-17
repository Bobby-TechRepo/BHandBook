"""Poor man's compiler front-end for the layers that cannot be compiled here (Android / Compose / Hilt):
   every project class, object, interface, enum, typealias or top-level function that a file uses must be imported, fully qualified, or in the same package."""
import re, os, sys, glob
ROOT = sys.argv[1]; BASE = 'com.bsoft.bhandbook'
decl_type = re.compile(r'^(?:(?:public|internal|private|data|sealed|enum|abstract|open|annotation|value|fun)\s+)*(?:class|object|interface|typealias)\s+(\w+)', re.M)
decl_fun = re.compile(r'^(?:(?:public|internal|inline|operator|infix|suspend)\s+)*fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)\s*\(', re.M)
decl_val = re.compile(r'^(?:(?:public|internal|const)\s+)*(?:val|var)\s+(?:[\w.<>?, ]+\.)?(\w+)\b', re.M)
files = glob.glob(ROOT + '/**/*.kt', recursive=True)
def pkg(src): m = re.search(r'^package\s+([\w.]+)', src, re.M); return m.group(1) if m else ''
declared = {}   # name -> set(packages)
for f in files:
    s = open(f, encoding='utf8').read(); p = pkg(s)
    if not p.startswith(BASE): continue
    for rx in (decl_type, decl_fun, decl_val):
        for m in rx.finditer(s):
            if re.search(r'private\s', s[max(0, m.start()-12):m.start()+8]): continue
            declared.setdefault(m.group(1), set()).add(p)
def strip(src):
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S); src = re.sub(r'//[^\n]*', '', src); src = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', src)
    return src
targets = [f for f in files if not os.sep + 'core' + os.sep in f and not (os.sep + 'domain' + os.sep in f)]
problems = 0
for f in sorted(targets):
    raw = open(f, encoding='utf8').read(); p = pkg(raw); body = strip(raw)
    imports = re.findall(r'^import\s+([\w.]+)(?:\s+as\s+(\w+))?', raw, re.M); imp_names = set(); wild = set()
    for full, alias in imports:
        imp_names.add(alias or full.split('.')[-1]); 
    wild = {m for m in re.findall(r'^import\s+([\w.]+)\.\*', raw, re.M)}
    own = set(re.findall(r'(?:class|object|interface|typealias|fun|val|var)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)', body))
    code = re.sub(r'^(?:import|package)[^\n]*', '', body, flags=re.M)
    for name, pkgs in declared.items():
        if name in own or len(name) < 3: continue
        for m in re.finditer(r'(?<![\w.])' + re.escape(name) + r'\b', code):
            nxt = code[m.end():m.end()+1]
            # only count real uses: a type/constructor/call/reference, not a named argument label or a lambda parameter
            if nxt == '=' and code[m.end():m.end()+2] != '==': continue
            if any(pk == p for pk in pkgs): break
            if name in imp_names or any(pk in wild for pk in pkgs): break
            line = code[:m.start()].count('\n') + 1
            print(f'{os.path.relpath(f, ROOT)}:{line}: "{name}" is used but not imported (declared in {sorted(pkgs)})'); problems += 1; break
print('import check:', problems, 'problem(s) in', len(targets), 'files')
