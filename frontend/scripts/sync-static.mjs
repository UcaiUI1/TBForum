// 将前端构建产物同步到 Spring Boot 静态资源目录（生产同源部署）。
import { cpSync, rmSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const dist = path.resolve(here, '../dist');
const target = path.resolve(here, '../../src/main/resources/static');

const expected = 'src' + path.sep + 'main' + path.sep + 'resources' + path.sep + 'static';
if (!target.includes(expected)) {
  throw new Error(`拒绝同步到意外路径: ${target}`);
}

rmSync(target, { recursive: true, force: true });
mkdirSync(target, { recursive: true });
cpSync(dist, target, { recursive: true });
console.log(`已同步 ${dist} -> ${target}`);
