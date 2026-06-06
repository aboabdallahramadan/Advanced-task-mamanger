import { copyFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const pkgRoot = join(here, '..');

// Microsoft.Extensions.ApiDescription.Server emits as "{AssemblyName}.json"
// (with OpenApiDocumentName=v1, the file is Tmap.Api.json in this project)
const emitted = existsSync(join(pkgRoot, 'Tmap.Api_v1.json'))
  ? join(pkgRoot, 'Tmap.Api_v1.json')
  : join(pkgRoot, 'Tmap.Api.json');

const target = join(pkgRoot, 'openapi.json');

if (!existsSync(emitted)) {
  console.error(
    `OpenAPI document not found at ${emitted}. ` +
      `Run "npm run emit:openapi" (or "dotnet build") first.`,
  );
  process.exit(1);
}

copyFileSync(emitted, target);
console.log(`Copied ${emitted} -> ${target}`);
