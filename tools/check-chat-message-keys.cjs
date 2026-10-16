// 聊天栏消息键自检：代码里发出的每个消息键都必须在两语 lang 里存在；配置开关的每个条目
// 也要在 lang 里配齐三处（消息本体 + 配置标签 + 说明）。
//
// 为什么需要它：删掉一条消息的开关与语言键、却漏删某个发出点时，
// Component.translatable 会把「键原文串」直接打到玩家聊天栏（查不到键不会报错，只是显示键名）
// ——比原来的刷屏更糟，而 compileJava 一点毛病都看不出来。已经踩过两次（2026-10：
// cell_management_terminal.notice.put 的回执还在发、以及 takeFailureKey 对「空着」返回 null
// 拼出的 …notice.null）。
//
// 用法：
//   node tools/check-chat-message-keys.cjs [仓库根]
//
// 退出码：0 = 全部对齐；1 = 有键对不上（见输出）；2 = 没找到 lang / 源码目录，或语言文件不是合法 JSON
//         —— 这次没能核完。
//
// 它看不见什么（别把「通过」当成这类 bug 已绝迹的证明）：
//   - 运行时才拼出的键：notice(NOTICE + 变量) 这类既不是字面量、前缀也拼不出来。2026-10 那两处漏改
//     是因为还留着字面痕迹才被拦住；若换成纯变量拼键，它会静默通过。
//   - 具体后缀的缺失：动态前缀只判「至少有一个键兜底」，删掉其中一个后缀（另一个还在）不报。
//   - 反向：lang 里的孤儿键、以及 assets/ae2/screens/**/*.json 里 "translate" 引用的 GUI 文案键，都不看。
//   - 开关定义只认 MESSAGES.define(builder, " 这一种写法（换变量名或换行即漏）。

const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(process.argv[2] || '.');
const langDir = path.join(root, 'src/main/resources/assets/ae2_pattern_disk/lang');
const msgPrefix = 'gui.ae2_pattern_disk.';
const cfgPrefix = 'ae2_pattern_disk.configuration.chat_messages.';

function fail(msg) {
    console.error(msg);
    process.exit(2);
}

function escapeRegExp(s) {
    return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function readLang(name) {
    const file = path.join(langDir, `${name}.json`);
    if (!fs.existsSync(file)) {
        fail(`找不到语言文件：${file}`);
    }
    try {
        return JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (err) {
        fail(`语言文件不是合法 JSON：${file}\n${err.message}`);
    }
}

function javaFiles(dir, acc = []) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            javaFiles(p, acc);
        } else if (p.endsWith('.java')) {
            acc.push(p);
        }
    }
    return acc;
}

const zh = readLang('zh_cn');
const en = readLang('en_us');

const srcDirs = [path.join(root, 'src/main/java'), path.join(root, 'src/client/java')].filter(fs.existsSync);
if (srcDirs.length === 0) {
    fail(`找不到 src 目录（仓库根：${root}）`);
}
const files = srcDirs.flatMap((d) => javaFiles(d));

const defineRe = /MESSAGES\.define\(builder,\s*"([^"]+)"/g;
// NOTICE 前缀在代码里只写后缀（前缀是常量），这里按它的固定前缀补全。
const noticeRe = /NOTICE\s*\+\s*"([a-z_]+)"/g;
const msgLiteralRe = new RegExp('"(' + escapeRegExp(msgPrefix) + '[a-zA-Z0-9_.]+)"', 'g');
const msgDynamicRe = new RegExp('"(' + escapeRegExp(msgPrefix) + '[a-zA-Z0-9_.]*\\.)"\\s*\\+', 'g');

const defineKeys = [];
const emitted = new Set();
const dynamicPrefixes = new Set();

for (const file of files) {
    const text = fs.readFileSync(file, 'utf8');
    for (const m of text.matchAll(defineRe)) {
        defineKeys.push({ key: m[1], file: path.relative(root, file) });
    }
    for (const m of text.matchAll(noticeRe)) {
        emitted.add(`${msgPrefix}cell_management_terminal.notice.${m[1]}`);
    }
    for (const m of text.matchAll(msgLiteralRe)) {
        if (!m[1].endsWith('.')) {
            emitted.add(m[1]);
        }
    }
    for (const m of text.matchAll(msgDynamicRe)) {
        dynamicPrefixes.add(m[1]);
    }
}

const problems = [];

for (const { key, file } of defineKeys) {
    for (const [name, obj] of [['zh_cn', zh], ['en_us', en]]) {
        for (const langKey of [`${cfgPrefix}${key}`, `${cfgPrefix}${key}.tooltip`, `${msgPrefix}${key}`]) {
            if (!(langKey in obj)) {
                problems.push(`${name} 缺键：${langKey}（开关来自 ${file}）`);
            }
        }
    }
}

for (const key of emitted) {
    for (const [name, obj] of [['zh_cn', zh], ['en_us', en]]) {
        if (!(key in obj)) {
            problems.push(`${name} 缺键：${key}（代码里在发、语言文件里没有 ⇒ 玩家看到的是键原文串）`);
        }
    }
}

for (const prefix of dynamicPrefixes) {
    const hitZh = Object.keys(zh).some((k) => k.startsWith(prefix));
    const hitEn = Object.keys(en).some((k) => k.startsWith(prefix));
    if (!hitZh || !hitEn) {
        problems.push(`动态拼接前缀没有任何语言键：${prefix}（拼接出来的键全靠 lang 兜底）`);
    }
}

const zhKeys = Object.keys(zh);
const enKeys = Object.keys(en);
if (zhKeys.length !== enKeys.length || zhKeys.some((k) => !(k in en))) {
    const onlyZh = zhKeys.filter((k) => !(k in en));
    const onlyEn = enKeys.filter((k) => !(k in zh));
    problems.push(`两语键集不相等：zh_cn ${zhKeys.length} / en_us ${enKeys.length}` +
        (onlyZh.length ? `；仅 zh_cn 有：${onlyZh.join(', ')}` : '') +
        (onlyEn.length ? `；仅 en_us 有：${onlyEn.join(', ')}` : ''));
}

if (problems.length > 0) {
    console.error(`聊天栏消息键自检不通过（${problems.length} 项）：`);
    for (const p of problems) {
        console.error(`  - ${p}`);
    }
    process.exit(1);
}

console.log(`聊天栏消息键自检通过：开关 ${defineKeys.length} 条、代码发出的键 ${emitted.size} 个，` +
    `两语各 ${zhKeys.length} 键，动态前缀 ${dynamicPrefixes.size} 组均有兜底。`);
