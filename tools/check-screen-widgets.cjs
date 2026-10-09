// 屏幕样式自检：屏幕类按 id 取的控件/贴图，样式文档必须声明。
//
// 为什么需要它：AE2 的 ScreenStyle.getWidget(id) 与 getImage(id) 在文档里找不到那个键时
// 直接 throw IllegalStateException——抛在屏幕构造期，玩家看到的是「打开 GUI 时客户端被断开」
// （NeoForge 的 ClientPayloadHandler 拿不到屏幕实例）。已经踩过一次：
// BatchAssemblerScreen 调 addOpenPriorityButton()，而 batch_molecular_assembler.json 没有
// widgets.openPriority（2026-10-09）。
//
// 用法：
//   node tools/check-screen-widgets.cjs [仓库根] [AE2 jar]
// AE2 jar 用来补读 includes 链里的 AE2 公共段落（common/*.json）。不传则自己找：
// 先看 build/moddev/*LegacyClasspath.txt（构建真正在用的那个 jar），再退到 gradle 缓存。
//
// 退出码：0 = 全部已声明；1 = 有控件/贴图未声明；2 = AE2 段落未取到，有部分无法判定。
// 2 不是通过——它意味着这次没能核完，别把它当绿灯。
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const repo = process.argv[2] || '.';
const screensDir = path.join(repo, 'src/main/resources/assets/ae2/screens');
const ourDir = path.join(screensDir, 'ae2_pattern_disk');

/**
 * 找 AE2 jar：优先用构建自己写下的 classpath（build/moddev/*LegacyClasspath.txt），
 * 那里是构建真正在用的那个 jar，不会拿错变体（Forge 的 appeng/appliedenergistics2-forge
 * 与本项目的 NeoForge ae2 不是同一个包）。
 */
function findAe2Jar(root) {
  for (const name of ['clientLegacyClasspath.txt', 'serverLegacyClasspath.txt']) {
    const p = path.join(root, 'build/moddev', name);
    if (!fs.existsSync(p)) continue;
    const hit = fs
      .readFileSync(p, 'utf8')
      .split(';')
      .find((e) => /[/\\]ae2-\d[^/\\]*\.jar$/.test(e) && fs.existsSync(e));
    if (hit) return hit;
  }
  const roots = [process.env.GRADLE_USER_HOME, path.join(os.homedir(), '.gradle')].filter(Boolean);
  for (const root of roots) {
    const dir = path.join(root, 'caches/modules-2/files-2.1/maven.modrinth/ae2');
    if (!fs.existsSync(dir)) continue;
    for (const ver of fs.readdirSync(dir)) {
      for (const hash of fs.readdirSync(path.join(dir, ver))) {
        const hdir = path.join(dir, ver, hash);
        for (const f of fs.readdirSync(hdir)) {
          if (/^ae2-\d.*\.jar$/.test(f)) return path.join(hdir, f);
        }
      }
    }
  }
  return null;
}

const ae2Jar = process.argv[3] || findAe2Jar(repo);
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ae2-common-'));
let ae2Available = false;
if (ae2Jar && fs.existsSync(ae2Jar)) {
  try {
    const entries = execFileSync('unzip', ['-Z1', ae2Jar], { encoding: 'utf8' })
      .split('\n')
      .filter((f) => f.startsWith('assets/ae2/screens/') && f.endsWith('.json'));
    for (const entry of entries) {
      const dest = path.join(tmp, entry.replace('assets/ae2/screens/', ''));
      fs.mkdirSync(path.dirname(dest), { recursive: true });
      fs.writeFileSync(dest, execFileSync('unzip', ['-p', ae2Jar, entry], { maxBuffer: 1 << 24 }));
    }
    ae2Available = true;
  } catch (e) {
    // 没有 unzip / jar 损坏：退化成「无法判定」，不要抛栈——那看起来像脚本坏了，而不是环境缺件。
    console.log(`（读取 AE2 资源失败：${String(e.message).split('\n')[0]}）`);
  }
}

// 屏幕类 / 面板类 → 它使用的样式文档。面板类由终端屏幕用同一份文档添加控件，故同映射。
const SCREEN_TO_STYLE = {
  'BatchAssemblerScreen.java': 'batch_molecular_assembler.json',
  'CellManagementTermScreen.java': 'cell_management_terminal.json',
  'PatternDiskProviderScreen.java': 'pattern_disk_provider.json',
  'MeteoritePatternProviderScreen.java': 'meteorite_pattern_provider.json',
  'PatternDiskAssemblerScreen.java': 'pattern_disk_assembler.json',
  'PatternDiskEncodingTermScreen.java': 'pattern_disk_encoding_terminal.json',
  'PatternDiskManagementTermScreen.java': 'pattern_disk_management_terminal.json',
  'InstrumentPanelScreen.java': 'pattern_transferer.json',
  'DiskEncodingAmountScreen.java': 'set_processing_pattern_amount.json',
  'CraftingEncodingPanel.java': 'pattern_disk_encoding_terminal.json',
  'ProcessingEncodingPanel.java': 'pattern_disk_encoding_terminal.json',
  'SmithingTableEncodingPanel.java': 'pattern_disk_encoding_terminal.json',
  'AdvancedEncodingPanel.java': 'pattern_disk_encoding_terminal.json',
  'ChiselingEncodingPanel.java': 'pattern_disk_encoding_terminal.json',
  'OverloadedEncodingPanel.java': 'pattern_disk_encoding_terminal.json',
  'StonecuttingEncodingPanel.java': 'pattern_disk_encoding_terminal.json',
};

function resolveInclude(fromFile, inc) {
  const direct = path.resolve(path.dirname(fromFile), inc);
  if (fs.existsSync(direct)) return direct;
  const ae2Root = path.join(tmp, inc.replace(/^\.\.\//, ''));
  if (fs.existsSync(ae2Root)) return ae2Root;
  const ae2Rel = path.join(tmp, inc);
  if (fs.existsSync(ae2Rel)) return ae2Rel;
  return null;
}

/** 递归收集样式文档（含 includes）里 widgets 与 images 两个键空间的 id。 */
function loadStyle(file, seen = new Set()) {
  const abs = path.resolve(file);
  const empty = { widgets: new Set(), images: new Set(), unresolved: [] };
  if (seen.has(abs) || !fs.existsSync(abs)) return empty;
  seen.add(abs);
  const json = JSON.parse(fs.readFileSync(abs, 'utf8'));
  const widgets = new Set(Object.keys(json.widgets || {}));
  const images = new Set(Object.keys(json.images || {}));
  const unresolved = [];
  for (const inc of json.includes || []) {
    const target = resolveInclude(abs, inc);
    if (!target) {
      unresolved.push(inc);
      continue;
    }
    const sub = loadStyle(target, seen);
    for (const w of sub.widgets) widgets.add(w);
    for (const i of sub.images) images.add(i);
    for (const u of sub.unresolved) unresolved.push(u);
  }
  return { widgets, images, unresolved };
}

// 屏幕类里按 id 取控件的写法：字面量（widgets.add*/style.getWidget/getImage）与
// 运行时拼接的前缀（"modePanel" + i）。
const WIDGET_METHODS =
  'add|addButton|addCheckbox|addNumberEntryWidget|addScrollBar|addBackgroundPanel';
const refs = new Map();
(function scan(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      scan(p);
      continue;
    }
    if (!e.name.endsWith('.java')) continue;
    const src = fs.readFileSync(p, 'utf8');
    const widgets = new Set();
    const images = new Set();
    const dynamic = [];
    if (/addOpenPriorityButton\s*\(/.test(src)) widgets.add('openPriority');
    // 接收者不写死成 any.widgets：ScreenStyle 那边叫 style.getWidget / style.getImage。
    for (const m of src.matchAll(
      new RegExp(`[\\w.]+\\.(?:${WIDGET_METHODS})\\(\\s*"([A-Za-z0-9_]+)"`, 'g'),
    )) {
      widgets.add(m[1]);
    }
    for (const m of src.matchAll(/[\w.]+\.(?:add|getWidget)\(\s*"([A-Za-z0-9_]+)"\s*\+/g)) {
      dynamic.push(m[1]);
    }
    for (const m of src.matchAll(/[\w.]+\.getImage\(\s*"([A-Za-z0-9_]+)"/g)) {
      images.add(m[1]);
    }
    for (const m of src.matchAll(/[\w.]+\.setTooltipAreaEnabled\(\s*"([A-Za-z0-9_]+)"/g)) {
      widgets.add(m[1]);
    }
    for (const prefix of dynamic) widgets.delete(prefix);
    if (widgets.size || images.size || dynamic.length) {
      refs.set(e.name, { widgets, images, dynamic });
    }
  }
})(path.join(repo, 'src/client/java'));

let failed = 0;
let checked = 0;
let undecidable = 0;
console.log(`AE2 资源：${ae2Available ? ae2Jar : '未取到（includes 无法解析，相关项无法判定）'}`);

for (const [screen, { widgets: wantW, images: wantI, dynamic }] of refs) {
  const styleName = SCREEN_TO_STYLE[screen];
  if (!styleName) {
    undecidable += wantW.size + wantI.size + dynamic.length;
    console.log(`?    ${screen}: 引用了控件但没登记样式文档映射，无法判定`);
    continue;
  }
  checked += 1;
  const { widgets: haveW, images: haveI, unresolved } = loadStyle(path.join(ourDir, styleName));
  const canJudge = unresolved.length === 0;

  for (const id of wantW) {
    if (haveW.has(id)) continue;
    if (!canJudge) undecidable += 1;
    else {
      failed += 1;
      console.log(`FAIL ${screen} -> ${styleName}: 控件 "${id}" 未声明`);
    }
  }
  for (const id of wantI) {
    if (haveI.has(id)) continue;
    if (!canJudge) undecidable += 1;
    else {
      failed += 1;
      console.log(`FAIL ${screen} -> ${styleName}: 贴图 "${id}" 未在 images 里声明`);
    }
  }
  for (const prefix of dynamic) {
    const nums = [...haveW]
      .filter((w) => w.startsWith(prefix))
      .map((w) => Number(w.slice(prefix.length)))
      .filter((n) => Number.isInteger(n))
      .sort((a, b) => a - b);
    if (!nums.length) {
      if (!canJudge) undecidable += 1;
      else {
        failed += 1;
        console.log(`FAIL ${screen} -> ${styleName}: 动态控件 "${prefix}*" 一个都没声明`);
      }
      continue;
    }
    // 编号必须从 0 起连续：漏一个就在运行时抛「缺这个控件」。
    const gaps = [];
    for (let i = 0; i < nums[nums.length - 1]; i += 1) if (!nums.includes(i)) gaps.push(i);
    if (gaps.length) {
      failed += 1;
      console.log(
        `FAIL ${screen} -> ${styleName}: "${prefix}" 编号不连续，缺 ${gaps
          .map((i) => prefix + i)
          .join(', ')}`,
      );
    } else {
      console.log(
        `  ok ${screen} -> ${styleName}: ${prefix}0..${nums[nums.length - 1]}（共 ${nums.length} 个；` +
          `代码侧若增档，这里要跟着加）`,
      );
    }
  }
  if (unresolved.length) {
    console.log(`  （${styleName} 的 includes 未解析: ${[...new Set(unresolved)].join(', ')}）`);
  }
}

console.log(
  `屏幕样式自检：${checked} 个屏幕类，${
    failed ? `${failed} 处未声明` : '全部已声明'
  }${undecidable ? `，${undecidable} 处无法判定` : ''}`,
);
if (failed) process.exit(1);
// 没核完就不算通过：AE2 段落缺失时，我们文档里缺的键同样会被漏掉（2026-10-09 那次事故的形态）。
process.exit(undecidable ? 2 : 0);
