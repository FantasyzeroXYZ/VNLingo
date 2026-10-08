// emit-deinflect-all.mts — 从 TrackReader 各语言描述符导出全部词形还原规则 JSON
// 运行：在 TrackReader 仓库根（D:\code\test-JavaScript\TrackReader）执行
//   npx tsx <本文件路径>     （相对导入 TrackReader 源码，须在彼处解析）
// 产物：deinflect_ja/en/ko/zh/vi.json → engine/src/main/assets/
import { writeFileSync } from 'node:fs';
import { japaneseDescriptor } from '../packages/domain/src/yomitan/deinflection/ja/index.ts';
import { englishDescriptor } from '../packages/domain/src/yomitan/deinflection/en/index.ts';
import { koreanDescriptor } from '../packages/domain/src/yomitan/deinflection/ko/index.ts';
import { chineseDescriptor } from '../packages/domain/src/yomitan/deinflection/zh/index.ts';
import { vietnameseDescriptor } from '../packages/domain/src/yomitan/deinflection/vi/index.ts';

const outDir = 'D:/Desktop/demo-code/VNLingo/engine/src/main/assets/';
for (const desc of [japaneseDescriptor, englishDescriptor, koreanDescriptor, chineseDescriptor, vietnameseDescriptor]) {
  const out: any = {
    language: desc.language,
    conditions: desc.conditions,
    transforms: Object.fromEntries(
      Object.entries(desc.transforms).map(([k, t]: [string, any]) => [k, { name: t.name, rules: t.rules }])
    ),
    preprocessors: (desc.preprocessors || []).map((p: any) => p.name),
  };
  writeFileSync(outDir + 'deinflect_' + desc.language + '.json', JSON.stringify(out));
  console.log(desc.language, 'transforms:', Object.keys(desc.transforms).length,
    'rules:', Object.values(desc.transforms).reduce((a: number, t: any) => a + t.rules.length, 0));
}
