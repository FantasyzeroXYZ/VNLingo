// emit-deinflect-ja.mjs — 从 TrackReader ja 描述符导出 JSON 规则数据
// 用法：node emit-deinflect-ja.mjs > deinflect_ja.json
import { japaneseDescriptor } from '@trackreader/domain/yomitan/deinflection/ja/index.js';

const desc = japaneseDescriptor;
const out = {
  language: desc.language,
  conditions: desc.conditions,
  transforms: Object.fromEntries(
    Object.entries(desc.transforms).map(([k, t]) => [
      k,
      { name: t.name, rules: t.rules },
    ])
  ),
  preprocessors: (desc.preprocessors || []).map((p) => p.name),
};
process.stdout.write(JSON.stringify(out));
