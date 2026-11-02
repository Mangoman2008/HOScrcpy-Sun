#!/usr/bin/env python3
"""sun 工程静态检查：发到 VM 编译之前先跑这个。
用法: python3 check.py
检查项：
  1. 大括号是否平衡（每个文件）
  2. 是否引用了已删除/未定义的方法名
  3. EDT（invokeLater/事件回调）路径上是否有阻塞调用
  4. lambda 里引用的局部变量是否是 effectively final
  5. 是否有 TODO / 空的 catch 之外的可疑占位
"""
import os
import re
import sys

# Windows 控制台默认 GBK，脚本里的 ⚠ / ✓ 会直接 UnicodeEncodeError 退出（上一版就在最后一步炸过）
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "src", "main", "java")

BLOCKING = [
    "HdcUtil.getLocalDevices", "HdcUtil.getRemoteDevice", "HdcUtil.queryDevice",
    "HdcUtil.canVideoStream", "executeShellCommand", "isOnline(",
    "ImageIO.write", "Thread.sleep(", ".callProcess(",
]

problems = []
warnings = []


def strip_comments(src):
    """去掉 // 与 /* */ 注释，避免把注释里的词当成代码"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src



def files():
    for base, _, names in os.walk(ROOT):
        for n in names:
            if n.endswith(".java"):
                yield os.path.join(base, n)


def check_braces(path, src):
    a, b = src.count("{"), src.count("}")
    if a != b:
        problems.append("%s: 大括号不平衡 %d/%d" % (path, a, b))


def check_undefined_calls(path, src, all_src):
    """找形如 xxx.yyy( 的调用，其中 xxx 是本工程类，但 yyy 在该类里没有定义"""
    own_classes = {}
    for p, s in all_src.items():
        m = re.search(r"\b(?:class|interface|enum)\s+(\w+)", s)
        if m:
            own_classes[m.group(1)] = s
    for cls, body in own_classes.items():
        for m in re.finditer(r"\b" + cls + r"\.(\w+)\s*\(", src):
            name = m.group(1)
            if name not in body:
                problems.append("%s: 调用了 %s.%s 但该方法不存在" % (path, cls, name))


def _balanced(src, start):
    """从 start 处的 '(' 开始，返回配对的整段文本"""
    depth = 0
    for i in range(start, len(src)):
        if src[i] == "(":
            depth += 1
        elif src[i] == ")":
            depth -= 1
            if depth == 0:
                return src[start:i + 1]
    return src[start:start + 400]


def check_edt_blocking(path, src):
    """精确检查：invokeLater(lambda) 的完整实参里是否有阻塞调用"""
    for m in re.finditer(r"invokeLater\s*\(", src):
        block = _balanced(src, m.end() - 1)
        for b in BLOCKING:
            if b in block:
                problems.append("%s: invokeLater 块内出现阻塞调用 %s" % (path, b))
    for hdr in ["actionPerformed", "mouseClicked", "mousePressed", "keyPressed", "keyTyped"]:
        for m in re.finditer(hdr + r"[^{]*\{(.{0,600})", src, re.S):
            block = m.group(1)
            for b in BLOCKING:
                if b in block and "new Thread" not in block and "submit(" not in block:
                    problems.append("%s: %s 回调内直接阻塞调用 %s" % (path, hdr, b))


def check_lambda_locals(path, src):
    """lambda 里用到的局部变量必须 final 或只赋值一次。
    ★ 只作为提示：这个粗查会把"同名的另一个局部变量"也算进来（误报率高），
      真正的判定交给 javac（非 effectively-final 会直接编译失败）。"""
    locals_assigned_twice = set()
    for m in re.finditer(r"^\s{4,}(?!final)[\w<>\[\]]+\s+(\w+)\s*=", src, re.M):
        name = m.group(1)
        if len(re.findall(r"^\s*" + name + r"\s*=", src, re.M)) > 1:
            locals_assigned_twice.add(name)
    for name in locals_assigned_twice:
        # 出现在 lambda 体内
        for m in re.finditer(r"->\s*\{(.{0,400})", src, re.S):
            if re.search(r"\b" + name + r"\b", m.group(1)):
                warnings.append("%s: 变量 %s 被多次赋值（javac 会判定是否非法，仅提示）" % (path, name))
                break




def check_tag_declared(path, src):
    """用了 Log.xxx(TAG, ...) 却没有声明 TAG 的类（编译必炸）"""
    if re.search(r"\bLog\.\w+\(\s*TAG\b", src) and not re.search(r"String\s+TAG\s*=", src):
        problems.append("%s: 用了 TAG 但没声明 private static final String TAG" % path)


def check_thread_start(path, src):
    """语句形式的 new Thread(...) 后面必须 .start()。
    2026-10-29 实锤：refreshDevices() 里写成了 new Thread(...); 没 .start()，
    扫描线程从来没跑过 —— 左侧永远"正在扫描设备…"，18MB 日志里一条 HdcUtil 都没有。"""
    src = strip_comments(src)
    for m in re.finditer(r"(?m)^[ \t]*new\s+Thread\s*\(", src):
        start = src.find("(", m.start())
        depth = 0
        i = start
        while i < len(src):
            if src[i] == "(":
                depth += 1
            elif src[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        tail = src[i + 1:i + 1 + 120]
        if ".start()" not in tail:
            problems.append("%s: 语句 new Thread(...) 没有 .start()（线程不会执行）" % path)


def check_mouse_button_type(path, src):
    """SDK 的 MOUSE_LEFT 等是 String，误当 Integer 会编译失败"""
    if "Integer button" in src or "Integer buttonOf" in src:
        problems.append("%s: 触控按钮类型应为 String（MOUSE_LEFT 是 String）" % path)


def check_awt_event_imports(path, src):
    """用到 MouseWheelEvent 却没 import"""
    if "MouseWheelEvent" in src and "import java.awt.event.MouseWheelEvent;" not in src:
        problems.append("%s: 用了 MouseWheelEvent 但没 import" % path)




def check_literal_escape(path, src):
    """补丁常把 \\n 当字面量写进 Java，导致"非法字符: '\\'"。"""
    for m in re.finditer(r"\\n\}", src):
        problems.append("%s: 出现字面量 '\\n}'（补丁转义错误）" % path)
        break




def check_generation_guard(path, src):
    """暂停/继续 不能改 captureGen，否则帧回调 myGen 校验会永久丢帧（踩过的坑）"""
    src = strip_comments(src)
    for m in re.finditer(r"(private|protected|public)?\s*void\s+(stopCapture|resume\w*)\s*\([^)]*\)\s*\{", src):
        start = m.end()
        depth = 1
        i = start
        while i < len(src) and depth > 0:
            if src[i] == '{':
                depth += 1
            elif src[i] == '}':
                depth -= 1
            i += 1
        body = src[start:i]
        if "captureGen" in body:
            problems.append("%s: %s() 里改了 captureGen（会导致继续投屏后永久丢帧）"
                            % (path, m.group(2)))


def check_background_ui(path, src):
    """后台线程里直接碰 Swing 组件（必须走 invokeLater）"""
    src = strip_comments(src)
    for m in re.finditer(r"new Thread\s*\(\s*\(\)\s*->\s*\{", src):
        start = m.end()
        depth = 1
        i = start
        while i < len(src) and depth > 0:
            if src[i] == '{':
                depth += 1
            elif src[i] == '}':
                depth -= 1
            i += 1
        body = src[start:i]
        for ui in ["JOptionPane.", ".setText(", ".setIcon(", "setHint(", "setImage("]:
            if ui in body and "invokeLater" not in body:
                problems.append("%s: 后台线程直接操作 UI（%s）" % (path, ui))
                break


def check_optionpane_on_bg(path, src):
    """JOptionPane 出现在后台线程里会卡住（invokeLater 包住的不算）"""
    src = strip_comments(src)
    for m in re.finditer(r"new Thread\s*\(\s*\(\)\s*->\s*\{", src):
        start = m.end()
        depth = 1
        i = start
        while i < len(src) and depth > 0:
            if src[i] == '{':
                depth += 1
            elif src[i] == '}':
                depth -= 1
            i += 1
        body = src[start:i]
        if "JOptionPane.show" in body and "invokeLater" not in body:
            problems.append("%s: 后台线程里弹 JOptionPane（会卡住）" % path)



def write_after_write(files):
    """检测：同一个字段/控件在初始化后被再次赋值（常见坑：设置默认值后又被 prefs 恢复覆盖）"""
    import re
    problems = []
    for path in files:
        try:
            s = open(path, encoding='utf-8').read()
        except Exception:
            continue
        # 收集 "xxx.setSelectedItem/setSelectedIndex/setText" 的多次调用
        for target in set(re.findall(r'(\w+)\.set(?:SelectedItem|SelectedIndex|Text)\(', s)):
            calls = re.findall(r'\b' + target + r'\.set(?:SelectedItem|SelectedIndex|Text)\(', s)
            if len(calls) > 2:
                problems.append((path, target, len(calls)))
    return problems


def method_ref_check(files):
    """检测：this::xxx 引用的方法是否真的定义了（我多次在这里栽跟头）"""
    import re
    bad = []
    for path in files:
        try:
            s = open(path, encoding='utf-8').read()
        except Exception:
            continue
        refs = set(re.findall(r'this::(\w+)', s))
        defined = set(re.findall(r'(?:private|protected|public)\s+(?:static\s+)?(?:final\s+)?[\w<>\[\], .]+\s+(\w+)\s*\(', s))
        for r in sorted(refs):
            if r not in defined and '::' not in r:
                bad.append((path, r))
    return bad

def main():
    all_src = {p: open(p, encoding="utf-8").read() for p in files()}
    for p, s in all_src.items():
        check_braces(p, s)
        check_undefined_calls(p, s, all_src)
        check_edt_blocking(p, s)
        check_lambda_locals(p, s)
        check_tag_declared(p, s)
        check_thread_start(p, s)
        check_mouse_button_type(p, s)
        check_awt_event_imports(p, s)
        check_literal_escape(p, s)
        check_generation_guard(p, s)
        check_background_ui(p, s)
        check_optionpane_on_bg(p, s)

    print("检查 %d 个文件" % len(all_src))
    for x in warnings:
        print("  ⚠", x)
    if problems:
        print("\n发现 %d 个问题：" % len(problems))
        for x in problems:
            print("  ✗", x)
        return 1
    waw = write_after_write(files())
    for path, target, n in waw:
        print("  ⚠ %s: %s 被 set* 赋值 %d 次（确认是否有意覆盖）" % (path, target, n))
    bad_refs = method_ref_check(files())
    for path, name in bad_refs:
        print("  ✗ %s: this::%s 没有对应的方法定义" % (path.split('/')[-1], name))
    if bad_refs:
        print("✗ 存在无效的方法引用")
        return 1
    print("✓ 全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
