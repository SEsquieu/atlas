# Third-Party Notices

Atlas is licensed under Apache License 2.0, but it depends on software distributed under other licenses. Those projects retain their respective copyrights and license terms.

This inventory describes the dependency locks committed for `v0.1.0-alpha.5`. Exact versions remain authoritative in `package-lock.json`, `apps/atlas-cloud/package-lock.json`, and `apps/atlas-android/app/build.gradle.kts`.

## Native Android application

The release application uses AndroidX Core, Activity, Lifecycle, Compose, Material, CameraX, and ExifInterface; Kotlin and Kotlin Coroutines; OkHttp; and their runtime transitives. These components are distributed under Apache License 2.0.

Development tests additionally use:

- JUnit 4, Eclipse Public License 1.0;
- MockWebServer, Apache License 2.0; and
- JSON-java, under its upstream JSON license.

A compact copy of the runtime attribution is packaged in the Android application at `app/src/main/res/raw/third_party_notices.txt`.

## TypeScript workspace

The root TypeScript toolchain uses TypeScript (Apache-2.0), `@types/node` (MIT), and `undici-types` (MIT). Atlas workspace packages are part of this repository and use Apache-2.0.

## Optional Atlas Cloud reference application

The cloud lock contains packages under these identifiers:

- MIT: Next.js, React, React DOM, Supabase JavaScript packages, Stripe's Node library, Zod, esbuild, SWC platform packages, and supporting utilities;
- Apache-2.0: OpenAI's Node library, Sharp platform packages, SWC helpers, browser-baseline data, and supporting utilities;
- ISC: `picocolors` and `semver`;
- BSD-3-Clause: `source-map-js`;
- 0BSD: `tslib`;
- CC-BY-4.0: `caniuse-lite` browser-compatibility data; and
- LGPL-3.0-or-later: platform-specific libvips binaries distributed through Sharp's optional `@img/sharp-libvips-*` packages. `@img/sharp-wasm32` declares Apache-2.0, LGPL-3.0-or-later, and MIT components.

Atlas does not modify libvips. The lock includes platform-specific optional packages for reproducible deployment; a particular deployment installs only the packages applicable to its platform. Anyone redistributing a bundled cloud image must preserve the corresponding license texts and LGPL relinking/source obligations described by the upstream package.

## License texts and authoritative metadata

- Apache License 2.0: [`LICENSE`](./LICENSE)
- Eclipse Public License 1.0: <https://www.eclipse.org/legal/epl-v10.html>
- GNU Lesser General Public License 3.0: <https://www.gnu.org/licenses/lgpl-3.0.html>
- Creative Commons Attribution 4.0: <https://creativecommons.org/licenses/by/4.0/>
- Installed npm package metadata and license files: each package directory in `node_modules` after `npm ci`

This notice is an engineering inventory, not a substitute for the upstream license text or legal advice. Release maintainers must regenerate and review the dependency locks when dependencies change.
